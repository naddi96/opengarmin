package dev.opengarmin.app.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class LlmToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

data class LlmStreamDelta(
    val text: String = "",
    val toolCalls: List<LlmToolCallDelta> = emptyList(),
    val finishReason: String? = null,
)

data class LlmCompletion(
    val content: String,
    val toolCalls: List<LlmToolCall>,
    val finishReason: String?,
)

class LlmException(
    val kind: Kind,
    override val message: String,
    val statusCode: Int? = null,
) : Exception(message) {
    enum class Kind { Auth, RateLimit, NotFound, Server, Network, Timeout, Malformed, Cancelled, Unknown }

    fun userMessage(): String = when (kind) {
        Kind.Auth -> "The provider rejected the API key. Check it in provider settings."
        Kind.RateLimit -> "The provider is rate-limiting requests. Wait a moment and try again."
        Kind.NotFound -> "The endpoint or model was not found. Check the base URL and model name."
        Kind.Server -> "The provider returned a server error. Try again shortly."
        Kind.Network -> "Could not reach the provider. Check the connection and base URL."
        Kind.Timeout -> "The provider took too long to respond. Try again, or use a smaller model."
        Kind.Malformed -> "The provider returned an unexpected response."
        Kind.Cancelled -> "Response cancelled."
        Kind.Unknown -> "The provider request failed."
    }
}

