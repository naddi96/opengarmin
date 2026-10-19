package dev.opengarmin.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.opengarmin.app.settings.PulseSettings
import java.util.concurrent.TimeUnit

/**
 * Owns the single unique periodic sync request.
 *
 * The request is replaced with [ExistingPeriodicWorkPolicy.UPDATE] whenever the
 * user changes the interval or constraints. Android does not guarantee an exact
 * run time; the UI must not show a fabricated "next sync at" timestamp.
 */
object SyncScheduler {
    const val UNIQUE_WORK_NAME = "watch-periodic-sync"
    const val ONCE_WORK_NAME = "watch-sync-once"
    const val TAG = "watch-sync"

    fun apply(
        context: Context,
        settings: PulseSettings,
        replaceSchedule: Boolean = false,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.periodicSyncEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val interval = SyncPolicy.normalizeInterval(settings.periodicSyncIntervalMinutes)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(SyncPolicy.REQUIRES_BATTERY_NOT_LOW)
            .setRequiresCharging(settings.periodicSyncChargingOnly)
            .build()
        val request: PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<WatchSyncWorker>(interval.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    SyncPolicy.BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                )
                .addTag(TAG)
                .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            if (replaceSchedule) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            },
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Queues one immediate run through the same worker for the in-app
     * "Run background sync now" action. It still respects the battery/charging
     * constraints and the single-flight gate.
     */
    fun runOnce(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(SyncPolicy.REQUIRES_BATTERY_NOT_LOW)
            .build()
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<WatchSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                SyncPolicy.BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(
            ONCE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
