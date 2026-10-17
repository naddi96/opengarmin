package dev.opengarmin.app.coach

/**
 * Minimal Markdown parser for coach answers. It intentionally supports only the
 * constructs the model is asked to use: paragraphs, headings, short lists,
 * quotes, inline bold/italic/code/links, fenced code and ```chart blocks.
 *
 * Kept free of Compose types so the formatting rules are unit-testable.
 */
sealed interface ChatBlock {
    data class Paragraph(val spans: List<ChatSpan>) : ChatBlock
    data class Heading(val level: Int, val spans: List<ChatSpan>) : ChatBlock
    data class ListItem(val spans: List<ChatSpan>, val ordered: Boolean, val number: Int) : ChatBlock
    data class Quote(val spans: List<ChatSpan>) : ChatBlock
    data class Code(val language: String?, val text: String) : ChatBlock
    data class Chart(val spec: ChartSpec) : ChatBlock
    data object Divider : ChatBlock
}

data class ChatSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

object ChatMarkdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^[-*+]\\s+(.*)$")
    private val ordered = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val divider = Regex("^([-*_])(?:\\s*\\1){2,}$")

    fun parse(text: String, streaming: Boolean = false): List<ChatBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<ChatBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += ChatBlock.Paragraph(inline(paragraph.toString().trim()))
                paragraph.clear()
            }
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index].trimEnd()
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                val body = StringBuilder()
                var closed = false
                index++
                while (index < lines.size) {
                    val candidate = lines[index]
                    if (candidate.trimStart().startsWith("```")) {
                        closed = true
                        index++
                        break
                    }
                    body.append(candidate).append('\n')
                    index++
                }
                val code = body.toString().trimEnd('\n')
                if (language?.lowercase() in setOf("chart", "pulse-chart")) {
                    val spec = ChartSpec.fromJson(code)
                    when {
                        spec != null -> blocks += ChatBlock.Chart(spec)
                        streaming && !closed -> Unit // hide a half-streamed chart body
                        else -> blocks += ChatBlock.Code(language, code)
                    }
                } else {
                    blocks += ChatBlock.Code(language, code)
                }
                continue
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                index++
                continue
            }

            val headingMatch = heading.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                blocks += ChatBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    spans = inline(headingMatch.groupValues[2]),
                )
                index++
                continue
            }

            if (divider.matches(trimmed)) {
                flushParagraph()
                blocks += ChatBlock.Divider
                index++
                continue
            }

            val bulletMatch = bullet.matchEntire(trimmed)
            if (bulletMatch != null) {
                flushParagraph()
                blocks += ChatBlock.ListItem(inline(bulletMatch.groupValues[1]), ordered = false, number = 0)
                index++
                continue
            }

            val orderedMatch = ordered.matchEntire(trimmed)
            if (orderedMatch != null) {
                flushParagraph()
                blocks += ChatBlock.ListItem(
                    spans = inline(orderedMatch.groupValues[2]),
                    ordered = true,
                    number = orderedMatch.groupValues[1].toIntOrNull() ?: 1,
                )
                index++
                continue
            }

            val quoteMatch = quote.matchEntire(trimmed)
            if (quoteMatch != null) {
                flushParagraph()
                blocks += ChatBlock.Quote(inline(quoteMatch.groupValues[1]))
                index++
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trim())
            index++
        }
        flushParagraph()
        return blocks
    }

    fun inline(text: String): List<ChatSpan> {
        val spans = mutableListOf<ChatSpan>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans += ChatSpan(plain.toString())
                plain.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]

            if (char == '\\' && index + 1 < text.length && text[index + 1] in "\\`*_{}[]()#+-.!") {
                plain.append(text[index + 1])
                index += 2
                continue
            }

            if (char == '`') {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    flushPlain()
                    spans += ChatSpan(text.substring(index + 1, end), code = true)
                    index = end + 1
                    continue
                }
            }

            if (char == '[') {
                val labelEnd = text.indexOf(']', index + 1)
                if (labelEnd > index && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        val label = text.substring(index + 1, labelEnd)
                        val url = text.substring(labelEnd + 2, urlEnd).trim()
                        if ((url.startsWith("https://") || url.startsWith("http://")) &&
                            label.isNotBlank()
                        ) {
                            flushPlain()
                            spans += ChatSpan(label, link = url)
                            index = urlEnd + 1
                            continue
                        }
                    }
                }
            }

            if ((text.startsWith("**", index) || text.startsWith("__", index)) &&
                index + 2 < text.length &&
                !text[index + 2].isWhitespace()
            ) {
                val marker = text.substring(index, index + 2)
                val end = text.indexOf(marker, index + 2)
                if (end > index + 2) {
                    flushPlain()
                    spans += styled(text.substring(index + 2, end), bold = true)
                    index = end + 2
                    continue
                }
            }

            if (char == '*' || char == '_') {
                val previousIsWord = index > 0 && text[index - 1].isLetterOrDigit()
                val snakeCase = char == '_' && previousIsWord
                if (!snakeCase) {
                    val end = text.indexOf(char, index + 1)
                    val closesWord = end + 1 >= text.length || !text[end + 1].isLetterOrDigit()
                    if (end > index + 1 && !text[index + 1].isWhitespace() && closesWord) {
                        flushPlain()
                        spans += styled(text.substring(index + 1, end), italic = true)
                        index = end + 1
                        continue
                    }
                }
            }

            plain.append(char)
            index++
        }
        flushPlain()
        return spans.ifEmpty { listOf(ChatSpan("")) }
    }

    private fun styled(text: String, bold: Boolean = false, italic: Boolean = false): List<ChatSpan> =
        inline(text).map {
            it.copy(bold = it.bold || bold, italic = it.italic || italic)
        }
}
