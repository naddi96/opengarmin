package dev.opengarmin.app.analysis

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import java.util.concurrent.TimeUnit

/**
 * Queues one AI analysis per activity through WorkManager. A unique work name
 * per activity makes automatic and manual requests idempotent, and lets the job
 * continue if the app is backgrounded while the provider is thinking.
 */
class ActivityAnalysisScheduler(
    context: Context,
    private val repository: ActivityRepository,
    private val providerStore: ProviderStore,
    private val settings: PulseSettings,
) {
    private val appContext = context.applicationContext

    /**
     * Returns false when nothing was queued because the provider is missing,
     * automatic analysis is off, or the activity already has a ready analysis.
     * [force] is used by the manual Generate/Regenerate action.
     */
    suspend fun enqueue(activityId: Long, force: Boolean = false): Boolean {
        if (activityId <= 0L) return false
        if (!force && !settings.autoActivityAnalysisEnabled) return false
        if (!providerStore.load().isConfigured) return false
        if (!force) {
            // A first sync can import months of history. Analyze only genuinely
            // recent activities automatically so a bulk import cannot generate
            // dozens of provider requests and notifications. Older activities
            // still have the manual Generate action on their detail screen.
            val activity = repository.activity(activityId) ?: return false
            val cutoff = System.currentTimeMillis() / 1000 - AUTO_ANALYSIS_MAX_AGE_SECONDS
            if (activity.startTime < cutoff) return false
        }
        if (!repository.markAnalysisPending(activityId, force)) return false

        val request = OneTimeWorkRequestBuilder<ActivityAnalysisWorker>()
            .setInputData(
                workDataOf(
                    ActivityAnalysisWorker.KEY_ACTIVITY_ID to activityId,
                    ActivityAnalysisWorker.KEY_FORCE to force,
                ),
            )
            // No network constraint: local providers (Ollama, LM Studio, vLLM)
            // can answer over loopback/LAN without Android reporting an active
            // internet connection. Remote failures are retried with backoff.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            uniqueWorkName(activityId),
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    /** Stops queued/running analyses, for example before replacing the database. */
    fun cancelAll() {
        WorkManager.getInstance(appContext).cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "activity-analysis"
        private const val AUTO_ANALYSIS_MAX_AGE_SECONDS = 30L * 24 * 60 * 60

        fun uniqueWorkName(activityId: Long): String = "activity-analysis-$activityId"
    }
}
