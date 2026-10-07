/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Thin, callback-based wrapper around the Android BLE stack for the
 * "Bluetooth water controller" (蓝牙水控器).
 *
 * This replaces the Web Bluetooth calls used by the original TypeScript app:
 *
 * | Web Bluetooth                    | Android                          |
 * |----------------------------------|----------------------------------|
 * | `navigator.bluetooth.requestDevice` | [startScan] + [stopScan]       |
 * | `device.gatt.connect()`          | [connect]                        |
 * | `server.getPrimaryService`       | [onServicesDiscovered]           |
 * | `characteristic.startNotifications` | [enableNotifications]         |
 * | `characteristic.writeValue`      | [write]                          |
 * | `device.addEventListener('characteristicvaluechanged')` | [Listener.onNotification] |
 *
 * All callbacks are delivered on the main thread, so callers never have to think about
 * the BLE binder threads.
 */
class WaterBleClient(private val context: Context) {

    companion object {
        /** The primary service exposed by the controller. */
        val SERVICE_UUID: UUID = UUID.fromString("0000f1f0-0000-1000-8000-00805f9b34fb")

        /** TXD: app -> controller. */
        val TXD_UUID: UUID = UUID.fromString("0000f1f1-0000-1000-8000-00805f9b34fb")

        /** RXD: controller -> app (notifications). */
        val RXD_UUID: UUID = UUID.fromString("0000f1f2-0000-1000-8000-00805f9b34fb")

        /** Client Characteristic Configuration descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * The framework reports GATT failures with no parameter validation, so a large value
         * is used here so that every ATT MTU this app could negotiate fits in one write.
         */
        private const val REQUESTED_MTU = 517
    }

    interface Listener {
        fun onScanStarted()
        fun onDeviceFound(device: BluetoothDevice, name: String)

        /** The adapter is off, unsupported, or permissions are missing. */
        fun onAdapterUnavailable(reason: String)

        fun onConnected(deviceName: String)
        fun onServicesReady(characteristicCount: Int)
        fun onNotification(bytes: ByteArray)

        /** `status` is the raw `BluetoothGatt.GATT_*` code. */
        fun onGattFailure(operation: String, status: Int)

        fun onDisconnected()

        /** A write was accepted by the stack. */
        fun onWrite(bytes: ByteArray)
    }

    private val main = Handler(Looper.getMainLooper())

    var listener: Listener? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var txd: BluetoothGattCharacteristic? = null
    private var rxd: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connected = false
    private val seenAddresses = HashSet<String>()

