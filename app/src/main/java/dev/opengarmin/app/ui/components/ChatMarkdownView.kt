package dev.opengarmin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.ChatBlock
import dev.opengarmin.app.coach.ChatMarkdown
import dev.opengarmin.app.coach.ChatSpan
import dev.opengarmin.app.ui.theme.PulseColors

/**
 * Renders assistant Markdown without pulling in a Markdown library. Only the
 * constructs produced by the coach are supported: bold/italic/code/links,
 * headings, short lists, quotes, code and charts.
 */
@Composable
fun AssistantMarkdown(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text, streaming) { ChatMarkdown.parse(text, streaming) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ChatBlock.Paragraph -> MarkdownText(block.spans, MaterialTheme.typography.bodyMedium)
                is ChatBlock.Heading -> MarkdownText(
                    spans = block.spans,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = PulseColors.Text,
                )
                is ChatBlock.ListItem -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = if (block.ordered) "${block.number}." else "•",
                        modifier = Modifier.width(if (block.ordered) 20.dp else 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.Accent,
                    )
                    MarkdownText(
                        spans = block.spans,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                is ChatBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "│",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.Accent,
                    )
                    MarkdownText(
                        spans = block.spans,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
                is ChatBlock.Code -> CodeBlock(block.text)
                is ChatBlock.Chart -> ChatChart(block.spec)
                ChatBlock.Divider -> DividerLine()
            }
        }
    }
}

@Composable
private fun MarkdownText(
    spans: List<ChatSpan>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = PulseColors.Text,
) {
    val annotated = remember(spans) { buildMarkdown(spans) }
    if (spans.any { it.link != null }) {
        val uriHandler = LocalUriHandler.current
        ClickableText(
            text = annotated,
            modifier = modifier,
            style = style.copy(color = color),
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
                    runCatching { uriHandler.openUri(annotation.item) }
                }
            },
        )
    } else {
        Text(
            text = annotated,
            modifier = modifier,
            style = style,
            color = color,
            translate = false,
        )
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PulseColors.SurfaceElevated)
            .border(1.dp, PulseColors.Border, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = PulseColors.Text,
            translate = false,
        )
    }
}

private fun buildMarkdown(spans: List<ChatSpan>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        if (span.text.isEmpty()) return@forEach
        val link = span.link
        if (link != null) pushStringAnnotation("URL", link)
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) PulseColors.SurfaceElevated else Color.Unspecified,
                color = if (link != null) PulseColors.Accent else Color.Unspecified,
                textDecoration = if (link != null) TextDecoration.Underline else null,
            ),
        ) {
            append(span.text)
        }
        if (link != null) pop()
    }
}
