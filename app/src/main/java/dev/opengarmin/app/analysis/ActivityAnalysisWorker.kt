package dev.opengarmin.app.analysis

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.opengarmin.app.data.ActivityAnalysisStatus
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.AppDatabase
import dev.opengarmin.app.llm.LlmException
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import kotlinx.coroutines.CancellationException

/**
 * Generates and caches one activity analysis, then posts a notification when
 * it is ready. WorkManager keeps the request alive across process death and
 * lets automatic generation run after background syncs.
 */
class ActivityAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val activityId = inputData.getLong(KEY_ACTIVITY_ID, -1L)
        if (activityId <= 0L) return Result.failure()
        val force = inputData.getBoolean(KEY_FORCE, false)

        val repository = ActivityRepository { AppDatabase.get(applicationContext) }
        val activity = repository.activity(activityId) ?: return Result.success()
        val existing = repository.activityAnalysis(activityId)
        if (!force && existing?.status == ActivityAnalysisStatus.READY) {
            return Result.success()
        }

        val providerStore = ProviderStore(applicationContext)
        val settings = PulseSettings(applicationContext)
        val config = providerStore.load()
        if (!config.isConfigured) {
            repository.markAnalysisError(
                activityId,
                "Add an OpenAI-compatible provider in Settings to generate an analysis.",
            )
            return Result.success()
        }

        repository.markAnalysisGenerating(activityId)
        val generator = ActivityAnalysisGenerator(repository, providerStore, settings)
        return try {
            val generated = generator.generate(activityId, config)
            repository.saveAnalysisReady(activityId, generated.content, generated.model)
            ActivityAnalysisNotifications.notifyReady(
                context = applicationContext,
                activity = activity,
                content = generated.content,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LlmException) {
            repository.markAnalysisError(activityId, error.userMessage())
            if (error.isRetryableAnalysisFailure() && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (error: ActivityAnalysisException) {
            repository.markAnalysisError(activityId, error.message ?: "Could not generate the analysis.")
            Result.success()
        } catch (error: Exception) {
            repository.markAnalysisError(
                activityId,
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not generate the analysis. Try again.",
            )
            Result.success()
        }
    }

    companion object {
        const val KEY_ACTIVITY_ID = "activity_id"
        const val KEY_FORCE = "force"
        private const val MAX_RETRIES = 2
    }
}