    // ---------------------------------------------------------------- permissions

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The runtime permissions that must be held before any BLE call is legal. */
    fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filterNot { hasPermission(it) }
    }

    // --------------------------------------------------------------------- scan

    @SuppressLint("MissingPermission")
    fun startScan(namePrefixes: List<String>) {
        val listener = listener
        val adapter = adapter
        if (adapter == null) {
            listener?.onAdapterUnavailable("Bluetooth adapter not available")
            return
        }
        if (!adapter.isEnabled) {
            listener?.onAdapterUnavailable("Bluetooth is turned off")
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            listener?.onAdapterUnavailable("Missing permission: ${missing.joinToString()}")
            return
        }
        if (scanning) return

        seenAddresses.clear()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener?.onAdapterUnavailable("BLE scanner not available")
            return
        }

        // The original uses a `namePrefix` filter over every alphanumeric character, which is
        // equivalent to "the advertised name starts with an alphanumeric character". Android's
        // ScanFilter cannot express a generic prefix without also requiring location consent
        // on API 31+, so all results are accepted here and the name is checked per result in
        // [scanCallback]. The controllers do not advertise their service UUID, so a service
        // filter is not an option either.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        } catch (e: SecurityException) {
            listener?.onAdapterUnavailable("Bluetooth permission denied: ${e.message}")
            return
        }
        scanning = true
        listener?.onScanStarted()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        val adapter = adapter ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return
        }
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // adapter went away underneath us; nothing useful to do
        }
    }

    val isScanning: Boolean get() = scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = deviceName(result.device) ?: result.scanRecord?.deviceName ?: return
            if (name.isEmpty()) return
            // Equivalent to the original `namePrefix` filter over 0-9a-zA-Z.
            if (!name[0].isLetterOrDigit()) return
            if (!seenAddresses.add(result.device.address)) return
            main.post { listener?.onDeviceFound(result.device, name) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.post { listener?.onGattFailure("startScan", errorCode) }
        }
    }

    // ------------------------------------------------------------------ connect

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (missingPermissions().isNotEmpty()) {
            listener?.onAdapterUnavailable("Missing permission: ${missingPermissions().joinToString()}")
            return
        }
        closeGatt()
        seenAddresses.clear()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    connected = true
                    val name = deviceName(g.device).orEmpty()
                    main.post { listener?.onConnected(name) }
                    // A larger MTU keeps the 20-byte AF response in a single ATT packet.
                    // `requestMtu` needs API 21 and minSdk is 24, so it is always available;
                    // discovery is only kicked off from `onMtuChanged` instead.
                    g.requestMtu(REQUESTED_MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    main.post { listener?.onDisconnected() }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    main.post { listener?.onGattFailure("connect", status) }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                // Even if the MTU request is refused or unsupported, service discovery must
                // still run; the MTU is only an optimisation here.
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    main.post { listener?.onGattFailure("discoverServices", status) }
                    return
                }
                val service = g.getService(SERVICE_UUID)
                if (service == null) {
                    main.post { listener?.onGattFailure("getPrimaryService(0xF1F0)", status) }
                    return
                }
                txd = service.getCharacteristic(TXD_UUID)
                rxd = service.getCharacteristic(RXD_UUID)
                if (txd == null || rxd == null) {
                    main.post { listener?.onGattFailure("getCharacteristic(0xF1F1/0xF1F2)", status) }
                    return
                }
                main.post { listener?.onServicesReady(service.characteristics.size) }
                enableNotifications(g, rxd!!)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CCCD_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                    main.post { listener?.onGattFailure("startNotifications", status) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                dispatchNotification(value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                dispatchNotification(value)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    main.post { listener?.onGattFailure("writeValue", status) }
                }
            }
        }

        // `TRANSPORT_LE` needs API 23 and minSdk is 24, so it is always available. The
        // `autoConnect` flag stays `false` on every version: on API 26+ that maps to
        // DIRECT_CONNECT, which is the behaviour the controller needs. A direct connection
        // also lets the app report a failure quickly instead of waiting on a background scan.
        val newGatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (newGatt == null) {
            listener?.onGattFailure("connectGatt", -1)
            return
        }
        gatt = newGatt
    }

    private fun dispatchNotification(value: ByteArray) {
        val copy = value.copyOf()
        main.post { listener?.onNotification(copy) }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            // Some firmware revisions do not expose the descriptor; the local notification
            // registration above is then the best that can be done.
            return
        }
        val enableIndications =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = if (enableIndications) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // -------------------------------------------------------------------- write

    /**
     * Writes one frame to TXD.
     *
     * `writeValue` in the original is asynchronous and never waits for the notification
     * that may arrive while it is in flight, so this does not wait either. If a write is
     * genuinely rejected the stack reports it through [Listener.onGattFailure].
     */
    @SuppressLint("MissingPermission")
    fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val characteristic = txd ?: return false
        val value = bytes.copyOf()

        // API 33 and later report the outcome as a BluetoothStatusCodes value -- notably *not*
        // a BluetoothGatt status code -- so the two overloads are checked separately. Both
        // constants happen to be 0, but comparing the wrong family would silently treat a
        // rejected write as success if either enum ever changes.
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }

        if (accepted) {
            listener?.onWrite(value)
        } else {
            listener?.onGattFailure("writeValue", -1)
        }
        return accepted
    }

    // ------------------------------------------------------------------ teardown

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        val g = gatt
        if (g != null) {
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) {
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                    // already gone
                }
            }
        }
    }

    /** Releases the GATT client without notifying the listener. */
    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        txd = null
        rxd = null
        connected = false
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            try {
                g.disconnect()
            } catch (_: SecurityException) {
                // ignore
            }
        }
        g.close()
    }

    val isConnected: Boolean get() = connected

    /** The MAC address of the connected device, or `null` when nothing is connected. */
    val connectedDeviceAddress: String?
        @SuppressLint("MissingPermission")
        get() = if (connected && missingPermissions().isEmpty()) {
            gatt?.device?.address
        } else {
            null
        }

    /**
     * Turns a saved MAC address back into a [BluetoothDevice] without scanning.
     *
     * Returns `null` when there is no adapter, a permission is missing, or the address is not
     * a valid MAC. A controller that has since been powered off still resolves here -- the
     * connection attempt is what discovers that, and it fails with a normal GATT error.
     */
    @SuppressLint("MissingPermission")
    fun deviceForAddress(address: String): BluetoothDevice? {
        val adapter = adapter ?: return null
        if (missingPermissions().isNotEmpty()) return null
        return try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            // Not a parseable MAC address; the stored value is unusable.
            null
        } catch (_: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun deviceName(device: BluetoothDevice): String? {
        if (missingPermissions().isNotEmpty()) return null
        return try {
            device.name
        } catch (_: SecurityException) {
            null
        }
    }
}