class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    connectTimeoutSeconds: Long = 15,
    readTimeoutSeconds: Long = 60,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(readTimeoutSeconds + 30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
) {
    private val endpoint = baseUrl.trim().trimEnd('/')

    fun streamChat(
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): Flow<LlmStreamDelta> = flow {
        val body = requestBody(messages, tools, temperature, maxTokens, stream = true)
        val request = request(body, stream = true)
        var emitted = false
        executeWithRetry(request, canRetry = { !emitted }) { response ->
            val source = response.body?.source()
                ?: throw LlmException(LlmException.Kind.Malformed, "Empty response body")
            source.use { src ->
                while (!src.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        emit(LlmStreamDelta(finishReason = "stop"))
                        return@executeWithRetry
                    }
                    val parsed = runCatching { parseStreamDelta(JSONObject(data)) }.getOrNull()
                    if (parsed != null &&
                        (parsed.text.isNotEmpty() || parsed.toolCalls.isNotEmpty() || parsed.finishReason != null)
                    ) {
                        emitted = true
                        emit(parsed)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun complete(
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        stream: Boolean = false,
    ): LlmCompletion = withContext(Dispatchers.IO) {
        val body = requestBody(messages, tools, temperature, maxTokens, stream = stream)
        val request = request(body, stream = false)
        executeWithRetry(request, canRetry = { true }) { response ->
            val text = response.body?.string()
                ?: throw LlmException(LlmException.Kind.Malformed, "Empty response body")
            val json = runCatching { JSONObject(text) }
                .getOrElse { throw LlmException(LlmException.Kind.Malformed, "Invalid JSON response") }
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw LlmException(LlmException.Kind.Malformed, "No choices in response")
            val message = choice.optJSONObject("message")
            LlmCompletion(
                content = message?.optStringOrNull("content").orEmpty(),
                toolCalls = message?.optJSONArray("tool_calls").toToolCalls(),
                finishReason = choice.optStringOrNull("finish_reason")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Executes [request], retrying server and network failures with the same
     * bounded backoff for streaming and non-streaming calls.
     */
    private suspend fun <T> executeWithRetry(
        request: Request,
        canRetry: () -> Boolean,
        onResponse: suspend (Response) -> T,
    ): T {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val call = okHttpClient.newCall(request)
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val snippet = response.body?.string()?.take(500)
                    response.close()
                    if (code in 500..599 && canRetry() && attempt < MAX_RETRIES) {
                        attempt++
                        delay(backoffMs(attempt))
                        continue
                    }
                    throw httpError(code, snippet)
                }
                return onResponse(response)
            } catch (e: IOException) {
                if (canRetry() && attempt < MAX_RETRIES) {
                    attempt++
                    delay(backoffMs(attempt))
                    continue
                }
                throw wrapIo(e)
            } finally {
                call.cancel()
            }
        }
    }

    suspend fun listModels(): List<String> = withContext<List<String>>(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$endpoint/models")
            .get()
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json")
            .header("User-Agent", "Pulse-Android")
            .build()
        val call = okHttpClient.newCall(request)
        try {
            val response = call.execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw httpError(response.code, body?.take(500))
            }
            val json = runCatching { JSONObject(body.orEmpty()) }
                .getOrElse { throw LlmException(LlmException.Kind.Malformed, "Invalid models response") }
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            return@withContext buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    item.optStringOrNull("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        } catch (e: IOException) {
            throw wrapIo(e)
        } finally {
            call.cancel()
        }
    }

    suspend fun testConnection(): String {
        return try {
            val models = listModels()
            if (models.isEmpty()) {
                "Connected, but the endpoint returned no models."
            } else {
                "Connected · ${models.size} models available"
            }
        } catch (e: LlmException) {
            if (e.statusCode == 404 || e.statusCode == 405 || e.kind == LlmException.Kind.Malformed) {
                val completion = complete(
                    messages = listOf(LlmMessage("user", "Reply with OK")),
                    maxTokens = 1,
                )
                "Connected · completion returned ${completion.content.take(20).ifBlank { "OK" }}"
            } else {
                throw e
            }
        }
    }

    private fun request(body: JSONObject, stream: Boolean): Request {
        val builder = Request.Builder()
            .url("$endpoint/chat/completions")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Pulse-Android")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
        apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    private fun requestBody(
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean,
    ): JSONObject {
        if (model.isBlank()) throw LlmException(LlmException.Kind.Unknown, "No model configured")
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put("role", message.role)
                    put("content", message.content)
                    if (message.toolCalls.isNotEmpty()) {
                        put(
                            "tool_calls",
                            JSONArray().apply {
                                message.toolCalls.forEach { call ->
                                    put(
                                        JSONObject().apply {
                                            put("id", call.id)
                                            put("type", "function")
                                            put(
                                                "function",
                                                JSONObject().apply {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments)
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                    message.toolCallId?.let { put("tool_call_id", it) }
                },
            )
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", array)
            put("stream", stream)
            temperature?.let { put("temperature", it.coerceIn(0.0, 2.0)) }
            maxTokens?.let { put("max_tokens", it.coerceIn(1, 8192)) }
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { tool ->
                            put(
                                JSONObject().apply {
                                    put("type", "function")
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parameters", tool.parameters)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put("tool_choice", "auto")
            }
        }
    }

    private fun parseStreamDelta(json: JSONObject): LlmStreamDelta? {
        val choices = json.optJSONArray("choices") ?: return null
        val choice = choices.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        val text = delta?.optStringOrNull("content").orEmpty()
        val toolDeltas = mutableListOf<LlmToolCallDelta>()
        val calls = delta?.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function")
                toolDeltas += LlmToolCallDelta(
                    index = call.optInt("index", i),
                    id = call.optStringOrNull("id")?.takeIf { it.isNotBlank() },
                    name = fn?.optStringOrNull("name")?.takeIf { it.isNotBlank() },
                    arguments = fn?.optStringOrNull("arguments")?.takeIf { it.isNotBlank() },
                )
            }
        }
        return LlmStreamDelta(
            text = text,
            toolCalls = toolDeltas,
            finishReason = choice.optStringOrNull("finish_reason")?.takeIf { it.isNotBlank() },
        )
    }

    private fun JSONArray?.toToolCalls(): List<LlmToolCall> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONObject(i) ?: continue
                val fn = item.optJSONObject("function") ?: continue
                val name = fn.optStringOrNull("name").orEmpty()
                if (name.isBlank()) continue
                add(
                    LlmToolCall(
                        id = item.optStringOrNull("id").orEmpty().ifBlank { "call_$i" },
                        name = name,
                        arguments = fn.optStringOrNull("arguments").orEmpty().ifBlank { "{}" },
                    ),
                )
            }
        }
    }

    /**
     * Android's [JSONObject.optString] converts a JSON `null` into the literal
     * string "null" (the JVM JSON library used by tests does not). OpenAI-style
     * streams send `"content": null` and `"finish_reason": null` on routine
     * chunks, so those values must stay absent rather than leak into the UI.
     */
    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name)
    }

    private fun httpError(code: Int, body: String?): LlmException {
        val kind = when (code) {
            401, 403 -> LlmException.Kind.Auth
            404 -> LlmException.Kind.NotFound
            408, 504 -> LlmException.Kind.Timeout
            429 -> LlmException.Kind.RateLimit
            in 500..599 -> LlmException.Kind.Server
            else -> LlmException.Kind.Unknown
        }
        val detail = sanitize(body)
        return LlmException(
            kind = kind,
            message = "HTTP $code" + if (detail.isBlank()) "" else ": $detail",
            statusCode = code,
        )
    }

    private fun wrapIo(e: IOException): LlmException {
        val message = e.message.orEmpty()
        val kind = when {
            message.contains("timeout", ignoreCase = true) -> LlmException.Kind.Timeout
            message.contains("cancel", ignoreCase = true) -> LlmException.Kind.Cancelled
            else -> LlmException.Kind.Network
        }
        return LlmException(kind, kind.name.lowercase().replaceFirstChar { it.uppercase() })
    }

    private fun sanitize(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val compact = body.replace(Regex("\\s+"), " ").trim()
        val withoutKey = compact.replace(Regex("sk-[A-Za-z0-9_-]+"), "sk-…")
        return withoutKey.take(160)
    }

    private fun backoffMs(attempt: Int): Long = (300L * (1L shl (attempt - 1))).coerceAtMost(2_000L)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 2
    }
}
