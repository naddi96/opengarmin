package dev.opengarmin.app.coach

import dev.opengarmin.app.ui.components.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCodecTest {
    private fun chart() = ChartSpec(
        type = ChartType.Line,
        title = "Steps",
        unit = "steps",
        series = listOf(
            ChartSeries(
                name = "This week",
                points = listOf(ChartPoint(1f, 1000f), ChartPoint(2f, 2000f)),
            ),
        ),
    )

    @Test
    fun `round trips conversations with evidence, charts and status`() {
        val conversations = listOf(
            StoredConversation(
                id = "a",
                title = "How was my week?",
                createdAt = 1000L,
                updatedAt = 2000L,
                messages = listOf(
                    CoachUiMessage(1L, ChatRole.Assistant, "Hi"),
                    CoachUiMessage(2L, ChatRole.User, "How was my week?"),
                    CoachUiMessage(
                        id = 3L,
                        role = ChatRole.Assistant,
                        text = "Good week.",
                        evidence = listOf("Daily summary · 7 days"),
                        charts = listOf(chart()),
                        status = MessageStatus.Cancelled,
                    ),
                ),
            ),
        )

        val decoded = ConversationCodec.decode(ConversationCodec.encode(conversations))

        assertEquals(1, decoded.size)
        val conversation = decoded.single()
        assertEquals("a", conversation.id)
        assertEquals("How was my week?", conversation.title)
        assertEquals(1000L, conversation.createdAt)
        assertEquals(2000L, conversation.updatedAt)
        assertEquals(3, conversation.messages.size)
        assertEquals(MessageStatus.Cancelled, conversation.messages[2].status)
        assertEquals(listOf("Daily summary · 7 days"), conversation.messages[2].evidence)
        assertEquals("Steps", conversation.messages[2].charts.single().title)
        assertEquals(2, conversation.messages[2].charts.single().series.single().points.size)
    }

    @Test
    fun `infers a title from the first user message`() {
        val raw = """
            [{"id":"x","createdAt":1,"updatedAt":2,"messages":[
              {"id":1,"role":"Assistant","text":"Hi","status":"Complete"},
              {"id":2,"role":"User","text":"  Plan   tomorrow's workout  ","status":"Complete"}
            ]}]
        """.trimIndent()

        val decoded = ConversationCodec.decode(raw)

        assertEquals("Plan tomorrow's workout", decoded.single().title)
    }

    @Test
    fun `streaming messages become cancelled after a reload`() {
        val raw = """
            [{"id":"x","title":"t","createdAt":1,"updatedAt":2,"messages":[
              {"id":1,"role":"Assistant","text":"partial","status":"Streaming"}
            ]}]
        """.trimIndent()

        val message = ConversationCodec.decode(raw).single().messages.single()

        assertEquals(MessageStatus.Cancelled, message.status)
        assertTrue(message.text == "partial")
    }

    @Test
    fun `invalid json decodes to an empty list`() {
        assertTrue(ConversationCodec.decode("not json").isEmpty())
    }
}
