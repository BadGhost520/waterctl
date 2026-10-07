/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.badghost.watercontrol.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * The single screen of the app.
 *
 * This replaces the DOM manipulation of the original `updateUi`/`handleBluetoothError` with
 * ordinary view updates, but keeps the same three stages and the same wording.
 */
class MainActivity : AppCompatActivity(), WaterSession.Callback {

    companion object {
        /** The protocol version of the upstream project this port implements. */
        private const val UPSTREAM_VERSION = "2.1.4"

        /** How many log lines the debug panel renders at most. */
        private const val MAX_LOG_LINES = 200

        /**
         * How long a finished scan stays reusable.
         *
         * Within this window pressing Connect re-opens the list the user just produced; after
         * it, the result is treated as stale and a fresh scan runs instead, because nearby
         * BLE devices come and go quickly.
         */
        private const val SCAN_RESULT_LIFETIME_MS = 60_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: WaterSession
    private lateinit var lastDeviceStore: LastDeviceStore

    private var logVisible = false

    /** The remembered controller, or `null` when nothing has been connected yet. */
    private var lastDevice: LastDeviceStore.Entry? = null

    /**
     * The controller a reconnect is currently being attempted for, so a failure can be
     * reported in terms of that device rather than as a generic connection error.
     */
    private var reconnectingTo: LastDeviceStore.Entry? = null

    /**
     * Devices found during the current scan, keyed by advertised name.
     *
     * The name is the key because it is what the user recognises (and what the protocol
     * actually depends on); the address is only shown in the debug log.
     */
    private val foundDevices = LinkedHashMap<String, BluetoothDevice>()

    /**
     * Whether the current [foundDevices] came from a scan that has already finished.
     *
     * While `false` a press on Connect starts a scan; once `true` it re-presents the list
     * instead, so the result stays reachable without scanning again.
     */
    private var scanCompleted = false

    /** When [scanCompleted] became true, used to judge whether the result is still usable. */
    private var scanCompletedAt = 0L

    /** Whether a BLE scan is currently running, so the buttons can be gated on it. */
    private var scanning = false

    /** The device picker currently on screen, so a new one can replace rather than stack. */
    private var pickerDialog: AlertDialog? = null

    /** The "nothing found" dialog, so it can be closed once a retry succeeds. */
    private var scanEmptyDialog: AlertDialog? = null

    private val loggerListener: (List<String>) -> Unit = { lines ->
        renderLog(lines)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val denied = granted.filterValues { !it }.keys
        if (denied.isEmpty()) {
            session.beginScan()
        } else {
            showDialog(
                getString(R.string.app_name),
                getString(R.string.permission_rationale),
                denied.joinToString(),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = WaterSession(this, this)
        lastDeviceStore = LastDeviceStore(this)

        binding.versionText.text = getString(R.string.version_format, UPSTREAM_VERSION)

        binding.connectButton.setOnClickListener {
            // A finished scan is shown again only while it is still fresh. Beyond that the list
            // may name controllers that have since gone away, and a fresh scan is what the user
            // actually wants -- so a stale result is replaced rather than replayed.
            val freshResult = scanCompleted &&
                SystemClock.elapsedRealtime() - scanCompletedAt < SCAN_RESULT_LIFETIME_MS
            session.handleConnectButtonClick(showFoundDevices = freshResult)
        }
        binding.mainButton.setOnClickListener { session.handleStartButtonClick() }
        binding.reconnectButton.setOnClickListener { onReconnectClick() }
        binding.refreshButton.setOnClickListener { refreshScan() }
        binding.logToggle.setOnClickListener { toggleLog() }

        Logger.addListener(loggerListener)

        refreshReconnectButton()
        setStage(WaterSession.Stage.STANDBY, null)
        startScan()
    }

    override fun onDestroy() {
        Logger.removeListener(loggerListener)
        session.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ permissions

    private fun startScan() {
        foundDevices.clear()
        scanCompleted = false
        scanCompletedAt = 0L
        pickerDialog?.dismiss()
        pickerDialog = null
        val missing = session.missingPermissions()
        if (missing.isEmpty()) {
            session.beginScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * The rescan button: throw away the previous result and look again.
     *
     * A scan can legitimately come back with nothing -- the controller may still be waking up,
     * or someone else's phone may have been holding the link -- so there has to be a way to
     * simply try again without leaving the screen.
     */
    private fun refreshScan() {
        if (session.isConnected) {
            // Reconnecting is the only way to pick up a different controller while linked;
            // disconnecting first is what "refresh" has to mean in that state.
            Logger.log("UI: refresh pressed while connected, disconnecting first")
            session.handleConnectButtonClick()
            return
        }
        Logger.log("UI: refresh pressed, rescanning")
        startScan()
    }

    // ------------------------------------------------------------------------ UI

    /**
     * Shows the one-tap reconnect button for the remembered controller, or hides it when
     * nothing has been connected yet or a link is already up.
     *
     * The gate is the session *stage*, not `session.isConnected`: teardown flips the stage to
     * STANDBY before the BLE stack reports the disconnect, so keying off the connection flag
     * would leave the button hidden after a manual disconnect.
     *
     * The button names the controller it will connect to, so pressing it is as explicit a
     * choice as selecting that device from the scanned list.
     */
    /**
     * Shows the one-tap reconnect button for the remembered controller, or hides it when
     * nothing has been connected yet, a link is already up, or a scan is competing for the
     * same radio.
     *
     * The gate is the session *stage*, not `session.isConnected`: teardown flips the stage to
     * STANDBY before the BLE stack reports the disconnect, so keying off the connection flag
     * would leave the button hidden after a manual disconnect.
     *
     * The remembered device is read from disk on every call, so it survives the app being
     * killed and relaunched.
     */
    private fun refreshReconnectButton() {
        val entry = lastDeviceStore.load()
        lastDevice = entry
        val disconnected = session.currentStage == WaterSession.Stage.STANDBY
        // Hidden during a scan too: the shortcut would compete with the scan for the same link,
        // and offering two different "connect" actions at once is just confusing.
        if (entry == null || !disconnected || scanning) {
            binding.reconnectButton.visibility = View.GONE
            return
        }
        binding.reconnectButton.text = getString(R.string.action_reconnect, entry.name)
        binding.reconnectButton.visibility = View.VISIBLE
    }

    /** Connect straight to the remembered controller, skipping the scan. */
    private fun onReconnectClick() {
        val entry = lastDevice ?: return
        // Connecting is not starting: the session still waits in CONNECTED for an explicit
        // "寮€鍚?, exactly as it does after picking from the list.
        reconnectingTo = entry
        binding.statusText.text = getString(R.string.status_connecting, entry.name)
        session.reconnectTo(entry.address, entry.name)
    }

    private fun toggleLog() {
        logVisible = !logVisible
        binding.logContainer.visibility = if (logVisible) View.VISIBLE else View.GONE
        binding.logToggle.setText(
            if (logVisible) R.string.action_hide_log else R.string.action_show_log
        )
        renderLog(Logger.getLogs())
    }

    private fun renderLog(lines: List<String>) {
        if (!logVisible) return
        binding.logText.text = if (lines.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        }
        binding.logContainer.post {
            binding.logContainer.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Drives both buttons from the session stage.
     *
     * The two controls are deliberately independent: Connect/Disconnect reflects the *link*,
     * Start/Stop reflects the *billing session*. Start/Stop is greyed out until the link is
     * up, so there is no way to press it while disconnected.
     */
    private fun setStage(stage: WaterSession.Stage, deviceName: String?) {
        // `updateUi` from the original, stage for stage.
        binding.deviceNameLabel.text = if (deviceName.isNullOrEmpty()) {
            getString(R.string.status_not_connected)
        } else {
            getString(R.string.status_connected, deviceName)
        }

        when (stage) {
            WaterSession.Stage.STANDBY -> {
                binding.connectButton.setText(R.string.action_connect)
                binding.connectButton.isEnabled = true
                // No link, no session: the start button must not be pressable here.
                binding.mainButton.setText(R.string.action_start)
                binding.mainButton.isEnabled = false
                showRefreshButton(RefreshState.VISIBLE)
                showIdleStatus()
            }

            WaterSession.Stage.PENDING -> {
                binding.connectButton.setText(R.string.action_connecting)
                // Stays live so a connect attempt that hangs can be abandoned: pressing it
                // again cancels and rescans rather than leaving the user stuck waiting.
                binding.connectButton.isEnabled = true
                binding.mainButton.setText(R.string.action_wait)
                binding.mainButton.isEnabled = false
                // Hidden during the connect handshake; the scan itself hides it separately
                // from `onProgress(SCANNING)`.
                showRefreshButton(RefreshState.HIDDEN)
                binding.statusText.setText(R.string.status_busy)
            }

            WaterSession.Stage.CONNECTED -> {
                binding.connectButton.setText(R.string.action_disconnect)
                binding.connectButton.isEnabled = true
                // Connected and idle, so opening a session is now allowed.
                binding.mainButton.setText(R.string.action_start)
                binding.mainButton.isEnabled = true
                showRefreshButton(RefreshState.HIDDEN)
                binding.statusText.text = getString(
                    R.string.status_ready,
                    deviceName ?: "",
                )
            }

            WaterSession.Stage.RUNNING -> {
                binding.connectButton.setText(R.string.action_disconnect)
                binding.connectButton.isEnabled = true
                binding.mainButton.setText(R.string.action_stop)
                binding.mainButton.isEnabled = true
                showRefreshButton(RefreshState.HIDDEN)
                binding.statusText.setText(R.string.status_running)
            }
        }
    }

    /**
     * Sets how available the rescan button is.
     *
     * Three states, because "not now" and "not here" are different things to the user:
     *
     *  * `VISIBLE`  -- idle, so a rescan is exactly what is wanted;
     *  * `DIMMED`   -- a scan is already running, so the button stays on screen but greyed
     *                  out. Fading it to nothing would make it look like the app lost a
     *                  control, and a disabled button is the usual way to say "busy";
     *  * `HIDDEN`   -- connected or running, where scanning cannot help.
     *
     * `HIDDEN` uses alpha 0 rather than `GONE` on purpose: the button shares its row with the
     * device-name label, so removing it from the layout would shift that label around.
     */
    private enum class RefreshState { VISIBLE, DIMMED, HIDDEN }

    private fun showRefreshButton(state: RefreshState) {
        when (state) {
            RefreshState.VISIBLE -> {
                binding.refreshButton.alpha = 1f
                binding.refreshButton.isEnabled = true
            }

            RefreshState.DIMMED -> {
                binding.refreshButton.alpha = 0.4f
                binding.refreshButton.isEnabled = false
            }

            RefreshState.HIDDEN -> {
                binding.refreshButton.alpha = 0f
                binding.refreshButton.isEnabled = false
            }
        }
    }

    /**
     * The status line for the idle state, which differs depending on whether a finished scan
     * left devices to choose from.
     *
     * Kept separate from [setStage] because `setStage(STANDBY)` returns to standby after a
     * disconnect too, where the previous scan result is still available.
     */
    private fun showIdleStatus() {
        if (session.currentStage != WaterSession.Stage.STANDBY) return
        binding.statusText.text = when {
            foundDevices.isNotEmpty() ->
                resources.getQuantityString(
                    R.plurals.status_found_devices,
                    foundDevices.size,
                    foundDevices.size,
                )

            else -> getString(R.string.status_idle_hint)
        }
    }

    private fun showDialog(title: String, message: String, detail: String?) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)

        if (!detail.isNullOrBlank()) {
            // Mirrors the original's "璋冭瘯淇℃伅" block, which is only shown for errors that
            // are worth reporting upstream.
            val debug = TextView(this).apply {
                text = detail
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setPadding(48, 24, 48, 24)
                setTextIsSelectable(true)
            }
            builder.setView(debug)
        }

        builder.show()
    }

    // ------------------------------------------------------------ WaterSession.Callback

    override fun onStageChanged(stage: WaterSession.Stage, deviceName: String?) {
        // A pick from the scanned list supersedes any pending reconnect bookkeeping.
        if (stage == WaterSession.Stage.CONNECTED) reconnectingTo = null
        setStage(stage, deviceName)
        // The button only makes sense while disconnected, so it is re-evaluated whenever the
        // stage moves rather than being toggled ad hoc at each call site.
        refreshReconnectButton()
    }

    override fun onDeviceFound(
        device: BluetoothDevice,
        name: String,
        isLikelyController: Boolean,
    ) {
        foundDevices[name] = device
        // Only the status line reports progress; no dialog is raised per discovery, and none is
        // raised when the scan ends either (see [onScanComplete]).
        binding.statusText.text = if (isLikelyController) {
            getString(R.string.status_found_controller, name)
        } else {
            getString(R.string.status_found_other, name)
        }
    }

    /**
     * A scan finished. The result is *not* pushed at the user as a dialog: the status line
     * reports it and the Connect button becomes the way to open the list.
     */
    override fun onScanComplete() {
        // The scan phase ends here, so two things have to be handed back explicitly.
        //
        // The Connect button, because `Progress.SCANNING` is what disabled it and no further
        // progress event follows.
        //
        // And the reconnect button: `setStage` only notifies through `onStageChanged` when the
        // stage actually *changes*, and after a scan the stage is already STANDBY -- so relying
        // on that callback left the button hidden for the whole session, which is exactly the
        // "the last device was forgotten" symptom.
        scanning = false
        scanCompleted = true
        scanCompletedAt = SystemClock.elapsedRealtime()
        // A retry from the not-found dialog succeeded, so that dialog has served its purpose.
        scanEmptyDialog?.dismiss()
        scanEmptyDialog = null
        setStage(session.currentStage, session.currentDeviceName)
        refreshReconnectButton()
    }

    /** The user pressed Connect while idle, so re-present the list from the last scan. */
    override fun onShowFoundDevices() {
        showPicker(expanded = false)
    }

    /**
     * Presents the discovery result exactly once, sorted so that plausible controllers come
     * first.
     *
     * @param expanded when `true`, every device is listed; otherwise only the plausible
     *   controllers are shown and "鏄剧ず鍏ㄩ儴璁惧" reveals the rest.
     */
    private fun showPicker(expanded: Boolean) {
        val candidates = foundDevices.keys.filter { looksLikeController(it) }.sorted()
        val others = foundDevices.keys.filterNot { looksLikeController(it) }.sorted()
        val showAll = expanded || candidates.isEmpty()
        val entries: List<String> = if (showAll) candidates + others else candidates

        // Any previous picker is dismissed first so that two can never stack: opening the
        // expanded list from the short one replaces it rather than layering on top.
        pickerDialog?.dismiss()
        pickerDialog = null

        if (entries.isEmpty()) {
            // Genuinely nothing to offer: say so instead of showing an empty list.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_pick_device)
                .setMessage(R.string.dialog_pick_device_hint)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
            return
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (showAll) R.string.dialog_pick_device else R.string.dialog_pick_controller)
            .setItems(entries.toTypedArray()) { _, which ->
                pickerDialog = null
                val name = entries[which]
                // Choosing the controller that is already connected is a no-op rather than a
                // reconnect, which would drop a live session on the floor.
                if (name == connectingToName && session.isConnected) return@setItems
                connectingToName = name
                foundDevices[name]?.let { device ->
                    session.connectTo(device, name)
                }
            }
            .setOnCancelListener { pickerDialog = null }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> pickerDialog = null }

        // No explanatory message is attached alongside the list: on a phone screen a long
        // message takes the vertical space the list needs and can squeeze it out entirely.
        if (!showAll && others.isNotEmpty()) {
            builder.setNeutralButton(R.string.action_show_all_devices) { _, _ ->
                pickerDialog = null
                showPicker(expanded = true)
            }
        }

        pickerDialog = builder.show()
    }

    private fun looksLikeController(name: String): Boolean =
        WaterSession.CONTROLLER_NAME_PREFIXES.any { name.lowercase(Locale.ROOT).startsWith(it) }

    override fun onScanEmpty() {
        // Same hand-back as `onScanComplete`: the scan disabled the reconnect and rescan
        // buttons, and a fruitless scan should still leave both reachable.
        scanning = false
        scanCompleted = true
        scanCompletedAt = SystemClock.elapsedRealtime()
        showIdleStatus()
        refreshReconnectButton()
        showNotFoundDialog()
    }

    /**
     * "Nothing found" with a retry action.
     *
     * Tapping retry rescans without dismissing the dialog, so repeated attempts are one tap
     * each; if a scan then succeeds the dialog is closed from [onScanComplete].
     */
    private fun showNotFoundDialog() {
        // A retry that also finds nothing lands here again, so the previous instance has to go
        // first -- otherwise the dialogs pile up on top of each other.
        scanEmptyDialog?.dismiss()
        scanEmptyDialog = null

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.not_found)
            .setPositiveButton(R.string.action_refresh, null)
            .setNegativeButton(R.string.dialog_ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Logger.log("UI: retrying the scan from the not-found dialog")
                startScan()
            }
        }
        scanEmptyDialog = dialog
        dialog.show()
    }

    override fun onDisconnected() {
        connectingToName = null
        // The controller may have gone out of range, so offer the direct route again. The
        // stage may not have moved (for example after a drop the session did not initiate),
        // so this is refreshed here as well as from `onStageChanged`.
        refreshReconnectButton()
        showIdleStatus()
    }

    override fun onDeviceRemembered(address: String, name: String) {
        // The attempt (reconnect or otherwise) succeeded, so it is no longer "pending".
        reconnectingTo = null
        lastDeviceStore.save(address, name)
        Logger.log("SESSION: remembered $name ($address) as the last valid connection")
        // While connected there is nothing to reconnect to, so the button stays hidden; it
        // reappears from `onDisconnected` or on the next launch.
        refreshReconnectButton()
    }

    /** The name of the controller being connected to, for the "connecting" message. */
    private var connectingToName: String? = null

    override fun onProgress(progress: WaterSession.Progress) {
        when (progress) {
            WaterSession.Progress.SCANNING -> {
                scanning = true
                binding.statusText.setText(R.string.status_searching)
                // There is nothing to cancel while merely scanning, so the button goes inert
                // and says what is happening instead. A second tap used to do nothing visible,
                // which read as "the app is stuck".
                binding.connectButton.setText(R.string.action_scanning)
                binding.connectButton.isEnabled = false
                // Stay on screen but greyed out: a scan is already running, so there is
                // nothing to refresh, yet disappearing would look like a lost control.
                showRefreshButton(RefreshState.DIMMED)
                // The reconnect shortcut would compete with the scan for the same link.
                refreshReconnectButton()
            }

            WaterSession.Progress.CONNECTING -> {
                scanning = false
                binding.statusText.text = getString(
                    R.string.status_connecting,
                    connectingToName.orEmpty(),
                )
                // Deliberately left enabled: a connect attempt that hangs has to stay
                // cancellable, and pressing again abandons it and rescans.
                binding.connectButton.setText(R.string.action_connecting)
                binding.connectButton.isEnabled = true
                showRefreshButton(RefreshState.HIDDEN)
                refreshReconnectButton()
            }

            WaterSession.Progress.READY -> {
                scanning = false
                binding.statusText.setText(R.string.status_ready)
                showRefreshButton(RefreshState.HIDDEN)
                refreshReconnectButton()
            }

            WaterSession.Progress.NEGOTIATING ->
                binding.statusText.setText(R.string.status_negotiating)

            WaterSession.Progress.ENDING ->
                binding.statusText.setText(R.string.status_ending)

            WaterSession.Progress.DISCONNECTING -> {
                scanning = false
                binding.statusText.setText(R.string.status_disconnecting)
                // The teardown is asynchronous; both buttons stay inert until it completes so
                // a second press cannot send another end prologue into a closing link.
                binding.connectButton.isEnabled = false
                binding.mainButton.isEnabled = false
                showRefreshButton(RefreshState.HIDDEN)
            }
        }
    }
    override fun onFailure(failure: WaterSession.Failure) {
        // A reconnect that could not reach the remembered controller deserves its own wording:
        // the generic "connection unstable" message would not tell the user what to do next.
        val wasReconnect = reconnectingTo != null
        val message = if (wasReconnect &&
            (failure.reason == WaterSession.Reason.CONNECTION_ERROR ||
                failure.reason == WaterSession.Reason.UNSUPPORTED_SERVICE)
        ) {
            getString(R.string.err_reconnect_failed, reconnectingTo?.name.orEmpty())
        } else {
            getString(
                when (failure.reason) {
                    WaterSession.Reason.UNKNOWN_RXD_DATA -> R.string.err_unknown_rxd
                    WaterSession.Reason.BAD_KEY -> R.string.err_bad_key
                    WaterSession.Reason.OPERATION_TIMED_OUT -> R.string.err_timeout
                    WaterSession.Reason.UNSUPPORTED_SERVICE -> R.string.err_unsupported
                    WaterSession.Reason.REFUSED -> R.string.err_bad_key
                    WaterSession.Reason.BLUETOOTH_UNAVAILABLE -> R.string.err_bluetooth_unavailable
                    WaterSession.Reason.CONNECTION_ERROR -> R.string.err_connection
                }
            )
        }
        reconnectingTo = null
        refreshReconnectButton()

        // `Failure.debugInfo` is a snapshot taken before teardown, so a fatal error still shows
        // the exchange that led up to it.
        val debug = if (failure.reason.showsDebugInfo) {
            (listOf(failure.detail) + failure.debugInfo).joinToString("\n")
        } else {
            failure.detail
        }
        showDialog(getString(R.string.app_name), message, debug)
    }
}
