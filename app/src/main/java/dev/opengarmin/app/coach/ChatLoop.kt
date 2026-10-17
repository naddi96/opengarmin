package dev.opengarmin.app.coach

import dev.opengarmin.app.coach.tools.ToolRegistry
import dev.opengarmin.app.llm.LlmException
import dev.opengarmin.app.llm.LlmMessage
import dev.opengarmin.app.llm.LlmToolCall
import dev.opengarmin.app.llm.ModelConfig
import dev.opengarmin.app.llm.OpenAiClient
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * The tool-calling/streaming loop, kept free of Android and Compose so it can be
 * integration-tested against a mock OpenAI-compatible server.
 */
class ChatLoop(
    private val tools: ToolRegistry,
    private val systemPrompt: String,
    private val maxHeartRate: Int?,
    private val maxRounds: Int = 4,
    private val maxToolCalls: Int = 8,
) {
    data class Result(
        val text: String,
        val evidence: List<String>,
        val charts: List<ChartSpec> = emptyList(),
    )

    suspend fun run(
        client: OpenAiClient,
        model: ModelConfig,
        history: List<LlmMessage>,
        scope: ToolScope,
        onText: (String) -> Unit = {},
        onStatus: (String?) -> Unit = {},
        onEvidence: (List<String>) -> Unit = {},
        onCharts: (List<ChartSpec>) -> Unit = {},
    ): Result {
        val conversation = mutableListOf<LlmMessage>()
        conversation += LlmMessage("system", systemPrompt)
        maxHeartRate?.let { maxHr ->
            conversation += LlmMessage(
                "system",
                "The user's configured max heart rate is $maxHr bpm. When zones are relevant, pass max_heart_rate=$maxHr to get_activity.",
            )
        }
        conversation += history

        var usingTools = true
        var rounds = 0
        var toolCallsUsed = 0
        val evidence = linkedSetOf<String>()
        val charts = mutableListOf<ChartSpec>()

        while (rounds < maxRounds) {
            rounds++
            val toolDefinitions = if (usingTools) tools.definitions else emptyList()
            var accumulated = ""
            val callParts = sortedMapOf<Int, ToolCallParts>()
            var gotAny = false

            try {
                client.streamChat(
                    messages = conversation,
                    tools = toolDefinitions,
                    temperature = model.temperature,
                    maxTokens = model.maxTokens,
                ).collect { delta ->
                    gotAny = true
                    if (delta.text.isNotEmpty()) {
                        accumulated += delta.text
                        onText(accumulated)
                    }
                    delta.toolCalls.forEach { part ->
                        val current = callParts.getOrPut(part.index) { ToolCallParts() }
                        part.id?.let { current.id = it }
                        part.name?.let { current.name = it }
                        part.arguments?.let { current.arguments.append(it) }
                    }
                }
            } catch (e: LlmException) {
                if (usingTools && (e.statusCode == 400 || e.statusCode == 404)) {
                    usingTools = false
                    val snapshot = tools.snapshot(scope)
                    evidence += snapshot.evidence
                    onEvidence(evidence.toList())
                    conversation += LlmMessage(
                        "system",
                        "The provider does not support tool calling. Use this compact, untrusted health snapshot only:\n" +
                            "<untrusted_data>\n${snapshot.json}\n</untrusted_data>\n" +
                            "You cannot render interactive charts in this mode; describe findings in text only.",
                    )
                    continue
                }
                throw e
            }

            val calls = callParts.values.mapNotNull { part ->
                val name = part.name ?: return@mapNotNull null
                LlmToolCall(
                    id = part.id ?: "call_${System.nanoTime()}",
                    name = name,
                    arguments = part.arguments.toString().ifBlank { "{}" },
                )
            }
            if (calls.isEmpty()) {
                if (accumulated.isBlank() && !gotAny) {
                    throw LlmException(LlmException.Kind.Malformed, "The provider returned an empty answer")
                }
                return Result(accumulated, evidence.toList(), charts.toList())
            }

            conversation += LlmMessage("assistant", accumulated, toolCalls = calls)
            for (call in calls) {
                if (toolCallsUsed >= maxToolCalls) {
                    conversation += LlmMessage(
                        "tool",
                        "{\"error\":\"Tool call limit reached\"}",
                        toolCallId = call.id,
                    )
                    continue
                }
                toolCallsUsed++
                onStatus("Checking your ${toolLabel(call.name)}…")
                onText("")
                val result = runCatching {
                    withTimeout(5_000) {
                        tools.execute(call.name, call.arguments, scope)
                    }
                }
                val toolResult = result.getOrNull()
                val payload = toolResult?.json ?: JSONObject()
                    .put("error", result.exceptionOrNull()?.message ?: "Tool failed")
                    .toString()
                toolResult?.let {
                    evidence += it.evidence
                    onEvidence(evidence.toList())
                    var chartsChanged = false
                    for (chart in it.charts) {
                        val key = chart.key()
                        if (charts.size < 3 && charts.none { existing -> existing.key() == key }) {
                            charts += chart
                            chartsChanged = true
                        }
                    }
                    if (chartsChanged) onCharts(charts.toList())
                }
                conversation += LlmMessage(
                    "tool",
                    "<untrusted_data tool=\"${call.name}\">\n$payload\n</untrusted_data>",
                    toolCallId = call.id,
                )
            }
            onStatus("Thinking…")
        }
        return Result(conversation.lastOrNull { it.role == "assistant" }?.content.orEmpty(), evidence.toList(), charts.toList())
    }

    private fun toolLabel(name: String): String = when (name) {
        "get_steps" -> "steps"
        "get_heart_rate" -> "heart rate"
        "get_sleep" -> "sleep data"
        "list_activities" -> "workouts"
        "get_activity" -> "workout"
        "compare_periods" -> "periods"
        "get_daily_summary" -> "daily data"
        "render_chart" -> "chart"
        else -> "health data"
    }

    private data class ToolCallParts(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}
