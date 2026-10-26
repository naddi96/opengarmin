package dev.opengarmin.app.sync

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.WorkoutSendStatus
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.util.formatFileSize
import dev.opengarmin.protocol.FitPaceBand
import dev.opengarmin.protocol.FitWorkout
import dev.opengarmin.protocol.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the visible BLE session: connection lifecycle, watchdog/reconnect,
 * sync notifications, and queued workout/pace-plan uploads.
 *
 * Compose observes the exposed properties; all state writes happen on the main
 * thread, matching the previous Activity-owned implementation.
 */
class SyncSessionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: PulseSettings,
    private val repository: ActivityRepository,
    private val ui: SyncUiState,
    private val importer: ActivityImporter,
    private val isActivityKnown: (String) -> Boolean,
    private val requestNotificationPermission: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var client: GarminBleClient? = null
    private var coordinator: SyncCoordinator? = null
    private var syncAttempts = 0
    private val maxSyncAttempts = 8
    private var watchdog: Runnable? = null
    private var pendingWorkoutSend: PendingWorkoutSend? = null
    private var syncInProgress = false
    private var syncTrigger = "manual"
    private var syncStartedAt = 0L
    private var appVisible = false
    private var disconnectWhenSyncFinishes = false
    private var syncNotificationActive = false
    private var aclReceiverRegistered = false

    private val devices = mutableStateListOf<DeviceEntry>()

    private data class DeviceEntry(val device: BluetoothDevice, val rssi: Int) {
        val label: String get() = "${device.name ?: "(unnamed)"}  ${device.address}  ${rssi} dBm"
    }

    private enum class UploadKind { WORKOUT, PACE_PLAN }

    private data class PendingWorkoutSend(
        val id: Long,
        val name: String,
        val bytes: ByteArray,
        val kind: UploadKind = UploadKind.WORKOUT,
    )

    private data class UploadState(
        val id: Long,
        val name: String,
        val sending: Boolean,
        val success: Boolean = false,
        val message: String,
    )

    private data class UploadRequest<T>(
        val kind: UploadKind,
        val initialId: Long,
        val initialName: String,
        val preparingMessage: String,
        val logPrefix: String,
        val requiresWatchBeforeSave: Boolean,
        val noWatchMessage: String,
        val saveFailureMessage: String,
        val encodeFailureMessage: String,
        val save: suspend () -> T,
        val encode: (T) -> ByteArray,
        val id: (T) -> Long,
        val name: (T) -> String,
        val onSaved: (T) -> Unit = {},
        val onStatus: (UploadState) -> Unit,
    )

    private val watchReconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            main.post {
                if (!appVisible || syncInProgress) return@post
                if (SyncGate.owner() == SyncGate.OWNER_BACKGROUND) return@post
                val watch = client?.findBondedWatch() ?: return@post
                if (watch.address != device.address) return@post
                ui.log("watch reconnected; starting sync")
                syncNow(trigger = "reconnect")
            }
        }
    }

    init {
        importer.onImportProgress = {
            updateSyncNotification(progress = ui.activityProgress)
        }
        initialize()
    }

    private fun initialize() {
        val c = GarminBleClient(
            context = context,
            onLog = { ui.log(it) },
            onDeviceFound = { device, rssi ->
                main.post {
                    if (devices.none { it.device.address == device.address }) {
                        devices.add(DeviceEntry(device, rssi))
                    }
                }
            },
            onStateChanged = { s ->
                main.post {
                    ui.phase = when (s) {
                        "connected" -> SyncPhase.Connected
                        "failed" -> SyncPhase.Failed
                        "reconnecting" -> SyncPhase.Reconnecting
                        "disconnected" -> if (syncInProgress) SyncPhase.Failed else SyncPhase.Idle
                        else -> SyncPhase.Idle
                    }
                    when (s) {
                        "connected" -> {
                            coordinator?.reset()
                            pendingWorkoutSend?.let { pending ->
                                when (pending.kind) {
                                    UploadKind.WORKOUT -> coordinator?.enqueueWorkout(
                                        pending.id,
                                        pending.name,
                                        pending.bytes,
                                    )
                                    UploadKind.PACE_PLAN -> coordinator?.enqueuePacePlan(
                                        pending.id,
                                        pending.name,
                                        pending.bytes,
                                    )
                                }
                            }
                            ui.activityProgress = null
                            requestNotificationPermission()
                            syncNotificationActive = true
                            SyncNotifications.start(context)
                            updateSyncNotification(
                                status = "Connected · reading activity list…",
                                progress = null,
                            )
                            client?.findBondedWatch()?.name?.let { settings.updateWatchName(it) }
                            armWatchdog()
                        }
                        "failed" -> {
                            val reason = syncFailureReason()
                            coordinator?.clearWorkouts()
                            pendingWorkoutSend?.let { pending ->
                                pendingWorkoutSend = null
                                when (pending.kind) {
                                    UploadKind.WORKOUT -> ui.workoutSendStatus = WorkoutSendStatus(
                                        workoutId = pending.id,
                                        name = pending.name,
                                        sending = false,
                                        success = false,
                                        message = reason,
                                    )
                                    UploadKind.PACE_PLAN -> ui.pacePlanSendStatus = PacePlanSendStatus(
                                        planId = pending.id,
                                        name = pending.name,
                                        sending = false,
                                        success = false,
                                        message = reason,
                                    )
                                }
                            }
                            finishSync(success = false, error = reason)
                        }
                        "reconnecting" -> {
                            cancelWatchdog()
                            if (ui.activityProgress != null) {
                                updateSyncNotification(
                                    status = "Reconnecting to watch…",
                                    progress = null,
                                )
                            }
                        }
                        "disconnected" -> {
                            cancelWatchdog()
                            if (syncInProgress) {
                                finishSync(
                                    success = false,
                                    error = "Watch disconnected before the sync finished.",
                                )
                            } else {
                                ui.activityProgress = null
                                SyncGate.release(SyncGate.OWNER_UI)
                            }
                        }
                    }
                }
            },
        )
        client = c
        runCatching {
            ContextCompat.registerReceiver(
                context,
                watchReconnectReceiver,
                IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            aclReceiverRegistered = true
        }

        c.session.onGfdiMessage = { frame ->
            main.post {
                val payload = Hex.encode(frame.payload)
                val shown = if (payload.length <= 120) payload else payload.take(120) + "…"
                ui.log("GFDI type=${frame.type} seq=${frame.seq} len=${frame.payload.size} $shown")
            }
        }

        coordinator = SyncCoordinator(
            session = c.session,
            filesDir = context.filesDir,
            handler = main,
            log = { ui.log(it) },
            isItemKnown = isActivityKnown,
            isWellnessItemKnown = importer::isKnownWellnessUid,
            onFinished = { raw, decoded, itemUid ->
                cancelWatchdog()
                ui.log(
                    "SYNC complete: raw=${raw.length()} decoded=${decoded.length()} (${decoded.name})",
                )
                importer.importFit(decoded, itemUid)
            },
            onWellnessFile = { decoded, type, itemUid ->
                ui.log("WELLNESS file $type: ${decoded.name} (${decoded.length()} bytes)")
                importer.importWellness(decoded, type, itemUid)
            },
            onUpToDate = {
                cancelWatchdog()
                ui.activityProgress = null
                ui.phase = SyncPhase.UpToDate
                ui.log("SYNC up to date: all activity files already imported")
                finishSync(success = true)
            },
            onSyncError = { reason ->
                cancelWatchdog()
                ui.log("SYNC legacy error: $reason")
                finishSync(success = false, error = reason)
            },
            onActivityProgress = { progress ->
                ui.activityProgress = progress.takeIf { it.total > 0 && it.done < it.total }
                if (progress.total > 0 && progress.done < progress.total) {
                    ui.phase = SyncPhase.Syncing
                    updateSyncNotification(progress = ui.activityProgress)
                }
            },
            onWorkoutSent = { id, name, bytes ->
                val kind = pendingWorkoutSend?.kind ?: UploadKind.WORKOUT
                pendingWorkoutSend = null
                when (kind) {
                    UploadKind.WORKOUT -> {
                        ui.workoutSendStatus = WorkoutSendStatus(
                            workoutId = id,
                            name = name,
                            sending = false,
                            success = true,
                            message = "Sent to watch · ${formatFileSize(bytes.toLong())}",
                        )
                        scope.launch(Dispatchers.IO) {
                            runCatching { repository.markWorkoutSent(id) }
                        }
                        ui.log("WORKOUT sent: \"$name\" ($bytes bytes)")
                    }
                    UploadKind.PACE_PLAN -> {
                        ui.pacePlanSendStatus = PacePlanSendStatus(
                            planId = id,
                            name = name,
                            sending = false,
                            success = true,
                            message = "Sent to watch · ${formatFileSize(bytes.toLong())}",
                        )
                        scope.launch(Dispatchers.IO) {
                            runCatching { repository.markPacePlanSent(id) }
                        }
                        ui.log("PACE sent: \"$name\" ($bytes bytes)")
                    }
                }
            },
            onWorkoutFailed = { id, name, message ->
                Diagnostics.record(
                    area = Diagnostics.AREA_SYNC,
                    code = "UPLOAD_FAILED",
                    message = message,
                    context = mapOf("id" to id.toString(), "name" to name),
                )
                val kind = pendingWorkoutSend?.kind ?: UploadKind.WORKOUT
                pendingWorkoutSend = null
                when (kind) {
                    UploadKind.WORKOUT -> {
                        ui.workoutSendStatus = WorkoutSendStatus(
                            workoutId = id,
                            name = name,
                            sending = false,
                            success = false,
                            message = message,
                        )
                        ui.log("WORKOUT send failed: \"$name\" — $message")
                    }
                    UploadKind.PACE_PLAN -> {
                        ui.pacePlanSendStatus = PacePlanSendStatus(
                            planId = id,
                            name = name,
                            sending = false,
                            success = false,
                            message = message,
                        )
                        ui.log("PACE send failed: \"$name\" — $message")
                    }
                }
            },
        )
    }

    // Compose-facing state ---------------------------------------------------

    val logs: List<String> get() = ui.logs
    val activityProgress: ActivitySyncProgress? get() = ui.activityProgress
    val lastImportedLabel: String? get() = ui.lastImportedLabel
    val workoutSendStatus: WorkoutSendStatus? get() = ui.workoutSendStatus
    val pacePlanSendStatus: PacePlanSendStatus? get() = ui.pacePlanSendStatus

    val phase: SyncPhase get() = ui.phase

    fun deviceInfo(): List<Pair<String, String>> {
        val session = client?.session ?: return emptyList()
        return listOf(
            "GFDI handle" to if (session.gfdiHandle >= 0) "${session.gfdiHandle}" else "—",
            "product" to if (session.productNumber != 0) "${session.productNumber}" else "—",
            "firmware" to if (session.firmwareVersion != 0) "${session.firmwareVersion}" else "—",
            "unit id" to if (session.unitId != 0L) "${session.unitId}" else "—",
            "services mask" to "0x%06x".format(session.supportedServicesMask),
            "sync attempts" to "$syncAttempts",
        )
    }

    fun isSyncingOrImporting(): Boolean =
        ui.phase == SyncPhase.Syncing || ui.phase == SyncPhase.Importing

    // Lifecycle --------------------------------------------------------------

    fun onStart() {
        appVisible = true
        ui.log("APP onStart syncInProgress=$syncInProgress lease=${SyncGate.owner()}")
        if (syncInProgress) {
            disconnectWhenSyncFinishes = false
            return
        }
        val backgroundRunning = SyncGate.owner() == SyncGate.OWNER_BACKGROUND
        if (!backgroundRunning && SyncPhase.fromStored(settings.lastSyncStatus) == SyncPhase.Syncing) {
            // A process death or force-stop can leave the persisted status at
            // "syncing" even though no worker survived. Clear it before the
            // client/permission checks below: on a cold start there is no
            // client yet, and the chip would otherwise say "Syncing…" forever.
            settings.recordSyncError(
                "The previous sync was interrupted before it finished.",
                trigger = settings.lastSyncTrigger.ifBlank { "manual" },
                kind = "cancelled",
            )
        }
        val c = client ?: return
        if (!c.hasPermissions()) return
        if (backgroundRunning) {
            ui.phase = SyncPhase.Background
            ui.log("background sync is already running")
            return
        }
        val lastSync = settings.lastSyncAt
        val due = lastSync == null ||
            System.currentTimeMillis() - lastSync >= AUTO_SYNC_MIN_INTERVAL_MS
        if (due) syncNow(trigger = "launch")
    }

    fun onStop() {
        appVisible = false
        ui.log("APP onStop syncInProgress=$syncInProgress")
        if (syncInProgress) {
            // Let the transfer finish, then drop the BLE lease so the periodic
            // worker can take over while the app is in the background.
            disconnectWhenSyncFinishes = true
        } else {
            disconnectWatch()
        }
    }

    fun destroy() {
        cancelWatchdog()
        syncInProgress = false
        coordinator?.reset()
        client?.disconnect()
        SyncGate.release(SyncGate.OWNER_UI)
        if (aclReceiverRegistered) {
            runCatching { context.unregisterReceiver(watchReconnectReceiver) }
            aclReceiverRegistered = false
        }
        if (syncNotificationActive) {
            syncNotificationActive = false
            SyncNotifications.cancel(context)
        }
    }

    // Scanning ---------------------------------------------------------------

    fun hasPermissions(): Boolean = client?.hasPermissions() == true

    /** Clears the discovered devices and starts a scan when permissions allow. */
    fun startScan(): Boolean {
        val c = client ?: return false
        devices.clear()
        if (!c.hasPermissions()) return false
        c.startScan()
        return true
    }

    fun stopScan() {
        client?.stopScan()
    }

    // Sync -------------------------------------------------------------------

    fun syncNow(trigger: String = "manual") {
        syncNow(pendingWorkout = null, trigger = trigger)
    }

    fun disconnectWatch() {
        cancelWatchdog()
        if (syncInProgress) {
            val durationMs = (SystemClock.elapsedRealtime() - syncStartedAt).coerceAtLeast(0L)
            settings.recordSyncError(
                "Sync stopped before it finished.",
                durationMs = durationMs,
                trigger = syncTrigger,
                kind = "cancelled",
            )
        }
        syncInProgress = false
        ui.activityProgress = null
        coordinator?.reset()
        client?.disconnect()
        SyncGate.release(SyncGate.OWNER_UI)
        ui.log("BLE lease released (idle)")
        if (syncNotificationActive) {
            syncNotificationActive = false
            SyncNotifications.cancel(context)
        }
    }

    private fun syncNow(pendingWorkout: PendingWorkoutSend?, trigger: String) {
        if (pendingWorkout != null) pendingWorkoutSend = pendingWorkout
        val c = client ?: return
        if (syncInProgress) {
            pendingWorkout?.let { pending ->
                markPendingUploadFailed(pending, "A sync is already running. Try again when it finishes.")
            }
            return
        }
        if (!SyncGate.tryAcquire(SyncGate.OWNER_UI)) {
            val message = "A background sync is running. Try again when it finishes."
            ui.log(message)
            pendingWorkout?.let { markPendingUploadFailed(it, message) }
            return
        }
        if (!c.hasBluetoothAdapter()) {
            SyncGate.release(SyncGate.OWNER_UI)
            recordPermanentSetupError("Bluetooth LE is not available on this phone.", trigger)
            pendingWorkout?.let { markPendingUploadFailed(it, "Bluetooth LE is not available.") }
            return
        }
        if (!c.isBluetoothEnabled()) {
            SyncGate.release(SyncGate.OWNER_UI)
            recordPermanentSetupError("Bluetooth is off. Turn Bluetooth on, then sync again.", trigger)
            pendingWorkout?.let { markPendingUploadFailed(it, "Bluetooth is off.") }
            return
        }
        val watch = c.findBondedWatch()
        if (watch == null) {
            SyncGate.release(SyncGate.OWNER_UI)
            val message = "No bonded Garmin watch found. Pair the watch in Android Bluetooth settings."
            recordPermanentSetupError(message, trigger)
            pendingWorkout?.let { markPendingUploadFailed(it, message) }
            return
        }
        syncAttempts = 0
        cancelWatchdog()
        ui.activityProgress = null
        ui.lastImportedLabel = null
        ui.importedThisSync = 0
        ui.importErrorThisSync = null
        syncInProgress = true
        ui.phase = SyncPhase.Connecting
        syncTrigger = trigger
        syncStartedAt = SystemClock.elapsedRealtime()
        disconnectWhenSyncFinishes = false
        settings.recordSyncStarted(trigger)
        requestNotificationPermission()
        syncNotificationActive = true
        SyncNotifications.start(context)
        updateSyncNotification(
            status = when (pendingWorkoutSend?.kind) {
                UploadKind.PACE_PLAN -> "Connecting to ${watch.name} to send a pace plan…"
                UploadKind.WORKOUT -> "Connecting to ${watch.name} to send a workout…"
                null -> "Connecting to ${watch.name}…"
            },
            progress = null,
        )
        settings.updateWatchName(watch.name ?: "")
        coordinator?.reset()
        // Refresh the imported-UID set before the first directory response is
        // processed. Doing it here (rather than only when the Activity starts)
        // keeps the dedupe check independent of the activity Flow timing.
        scope.launch {
            withContext(Dispatchers.IO) { importer.reloadKnownActivityUids() }
            if (syncInProgress) c.connect(watch)
        }
    }

    private fun armWatchdog(timeoutMs: Long = 15_000) {
        cancelWatchdog()
        val r = Runnable {
            val co = coordinator ?: return@Runnable
            if (co.isDone()) return@Runnable
            if (co.isTransferring()) {
                armWatchdog(60_000)
                return@Runnable
            }
            if (!co.hasStarted() && syncAttempts == 0) {
                ui.log("awaiting watch-side sync (link stays open)")
                ui.phase = SyncPhase.AwaitingWatch
                armWatchdog(60_000)
                return@Runnable
            }
            if (syncAttempts < maxSyncAttempts) {
                syncAttempts++
                ui.log("sync stalled; reconnecting ($syncAttempts/$maxSyncAttempts)")
                ui.phase = SyncPhase.Reconnecting
                updateSyncNotification(status = "Reconnecting to watch…", progress = null)
                client?.reconnect()
            } else {
                val reason = syncFailureReason()
                ui.log("sync failed after $syncAttempts attempts: $reason")
                ui.phase = SyncPhase.Failed
                finishSync(success = false, error = reason)
            }
        }
        watchdog = r
        main.postDelayed(r, timeoutMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    private fun syncFailureReason(): String =
        if (coordinator?.stalledBeforeCapabilities() == true) {
            "The watch accepted the connection but did not open its GDI session. " +
                "Garmin Connect can hold that single session — close/force-stop it and try again."
        } else {
            "Could not finish syncing. Keep the watch nearby and try again."
        }

    private fun importedSummary(): String =
        if (ui.importedThisSync > 0) {
            "Imported ${ui.importedThisSync} " +
                (if (ui.importedThisSync == 1) "activity" else "activities")
        } else {
            "Everything up to date"
        }

    private fun syncProgressText(progress: ActivitySyncProgress): String {
        val current = (progress.done + 1).coerceAtMost(progress.total)
        return progress.currentSize?.let {
            "Activity $current of ${progress.total} · ${formatFileSize(it)}"
        } ?: "${progress.done} of ${progress.total} imported"
    }

    private fun updateSyncNotification(
        status: String? = null,
        progress: ActivitySyncProgress? = ui.activityProgress,
    ) {
        SyncNotifications.show(
            context = context,
            title = "Syncing watch",
            status = status ?: progress?.let { syncProgressText(it) } ?: "Waiting for the watch…",
            detail = ui.lastImportedLabel?.let { "Last imported: $it" },
            progress = progress,
            ongoing = true,
        )
    }

    private fun finishSyncNotification(title: String, status: String) {
        ui.log("SYNC notification: $title — $status")
        SyncNotifications.finish(
            context = context,
            title = title,
            status = status,
            detail = ui.lastImportedLabel?.let { "Last imported: $it" },
        )
        ui.importedThisSync = 0
        syncNotificationActive = false
    }

    private fun finishSync(success: Boolean, error: String? = null) {
        if (!syncInProgress) {
            SyncGate.release(SyncGate.OWNER_UI)
            return
        }
        syncInProgress = false
        cancelWatchdog()
        val durationMs = (SystemClock.elapsedRealtime() - syncStartedAt).coerceAtLeast(0L)
        val importError = ui.importErrorThisSync
        if (success && importError != null) {
            val reason = "Sync finished, but importing an activity failed: $importError"
            settings.recordSyncError(
                reason,
                durationMs = durationMs,
                trigger = syncTrigger,
                kind = SyncPolicy.FailureKind.TRANSIENT.storageKey,
            )
            ui.phase = SyncPhase.Failed
            finishSyncNotification("Sync failed", reason)
        } else if (success) {
            settings.recordSyncSuccess(durationMs, syncTrigger)
            ui.phase = SyncPhase.UpToDate
            finishSyncNotification("Sync complete", importedSummary())
        } else {
            val reason = error ?: "Could not finish syncing."
            val kind = SyncPolicy.classifyFailure(
                reason,
                stalledBeforeCapabilities = coordinator?.stalledBeforeCapabilities() == true,
            )
            settings.recordSyncError(
                reason,
                durationMs = durationMs,
                trigger = syncTrigger,
                kind = kind.storageKey,
            )
            ui.phase = SyncPhase.Failed
            finishSyncNotification("Sync failed", reason)
        }
        ui.activityProgress = null
        if (!success || importError != null || !appVisible || disconnectWhenSyncFinishes) {
            disconnectWhenSyncFinishes = false
            coordinator?.reset()
            client?.disconnect()
            SyncGate.release(SyncGate.OWNER_UI)
        }
    }

    private fun recordPermanentSetupError(message: String, trigger: String) {
        ui.log(message)
        settings.recordSyncStarted(trigger)
        settings.recordSyncError(
            message,
            durationMs = 0,
            trigger = trigger,
            kind = "permanent",
        )
    }

    private fun markPendingUploadFailed(pending: PendingWorkoutSend, message: String) {
        pendingWorkoutSend = null
        when (pending.kind) {
            UploadKind.WORKOUT -> ui.workoutSendStatus = WorkoutSendStatus(
                workoutId = pending.id,
                name = pending.name,
                sending = false,
                success = false,
                message = message,
            )
            UploadKind.PACE_PLAN -> ui.pacePlanSendStatus = PacePlanSendStatus(
                planId = pending.id,
                name = pending.name,
                sending = false,
                success = false,
                message = message,
            )
        }
    }

    // Uploads ----------------------------------------------------------------

    fun sendWorkout(workout: PlannedWorkoutEntity) {
        sendUpload(
            UploadRequest(
                kind = UploadKind.WORKOUT,
                initialId = workout.id,
                initialName = workout.name,
                preparingMessage = "Preparing workout…",
                logPrefix = "WORKOUT",
                requiresWatchBeforeSave = true,
                noWatchMessage = "No bonded Garmin watch found. Pair the watch first.",
                saveFailureMessage = "Could not save the workout.",
                encodeFailureMessage = "Could not build the FIT workout.",
                save = { repository.saveWorkout(workout) },
                encode = { FitWorkout.encode(it.toFitWorkout()) },
                id = { it.id },
                name = { it.name },
                onStatus = { state ->
                    ui.workoutSendStatus = WorkoutSendStatus(
                        workoutId = state.id,
                        name = state.name,
                        sending = state.sending,
                        success = state.success,
                        message = state.message,
                    )
                },
            ),
        )
    }

    fun sendPacePlan(plan: PacePlanEntity) {
        sendUpload(
            UploadRequest(
                kind = UploadKind.PACE_PLAN,
                initialId = plan.id,
                initialName = plan.name,
                preparingMessage = "Preparing pace plan…",
                logPrefix = "PACE",
                requiresWatchBeforeSave = false,
                noWatchMessage = "Saved on this phone. No bonded Garmin watch found.",
                saveFailureMessage = "Could not save the pace plan.",
                encodeFailureMessage = "Could not build the FIT pace band.",
                save = { repository.savePacePlan(plan) },
                encode = { FitPaceBand.encode(it.toFitPaceBand()) },
                id = { it.id },
                name = { it.name },
                onSaved = { ui.log("PACE saved \"${it.name}\" (${it.splits.size} splits)") },
                onStatus = { state ->
                    ui.pacePlanSendStatus = PacePlanSendStatus(
                        planId = state.id,
                        name = state.name,
                        sending = state.sending,
                        success = state.success,
                        message = state.message,
                    )
                },
            ),
        )
    }

    private fun <T> sendUpload(request: UploadRequest<T>) {
        if (ui.workoutSendStatus?.sending == true || ui.pacePlanSendStatus?.sending == true) return
        val watch = client?.findBondedWatch()
        if (request.requiresWatchBeforeSave && watch == null) {
            request.onStatus(
                UploadState(
                    id = request.initialId,
                    name = request.initialName,
                    sending = false,
                    message = request.noWatchMessage,
                ),
            )
            return
        }
        request.onStatus(
            UploadState(
                id = request.initialId,
                name = request.initialName,
                sending = true,
                message = request.preparingMessage,
            ),
        )
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { request.save() } }
                .getOrElse { error ->
                    request.onStatus(
                        UploadState(
                            id = request.initialId,
                            name = request.initialName,
                            sending = false,
                            message = error.message ?: request.saveFailureMessage,
                        ),
                    )
                    return@launch
                }
            request.onSaved(saved)
            if (watch == null) {
                request.onStatus(
                    UploadState(
                        id = request.id(saved),
                        name = request.name(saved),
                        sending = false,
                        message = request.noWatchMessage,
                    ),
                )
                return@launch
            }
            val bytes = runCatching { request.encode(saved) }.getOrElse { error ->
                request.onStatus(
                    UploadState(
                        id = request.id(saved),
                        name = request.name(saved),
                        sending = false,
                        message = error.message ?: request.encodeFailureMessage,
                    ),
                )
                return@launch
            }
            val pending = PendingWorkoutSend(request.id(saved), request.name(saved), bytes, request.kind)
            pendingWorkoutSend = pending
            request.onStatus(
                UploadState(
                    id = request.id(saved),
                    name = request.name(saved),
                    sending = true,
                    message = "Connecting to ${watch.name ?: "watch"}…",
                ),
            )
            ui.log("${request.logPrefix} preparing \"${request.name(saved)}\": ${bytes.size} FIT bytes")
            syncNow(pending, trigger = "upload")
        }
    }

    private companion object {
        const val AUTO_SYNC_MIN_INTERVAL_MS = 5 * 60 * 1000L

    }
}
