package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.SleepBucket
import dev.opengarmin.app.data.SleepNight
import dev.opengarmin.app.data.SleepStageEntity
import dev.opengarmin.app.data.StressBreakdown
import dev.opengarmin.app.data.StressLevel
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal val SleepDeep = Color(0xFF5B7CFF)
internal val SleepLight = Color(0xFF8FA8FF)
internal val SleepRem = Color(0xFFE879F9)
internal val SleepAwake = Color(0xFFFFB45E)
internal val StressRest = PulseColors.Blue
internal val StressLow = PulseColors.Teal
internal val StressMedium = PulseColors.Yellow
internal val StressHigh = PulseColors.Coral

internal fun stressColor(level: StressLevel): Color = when (level) {
    StressLevel.REST -> StressRest
    StressLevel.LOW -> StressLow
    StressLevel.MEDIUM -> StressMedium
    StressLevel.HIGH -> StressHigh
}

internal fun sleepScoreColor(quality: String?): Color = when (quality?.lowercase(Locale.getDefault())) {
    "excellent" -> PulseColors.Green
    "good" -> PulseColors.Blue
    "fair" -> PulseColors.Orange
    else -> PulseColors.Coral
}

internal fun hrvStatusColor(status: String?): Color = when (status?.lowercase(Locale.getDefault())) {
    "balanced" -> PulseColors.Green
    "unbalanced" -> PulseColors.Orange
    "low", "poor" -> PulseColors.Coral
    else -> PulseColors.TextMuted
}

internal fun sampleTime(timestamp: Long): String =
    Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun dayLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

