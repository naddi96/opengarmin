package dev.opengarmin.app.sync

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
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.AppDatabase
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

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
        val settings = PulseSettings(applicationContext)
        if (!settings.periodicSyncEnabled) {
            Log.d(TAG, "skipped: periodic sync disabled")
            return Result.success()
        }

        // A visible/manual sync or its idle BLE lease owns the watch. Skip this
        // period rather than opening a second GATT connection; the next
        // periodic interval (or the app itself) will sync.
        if (SyncGate.owner() == SyncGate.OWNER_UI) {
            Log.d(TAG, "skipped: UI lease held")
            return Result.success()
        }
        if (!SyncGate.tryAcquire(SyncGate.OWNER_BACKGROUND)) {
            Log.d(TAG, "skipped: another sync owns the transfer slot")
            return Result.success()
        }
        Log.d(TAG, "started")

        val startedAt = SystemClock.elapsedRealtime()
        settings.recordSyncStarted("background")
        val updates = Channel<ForegroundState>(Channel.CONFLATED)
        val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notifier = updateScope.launch {
            for (state in updates) {
                runCatching { setForeground(createForegroundInfo(state)) }
            }
        }
        var outcome: BackgroundSyncOutcome
        try {
            setForeground(createForegroundInfo(ForegroundState("Connecting to watch…", null, null)))
            outcome = runBackgroundSync { state -> updates.trySend(state) }
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

        val durationMs = SystemClock.elapsedRealtime() - startedAt
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
            val kindLabel = when (kind) {
                SyncPolicy.FailureKind.PERMANENT -> "permanent"
                SyncPolicy.FailureKind.BUSY -> "busy"
                SyncPolicy.FailureKind.TRANSIENT -> "transient"
            }
            settings.recordSyncError(
                outcome.message,
                durationMs = durationMs,
                trigger = "background",
                kind = kindLabel,
            )
            // A missing watch/permission is an actionable setup problem. Keep
            // it in Settings/Today instead of posting the same notification on
            // every interval. Transient failures get a dismissible retry note.
            if (kind != SyncPolicy.FailureKind.PERMANENT) {
                SyncNotifications.notifyFinal(
                    applicationContext,
                    "Background sync needs attention",
                    outcome.message,
                    if (SyncPolicy.shouldRetry(kind)) {
                        "Android will retry with backoff. You can also open the app and sync manually."
                    } else {
                        "Open OpenGarmin to review the sync status."
                    },
                )
            }
        }

        return when {
            outcome.success -> Result.success()
            SyncPolicy.shouldRetry(outcome.kind ?: SyncPolicy.FailureKind.TRANSIENT) -> Result.retry()
            // Periodic work should still run at the next interval after a
            // permanent setup problem is fixed by the user.
            else -> Result.success()
        }
    }

    private suspend fun runBackgroundSync(
        onStatus: (ForegroundState) -> Unit,
    ): BackgroundSyncOutcome = withContext(Dispatchers.Main.immediate) {
        val repository = ActivityRepository { AppDatabase.get(applicationContext) }
        val knownActivities: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val knownWellness: MutableSet<String> = ConcurrentHashMap.newKeySet()
        withContext(Dispatchers.IO) {
            knownActivities.addAll(repository.activityItemUids())
            knownWellness.addAll(repository.importedWellnessFileIds())
        }

        val deferred = CompletableDeferred<BackgroundSyncOutcome>()
        val connected = CompletableDeferred<Boolean>()
        val finishing = AtomicBoolean(false)
        val client = GarminBleClient(
            context = applicationContext,
            onLog = { /* background protocol logs are intentionally discarded */ },
            onDeviceFound = { _, _ -> },
            onStateChanged = { state ->
                when (state) {
                    "connected" -> {
                        if (!connected.isCompleted) connected.complete(true)
                        onStatus(
                            ForegroundState("Reading watch data…", "Keep the watch nearby.", null),
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
            },
        )
        if (!client.hasPermissions()) {
            return@withContext BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "Bluetooth permission is missing. Open Android Settings and allow Nearby devices for OpenGarmin.",
            )
        }
        if (!client.hasBluetoothAdapter()) {
            return@withContext BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "Bluetooth LE is not available on this phone.",
            )
        }
        if (!client.isBluetoothEnabled()) {
            return@withContext BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "Bluetooth is off. Turn Bluetooth on, then sync again.",
            )
        }
        val watch = client.findBondedWatch()
        if (watch == null) {
            return@withContext BackgroundSyncOutcome.failure(
                SyncPolicy.FailureKind.PERMANENT,
                "No bonded Garmin watch found. Pair the watch in Android Bluetooth settings, then sync again.",
            )
        }

        val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pendingImports = Collections.synchronizedList(mutableListOf<Job>())
        val importedActivities = AtomicInteger(0)
        val importedWellnessDays = AtomicInteger(0)
        val importError = AtomicReference<String?>(null)

        fun finishWhenImportsDone(outcome: BackgroundSyncOutcome) {
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

        val coordinator = SyncCoordinator(
            session = client.session,
            filesDir = applicationContext.filesDir,
            handler = Handler(Looper.getMainLooper()),
            log = { /* no background log: keep protocol/health payloads out of logs */ },
            isItemKnown = { uid -> uid.isNotEmpty() && uid in knownActivities },
            isWellnessItemKnown = { uid -> uid.isNotEmpty() && uid in knownWellness },
            onFinished = { _, decoded, itemUid ->
                if (itemUid.isNotEmpty()) knownActivities.add(itemUid)
                pendingImports += importScope.launch {
                    runCatching {
                        repository.importFit(decoded.name, decoded.readBytes(), itemUid)
                    }.onSuccess { entity ->
                        if (entity != null) importedActivities.incrementAndGet()
                    }.onFailure { error ->
                        importError.compareAndSet(null, error.message ?: "FIT import failed")
                    }
                }
            },
            onWellnessFile = { decoded, type, itemUid ->
                pendingImports += importScope.launch {
                    runCatching {
                        val days = repository.importWellness(decoded.readBytes())
                        if (type == "FIT_TYPE_44" || type == "FIT_TYPE_49") {
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
            },
            onUpToDate = {
                finishWhenImportsDone(BackgroundSyncOutcome.success())
            },
            onActivityProgress = { progress ->
                val status = if (progress.total > 0 && progress.done < progress.total) {
                    "Syncing activity ${(progress.done + 1).coerceAtMost(progress.total)} of ${progress.total}"
                } else {
                    "Reading watch data…"
                }
                onStatus(ForegroundState(status, "Keep the watch nearby.", progress))
            },
        )

        try {
            coordinator.reset()
            onStatus(ForegroundState("Connecting to ${watch.name ?: "watch"}…", null, null))
            client.connect(watch)
            val isConnected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: false
            if (!isConnected) {
                return@withContext BackgroundSyncOutcome.failure(
                    SyncPolicy.FailureKind.TRANSIENT,
                    "Could not connect to the watch. Keep it nearby, awake, and not connected to Garmin Connect.",
                )
            }
            // A GATT connection is not a sync: the watch must also open its
            // GDI session. Fail fast instead of holding a wake lock for the
            // whole transfer timeout when Garmin Connect owns that slot.
            val sessionStarted = withTimeoutOrNull(SESSION_START_TIMEOUT_MS) {
                while (!coordinator.hasStarted() && !deferred.isCompleted) {
                    delay(200)
                }
                true
            } ?: false
            if (deferred.isCompleted) return@withContext deferred.await()
            if (!sessionStarted) {
                return@withContext BackgroundSyncOutcome.failure(
                    SyncPolicy.FailureKind.BUSY,
                    "The watch connected but did not start its GDI session. Close Garmin Connect and try again.",
                )
            }
            withTimeoutOrNull(SYNC_TIMEOUT_MS) { deferred.await() }
                ?: BackgroundSyncOutcome.failure(
                    SyncPolicy.FailureKind.TRANSIENT,
                    if (coordinator.stalledBeforeCapabilities()) {
                        "The watch did not open its GDI session. Close Garmin Connect and try again."
                    } else {
                        "The watch did not finish syncing in time. Keep it nearby and charge it if needed."
                    },
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            runCatching { client.disconnect() }
            importScope.cancel()
        }
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

    companion object {
        private const val TAG = "OpenGarminBg"

        /** Give an out-of-range/asleep watch a bounded connect attempt. */
        private const val CONNECT_TIMEOUT_MS = 45_000L

        /** Wait for the watch to open the GDI session after GATT connects. */
        private const val SESSION_START_TIMEOUT_MS = 45_000L

        /** WorkManager's own limit is ~10 minutes; leave room to shut down cleanly. */
        private const val SYNC_TIMEOUT_MS = 6 * 60 * 1000L
    }
}
