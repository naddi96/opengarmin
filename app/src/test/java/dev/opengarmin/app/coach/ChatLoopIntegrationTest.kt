package dev.opengarmin.app.coach

import dev.opengarmin.app.coach.tools.ToolRegistry
import dev.opengarmin.app.llm.LlmMessage
import dev.opengarmin.app.llm.ModelConfig
import dev.opengarmin.app.llm.OpenAiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ChatLoopIntegrationTest {
    private lateinit var server: MockWebServer
    private val today = LocalDate.of(2026, 10, 17)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OpenAiClient = OpenAiClient(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "mock-model",
        readTimeoutSeconds = 5,
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build(),
    )

    @Test
    fun `streams a tool call executes it and returns an evidence-backed answer`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"get_daily_summary\",\"arguments\":\"{\\\"from\\\":\\\"2026-10-17\\\",\\\"to\\\":\\\"2026-10-17\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"You logged 3,923 steps today.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val loop = ChatLoop(
            tools = ToolRegistry(FakeSource(today)) { today },
            systemPrompt = "test prompt",
            maxHeartRate = 190,
        )
        val result = loop.run(
            client = client(),
            model = ModelConfig("m", "mock-model"),
            history = listOf(LlmMessage("user", "How was my day?")),
            scope = ToolScope(),
        )

        assertEquals("You logged 3,923 steps today.", result.text)
        assertEquals(listOf("Daily summary · 1 day"), result.evidence)

        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(first.has("tools"))
        assertEquals("mock-model", first.getString("model"))

        val second = JSONObject(server.takeRequest().body.readUtf8())
        val messages = second.getJSONArray("messages")
        val toolMessage = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .last { it.optString("role") == "tool" }
        val toolContent = toolMessage.getString("content")
        assertTrue(toolContent.contains("<untrusted_data"))
        assertFalse(toolContent.contains("ToolResult("))
        val jsonPayload = toolContent.substringAfter(">").substringBeforeLast("</untrusted_data>").trim()
        assertTrue(jsonPayload.startsWith("{"))
        assertEquals(3923, JSONObject(jsonPayload).getJSONArray("days").getJSONObject(0).getInt("steps"))
        assertTrue(second.has("tools"))
    }

    @Test
    fun `falls back to a snapshot when tools are rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("tools unsupported"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"I can see limited local data.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val loop = ChatLoop(
            tools = ToolRegistry(FakeSource(today)) { today },
            systemPrompt = "test prompt",
            maxHeartRate = null,
        )
        val result = loop.run(
            client = client(),
            model = ModelConfig("m", "mock-model"),
            history = listOf(LlmMessage("user", "How was my week?")),
            scope = ToolScope(),
        )

        assertEquals("I can see limited local data.", result.text)
        assertTrue(result.evidence.any { it.contains("Daily summary") })
        server.takeRequest() // the rejected tools request
        val retry = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(retry.has("tools"))
        val system = (0 until retry.getJSONArray("messages").length())
            .map { retry.getJSONArray("messages").getJSONObject(it) }
            .last { it.optString("role") == "system" }
        assertTrue(system.getString("content").contains("<untrusted_data>"))
    }

    @Test
    fun `propagates charts from a render_chart tool call`() = runBlocking {
        val arguments = JSONObject()
            .put("source", "daily")
            .put("metric", "steps")
            .put("from", "2026-10-17")
            .put("to", "2026-10-17")
            .put("chart_type", "bar")
            .toString()
        val toolCall = JSONObject()
            .put("index", 0)
            .put("id", "call_chart")
            .put(
                "function",
                JSONObject()
                    .put("name", "render_chart")
                    .put("arguments", arguments),
            )
        val firstPayload = JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(toolCall)))
                        .put("finish_reason", "tool_calls"),
                ),
            )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $firstPayload\n\ndata: [DONE]\n\n"),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"The chart below shows today's steps.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val loop = ChatLoop(
            tools = ToolRegistry(FakeSource(today)) { today },
            systemPrompt = "test prompt",
            maxHeartRate = null,
        )
        val seen = mutableListOf<List<ChartSpec>>()
        val result = loop.run(
            client = client(),
            model = ModelConfig("m", "mock-model"),
            history = listOf(LlmMessage("user", "Chart my steps today")),
            scope = ToolScope(),
            onCharts = { seen += it },
        )

        assertEquals(1, result.charts.size)
        assertEquals(ChartType.Bar, result.charts.single().type)
        assertEquals("steps", result.charts.single().unit)
        assertTrue(seen.isNotEmpty())
        assertTrue(result.evidence.any { it.contains("Chart") })
    }

    @Test
    fun `executes create_workout and returns a saved plan to the model`() = runBlocking {
        val arguments = JSONObject()
            .put("name", "Coach Easy Run")
            .put("sport", "run")
            .put(
                "steps",
                org.json.JSONArray()
                    .put(JSONObject().put("role", "warmup").put("minutes", 5))
                    .put(JSONObject().put("role", "active").put("minutes", 25))
                    .put(JSONObject().put("role", "cooldown").put("minutes", 5)),
            )
            .toString()
        val toolCall = JSONObject()
            .put("index", 0)
            .put("id", "call_workout")
            .put(
                "function",
                JSONObject()
                    .put("name", "create_workout")
                    .put("arguments", arguments),
            )
        val firstPayload = JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(toolCall)))
                        .put("finish_reason", "tool_calls"),
                ),
            )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $firstPayload\n\ndata: [DONE]\n\n"),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"I saved the workout in Training → Workout plan. Review it and tap Send to watch.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val writer = FakeWorkoutWriter()
        val loop = ChatLoop(
            tools = ToolRegistry(FakeSource(today), writer) { today },
            systemPrompt = "test prompt",
            maxHeartRate = null,
        )
        val result = loop.run(
            client = client(),
            model = ModelConfig("m", "mock-model"),
            history = listOf(LlmMessage("user", "Create an easy 35 minute run")),
            scope = ToolScope(),
        )

        assertEquals(1, writer.created.size)
        assertEquals("Coach Easy Run", writer.created.single().name)
        assertTrue(result.text.contains("Workout plan"))
        assertTrue(result.evidence.any { it.contains("Workout created") })
        server.takeRequest() // first streamed tool call
        val second = JSONObject(server.takeRequest().body.readUtf8())
        val messages = second.getJSONArray("messages")
        val toolMessage = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .last { it.optString("role") == "tool" }
        val payload = toolMessage.getString("content")
            .substringAfter(">").substringBeforeLast("</untrusted_data>").trim()
        val json = JSONObject(payload)
        assertTrue(json.getBoolean("created"))
        assertFalse(json.getBoolean("sentToWatch"))
        assertEquals(1L, json.getLong("id"))
    }

    @Test
    fun `injects the configured profile into the coach context`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Use a steady effort.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val loop = ChatLoop(
            tools = ToolRegistry(FakeSource(today)) { today },
            systemPrompt = "test prompt",
            maxHeartRate = 190,
            heightCm = 180,
            weightKg = 75.0,
        )
        loop.run(
            client = client(),
            model = ModelConfig("m", "mock-model"),
            history = listOf(LlmMessage("user", "How should I pace my next run?")),
            scope = ToolScope(),
        )

        val request = JSONObject(server.takeRequest().body.readUtf8())
        val messages = request.getJSONArray("messages")
        val systemText = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.optString("role") == "system" }
            .joinToString("\n") { it.optString("content") }
        assertTrue(systemText.contains("max heart rate 190 bpm"))
        assertTrue(systemText.contains("height 180 cm"))
        assertTrue(systemText.contains("weight 75.0 kg"))
        assertTrue(systemText.contains("configured max heart rate is 190 bpm"))
    }

    private class FakeWorkoutWriter : WorkoutWriter {
        val created = mutableListOf<dev.opengarmin.app.data.PlannedWorkoutEntity>()

        override suspend fun createWorkout(
            scope: ToolScope,
            workout: dev.opengarmin.app.data.PlannedWorkoutEntity,
        ): dev.opengarmin.app.data.PlannedWorkoutEntity {
            val saved = workout.copy(id = created.size + 1L)
            created += saved
            return saved
        }

        override suspend fun listWorkouts(scope: ToolScope): List<dev.opengarmin.app.data.PlannedWorkoutEntity> =
            created
    }

    private class FakeSource(private val today: LocalDate) : HealthDataSource {
        override suspend fun daily(scope: ToolScope, start: LocalDate, end: LocalDate): List<DailyHealth> {
            if (scope.profileId != LOCAL_PROFILE_ID) return emptyList()
            if (today.isBefore(start) || today.isAfter(end)) return emptyList()
            return listOf(
                DailyHealth(
                    date = today.toString(),
                    steps = 3_923,
                    distanceMeters = 3_100.0,
                    activeCalories = 83,
                    activeSeconds = 2_280,
                    restingHeartRate = 57,
                    avgHeartRate = 72,
                    maxHeartRate = 120,
                    stressAvg = 22,
                    spo2Avg = 97,
                    respirationAvg = 14.0,
                ),
            )
        }

        override suspend fun samples(
            scope: ToolScope,
            kind: String,
            startEpoch: Long,
            endEpoch: Long,
        ): List<HealthSample> = emptyList()

        override suspend fun workouts(
            scope: ToolScope,
            startEpoch: Long,
            endEpoch: Long,
            limit: Int,
            offset: Int,
        ): List<WorkoutSummary> = emptyList()

        override suspend fun workout(scope: ToolScope, id: Long): WorkoutDetail? = null
    }
}
