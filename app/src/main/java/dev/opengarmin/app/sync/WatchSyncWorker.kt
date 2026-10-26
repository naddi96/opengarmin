package dev.opengarmin.app.sync

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.opengarmin.app.analysis.ActivityAnalysisScheduler
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.AppDatabase
import dev.opengarmin.app.data.WellnessFileTypes
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless periodic sync. WorkManager starts it on the requested interval with
 * the battery/charging constraints; the same [SyncGate] used by the manual path
 * prevents two transfers from sharing the watch's single slot.
 */
class WatchSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // "Run background sync now" is allowed to retry; periodic work is not.
        // Retrying periodic work puts it in WorkManager's exponential backoff,
        // which can delay the next 15-minute interval by five hours.
        val oneTimeSync = inputData.getBoolean(SyncScheduler.ONE_TIME_INPUT_KEY, false)
        val settings = PulseSettings(applicationContext)
        if (!shouldRun(settings)) return Result.success()

        if (!SyncGate.tryAcquire(SyncGate.OWNER_BACKGROUND)) {
            Log.d(TAG, "skipped: another sync owns the transfer slot")
            return Result.success()
        }
        Log.d(TAG, "started")

        val startedAt = SystemClock.elapsedRealtime()
        settings.recordSyncStarted("background")
        val outcome = runWithForeground(settings, startedAt) { onStatus ->
            runBackgroundSync(onStatus)
        }

        val durationMs = SystemClock.elapsedRealtime() - startedAt
        reportOutcome(settings, outcome, durationMs, oneTimeSync)
        return resultFor(outcome, oneTimeSync)
    }

    /**
     * Periodic sync stays disabled by default, and a visible/manual sync or its
     * idle BLE lease owns the watch. Skip this period rather than opening a
     * second GATT connection; the next periodic interval (or the app itself)
     * will sync.
     */
    private fun shouldRun(settings: PulseSettings): Boolean {
        if (!settings.periodicSyncEnabled) {
            Log.d(TAG, "skipped: periodic sync disabled")
            return false
        }
        if (SyncGate.owner() == SyncGate.OWNER_UI) {
            Log.d(TAG, "skipped: UI lease held")
            return false
        }
        return true
    }

    /**
     * Runs [block] with the progress notification whenever Android allows a
     * foreground service. The BLE lease is held for the whole run and released
     * only after the notifier has drained and stopped.
     */
    private suspend fun runWithForeground(
        settings: PulseSettings,
        startedAt: Long,
        block: suspend (onStatus: (ForegroundState) -> Unit) -> BackgroundSyncOutcome,
    ): BackgroundSyncOutcome {
        val updates = Channel<ForegroundState>(Channel.CONFLATED)
        val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val foregroundAvailable = AtomicBoolean(false)
        val notifier = updateScope.launch {
            for (state in updates) {
                if (!foregroundAvailable.get()) continue
                try {
                    setForeground(createForegroundInfo(state))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d(TAG, "foreground update unavailable: ${error.message}")
                }
            }
        }
        var outcome: BackgroundSyncOutcome
        try {
            // Android 12+ can deny a foreground-service start from a periodic
            // worker while the app is in the background. That must not abort
            // the sync: the JobScheduler job still has its ~10-minute window,
            // and the final notification is posted normally. Keep using the
            // foreground notification whenever the OS allows it.
            try {
                setForeground(
                    createForegroundInfo(ForegroundState("Connecting to watch…", null, null)),
                )
                foregroundAvailable.set(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Running a long BLE sync headless makes the process cached;
                // Samsung/Android can then kill it mid-transfer and leave the
                // persisted status at "syncing". Fail fast with an actionable
                // message instead, and let the next interval (or the user
                // opening the app) try again.
                Log.d(
                    TAG,
                    "foreground service unavailable; aborting background sync: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
                Diagnostics.record(
                    area = Diagnostics.AREA_SYNC,
                    code = "BG_FOREGROUND_UNAVAILABLE",
                    message = "Background sync could not start a foreground service.",
                    throwable = error,
                )
                return BackgroundSyncOutcome.failure(
                    SyncPolicy.FailureKind.BUSY,
                    "Android blocked the background sync. Open OpenGarmin to sync.",
                )
            }
            outcome = block { state -> updates.trySend(state) }
        } catch (cancelled: CancellationException) {
            val durationMs = SystemClock.elapsedRealtime() - startedAt
            settings.recordSyncError(
                "Background sync was stopped by Android before it finished.",
                durationMs = durationMs,
                trigger = "background",
                kind = "cancelled",
            )
            throw cancelled
        } catch (error: Exception) {
            outcome = BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.TRANSIENT,
                error.message ?: "Background sync failed.",
            )
        } finally {
            updates.close()
            notifier.join()
            updateScope.cancel()
            SyncGate.release(SyncGate.OWNER_BACKGROUND)
        }
        return outcome
    }

    /** Records the run, then leaves the final success or attention notification. */
    private fun reportOutcome(
        settings: PulseSettings,
        outcome: BackgroundSyncOutcome,
        durationMs: Long,
        oneTimeSync: Boolean,
    ) {
        Log.d(
            TAG,
            "finished success=${outcome.success} kind=${outcome.kind} " +
                "activities=${outcome.importedActivities} wellnessDays=${outcome.importedWellnessDays} " +
                "durationMs=$durationMs",
        )
        if (outcome.success) {
            settings.recordSyncSuccess(durationMs, "background")
            SyncNotifications.notifyFinal(
                applicationContext,
                "Background sync complete",
                outcome.summary(),
                "Open OpenGarmin to review the new data.",
            )
        } else {
            val kind = outcome.kind ?: SyncPolicy.FailureKind.TRANSIENT
            settings.recordSyncError(
                outcome.message,
                durationMs = durationMs,
                trigger = "background",
                kind = kind.storageKey,
            )
            val willRetry = SyncPolicy.shouldRetryWork(oneTimeSync, kind, runAttemptCount)
            // A missing watch/permission is an actionable setup problem. Keep
            // it in Settings/Today instead of posting the same notification on
            // every interval. Transient failures get a dismissible note.
            if (kind != SyncPolicy.FailureKind.PERMANENT) {
                SyncNotifications.notifyFinal(
                    applicationContext,
                    "Background sync needs attention",
                    outcome.message,
                    when {
                        willRetry ->
                            "Android will retry with backoff. You can also open the app and sync manually."
                        oneTimeSync ->
                            "Open OpenGarmin to review the sync status."
                        else ->
                            "The next scheduled background sync will try again."
                    },
                )
            }
        }
    }

    private fun resultFor(outcome: BackgroundSyncOutcome, oneTimeSync: Boolean): Result {
        val kind = outcome.kind ?: SyncPolicy.FailureKind.TRANSIENT
        return when {
            outcome.success -> Result.success()
            // Periodic failures intentionally return success. WorkManager's
            // retry backoff would otherwise delay the next period by up to
            // five hours after a few stalled attempts.
            !oneTimeSync -> Result.success()
            SyncPolicy.shouldRetryWork(oneTimeSync, kind, runAttemptCount) -> Result.retry()
            else -> Result.failure()
        }
    }

    private suspend fun runBackgroundSync(
        onStatus: (ForegroundState) -> Unit,
    ): BackgroundSyncOutcome = withContext(Dispatchers.Main.immediate) {
        val run = BackgroundSyncRun(applicationContext, onStatus)
        run.loadKnownItems()
        val client = run.createClient()
        validateBluetooth(client)?.let { return@withContext it }

        val watch = client.findBondedWatch()
        if (watch == null) {
            return@withContext BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "No bonded Garmin watch found. Pair the watch in Android Bluetooth settings, then sync again.",
            )
        }

        val coordinator = run.createCoordinator(client)
        try {
            run.awaitSync(client, coordinator, watch)
        } finally {
            runCatching { client.disconnect() }
            run.cancelImports()
        }
    }

    /** The setup failures that make another attempt pointless until the user acts. */
    private fun validateBluetooth(client: GarminBleClient): BackgroundSyncOutcome? {
        if (!client.hasPermissions()) {
            return BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "Bluetooth permission is missing. Open Android Settings and allow Nearby devices for OpenGarmin.",
            )
        }
        if (!client.hasBluetoothAdapter()) {
            return BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "Bluetooth LE is not available on this phone.",
            )
        }
        if (!client.isBluetoothEnabled()) {
            return BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "Bluetooth is off. Turn Bluetooth on, then sync again.",
            )
        }
        return null
    }

    private fun createForegroundInfo(state: ForegroundState): ForegroundInfo {
        val notification = SyncNotifications.build(
            context = applicationContext,
            title = "Background watch sync",
            status = state.status,
            detail = state.detail,
            progress = state.progress,
            ongoing = true,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            ForegroundInfo(SyncNotifications.NOTIFICATION_ID, notification)
        }
    }

    private data class ForegroundState(
        val status: String,
        val detail: String?,
        val progress: ActivitySyncProgress?,
    )

    private data class BackgroundSyncOutcome(
        val success: Boolean,
        val kind: SyncPolicy.FailureKind?,
        val message: String,
        val importedActivities: Int = 0,
        val importedWellnessDays: Int = 0,
    ) {
        fun summary(): String = if (importedActivities == 0 && importedWellnessDays == 0) {
            "Everything up to date."
        } else {
            buildString {
                append("$importedActivities ${if (importedActivities == 1) "activity" else "activities"}")
                append(" · ")
                append("$importedWellnessDays wellness ${if (importedWellnessDays == 1) "day" else "days"}")
            }
        }

        companion object {
            fun success() = BackgroundSyncOutcome(
                success = true,
                kind = null,
                message = "Sync complete.",
            )

            fun failure(kind: SyncPolicy.FailureKind, message: String) =
                BackgroundSyncOutcome(success = false, kind = kind, message = message)
        }
    }

    /**
     * Per-run BLE and import state. The client callbacks, coordinator callbacks,
     * and reconnect loop all observe the same progress clock and completion
     * signal, so they live together instead of being threaded through helpers.
     */
    private class BackgroundSyncRun(
        private val context: Context,
        private val onStatus: (ForegroundState) -> Unit,
    ) {
        private val repository = ActivityRepository { AppDatabase.get(context) }
        private val settings = PulseSettings(context)
        private val analysisScheduler = ActivityAnalysisScheduler(
            context = context,
            repository = repository,
            providerStore = ProviderStore(context),
            settings = settings,
        )
        private val filesDir = context.filesDir
        private val knownActivities: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val knownWellness: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val deferred = CompletableDeferred<BackgroundSyncOutcome>()
        private val connected = CompletableDeferred<Boolean>()
        private val finishing = AtomicBoolean(false)
        private val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())
        private val mainHandler = Handler(Looper.getMainLooper())
        private var coordinator: SyncCoordinator? = null
        private var sessionObserved = false

        private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pendingImports = Collections.synchronizedList(mutableListOf<Job>())
        private val importedActivities = AtomicInteger(0)
        private val importedWellnessDays = AtomicInteger(0)
        private val importError = AtomicReference<String?>(null)

        suspend fun loadKnownItems() {
            withContext(Dispatchers.IO) {
                knownActivities.addAll(repository.activityItemUids())
                knownActivities.addAll(repository.quarantinedActivityItemUids())
                knownWellness.addAll(repository.importedWellnessFileIds())
            }
        }

        fun createClient(): GarminBleClient = GarminBleClient(
            context = context,
            onLog = { /* background protocol logs are intentionally discarded */ },
            onDeviceFound = { _, _ -> },
            onStateChanged = { state ->
                // GATT callbacks arrive on a binder thread; coordinator state
                // and the foreground status are main-thread state.
                mainHandler.post {
                    when (state) {
                        "connected" -> {
                            // A reconnect reopens the protocol session. Reset
                            // the coordinator so the next GDI exchange starts
                            // clean, just like the visible sync path.
                            coordinator?.reset()
                            sessionObserved = false
                            noteProgress()
                            if (!connected.isCompleted) connected.complete(true)
                            onStatus(
                                ForegroundState(
                                    "Reading watch data…",
                                    "Keep the watch nearby.",
                                    null,
                                ),
                            )
                        }
                        "reconnecting" -> {
                            // Abandon the stalled session immediately;
                            // otherwise hasStarted() keeps returning true and
                            // the watchdog would reconnect again before the
                            // new session opens.
                            coordinator?.reset()
                            sessionObserved = false
                            noteProgress()
                            onStatus(
                                ForegroundState(
                                    "Reconnecting to watch…",
                                    "Keep the watch nearby.",
                                    null,
                                ),
                            )
                        }
                        "disconnected" -> {
                            if (!connected.isCompleted) connected.complete(false)
                            if (!finishing.get() && !deferred.isCompleted) {
                                deferred.complete(
                                    BackgroundSyncOutcome.failure(
                                        SyncPolicy.FailureKind.TRANSIENT,
                                        "The watch disconnected before the sync finished.",
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        )

        fun createCoordinator(client: GarminBleClient): SyncCoordinator {
            val coordinator = SyncCoordinator(
                session = client.session,
                filesDir = filesDir,
                handler = mainHandler,
                log = { /* no background log: keep protocol/health payloads out of logs */ },
                isItemKnown = { uid -> uid.isNotEmpty() && uid in knownActivities },
                isWellnessItemKnown = { uid -> uid.isNotEmpty() && uid in knownWellness },
                onFinished = { _, decoded, itemUid ->
                    noteProgress()
                    queueActivityImport(decoded, itemUid)
                },
                onWellnessFile = { decoded, type, itemUid ->
                    noteProgress()
                    queueWellnessImport(decoded, type, itemUid)
                },
                onUpToDate = {
                    noteProgress()
                    finishWhenImportsDone(BackgroundSyncOutcome.success())
                },
                onSyncError = { reason ->
                    noteProgress()
                    finishWhenImportsDone(
                        BackgroundSyncOutcome.failure(
                            SyncPolicy.classifyFailure(reason),
                            reason,
                        ),
                    )
                },
                onActivityProgress = { progress ->
                    noteProgress()
                    val status = if (progress.total > 0 && progress.done < progress.total) {
                        "Syncing activity ${(progress.done + 1).coerceAtMost(progress.total)} of ${progress.total}"
                    } else {
                        "Reading watch data…"
                    }
                    onStatus(ForegroundState(status, "Keep the watch nearby.", progress))
                },
            )
            this.coordinator = coordinator
            return coordinator
        }

        private fun queueActivityImport(decoded: File, itemUid: String) {
            if (itemUid.isNotEmpty()) knownActivities.add(itemUid)
            pendingImports += importScope.launch {
                runCatching {
                    repository.importFit(decoded.name, decoded.readBytes(), itemUid)
                }.onSuccess { entity ->
                    if (entity != null) {
                        importedActivities.incrementAndGet()
                        runCatching { analysisScheduler.enqueue(entity.id) }
                    }
                }.onFailure { error ->
                    val message = error.message ?: "FIT import failed"
                    if (itemUid.isNotEmpty()) {
                        runCatching { repository.recordActivityImportFailure(itemUid, message) }
                    }
                    importError.compareAndSet(null, message)
                }
            }
        }

        private fun queueWellnessImport(decoded: File, type: String, itemUid: String) {
            pendingImports += importScope.launch {
                runCatching {
                    val days = repository.importWellness(decoded.readBytes())
                    if (WellnessFileTypes.isImmutable(type)) {
                        repository.markWellnessFileImported(itemUid, type)
                        if (itemUid.isNotEmpty()) knownWellness.add(itemUid)
                    }
                    days.size
                }.onSuccess { count ->
                    if (count > 0) importedWellnessDays.addAndGet(count)
                }.onFailure { error ->
                    importError.compareAndSet(null, error.message ?: "Wellness import failed")
                }
            }
        }

        private fun noteProgress() {
            lastProgressAt.set(SystemClock.elapsedRealtime())
        }

        private fun finishWhenImportsDone(outcome: BackgroundSyncOutcome) {
            if (deferred.isCompleted) return
            finishing.set(true)
            importScope.launch {
                val snapshot = synchronized(pendingImports) { pendingImports.toList() }
                snapshot.joinAll()
                val error = importError.get()
                val completed = if (error != null && outcome.success) {
                    outcome.copy(
                        success = false,
                        kind = SyncPolicy.FailureKind.TRANSIENT,
                        message = "Sync finished, but importing a file failed: $error",
                    )
                } else {
                    outcome
                }
                deferred.complete(
                    completed.copy(
                        importedActivities = importedActivities.get(),
                        importedWellnessDays = importedWellnessDays.get(),
                    ),
                )
            }
        }

        suspend fun awaitSync(
            client: GarminBleClient,
            coordinator: SyncCoordinator,
            watch: BluetoothDevice,
        ): BackgroundSyncOutcome {
            coordinator.reset()
            onStatus(ForegroundState("Connecting to ${watch.name ?: "watch"}…", null, null))
            client.connect(watch)
            val isConnected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: false
            if (!isConnected) {
                return BackgroundSyncOutcome.failure(
                    SyncPolicy.FailureKind.TRANSIENT,
                    "Could not connect to the watch. Keep it nearby, awake, and not connected to Garmin Connect.",
                )
            }
            noteProgress()
            // A GATT connection is not a sync: the watch must also open its
            // GDI session and keep making progress. Mirror the visible sync
            // watchdog and reconnect on stalls instead of waiting for the
            // whole transfer budget to expire. Garmin Connect can hold the
            // watch's single session, so a bounded reconnect also recovers
            // after the user closes it.
            return withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                var reconnectAttempts = 0
                while (!deferred.isCompleted) {
                    // The watch work is done; only Room imports are still
                    // running. Do not mistake a slow import for a BLE stall.
                    if (finishing.get()) {
                        deferred.await()
                        break
                    }
                    if (!sessionObserved && coordinator.hasStarted()) {
                        sessionObserved = true
                        noteProgress()
                    }
                    val idleMs = SystemClock.elapsedRealtime() - lastProgressAt.get()
                    val stallLimitMs = when {
                        coordinator.isTransferring() -> TRANSFER_STALL_TIMEOUT_MS
                        !coordinator.hasStarted() -> SESSION_START_TIMEOUT_MS
                        else -> STALL_TIMEOUT_MS
                    }
                    if (idleMs >= stallLimitMs) {
                        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                            return@withTimeoutOrNull stalledOutcome(coordinator, final = true)
                        }
                        reconnectAttempts++
                        Log.d(
                            TAG,
                            "stalled after ${idleMs}ms; reconnecting " +
                                "($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)",
                        )
                        onStatus(
                            ForegroundState(
                                "Reconnecting to watch…",
                                "Sync stalled; reconnecting " +
                                    "($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS).",
                                null,
                            ),
                        )
                        lastProgressAt.set(SystemClock.elapsedRealtime())
                        client.reconnect()
                    }
                    delay(500)
                }
                deferred.await()
            } ?: stalledOutcome(coordinator, final = false)
        }

        private fun stalledOutcome(
            coordinator: SyncCoordinator,
            final: Boolean,
        ): BackgroundSyncOutcome {
            val stalledBeforeSession =
                !coordinator.hasStarted() || coordinator.stalledBeforeCapabilities()
            val message = when {
                stalledBeforeSession ->
                    "The watch connected but did not open its GDI session. Close Garmin Connect and try again."
                final ->
                    "The watch kept stalling during sync. Keep it nearby and try again."
                else ->
                    "The watch did not finish syncing in time. Keep it nearby and charge it if needed."
            }
            return BackgroundSyncOutcome.failure(
                if (stalledBeforeSession) SyncPolicy.FailureKind.BUSY
                else SyncPolicy.FailureKind.TRANSIENT,
                message,
            )
        }

        fun cancelImports() {
            importScope.cancel()
        }
    }

    companion object {
        private const val TAG = "OpenGarminBg"

        /** Give an out-of-range/asleep watch a bounded connect attempt. */
        private const val CONNECT_TIMEOUT_MS = 45_000L

        /** Wait for the watch to open the GDI session after GATT connects. */
        private const val SESSION_START_TIMEOUT_MS = 45_000L

        /** Reconnect when no sync progress arrives for this long. */
        private const val STALL_TIMEOUT_MS = 20_000L

        /** Transfers have no byte-level progress callback; allow longer. */
        private const val TRANSFER_STALL_TIMEOUT_MS = 4 * 60 * 1000L

        /** Same bounded recovery the visible sync uses. */
        private const val MAX_RECONNECT_ATTEMPTS = 8

        /**
         * WorkManager's own limit is ~10 minutes; leave room for the bounded
         * reconnect loop and a clean shutdown. The worker is in the foreground
         * for this period, so it may exceed the normal 10-minute job window.
         */
        private const val SYNC_TIMEOUT_MS = 8 * 60 * 1000L
    }
}
