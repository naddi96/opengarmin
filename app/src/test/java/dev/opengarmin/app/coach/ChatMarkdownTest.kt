package dev.opengarmin.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun `parses inline bold italic code and links`() {
        val spans = ChatMarkdown.inline(
            "**Bold** and *italic* with `max_heart_rate` and [docs](https://example.com/health)",
        )

        assertEquals(
            listOf("Bold", " and ", "italic", " with ", "max_heart_rate", " and ", "docs"),
            spans.map { it.text },
        )
        assertTrue(spans[0].bold)
        assertTrue(spans[2].italic)
        assertTrue(spans[4].code)
        assertEquals("https://example.com/health", spans[6].link)
    }

    @Test
    fun `does not italicize snake case identifiers`() {
        val spans = ChatMarkdown.inline("Use max_heart_rate and resting_hr for zones")
        assertEquals("Use max_heart_rate and resting_hr for zones", spans.joinToString("") { it.text })
        assertFalse(spans.any { it.italic })
    }

    @Test
    fun `parses headings bullet lists and ordered lists`() {
        val blocks = ChatMarkdown.parse(
            """
            ## Findings

            - First **point**
            - Second point

            1. Do this
            2) Then that
            """.trimIndent(),
        )

        assertTrue(blocks[0] is ChatBlock.Heading)
        assertEquals(2, (blocks[0] as ChatBlock.Heading).level)
        assertEquals(2, blocks.filterIsInstance<ChatBlock.ListItem>().count { !it.ordered })
        assertEquals(listOf(1, 2), blocks.filterIsInstance<ChatBlock.ListItem>().filter { it.ordered }.map { it.number })
        assertTrue(blocks.filterIsInstance<ChatBlock.ListItem>().first().spans.any { it.bold })
    }

    @Test
    fun `parses a completed chart fence`() {
        val chart = """
            {"type":"line","title":"Heart rate","series":[{"name":"Run","points":[[0,120],[10,150]]}]}
        """.trimIndent()
        val blocks = ChatMarkdown.parse("Below is your run.\n\n```chart\n$chart\n```\n\nIt rose steadily.")

        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is ChatBlock.Chart)
        assertEquals("Heart rate", (blocks[1] as ChatBlock.Chart).spec.title)
        assertEquals("It rose steadily.", (blocks[2] as ChatBlock.Paragraph).spans.joinToString("") { it.text })
    }

    @Test
    fun `hides a partially streamed chart instead of showing raw json`() {
        val partial = "Below is your run.\n\n```chart\n{\"type\":\"line\",\"title\":\"Heart rate\","
        val streamingBlocks = ChatMarkdown.parse(partial, streaming = true)

        assertFalse(streamingBlocks.any { it is ChatBlock.Code })
        assertFalse(streamingBlocks.any { it is ChatBlock.Chart })

        val completeBlocks = ChatMarkdown.parse(partial, streaming = false)
        assertTrue(completeBlocks.any { it is ChatBlock.Code })
    }

    @Test
    fun `invalid chart falls back to a code block`() {
        val blocks = ChatMarkdown.parse("```chart\n{\"type\":\"line\",\"series\":[]}\n```")
        assertTrue(blocks.single() is ChatBlock.Code)
    }

    @Test
    fun `plain paragraphs keep line breaks`() {
        val blocks = ChatMarkdown.parse("Line one\nLine two\n\nNew paragraph")
        assertEquals(2, blocks.size)
        assertEquals("Line one\nLine two", (blocks[0] as ChatBlock.Paragraph).spans.joinToString("") { it.text })
    }
}
