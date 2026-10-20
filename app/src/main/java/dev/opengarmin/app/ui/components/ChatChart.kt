package dev.opengarmin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.ChartSeries
import dev.opengarmin.app.coach.ChartSpec
import dev.opengarmin.app.coach.ChartType
import dev.opengarmin.app.coach.formatAxis
import dev.opengarmin.app.ui.theme.PulseColors
import kotlin.math.roundToInt

/**
 * Renders a [ChartSpec] inside a chat answer. Values are already aggregated by
 * the tool layer; this only maps them to a shared axis so multiple activity
 * series can be compared honestly. Charts are pinch-zoomable, pannable and
 * tappable for exact values, matching the activity charts.
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
                    maxLines = 2,
                )
            }
        }
        if (spec.series.size > 1) {
            ChartLegend(spec)
        }
        val series = spec.series.mapIndexed { index, item ->
            InteractiveSeries(
                name = item.name,
                color = chartColor(item, index),
                points = item.points,
            )
        }
        when (spec.type) {
            ChartType.Line -> InteractiveChart(
                series = series,
                style = InteractiveChartStyle.Line,
                unit = spec.unit,
                xTitle = spec.xLabel ?: spec.xStartLabel?.let { "Days since $it" },
                xValueFormatter = { value -> formatChartAxis(value) },
                yValueFormatter = { value -> formatAxis(value) },
                goal = spec.goal,
                invertY = spec.unit.equals("min/km", ignoreCase = true) ||
                    spec.yLabel?.startsWith("Pace", ignoreCase = true) == true,
                fill = series.size == 1,
                accessibility = spec.accessibilitySummary(),
                title = spec.title,
                fullscreenLegend = if (series.size > 1) series.map { it.name to it.color } else emptyList(),
            )
            ChartType.Bar -> {
                val categories = spec.categories.ifEmpty {
                    val count = spec.series.maxOfOrNull { item ->
                        item.points.maxOfOrNull { it.x.roundToInt() + 1 } ?: 0
                    } ?: 0
                    List(count) { formatChartAxis(it.toFloat()) }
                }
                if (categories.isEmpty()) return@Column
                val xMax = categories.size - 0.5f
                InteractiveChart(
                    series = series,
                    style = InteractiveChartStyle.Bar,
                    unit = spec.unit,
                    xTitle = spec.xLabel,
                    xValueFormatter = { value ->
                        categories.getOrNull(value.roundToInt())
                            ?: formatChartAxis(value)
                    },
                    yValueFormatter = { value -> formatAxis(value) },
                    xRange = -0.5f..xMax,
                    goal = spec.goal,
                    baseline = 0f,
                    fill = false,
                    accessibility = spec.accessibilitySummary(),
                    title = spec.title,
                    fullscreenLegend = if (series.size > 1) series.map { it.name to it.color } else emptyList(),
                )
            }
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

private fun chartSubtitle(spec: ChartSpec): String? {
    val parts = buildList {
        val y = spec.yLabel?.takeIf { it.isNotBlank() }
        val unit = spec.unit?.takeIf { it.isNotBlank() }
        if (y != null) {
            add(if (unit != null && !y.contains(unit, ignoreCase = true)) "$y · $unit" else y)
        } else if (unit != null) {
            add(unit)
        }
        if (spec.xStartLabel != null && spec.xEndLabel != null) {
            add("${spec.xStartLabel} – ${spec.xEndLabel}")
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
