/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.badghost.watercontrol.bluetooth.WaterBleClient
import com.badghost.watercontrol.protocol.Payloads
import com.badghost.watercontrol.protocol.RxdFrameParser
import com.badghost.watercontrol.protocol.Solvers
import com.badghost.watercontrol.protocol.WaterUtils
import java.util.Locale

/**
 * Orchestrates one interaction with a Bluetooth water controller.
 *
 * This is the Android counterpart of `src/bluetooth.ts`. The structure maps one-to-one onto
 * the original: [start] is `start()`, [end] is `end()`, [onFrame] is `handleRxdNotifications`
 * and [fail] is `handleBluetoothError`.
 *
 * It departs from the original in one structural way: the browser's single button toggles
 * between starting and ending a session, because Web Bluetooth's device chooser already did
 * the connecting. Here connecting is its own button ([handleConnectButtonClick]) and opening
 * or ending a session is another ([handleStartButtonClick]), so the controller's link state
 * and its billing state are never conflated.
 */
class WaterSession(
    context: Context,
    private val callback: Callback,
) : WaterBleClient.Listener {

    /**
     * The stages the original renders through `updateUi`, plus [CONNECTED].
     *
     * [CONNECTED] has no counterpart upstream because Web Bluetooth's device chooser already
     * provides it: in the browser, picking a device *is* the click that starts the session.
     * Here the two are separated so that connecting never opens a billing session on its own.
     */
    enum class Stage {
        /** Idle: nothing connected. */
        STANDBY,

        /** Busy: connecting, or starting a session. */
        PENDING,

        /** Connected and idle: the start prologue has not been sent yet. */
        CONNECTED,

        /** A session is running. */
        RUNNING,
    }

    /**
     * Machine-readable reasons for the failures the UI has to explain in words.
     *
     * The original matches on `toString()` of a thrown `Error`; an enum keeps that mapping
     * out of string comparison while preserving which failures are fatal.
     */
    enum class Reason {
        UNKNOWN_RXD_DATA,
        BAD_KEY,
        OPERATION_TIMED_OUT,
        UNSUPPORTED_SERVICE,

        /** The controller explicitly refused the start command with `C8`. */
        REFUSED,

        /** The adapter is off, or a Bluetooth permission is missing. */
        BLUETOOTH_UNAVAILABLE,

        /** The connection dropped or a GATT operation failed. */
        CONNECTION_ERROR,
        ;

        val isFatal: Boolean
            get() = when (this) {
                UNKNOWN_RXD_DATA, OPERATION_TIMED_OUT -> false
                else -> true
            }

        /** Whether failure details are useful enough to show the user. */
        val showsDebugInfo: Boolean
            get() = this != BLUETOOTH_UNAVAILABLE
    }

    /** User-visible progress that is not one of the three [Stage]s. */
    enum class Progress {
        /**
         * A BLE scan is running.
         *
         * Distinct from [CONNECTING] because the two need opposite button treatment: while
         * scanning there is nothing to cancel, so the Connect button is inert, whereas a
         * connect attempt that hangs must stay cancellable.
         */
        SCANNING,

        /** A GATT connection to the chosen controller is being established. */
        CONNECTING,

        /** The controller is connected and notifications are on; the button is now live. */
        READY,

        /** The GATT connection is up and services are being negotiated. */
        NEGOTIATING,

        /** The end-prologue was sent and the controller is acknowledging it. */
        ENDING,

        /**
         * An end-then-disconnect sequence is running, so both buttons are inert until the
         * session reaches standby.
         */
        DISCONNECTING,
    }

    class Failure(
        val reason: Reason,
        val detail: String,
        /**
         * The collected log lines, captured *before* teardown. A fatal failure tears the
         * session down, and teardown clears the log buffer, so the snapshot has to be taken
         * while the diagnostics still exist -- the original does the same by reading the
         * logs before calling `disconnect()`.
         */
        val debugInfo: List<String>,
    ) {
        val isFatal: Boolean get() = reason.isFatal
    }

    interface Callback {
        /** The stage changed; drives the main button label and the status line. */
        fun onStageChanged(stage: Stage, deviceName: String?)

        /**
         * A BLE device was discovered. The app decides what to do with it -- there is no
         * auto-connect here on purpose, because a dormitory corridor has several controllers
         * in range and only the user knows which one is theirs.
         */
        fun onDeviceFound(
            device: android.bluetooth.BluetoothDevice,
            name: String,
            isLikelyController: Boolean,
        )

        /** The scan stopped without ever finding a controller. */
        fun onScanEmpty()

        /**
         * A connection completed far enough to be usable, so this controller should be
         * remembered as the last one that actually worked.
         */
        fun onDeviceRemembered(address: String, name: String)

        /**
         * The scan window closed and produced at least one device.
         *
         * The list is only *reported*: presenting the picker from here would throw a dialog
         * over the screen the instant a scan finished, which is intrusive when the user is
         * merely glancing at what is nearby. The app decides when to show it.
         */
        fun onScanComplete()

        /**
         * The user asked to see the devices found by the last scan, without scanning again.
         * Sent when the Connect button is pressed while idle and a completed scan exists.
         */
        fun onShowFoundDevices()

        /** The connection to the controller went away. */
        fun onDisconnected()

        /** A failure that must be surfaced to the user. */
        fun onFailure(failure: Failure)

        /** A one-shot progress message; the activity owns the wording. */
        fun onProgress(progress: Progress)
    }

    companion object {
        /** Matches `setupTimeoutMessage()` in the original. */
        private const val OPERATION_TIMEOUT_MS = 15_000L

        /** The original waits 500 ms before answering B0/B1 on new firmware. */
        private const val START_EPILOGUE_DELAY_MS = 500L

        /** How long to scan when nothing recognisable has turned up yet. */
        private const val SCAN_TIMEOUT_MS = 6_000L

        /**
         * How long to keep scanning after a plausible controller appears.
         *
         * A scan is a stream, not a query: the first result arrives in well under a second,
         * but waiting for the full window means staring at a spinner for seconds after the
         * device is already known. Once a controller is in hand, a short grace period is
         * enough to catch its neighbours, and the rest of the window is pure waiting.
         */
        private const val SCAN_GRACE_MS = 2_000L

        /**
         * Name prefixes used by the controllers. The `Water` spelling is the one upstream
         * uses in its own tests; the others cover the common Chinese-branded variants.
         */
        val CONTROLLER_NAME_PREFIXES = listOf(
            "water",
            "shui",
            "cg",
            "水控",
            "热水",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val ble = WaterBleClient(context)
    private val parser = RxdFrameParser { Logger.log(it) }

    private var stage = Stage.STANDBY
    private var deviceName: String? = null
    private var isStarted = false

    /**
     * Whether the start prologue has already been sent for the current connection.
     *
     * Connecting and starting are deliberately two separate steps. Web Bluetooth forces this
     * on the original -- picking a device and pressing the button are two clicks -- and it
     * matters for real hardware: merely connecting must never begin a billing session.
     */
    private var prologueSent = false

    private var pendingTimeout: Runnable? = null
    private var pendingStartEpilogue: Runnable? = null
    private var pendingScanTimeout: Runnable? = null

    /** Whether the current scan has produced at least one candidate. */
    private var foundAnything = false

    /** Whether the current scan has seen a device that looks like a water controller. */
    private var foundController = false

    init {
        ble.listener = this
    }

    // ------------------------------------------------------------------ public API

    /** Starts scanning for a controller. Nothing is connected until the user picks one. */
    fun beginScan() {
        if (ble.isScanning) return
        val missing = ble.missingPermissions()
        if (missing.isNotEmpty()) {
            fail(Reason.BLUETOOTH_UNAVAILABLE, "Missing permission: ${missing.joinToString()}")
            return
        }
        Logger.log("SCAN: looking for a controller")
        foundAnything = false
        foundController = false
        ble.startScan(emptyList())
        callback.onProgress(Progress.SCANNING)

        // Safety net: if nothing recognisable shows up, stop rather than scanning forever. The
        // window is short because a controller that is switched on advertises constantly.
        val timeout = Runnable {
            if (ble.isScanning) {
                ble.stopScan()
                pendingScanTimeout = null
                Logger.log("SCAN: window closed after ${SCAN_TIMEOUT_MS}ms, found=$foundAnything")
                if (foundAnything) callback.onScanComplete() else callback.onScanEmpty()
            }
        }
        pendingScanTimeout = timeout
        main.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    /**
     * Ends the scan shortly after the first plausible controller is seen.
     *
     * Called for every discovery; the first qualifying one schedules the early finish and any
     * later call is ignored, so the deadline never gets pushed back.
     */
    private fun onControllerSpotted() {
        if (foundController || pendingScanTimeout == null) return
        foundController = true
        // Replace the safety net with the shorter grace deadline.
        pendingScanTimeout?.let { main.removeCallbacks(it) }
        val grace = Runnable {
            if (ble.isScanning) {
                ble.stopScan()
                pendingScanTimeout = null
                Logger.log("SCAN: controller found, finishing early after ${SCAN_GRACE_MS}ms")
                callback.onScanComplete()
            }
        }
        pendingScanTimeout = grace
        main.postDelayed(grace, SCAN_GRACE_MS)
    }

    fun stopScan() {
        cancelScanTimeout()
        ble.stopScan()
    }

    /**
     * Whether the session is currently being wound up: the end prologue is out and the
     * controller has not acknowledged it yet.
     *
     * Ending is asynchronous, and both buttons have to stay inert for its whole duration.
     * Without this, a repeat press would push another end prologue into a link that is already
     * closing -- which the controller ignores and the stack reports as a write failure.
     */
    private var ending = false

    /**
     * Set when the user asked to disconnect, so the teardown is not lost.
     *
     * A disconnect that arrives while an end is already in flight cannot send anything, but it
     * must still be honoured: this flag makes the pending end tear the link down too. Without
     * it the session would sit in RUNNING forever, waiting for a B3 that had already arrived.
     */
    private var teardownRequested = false

    /** Equivalent to the Connect/Disconnect button, always starting a fresh scan when idle. */
    fun handleConnectButtonClick() {
        handleConnectButtonClick(showFoundDevices = false)
    }

    /**
     * The Connect/Disconnect button.
     *
     * @param showFoundDevices when `true` and the session is idle, the devices already found
     *   are presented again instead of scanning once more. This is what lets a finished scan
     *   be reviewed on demand rather than forced on the user as an automatic dialog.
     */
    fun handleConnectButtonClick(showFoundDevices: Boolean) {
        // Already winding up; a second press has nothing meaningful to add.
        if (ending) return

        when (stage) {
            // Connected or running: drop the link. This ends a live session first, so the
            // button can never abandon a running one.
            Stage.CONNECTED, Stage.RUNNING -> disconnect()

            // A connect is in flight. Pressing again is an escape hatch: abandon it and look
            // for another controller rather than leaving the user stuck until it times out.
            Stage.PENDING -> {
                Logger.log("SESSION: connect attempt cancelled, rescanning")
                ble.close()
                beginScan()
            }

            // Idle: either re-present the last result, or scan.
            Stage.STANDBY -> when {
                showFoundDevices -> {
                    Logger.log("SESSION: showing the devices from the last scan")
                    callback.onShowFoundDevices()
                }

                // The GATT link can still be up briefly after a teardown, because the
                // disconnect callback has not landed yet; finish that first.
                ble.isConnected -> {
                    Logger.log("SESSION: link still up, disconnecting")
                    disconnect()
                }

                else -> {
                    Logger.log("SESSION: connect requested, scanning")
                    beginScan()
                }
            }
        }
    }

    /**
     * Equivalent to the Start/Stop button: opens a session when connected and idle, and ends
     * the running session otherwise.
     *
     * The two halves are guarded separately rather than by one `isStarted` flag so that a
     * press can never start a session that is already running, and a press while merely
     * connected can never end a session that was never opened.
     */
    fun handleStartButtonClick() {
        // While winding up, the session is already ending; another press would be a no-op at
        // best and a second end prologue at worst.
        if (ending) return

        if (isStarted) {
            ending = true
            callback.onProgress(Progress.ENDING)
            sendEndPrologue()
        } else {
            start()
        }
    }

    /** Whether an end-then-disconnect sequence is currently running. */
    val isEnding: Boolean get() = ending

    /**
     * Drops the link, ending any live session first.
     *
     * This is the Connect/Disconnect button's disconnect half. The "end the session, then
     * disconnect" ordering lives here and only here, so no caller can skip the ending half and
     * abandon a live session.
     */
    private fun disconnect() {
        if (isStarted || prologueSent) {
            teardownRequested = true
            if (!ending) {
                Logger.log("SESSION: disconnecting, ending the session first")
                ending = true
                callback.onProgress(Progress.DISCONNECTING)
                sendEndPrologue()
            } else {
                // An end is already in flight (the user pressed Stop first). Nothing new can be
                // sent, but the pending end now also has to take the link down -- the flag just
                // set is what makes that happen when B3 arrives.
                Logger.log("SESSION: end already in flight, it will disconnect when it completes")
            }
            return
        }
        Logger.log("SESSION: disconnecting")
        teardownAndStandby()
    }

    /** Sends the end prologue and waits for the controller's B3 acknowledgement. */
    private fun sendEndPrologue() {
        Logger.log("TXD: end prologue ${WaterUtils.bufferToHexString(Payloads.endPrologue)}")
        write(Payloads.endPrologue)
        setupTimeoutMessage()
    }

    /** Acknowledges the controller's B3 with the matching epilogue, as upstream does. */
    private fun sendEndEpilogue() {
        Logger.log("TXD: end epilogue ${WaterUtils.bufferToHexString(Payloads.endEpilogue)}")
        write(Payloads.endEpilogue)
    }

    /**
     * Connects straight to a previously used controller, addressed by its saved MAC.
     *
     * This skips the scan entirely, but it is *not* a shortcut around the user's decision:
     * the button that calls it names the controller it will connect to, so pressing it is as
     * explicit as picking that same device out of the list.
     *
     * Nothing is started here either -- landing on [Stage.CONNECTED] still requires a separate
     * press of "开启" before a billing session opens.
     */
    fun reconnectTo(address: String, name: String) {
        val device = ble.deviceForAddress(address)
        if (device == null) {
            fail(
                Reason.BLUETOOTH_UNAVAILABLE,
                "Cannot resolve $address -- missing permission or no Bluetooth adapter",
            )
            return
        }
        Logger.log("GATT: reconnecting to the last used controller")
        connectTo(device, name)
    }

    /** Connects to a controller the user picked from the discovery list. */
    fun connectTo(device: android.bluetooth.BluetoothDevice, name: String) {
        cancelScanTimeout()
        ble.stopScan()
        deviceName = name
        prologueSent = false
        parser.reset()
        Logger.log("GATT: connecting to $name (${device.address})")
        setStage(Stage.PENDING)
        callback.onProgress(Progress.CONNECTING)
        ble.connect(device)
    }

    /** Tears everything down and returns to standby. */
    fun shutdown() {
        cancelAllTimers()
        ble.close()
        isStarted = false
        prologueSent = false
        ending = false
        teardownRequested = false
        parser.reset()
        deviceName = null
        setStage(Stage.STANDBY)
    }

    val currentStage: Stage get() = stage

    /** The controller the session is currently working with, if any. */
    val currentDeviceName: String? get() = deviceName

    /** Whether the GATT link to a controller is up. */
    val isConnected: Boolean get() = ble.isConnected

    /** The runtime Bluetooth permissions that still need to be granted. */
    fun missingPermissions(): List<String> = ble.missingPermissions()

    // ------------------------------------------------------------ protocol actions

    private fun start() {
        if (!ble.isConnected) {
            // Nothing is connected (the phone slept, the controller went away). Fall back to
            // scanning so the button always does something sensible.
            Logger.log("SESSION: not connected, scanning instead")
            beginScan()
            return
        }
        prologueSent = true
        Logger.log("TXD: start prologue ${WaterUtils.bufferToHexString(Payloads.startPrologue)}")
        write(Payloads.startPrologue)
        setStage(Stage.PENDING)
        setupTimeoutMessage()
    }

    /**
     * The `handleRxdNotifications` switch from the original.
     */
    private fun onFrame(frame: ByteArray) {
        val dType = frame[3].toInt() and 0xFF
        Logger.log(
            "RXD: ${WaterUtils.bufferToHexString(frame)}  (type 0x${
                String.format(Locale.US, "%02X", dType)
            })"
        )

        when (dType) {
            // start prologue ok; delay 500 ms for the key authentication request (AE)
            // if this is a new firmware
            0xB0, 0xB1 -> scheduleStartEpilogue()

            // receiving an unlock request (AE), this is a new firmware
            0xAE -> {
                cancelStartEpilogue()
                respond(makeUnlockResponse(frame))
            }

            0xAF -> when (frame[5].toInt() and 0xFF) {
                // key authentication ok; continue to send start epilogue (B2)
                0x55 -> respond(Solvers.makeStartEpilogue(requireDeviceName(), isKeyAuthPresent = true))
                // key authentication failed: "err41" (bad key), 0x02 (?), "err43" (bad nonce)
                0x01, 0x02, 0x04 -> {
                    fail(Reason.BAD_KEY, "Key authentication rejected (0x${hex(frame[5])})")
                }

                else -> {
                    respond(Solvers.makeStartEpilogue(requireDeviceName(), isKeyAuthPresent = true))
                    fail(Reason.UNKNOWN_RXD_DATA, "Unknown AF status 0x${hex(frame[5])}")
                }
            }

            // start ok; update the UI
            0xB2 -> {
                cancelAllTimers()
                isStarted = true
                Logger.log("SESSION: controller accepted the start command")
                setStage(Stage.RUNNING)
            }

            // end prologue ok (B3); acknowledge, then either drop the link (the user asked to
            // disconnect) or stay connected and idle (the user only asked to stop).
            0xB3 -> {
                Logger.log("SESSION: controller confirmed the session ended")
                ending = false
                sendEndEpilogue()
                if (teardownRequested) {
                    teardownAndStandby()
                } else {
                    isStarted = false
                    prologueSent = false
                    parser.reset()
                    setStage(Stage.CONNECTED)
                }
            }
            // telemetry / temperature settings / unknown: no response expected
            0xAA, 0xB5, 0xB8 -> Unit

            // user info upload request; ack it to say we have handled it (we never do)
            0xBA -> respond(Payloads.baAck)

            // previous offline session is still open; clear it
            0xBC -> respond(Payloads.offlinebombFix)

            // start epilogue (B2) was refused
            0xC8 -> fail(Reason.REFUSED, "The controller refused the start command (C8)")

            else -> fail(Reason.UNKNOWN_RXD_DATA, "Unknown RXD type 0x${hex(frame[3])}")
        }
    }

    private fun makeUnlockResponse(frame: ByteArray): ByteArray {
        val name = requireDeviceName()
        val response = try {
            Solvers.makeUnlockResponse(frame, name)
        } catch (e: IllegalArgumentException) {
            fail(Reason.BAD_KEY, "Malformed unlock request: ${e.message}")
            return ByteArray(0)
        }
        Logger.log("KEY: unlock response ${WaterUtils.bufferToHexString(response)}")
        return response
    }

    private fun scheduleStartEpilogue() {
        cancelStartEpilogue()
        val runnable = Runnable {
            if (ble.isConnected) {
                respond(Solvers.makeStartEpilogue(requireDeviceName()))
            }
        }
        pendingStartEpilogue = runnable
        main.postDelayed(runnable, START_EPILOGUE_DELAY_MS)
    }

    private fun cancelStartEpilogue() {
        pendingStartEpilogue?.let { main.removeCallbacks(it) }
        pendingStartEpilogue = null
    }

    private fun setupTimeoutMessage() {
        if (pendingTimeout != null) return
        val runnable = Runnable {
            pendingTimeout = null
            // A successfully ended session tears itself down, and that clears `ending`. A timer
            // that fires after that point belongs to an operation which already completed, so
            // it must not raise a dialog. A timeout on a start, or on an end the controller
            // never acknowledged, still leaves `ending` or `isStarted` set and is reported.
            if (!ending && !isStarted) {
                Logger.log("SESSION: the pending operation already completed, ignoring the timeout")
                return@Runnable
            }
            fail(Reason.OPERATION_TIMED_OUT, "No response within ${OPERATION_TIMEOUT_MS / 1000}s")
        }
        pendingTimeout = runnable
        main.postDelayed(runnable, OPERATION_TIMEOUT_MS)
    }

    /**
     * Reports a failure unless it belongs to a session that has already been wound up.
     *
     * Failures arrive asynchronously -- a write is torn down by the teardown that follows it,
     * and the operation timeout is a posted runnable that can land just after a clean exit.
     * Once the stage is back to STANDBY there is nothing left for the user to act on, so those
     * late reports are logged rather than shown; every other stage still surfaces them.
     */
    private fun failUnlessAlreadyStandby(reason: Reason, detail: String) {
        if (stage == Stage.STANDBY) {
            Logger.log("SESSION: ignoring a late $reason after teardown ($detail)")
            return
        }
        fail(reason, detail)
    }

    /**
     * Releases the link and returns to standby.
     *
     * Clearing [ending] and [teardownRequested] is what *completes* an end-then-disconnect
     * sequence. Leaving either set stranded the session in RUNNING, waiting for an
     * acknowledgement that had already arrived -- which is what made a successful stop look
     * like a 15-second timeout. Cancelling the timers here is the other half of that: a
     * session that ended cleanly must not have a stale dialog raised over it.
     */
    private fun teardownAndStandby() {
        cancelAllTimers()
        ble.disconnect()
        isStarted = false
        prologueSent = false
        ending = false
        teardownRequested = false
        parser.reset()
        Logger.clear()
        deviceName = null
        setStage(Stage.STANDBY)
        // The stack reports a torn-down write asynchronously, by which point the stage is
        // already STANDBY; `onGattFailure` relies on that to tell it apart from a real failure.
        Logger.log("SESSION: disconnected")
    }

    // ------------------------------------------------------------------- plumbing

    private fun requireDeviceName(): String {
        val name = deviceName.orEmpty()
        if (name.isEmpty()) Logger.log("WARN: device name is unknown; the key will be wrong")
        return name
    }

    private fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ble.write(bytes)
    }

    private fun respond(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        Logger.log("TXD: ${WaterUtils.bufferToHexString(bytes)}")
        ble.write(bytes)
    }

    private fun setStage(newStage: Stage) {
        if (stage == newStage) return
        stage = newStage
        callback.onStageChanged(newStage, deviceName)
    }

    private fun fail(reason: Reason, detail: String) {
        Logger.log("ERROR: $reason -- $detail")
        val failure = Failure(reason, detail, Logger.getLogs())
        if (failure.isFatal) shutdown()
        callback.onFailure(failure)
    }

    private fun cancelScanTimeout() {
        pendingScanTimeout?.let { main.removeCallbacks(it) }
        pendingScanTimeout = null
    }

    private fun cancelAllTimers() {
        cancelScanTimeout()
        cancelStartEpilogue()
        pendingTimeout?.let { main.removeCallbacks(it) }
        pendingTimeout = null
    }

    private fun hex(byte: Byte): String =
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)

    /**
     * Whether a device name looks like one of these controllers.
     *
     * The controllers are named `Water` followed by five digits (for example `Water33982`,
     * which is the name used throughout upstream's tests); the key derivation depends on the
     * last five characters, and `makeStartEpilogue` checksums those five characters, so the
     * name really is load-bearing. The prefix list also covers the other spellings seen in
     * the wild. Everything else is treated as a stranger's device.
     */
    private fun looksLikeWaterController(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return CONTROLLER_NAME_PREFIXES.any { lower.startsWith(it) }
    }

    // -------------------------------------------------------- WaterBleClient.Listener

    override fun onScanStarted() = Unit

    override fun onDeviceFound(device: android.bluetooth.BluetoothDevice, name: String) {
        val likely = looksLikeWaterController(name)
        Logger.log("SCAN: found $name (${device.address})${if (likely) "" else " [not a controller?]"}")
        foundAnything = true
        // A likely controller is the signal to stop soon; strangers' devices are not, otherwise
        // the first laptop in range would cut the scan short before the controller appears.
        if (likely) onControllerSpotted()
        callback.onDeviceFound(device, name, likely)
    }

    override fun onAdapterUnavailable(reason: String) {
        fail(Reason.BLUETOOTH_UNAVAILABLE, reason)
    }

    override fun onConnected(deviceName: String) {
        // Prefer the name reported by the device itself; it is what the key derivation uses.
        this.deviceName = deviceName.ifEmpty { this.deviceName ?: "" }
        Logger.log("GATT: connected to $deviceName")
        cancelScanTimeout()
        ble.stopScan()
        callback.onProgress(Progress.NEGOTIATING)
    }

    override fun onServicesReady(characteristicCount: Int) {
        Logger.log("GATT: services discovered ($characteristicCount characteristics)")
        // Notifications are now enabled on RXD, so the connection is usable -- but the start
        // prologue is *not* sent here. Starting a session opens a billing session on the
        // controller, so it only ever happens in response to the user pressing the button.
        cancelScanTimeout()
        ble.stopScan()
        prologueSent = false
        callback.onProgress(Progress.READY)

        // Services are up, so this connection genuinely works: remember it. Failures earlier
        // in the handshake never reach this point, which is what makes this the "last *valid*
        // connection" rather than merely "the last thing we tried".
        val name = deviceName.orEmpty()
        val address = ble.connectedDeviceAddress
        if (name.isNotEmpty() && address != null) {
            callback.onDeviceRemembered(address, name)
        }

        // The controller is connected and idle. Opening a session is now the user's call, via
        // the Start button, and only that button can move the stage to RUNNING.
        setStage(Stage.CONNECTED)
    }

    override fun onNotification(bytes: ByteArray) {
        for (frame in parser.accept(bytes)) {
            onFrame(frame)
        }
    }

    override fun onGattFailure(operation: String, status: Int) {
        val detail = "$operation failed (status $status)"
        val reason = if (operation == "getPrimaryService(0xF1F0)" ||
            operation == "getCharacteristic(0xF1F1/0xF1F2)"
        ) {
            Reason.UNSUPPORTED_SERVICE
        } else {
            Reason.CONNECTION_ERROR
        }
        failUnlessAlreadyStandby(reason, detail)
    }

    override fun onDisconnected() {
        if (stage != Stage.STANDBY) {
            Logger.log("GATT: disconnected")
            cancelAllTimers()
            isStarted = false
            prologueSent = false
            deviceName = null
            setStage(Stage.STANDBY)
            callback.onDisconnected()
        }
    }

    override fun onWrite(bytes: ByteArray) = Unit
}
