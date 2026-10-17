package dev.opengarmin.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(readTimeoutSeconds: Long = 5): OpenAiClient = OpenAiClient(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "test-model",
        readTimeoutSeconds = readTimeoutSeconds,
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds + 5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )

    @Test
    fun `streams content deltas and sends auth and model`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" there\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val deltas = client().streamChat(listOf(LlmMessage("user", "hello"))).toList()
        assertEquals("Hi there", deltas.joinToString("") { it.text })
        assertEquals("stop", deltas.last().finishReason)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
    }

    @Test
    fun `assembles streamed tool call fragments`() = runBlocking {
        val first = JSONObject().apply {
            put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put(
                            "delta",
                            JSONObject().apply {
                                put(
                                    "tool_calls",
                                    org.json.JSONArray().put(
                                        JSONObject().apply {
                                            put("index", 0)
                                            put("id", "call_1")
                                            put(
                                                "function",
                                                JSONObject().apply {
                                                    put("name", "get_steps")
                                                    put("arguments", "{\"from\":")
                                                },
                                            )
                                        },
                                    ),
                                )
                            },
                        )
                    },
                ),
            )
        }
        val second = JSONObject().apply {
            put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put(
                            "delta",
                            JSONObject().apply {
                                put(
                                    "tool_calls",
                                    org.json.JSONArray().put(
                                        JSONObject().apply {
                                            put("index", 0)
                                            put(
                                                "function",
                                                JSONObject().apply {
                                                    put("arguments", "\"2026-10-01\"}")
                                                },
                                            )
                                        },
                                    ),
                                )
                            },
                        )
                        put("finish_reason", "tool_calls")
                    },
                ),
            )
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: $first\n\n" +
                        "data: $second\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val deltas = client().streamChat(
            messages = listOf(LlmMessage("user", "steps?")),
            tools = listOf(
                LlmToolDefinition(
                    name = "get_steps",
                    description = "steps",
                    parameters = JSONObject().put("type", "object"),
                ),
            ),
        ).toList()
        val calls = deltas.flatMap { it.toolCalls }
        assertEquals("get_steps", calls.firstNotNullOf { it.name })
        assertEquals("call_1", calls.firstNotNullOf { it.id })
        assertEquals("{\"from\":\"2026-10-01\"}", calls.joinToString("") { it.arguments ?: "" })
    }

    @Test
    fun `non-streaming completion returns content and tool calls`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                JSONObject().apply {
                    put(
                        "choices",
                        org.json.JSONArray().put(
                            JSONObject().apply {
                                put(
                                    "message",
                                    JSONObject().apply {
                                        put("content", "Here is the answer")
                                        put(
                                            "tool_calls",
                                            org.json.JSONArray().put(
                                                JSONObject().apply {
                                                    put("id", "call_x")
                                                    put(
                                                        "function",
                                                        JSONObject().apply {
                                                            put("name", "get_sleep")
                                                            put("arguments", "{}")
                                                        },
                                                    )
                                                },
                                            ),
                                        )
                                    },
                                )
                                put("finish_reason", "tool_calls")
                            },
                        ),
                    )
                }.toString(),
            ),
        )
        val result = client().complete(listOf(LlmMessage("user", "sleep?")))
        assertEquals("Here is the answer", result.content)
        assertEquals("get_sleep", result.toolCalls.single().name)
        assertEquals("tool_calls", result.finishReason)
    }

    @Test
    fun `401 is reported as an auth error without leaking the key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key test-key"))
        val error = runCatching {
            client().complete(listOf(LlmMessage("user", "hello")))
        }.exceptionOrNull()
        assertTrue(error is LlmException)
        val exception = error as LlmException
        assertEquals(LlmException.Kind.Auth, exception.kind)
        assertTrue(exception.userMessage().contains("API key"))
    }

    @Test
    fun `server errors are reported`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        val error = runCatching {
            client().complete(listOf(LlmMessage("user", "hello")))
        }.exceptionOrNull()
        assertTrue(error is LlmException)
        assertEquals(LlmException.Kind.Server, (error as LlmException).kind)
    }

    @Test
    fun `read timeout is bounded`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = runCatching {
            client(readTimeoutSeconds = 1).complete(listOf(LlmMessage("user", "hello")))
        }.exceptionOrNull()
        assertTrue(error is LlmException)
        val exception = error as LlmException
        assertTrue(
            exception.kind == LlmException.Kind.Timeout ||
                exception.kind == LlmException.Kind.Network,
        )
    }

    @Test
    fun `models list is parsed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"model-a"},{"id":"model-b"}]}"""),
        )
        val models = client().listModels()
        assertEquals(listOf("model-a", "model-b"), models)
        assertEquals("/v1/models", server.takeRequest().path)
    }
}
