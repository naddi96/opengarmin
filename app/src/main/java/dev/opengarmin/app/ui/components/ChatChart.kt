package dev.opengarmin.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.ChartSeries
import dev.opengarmin.app.coach.ChartSpec
import dev.opengarmin.app.coach.ChartType
import dev.opengarmin.app.coach.formatAxis
import dev.opengarmin.app.ui.theme.PulseColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Renders a [ChartSpec] inside a chat answer. Values are already aggregated by
 * the tool layer; this only maps them to a shared axis so multiple activity
 * series can be compared honestly.
 */
@Composable
fun ChatChart(spec: ChartSpec, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PulseColors.SurfaceElevated)
            .border(1.dp, PulseColors.BorderElevated, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = PulseColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            chartSubtitle(spec)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
        if (spec.series.size > 1) {
            ChartLegend(spec)
        }
        when (spec.type) {
            ChartType.Line -> LineChartBody(spec)
            ChartType.Bar -> BarChartBody(spec)
        }
    }
}

@Composable
private fun ChartLegend(spec: ChartSpec) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spec.series.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(chartColor(item, index)),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LineChartBody(spec: ChartSpec) {
    val points = spec.series.flatMap { it.points }
    if (points.isEmpty()) return
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val goal = spec.goal?.takeIf { it.isFinite() }
    val minY = minOf(points.minOf { it.y }, goal ?: points.minOf { it.y })
    val maxY = maxOf(points.maxOf { it.y }, goal ?: points.maxOf { it.y })
    val padY = ((maxY - minY).coerceAtLeast(0.001f) * 0.08f)
    val lowY = minY - padY
    val highY = maxY + padY
    val midY = (lowY + highY) / 2f
    val chartHeight = 150.dp

    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .width(42.dp)
                .height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            AxisLabel(formatAxis(highY))
            AxisLabel(formatAxis(midY))
            AxisLabel(formatAxis(lowY))
        }
        Spacer(Modifier.width(8.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(chartHeight)
                .semantics { contentDescription = spec.accessibilitySummary() },
        ) {
            val inset = 4f
            val width = size.width - inset * 2
            val height = size.height - inset * 2
            val spanX = (maxX - minX).coerceAtLeast(0.0001f)
            val spanY = (highY - lowY).coerceAtLeast(0.0001f)

            repeat(3) { step ->
                val y = inset + height * step / 2f
                drawLine(
                    color = PulseColors.Border,
                    start = Offset(inset, y),
                    end = Offset(size.width - inset, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f)),
                )
            }

            goal?.let { value ->
                val y = inset + (1f - (value - lowY) / spanY) * height
                drawLine(
                    color = PulseColors.GoalLine,
                    start = Offset(inset, y),
                    end = Offset(size.width - inset, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            spec.series.forEachIndexed { index, series ->
                val color = chartColor(series, index)
                val sorted = series.points.sortedBy { it.x }
                val drawn = sorted.map { point ->
                    Offset(
                        x = inset + (point.x - minX) / spanX * width,
                        y = inset + (1f - (point.y - lowY) / spanY) * height,
                    )
                }
                if (drawn.size == 1) {
                    drawCircle(color, radius = 4f, center = drawn.first())
                    return@forEachIndexed
                }
                val path = Path().apply {
                    drawn.forEachIndexed { pointIndex, point ->
                        if (pointIndex == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                if (spec.series.size == 1) {
                    val area = Path().apply {
                        addPath(path)
                        lineTo(drawn.last().x, size.height - inset)
                        lineTo(drawn.first().x, size.height - inset)
                        close()
                    }
                    drawPath(area, color = color.copy(alpha = 0.12f))
                }
                drawPath(
                    path,
                    color = color,
                    style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                if (spec.series.size == 1 && sorted.size <= 30) {
                    drawn.forEach { drawCircle(color = color.copy(alpha = 0.65f), radius = 2.2f, center = it) }
                }
                if (spec.series.size == 1) {
                    drawCircle(color = color, radius = 4.5f, center = drawn.last())
                    drawCircle(color = PulseColors.SurfaceElevated, radius = 2f, center = drawn.last())
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 50.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxisLabel(spec.xStartLabel ?: formatXAxis(minX))
        spec.xLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
                maxLines = 1,
            )
        }
        AxisLabel(spec.xEndLabel ?: formatXAxis(maxX))
    }
}

@Composable
private fun BarChartBody(spec: ChartSpec) {
    val categories = spec.categories.ifEmpty {
        spec.series.firstOrNull()?.points?.map { formatXAxis(it.x) }.orEmpty()
    }
    if (categories.isEmpty()) return
    val values = spec.series.flatMap { series -> series.points.map { it.y } }
    val maxValue = maxOf(values.maxOrNull() ?: 0f, spec.goal ?: 0f, 1f)
    val chartHeight = 130.dp

    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .width(42.dp)
                .height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            AxisLabel(formatAxis(maxValue))
            AxisLabel(formatAxis(maxValue / 2f))
            AxisLabel("0")
        }
        Spacer(Modifier.width(8.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(chartHeight)
                .semantics { contentDescription = spec.accessibilitySummary() },
        ) {
            val count = categories.size.coerceAtLeast(1)
            val slot = size.width / count
            val groupWidth = slot * 0.68f
            val barWidth = (groupWidth / spec.series.size.coerceAtLeast(1)).coerceAtLeast(1f)
            spec.series.forEachIndexed { seriesIndex, series ->
                val color = chartColor(series, seriesIndex)
                series.points.forEach { point ->
                    val pointIndex = point.x.roundToInt()
                    if (pointIndex !in 0 until count) return@forEach
                    val fraction = (point.y / maxValue).coerceIn(0f, 1f)
                    if (fraction <= 0f) return@forEach
                    val barHeight = size.height * fraction
                    val left = slot * pointIndex + (slot - groupWidth) / 2f + barWidth * seriesIndex
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth * 0.86f, barHeight),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                }
            }
            spec.goal?.takeIf { it > 0f }?.let { goal ->
                val y = size.height - (goal / maxValue).coerceIn(0f, 1f) * size.height
                drawLine(
                    color = PulseColors.GoalLine,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 50.dp),
    ) {
        val showEvery = if (categories.size <= 8) 1 else ceil(categories.size / 6.0).toInt().coerceAtLeast(1)
        categories.forEachIndexed { index, label ->
            val visible = index % showEvery == 0 || index == categories.lastIndex
            Text(
                text = if (visible) shortLabel(label) else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = PulseColors.TextMuted,
        maxLines = 1,
    )
}

private fun chartSubtitle(spec: ChartSpec): String? {
    val parts = buildList {
        val y = spec.yLabel?.takeIf { it.isNotBlank() }
        val unit = spec.unit?.takeIf { it.isNotBlank() }
        if (y != null) {
            add(if (unit != null && !y.contains(unit, ignoreCase = true)) "$y · $unit" else y)
        } else if (unit != null) {
            add(unit)
        }
        spec.goal?.takeIf { it > 0f }?.let { add("Goal ${formatAxis(it)}${unit?.let { u -> " $u" }.orEmpty()}") }
    }
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun chartColor(series: ChartSeries, index: Int): Color {
    val palette = listOf(
        PulseColors.Accent,
        PulseColors.Teal,
        PulseColors.Coral,
        PulseColors.Blue,
        PulseColors.Orange,
        PulseColors.Yellow,
    )
    return when (val key = series.color?.trim()?.lowercase()) {
        null, "" -> palette[index % palette.size]
        "accent" -> PulseColors.Accent
        "teal" -> PulseColors.Teal
        "coral" -> PulseColors.Coral
        "blue" -> PulseColors.Blue
        "orange" -> PulseColors.Orange
        "yellow" -> PulseColors.Yellow
        "muted" -> PulseColors.TextMuted
        else -> if (key.startsWith("#")) {
            runCatching { Color(android.graphics.Color.parseColor(key)) }
                .getOrDefault(palette[index % palette.size])
        } else {
            palette[index % palette.size]
        }
    }
}

private fun formatXAxis(value: Float): String = when {
    abs(value) >= 100f -> value.roundToInt().toString()
    value == value.toInt().toFloat() -> value.toInt().toString()
    else -> String.format(java.util.Locale.US, "%.1f", value)
}

private fun shortLabel(label: String): String {
    val trimmed = label.trim()
    return if (trimmed.length <= 8) trimmed else trimmed.take(7) + "…"
}