@Composable
internal fun CardTitle(title: String, aux: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
        if (aux != null) {
            Text(aux, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

@Composable
internal fun Legend(items: List<Triple<String, Color, Boolean>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        items.forEach { (label, color, line) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(if (line) 14.dp else 8.dp, 8.dp)
                        .clip(if (line) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.small)
                        .background(color),
                )
                Spacer(Modifier.width(PulseSpacing.sm))
                Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

/** Small line or bar sparkline used by the Health hub tiles. */
@Composable
internal fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    bars: Boolean = false,
    height: Dp = 30.dp,
) {
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height))
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Seven-day trend" },
    ) {
        val count = values.size
        if (bars) {
            val max = values.max().coerceAtLeast(1f)
            val slot = size.width / count
            val barWidth = (slot * 0.68f).coerceAtLeast(2f)
            values.forEachIndexed { index, value ->
                val fraction = (value / max).coerceIn(0.05f, 1f)
                val barHeight = size.height * fraction
                drawRoundRect(
                    color = color.copy(alpha = if (index == count - 1) 1f else 0.35f),
                    topLeft = Offset(slot * index + (slot - barWidth) / 2f, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }
        } else if (count == 1) {
            drawCircle(color, radius = 3.2f, center = Offset(size.width / 2f, size.height / 2f))
        } else {
            val min = values.min()
            val max = values.max()
            val span = (max - min).coerceAtLeast(0.0001f)
            val points = values.mapIndexed { index, value ->
                Offset(
                    x = size.width * index / (count - 1).toFloat(),
                    y = (1f - (value - min) / span) * (size.height - 4f) + 2f,
                )
            }
            val path = Path().apply {
                points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
            }
            drawPath(path, color = color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = 3.2f, center = points.last())
        }
    }
}

/** 270° progress gauge with a value centred inside. */
@Composable
internal fun ScoreGauge(
    progress: Float,
    value: String,
    unit: String,
    caption: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = PulseColors.RingTrack,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.displayMedium, color = color)
                if (unit.isNotBlank()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                    )
                }
            }
            Text(caption, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

internal data class GaugeBand(val fraction: Float, val color: Color)

/**
 * Gauge split into bands (HRV status, VO₂ fitness) with a marker for [marker].
 * Fractions are 0..1 across the 270° sweep.
 */
@Composable
internal fun BandGauge(
    bands: List<GaugeBand>,
    marker: Float,
    value: String,
    unit: String,
    caption: String,
    modifier: Modifier = Modifier,
    size: Dp = 190.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            var start = 0f
            bands.forEach { band ->
                val sweep = 270f * band.fraction
                if (sweep > 0.5f) {
                    drawArc(
                        color = band.color,
                        startAngle = 135f + start,
                        sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
            val angle = Math.toRadians((135f + 270f * marker.coerceIn(0f, 1f)).toDouble())
            val radius = (this.size.width - stroke) / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val point = Offset(
                x = center.x + radius * kotlin.math.cos(angle).toFloat(),
                y = center.y + radius * kotlin.math.sin(angle).toFloat(),
            )
            drawCircle(PulseColors.Background, radius = 9.dp.toPx(), center = point)
            drawCircle(PulseColors.Text, radius = 6.dp.toPx(), center = point)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.displayMedium, color = PulseColors.Text)
                if (unit.isNotBlank()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                    )
                }
            }
            Text(caption, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

/** Four-segment stress ring with the average in the middle. */
@Composable
internal fun StressRing(
    breakdown: StressBreakdown,
    average: Int,
    modifier: Modifier = Modifier,
    size: Dp = 124.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = PulseColors.RingTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            var start = 0f
            val total = breakdown.totalSeconds.coerceAtLeast(1L)
            StressLevel.entries.forEach { level ->
                val fraction = breakdown.secondsFor(level).toFloat() / total
                val sweep = 360f * fraction
                if (sweep > 0.5f) {
                    drawArc(
                        color = stressColor(level),
                        startAngle = start + 1.5f,
                        sweepAngle = (sweep - 3f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(average.toString(), style = MaterialTheme.typography.displaySmall, color = PulseColors.Text)
            Text("avg today", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

internal data class TimelineMarker(val start: Long, val end: Long?, val label: String, val color: Color)

/** Body Battery line over stress/rest bars, with sleep and activity markers. */
@Composable
internal fun BodyBatteryTimeline(
    battery: List<WellnessSampleEntity>,
    stress: List<WellnessSampleEntity>,
    markers: List<TimelineMarker>,
    overlay: TimelineOverlay,
    modifier: Modifier = Modifier,
) {
    if (battery.size < 2) {
        Text(
            "The detailed timeline is only stored for the most recent day.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextMuted,
        )
        return
    }
    val all = battery.map { it.timestamp } + stress.map { it.timestamp }
    val start = all.minOrNull() ?: return
    val end = all.maxOrNull() ?: return
    var selected by remember(battery, overlay) { mutableStateOf<Float?>(null) }
    val span = (end - start).coerceAtLeast(1L)
    val selectedIndex = selected?.let { fraction ->
        ((battery.size - 1) * fraction).roundToInt().coerceIn(0, battery.lastIndex)
    }
    val selectedSample = selectedIndex?.let { battery[it] }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedSample != null) {
                Text(
                    sampleTime(selectedSample.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
                Text(
                    "Body Battery ${selectedSample.value.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = PulseColors.Teal,
                )
            } else {
                Text("Tap or drag to inspect", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .semantics { contentDescription = "Body Battery and ${overlay.label.lowercase()} through the day" },
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(battery, overlay) {
                        detectTapGestures { offset ->
                            selected = (offset.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(battery, overlay) {
                        detectHorizontalDragGestures { change, _ ->
                            selected = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    },
            ) {
                val chartTop = size.height * 0.08f
                val chartBottom = size.height * 0.66f
                val barTop = size.height * 0.72f
                val barBottom = size.height * 0.94f
                val chartHeight = chartBottom - chartTop
                val barHeight = barBottom - barTop

                fun xFor(timestamp: Long): Float = (timestamp - start) / span.toFloat() * size.width
                fun yFor(value: Float): Float = chartBottom - (value / 100f).coerceIn(0f, 1f) * chartHeight

                // Activity / sleep markers.
                markers.forEach { marker ->
                    val left = xFor(marker.start).coerceIn(0f, size.width)
                    val right = marker.end?.let { xFor(it).coerceIn(0f, size.width) } ?: left
                    if (marker.end != null) {
                        drawRect(
                            color = marker.color.copy(alpha = 0.10f),
                            topLeft = Offset(left, chartTop),
                            size = Size((right - left).coerceAtLeast(2f), barBottom - chartTop),
                        )
                    }
                    drawLine(
                        color = marker.color.copy(alpha = 0.5f),
                        start = Offset(left, chartTop),
                        end = Offset(left, barBottom),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    )
                }

                // Grid lines for 0/25/50/75/100.
                listOf(0f, 25f, 50f, 75f, 100f).forEach { value ->
                    drawLine(
                        color = PulseColors.Border,
                        start = Offset(0f, yFor(value)),
                        end = Offset(size.width, yFor(value)),
                        strokeWidth = 1f,
                    )
                }

                // Bottom band: stress or rest bars. Body Battery keeps a
                // dimmed stress band so the two signals stay comparable.
                val stressBuckets = HealthMetrics.stressBuckets(stress)
                val interval = 900f
                val slot = (size.width * interval / span.toFloat()).coerceAtLeast(1.4f)
                stressBuckets.forEach { sample ->
                    val level = StressLevel.of(sample.value)
                    val height = when (level) {
                        StressLevel.REST -> barHeight * 0.28f
                        StressLevel.LOW -> barHeight * 0.5f
                        StressLevel.MEDIUM -> barHeight * 0.75f
                        StressLevel.HIGH -> barHeight
                    } * (if (overlay == TimelineOverlay.REST) 1f else (sample.value / 100f).toFloat().coerceIn(0.12f, 1f))
                    val color = if (overlay == TimelineOverlay.REST) StressRest else stressColor(level)
                    drawRect(
                        color = color.copy(alpha = if (overlay == TimelineOverlay.BODY_BATTERY) 0.35f else 1f),
                        topLeft = Offset(xFor(sample.timestamp), barBottom - height),
                        size = Size(slot, height),
                    )
                }

                // Body Battery line and fill.
                val points = battery.map { Offset(xFor(it.timestamp), yFor(it.value.toFloat())) }
                val line = Path().apply {
                    points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, chartBottom)
                    lineTo(points.first().x, chartBottom)
                    close()
                }
                drawPath(fill, color = PulseColors.Teal.copy(alpha = 0.14f))
                drawPath(line, color = PulseColors.Teal, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(PulseColors.Teal, radius = 3.2f, center = points.last())

                if (selected != null && selectedIndex != null) {
                    val x = xFor(battery[selectedIndex].timestamp)
                    drawLine(
                        color = PulseColors.Text.copy(alpha = 0.55f),
                        start = Offset(x, chartTop),
                        end = Offset(x, barBottom),
                        strokeWidth = 1.2f,
                    )
                    drawCircle(PulseColors.Text, radius = 4f, center = points[selectedIndex])
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0f, 0.33f, 0.66f, 1f).forEach { fraction ->
                Text(
                    sampleTime(start + (span * fraction).toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

internal enum class TimelineOverlay(val label: String) {
    BODY_BATTERY("Body Battery"),
    STRESS("Stress"),
    REST("Rest"),
}

/** Stress bars for the day with activity markers and a tap readout. */
@Composable
internal fun StressDayTimeline(
    stress: List<WellnessSampleEntity>,
    markers: List<TimelineMarker>,
    modifier: Modifier = Modifier,
) {
    if (stress.size < 2) {
        Text("No stress readings yet.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    val buckets = HealthMetrics.stressBuckets(stress)
    if (buckets.size < 2) {
        Text("Not enough stress readings for a timeline.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    var selected by remember(stress) { mutableStateOf<Float?>(null) }
    val start = buckets.minOf { it.timestamp }
    val end = buckets.maxOf { it.timestamp }
    val span = (end - start).coerceAtLeast(1L)
    val selectedIndex = selected?.let { fraction ->
        ((buckets.size - 1) * fraction).roundToInt().coerceIn(0, buckets.lastIndex)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedIndex != null) {
                Text(sampleTime(buckets[selectedIndex].timestamp), style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                Text(
                    "${buckets[selectedIndex].value.roundToInt()} · ${StressLevel.of(buckets[selectedIndex].value).label}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = stressColor(StressLevel.of(buckets[selectedIndex].value)),
                )
            } else {
                Text("Tap or drag to inspect", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .semantics { contentDescription = "Stress through the day with activity markers" },
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(stress) {
                        detectTapGestures { selected = (it.x / size.width).coerceIn(0f, 1f) }
                    }
                    .pointerInput(stress) {
                        detectHorizontalDragGestures { change, _ ->
                            selected = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    },
            ) {
                val top = size.height * 0.06f
                val bottom = size.height * 0.94f
                fun xFor(timestamp: Long) = (timestamp - start) / span.toFloat() * size.width
                fun yFor(value: Float) = bottom - (value / 100f).coerceIn(0f, 1f) * (bottom - top)
                markers.forEach { marker ->
                    val left = xFor(marker.start).coerceIn(0f, size.width)
                    val right = marker.end?.let { xFor(it).coerceIn(0f, size.width) } ?: left
                    if (marker.end != null) {
                        drawRect(
                            color = marker.color.copy(alpha = 0.10f),
                            topLeft = Offset(left, top),
                            size = Size((right - left).coerceAtLeast(2f), bottom - top),
                        )
                    }
                    drawLine(
                        color = marker.color.copy(alpha = 0.5f),
                        start = Offset(left, top),
                        end = Offset(left, bottom),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    )
                }
                listOf(25f, 50f, 75f).forEach { value ->
                    drawLine(
                        color = PulseColors.Border,
                        start = Offset(0f, yFor(value)),
                        end = Offset(size.width, yFor(value)),
                        strokeWidth = 1f,
                    )
                }
                val slot = (size.width * 900f / span.toFloat()).coerceAtLeast(1.6f)
                buckets.forEach { sample ->
                    val height = (yFor(0f) - yFor(sample.value.toFloat())).coerceAtLeast(1f)
                    drawRect(
                        color = stressColor(StressLevel.of(sample.value)),
                        topLeft = Offset(xFor(sample.timestamp), bottom - height),
                        size = Size(slot, height),
                    )
                }
                if (selectedIndex != null) {
                    val x = xFor(buckets[selectedIndex].timestamp)
                    drawLine(
                        color = PulseColors.Text.copy(alpha = 0.55f),
                        start = Offset(x, top),
                        end = Offset(x, bottom),
                        strokeWidth = 1.2f,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0f, 0.33f, 0.66f, 1f).forEach { fraction ->
                Text(sampleTime(start + (span * fraction).toLong()), style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

/** Sleep-stage bands with an optional heart-rate (or other metric) overlay. */
@Composable
internal fun SleepStagesOverlayChart(
    stages: List<SleepStageEntity>,
    overlay: List<WellnessSampleEntity>,
    overlayLabel: String,
    modifier: Modifier = Modifier,
) {
    if (stages.isEmpty()) {
        Text("Sleep stages were not written for this night.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    val start = stages.minOf { it.startTime }
    val end = stages.maxOf { it.endTime }
    val span = (end - start).coerceAtLeast(1L)
    var selected by remember(stages, overlayLabel) { mutableStateOf<Float?>(null) }
    val stageIndex = selected?.let { fraction ->
        val timestamp = start + (span * fraction).toLong()
        stages.indexOfLast { it.startTime <= timestamp }.coerceAtLeast(0)
    }
    val selectedStage = stageIndex?.let { stages.getOrNull(it) }
    val overlayPoint = selectedStage?.let { stage ->
        val mid = (stage.startTime + stage.endTime) / 2
        overlay.minByOrNull { abs(it.timestamp - mid) }
    }
    val rows = listOf("awake" to SleepAwake, "rem" to SleepRem, "light" to SleepLight, "deep" to SleepDeep)

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedStage != null) {
                Text(sampleTime(selectedStage.startTime), style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    Text(
                        if (selectedStage.stage == "rem") "REM" else selectedStage.stage.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PulseColors.Text,
                    )
                    if (overlayPoint != null) {
                        Text(
                            "$overlayLabel ${overlayPoint.value.roundToInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = PulseColors.Blue,
                        )
                    }
                }
            } else {
                Text("Tap or drag to inspect", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .height(168.dp)
                    .semantics { contentDescription = "Sleep stages with $overlayLabel overlay" },
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(stages, overlayLabel) {
                            detectTapGestures { selected = (it.x / size.width).coerceIn(0f, 1f) }
                        }
                        .pointerInput(stages, overlayLabel) {
                            detectHorizontalDragGestures { change, _ ->
                                selected = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    val rowHeight = size.height / 4f
                    fun xFor(timestamp: Long) = (timestamp - start) / span.toFloat() * size.width
                    rows.forEachIndexed { index, _ ->
                        val y = rowHeight * index + rowHeight / 2f
                        drawLine(
                            color = PulseColors.Border,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
                        )
                    }
                    stages.forEach { stage ->
                        val row = rows.indexOfFirst { it.first == stage.stage }
                        if (row < 0) return@forEach
                        val left = xFor(stage.startTime)
                        val right = xFor(stage.endTime)
                        val y = rowHeight * row + rowHeight / 2f
                        drawRoundRect(
                            color = rows[row].second,
                            topLeft = Offset(left, y - 10.dp.toPx()),
                            size = Size((right - left).coerceAtLeast(1.5f), 20.dp.toPx()),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                    }
                    if (overlay.size >= 2) {
                        val values = overlay.map { it.value.toFloat() }
                        val low = values.min()
                        val high = values.max()
                        val valueSpan = (high - low).coerceAtLeast(0.0001f)
                        val points = overlay.map { point ->
                            Offset(
                                x = xFor(point.timestamp),
                                y = 10.dp.toPx() + (1f - (point.value.toFloat() - low) / valueSpan) * (size.height - 20.dp.toPx()),
                            )
                        }
                        val path = Path().apply {
                            points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
                        }
                        drawPath(path, color = PulseColors.Text.copy(alpha = 0.28f), style = Stroke(width = 1.4f))
                    }
                    if (selected != null && selectedStage != null) {
                        val x = xFor(selectedStage.startTime)
                        drawLine(
                            color = PulseColors.Text.copy(alpha = 0.5f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.2f,
                        )
                    }
                }
            }
            Column(
                Modifier
                    .width(46.dp)
                    .height(168.dp),
                verticalArrangement = Arrangement.SpaceAround,
            ) {
                rows.forEach { (name, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(MaterialTheme.shapes.small).background(color))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (name == "rem") "REM" else name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                Text(sampleTime(start + (span * fraction).toLong()), style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

/** Daily high/low range bars for Body Battery history. */
@Composable
internal fun HighLowRangeChart(
    days: List<Pair<Int?, Int?>>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
) {
    val valid = days.filter { it.first != null && it.second != null }
    if (valid.isEmpty()) {
        Text("No Body Battery history yet.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(168.dp)
            .semantics { contentDescription = "Daily Body Battery high and low" },
    ) {
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        fun yFor(value: Int) = bottom - (value / 100f) * (bottom - top)
        listOf(0, 25, 50, 75, 100).forEach { value ->
            drawLine(
                color = PulseColors.Border,
                start = Offset(0f, yFor(value)),
                end = Offset(size.width, yFor(value)),
                strokeWidth = 1f,
            )
        }
        val slot = size.width / days.size
        val barWidth = (slot * 0.3f).coerceIn(3f, 10f)
        days.forEachIndexed { index, (high, low) ->
            if (high == null || low == null) return@forEachIndexed
            val center = slot * index + slot / 2f
            val topY = yFor(high)
            val bottomY = yFor(low)
            drawRoundRect(
                color = PulseColors.SurfaceElevated,
                topLeft = Offset(center - barWidth / 2f, topY),
                size = Size(barWidth, (bottomY - topY).coerceAtLeast(4f)),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            drawCircle(PulseColors.Teal, radius = 3.4f, center = Offset(center, topY))
            drawCircle(PulseColors.Text, radius = 3f, center = Offset(center, bottomY))
        }
    }
    if (labels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

/** Bedtime (violet) to wake-up (light) range bar for each night/month. */
@Composable
internal fun BedWakeRangeChart(
    nights: List<Triple<Int?, Int?, String>>,
    modifier: Modifier = Modifier,
    averageBed: Int? = null,
    averageWake: Int? = null,
) {
    val valid = nights.filter { it.first != null && it.second != null }
    if (valid.isEmpty()) {
        Text("Bedtime was not recorded for these nights.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "Bedtime and wake-up range" },
    ) {
        val minMinutes = 600f
        val maxMinutes = 1320f
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        fun yFor(minutes: Int) = bottom - ((minutes - minMinutes) / (maxMinutes - minMinutes)).coerceIn(0f, 1f) * (bottom - top)
        averageBed?.let {
            drawLine(
                color = PulseColors.Text.copy(alpha = 0.5f),
                start = Offset(0f, yFor(it)),
                end = Offset(size.width, yFor(it)),
                strokeWidth = 1.2f,
            )
        }
        averageWake?.let {
            drawLine(
                color = PulseColors.Text.copy(alpha = 0.5f),
                start = Offset(0f, yFor(it)),
                end = Offset(size.width, yFor(it)),
                strokeWidth = 1.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
            )
        }
        val slot = size.width / nights.size
        val barWidth = (slot * 0.32f).coerceIn(3f, 10f)
        nights.forEachIndexed { index, (bed, wake, _) ->
            if (bed == null || wake == null) return@forEachIndexed
            val center = slot * index + slot / 2f
            val topY = yFor(wake)
            val bottomY = yFor(bed)
            drawRoundRect(
                color = PulseColors.Blue.copy(alpha = 0.75f),
                topLeft = Offset(center - barWidth / 2f, topY),
                size = Size(barWidth, (bottomY - topY).coerceAtLeast(4f)),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(nights.first().third, nights.last().third).forEach { label ->
            Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

internal data class TrendBucket(
    val label: String,
    val score: Float?,
    val secondary: Float?,
)

/** Sleep score bars with a night-heart-rate line on a second scale. */
@Composable
internal fun DualTrendChart(
    buckets: List<TrendBucket>,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val scores = buckets.mapNotNull { it.score }
    val secondary = buckets.mapNotNull { it.secondary }
    if (scores.isEmpty() && secondary.isEmpty()) {
        Text("No history in this range yet.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = "Sleep score and night heart rate history" },
    ) {
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val count = buckets.size.coerceAtLeast(1)
        val slot = size.width / count
        val scoreMin = 0f
        val scoreMax = 100f
        val scoreSpan = (scoreMax - scoreMin).coerceAtLeast(1f)
        fun yScore(value: Float) = bottom - ((value - scoreMin) / scoreSpan).coerceIn(0f, 1f) * (bottom - top)
        val hrMin = (secondary.minOrNull() ?: 40f) - 4f
        val hrMax = (secondary.maxOrNull() ?: 80f) + 4f
        val hrSpan = (hrMax - hrMin).coerceAtLeast(1f)
        fun yHr(value: Float) = bottom - ((value - hrMin) / hrSpan).coerceIn(0f, 1f) * (bottom - top)

        buckets.forEachIndexed { index, bucket ->
            bucket.score?.let { score ->
                val barWidth = (slot * 0.32f).coerceIn(2f, 8f)
                val y = yScore(score)
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.75f),
                    topLeft = Offset(slot * index + slot / 2f - barWidth / 2f, y),
                    size = Size(barWidth, (bottom - y).coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        val points = buckets.mapIndexedNotNull { index, bucket ->
            bucket.secondary?.let { value -> Offset(slot * index + slot / 2f, yHr(value)) }
        }
        if (points.size >= 2) {
            val path = Path().apply {
                points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
            }
            drawPath(path, color = secondaryColor, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEach { point -> drawCircle(secondaryColor, radius = 2.6f, center = point) }
    }
}

/** Nightly HRV curve with the balanced baseline band and peak marker. */
@Composable
internal fun HrvOvernightChart(
    samples: List<WellnessSampleEntity>,
    baselineLow: Double?,
    baselineHigh: Double?,
    average: Double?,
    modifier: Modifier = Modifier,
) {
    val points = samples.filter { it.kind == "hrv" && it.value > 0.0 }.sortedBy { it.timestamp }
    if (points.size < 2) {
        Text("HRV samples were not written for this night.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(144.dp)
            .semantics { contentDescription = "Overnight HRV with baseline range" },
    ) {
        val values = points.map { it.value.toFloat() }
        var low = values.min()
        var high = values.max()
        baselineLow?.let { low = minOf(low, it.toFloat() - 3f) }
        baselineHigh?.let { high = maxOf(high, it.toFloat() + 3f) }
        average?.let { low = minOf(low, it.toFloat() - 3f); high = maxOf(high, it.toFloat() + 3f) }
        val span = (high - low).coerceAtLeast(1f)
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        fun yFor(value: Float) = bottom - ((value - low) / span) * (bottom - top)
        fun xFor(timestamp: Long) = (timestamp - points.first().timestamp) /
            (points.last().timestamp - points.first().timestamp).coerceAtLeast(1L).toFloat() * size.width

        if (baselineLow != null && baselineHigh != null) {
            drawRect(
                color = PulseColors.Green.copy(alpha = 0.10f),
                topLeft = Offset(0f, yFor(baselineHigh.toFloat())),
                size = Size(size.width, (yFor(baselineLow.toFloat()) - yFor(baselineHigh.toFloat())).coerceAtLeast(1f)),
            )
            drawLine(
                color = PulseColors.Green.copy(alpha = 0.7f),
                start = Offset(0f, yFor(baselineLow.toFloat())),
                end = Offset(size.width, yFor(baselineLow.toFloat())),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
        }
        average?.let {
            drawLine(
                color = PulseColors.TextMuted,
                start = Offset(0f, yFor(it.toFloat())),
                end = Offset(size.width, yFor(it.toFloat())),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)),
            )
        }
        val linePoints = points.map { Offset(xFor(it.timestamp), yFor(it.value.toFloat())) }
        val path = Path().apply {
            linePoints.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
        }
        drawPath(path, color = PulseColors.Text, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val peak = points.maxByOrNull { it.value }
        if (peak != null) {
            drawCircle(PulseColors.Blue, radius = 3.6f, center = Offset(xFor(peak.timestamp), yFor(peak.value.toFloat())))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(points.first().timestamp, points.last().timestamp).forEach {
            Text(sampleTime(it), style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

/** HRV status history: circle/square/triangle shapes over the baseline band. */
@Composable
internal fun HrvStatusChart(
    values: List<Pair<String?, Double?>>,
    baselineLow: Double?,
    baselineHigh: Double?,
    modifier: Modifier = Modifier,
) {
    val valid = values.filter { it.second != null }
    if (valid.isEmpty()) {
        Text("No HRV history in this range yet.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = "HRV status shapes against the baseline range" },
    ) {
        val numbers = valid.mapNotNull { it.second?.toFloat() }
        var low = numbers.min()
        var high = numbers.max()
        baselineLow?.let { low = minOf(low, it.toFloat() - 4f) }
        baselineHigh?.let { high = maxOf(high, it.toFloat() + 4f) }
        val span = (high - low).coerceAtLeast(1f)
        val top = 10.dp.toPx()
        val bottom = size.height - 10.dp.toPx()
        fun yFor(value: Float) = bottom - ((value - low) / span) * (bottom - top)
        if (baselineLow != null && baselineHigh != null) {
            drawRect(
                color = PulseColors.Text.copy(alpha = 0.09f),
                topLeft = Offset(0f, yFor(baselineHigh.toFloat())),
                size = Size(size.width, (yFor(baselineLow.toFloat()) - yFor(baselineHigh.toFloat())).coerceAtLeast(1f)),
            )
        }
        val slot = size.width / valid.size
        valid.forEachIndexed { index, (status, value) ->
            if (value == null) return@forEachIndexed
            val center = Offset(slot * index + slot / 2f, yFor(value.toFloat()))
            when (status?.lowercase(Locale.getDefault())) {
                "balanced" -> drawCircle(hrvStatusColor(status), radius = 4.5f, center = center)
                "unbalanced" -> drawRect(
                    color = hrvStatusColor(status),
                    topLeft = Offset(center.x - 4f, center.y - 4f),
                    size = Size(8f, 8f),
                )
                else -> {
                    val path = Path().apply {
                        moveTo(center.x, center.y - 5f)
                        lineTo(center.x + 5f, center.y + 4f)
                        lineTo(center.x - 5f, center.y + 4f)
                        close()
                    }
                    drawPath(path, color = hrvStatusColor(status))
                }
            }
        }
    }
}

/** Plain line chart used for the VO₂ max trend. */
@Composable
internal fun LineTrendChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 154.dp,
    band: ClosedFloatingPointRange<Float>? = null,
) {
    if (values.size < 2) {
        Text("Not enough readings for a trend yet.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Trend chart" },
    ) {
        val low = minOf(values.min(), band?.start ?: values.min())
        val high = maxOf(values.max(), band?.endInclusive ?: values.max())
        val span = (high - low).coerceAtLeast(0.0001f)
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        fun yFor(value: Float) = bottom - ((value - low) / span) * (bottom - top)
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = top + (bottom - top) * fraction
            drawLine(color = PulseColors.Border, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        }
        band?.let {
            drawRect(
                color = color.copy(alpha = 0.06f),
                topLeft = Offset(0f, yFor(it.endInclusive)),
                size = Size(size.width, (yFor(it.start) - yFor(it.endInclusive)).coerceAtLeast(1f)),
            )
        }
        val points = values.mapIndexed { index, value ->
            Offset(size.width * index / (values.size - 1).toFloat(), yFor(value))
        }
        val path = Path().apply {
            points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(points.last().x, bottom)
            lineTo(points.first().x, bottom)
            close()
        }
        drawPath(fill, color = color.copy(alpha = 0.10f))
        drawPath(path, color = PulseColors.Text, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEach { drawCircle(PulseColors.Text, radius = 2.2f, center = it) }
        drawCircle(color, radius = 4f, center = points.last())
    }
    if (labels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

/** Average stress per day/month with level colours. */
@Composable
internal fun StressAverageBars(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) {
        Text("No stress history in this range.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        return
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = "Average stress per day" },
    ) {
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        fun yFor(value: Float) = bottom - (value / 100f).coerceIn(0f, 1f) * (bottom - top)
        listOf(25f, 50f, 75f).forEach { value ->
            drawLine(
                color = PulseColors.Border,
                start = Offset(0f, yFor(value)),
                end = Offset(size.width, yFor(value)),
                strokeWidth = 1f,
            )
        }
        val slot = size.width / values.size
        val barWidth = (slot * 0.5f).coerceIn(2f, 12f)
        values.forEachIndexed { index, value ->
            val y = yFor(value)
            drawRoundRect(
                color = stressColor(StressLevel.of(value.toDouble())),
                topLeft = Offset(slot * index + (slot - barWidth) / 2f, y),
                size = Size(barWidth, (bottom - y).coerceAtLeast(2f)),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
    if (labels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

/** Compact value grid used for sleep and HRV summary cells. */
@Composable
internal fun ValueGrid(
    values: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PulseSpacing.lgPlus)) {
        values.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        Text(
                            value,
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                            maxLines = 1,
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Day row used by Body Battery history and HRV recent nights. */
@Composable
internal fun MetricDayRow(
    name: String,
    sub: String,
    left: String,
    leftLabel: String,
    right: String? = null,
    rightLabel: String? = null,
    leftColor: Color = PulseColors.Text,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(PulseColors.Surface)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button) { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = PulseSpacing.lgPlus, vertical = PulseSpacing.mdPlus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(left, style = MaterialTheme.typography.titleSmall, color = leftColor)
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
        if (right != null) {
            Spacer(Modifier.width(PulseSpacing.xl))
            Column(horizontalAlignment = Alignment.End) {
                Text(right, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                Text(rightLabel ?: "", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}
