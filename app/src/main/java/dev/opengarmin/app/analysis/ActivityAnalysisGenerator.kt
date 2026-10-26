package dev.opengarmin.app.analysis

import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.llm.LlmException
import dev.opengarmin.app.llm.LlmMessage
import dev.opengarmin.app.llm.OpenAiClient
import dev.opengarmin.app.llm.ProviderConfig
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings

data class GeneratedActivityAnalysis(
    val content: String,
    val model: String,
)

class ActivityAnalysisException(message: String) : Exception(message)

/**
 * Runs one activity through the configured OpenAI-compatible provider. This is
 * deliberately independent of Compose so the same path serves the foreground
 * sync, the background worker and the manual Generate action.
 */
class ActivityAnalysisGenerator(
    private val repository: ActivityRepository,
    private val providerStore: ProviderStore,
    private val settings: PulseSettings,
) {
    suspend fun generate(activityId: Long, config: ProviderConfig): GeneratedActivityAnalysis {
        val activity = repository.activity(activityId)
            ?: throw ActivityAnalysisException("This activity is no longer available.")
        val model = config.defaultModel
            ?: throw ActivityAnalysisException("No model is configured. Add one in provider settings.")
        val insights = repository.activityInsights(activityId)
        val prompt = ActivityAnalysisPrompt.user(
            activity = activity,
            insights = insights,
            profile = ActivityAnalysisProfile(
                maxHeartRate = settings.maxHeartRate,
                restingHeartRate = settings.importedRestingHeartRate,
                birthYear = settings.birthYear,
                biologicalSex = settings.biologicalSex,
                weightKg = settings.weightKg,
            ),
        )
        val client = OpenAiClient(
            baseUrl = config.baseUrl,
            apiKey = providerStore.apiKeyFor(config),
            model = model.name,
            readTimeoutSeconds = 90,
        )
        val completion = client.complete(
            messages = listOf(
                LlmMessage("system", ActivityAnalysisPrompt.SYSTEM),
                LlmMessage("user", prompt),
            ),
            temperature = model.temperature ?: 0.3,
            // Reasoning models spend part of this budget on hidden reasoning, so
            // keep enough room for a full analysis after that. A 700-token cap
            // made deepseek-reasoner finish with empty content on long analyses.
            maxTokens = model.maxTokens ?: DEFAULT_MAX_TOKENS,
        )
        val content = cleanMarkdown(completion.content)
        if (content.isBlank()) {
            if (completion.finishReason == "length") {
                throw ActivityAnalysisException(
                    "The model ran out of output tokens before writing the analysis. " +
                        "Try again or switch to a non-reasoning model.",
                )
            }
            throw ActivityAnalysisException("The provider returned an empty analysis. Try again.")
        }
        return GeneratedActivityAnalysis(content = content, model = model.name)
    }

    /** Some providers wrap answers in a Markdown fence despite being asked not to. */
    private fun cleanMarkdown(text: String): String {
        var value = text.trim()
        if (value.startsWith("```")) {
            value = value.substringAfter('\n', "").removeSuffix("```").trim()
        }
        return value
    }

    private companion object {
        /**
         * Room for hidden reasoning plus the 90-160 word answer. Kept below the
         * common 8192 output ceiling so providers accept the request.
         */
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

/** Only these failures are worth an automatic WorkManager retry. */
internal fun LlmException.isRetryableAnalysisFailure(): Boolean = when (kind) {
    LlmException.Kind.RateLimit,
    LlmException.Kind.Server,
    LlmException.Kind.Network,
    LlmException.Kind.Timeout,
    -> true
    else -> false
}
