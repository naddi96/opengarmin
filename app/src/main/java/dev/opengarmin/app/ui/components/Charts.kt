package dev.opengarmin.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlin.math.roundToInt

@Composable
fun ProgressRing(
    progress: Float,
    label: String,
    caption: String,
    color: Color = PulseColors.Accent,
    size: Dp = 124.dp,
    strokeWidth: Dp = 12.dp,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            drawArc(
                color = PulseColors.RingTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                color = PulseColors.Text,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }
    }
}

@Composable
fun LineChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    height: Dp = 80.dp,
    latestDot: Boolean = true,
) {
    Column(modifier = modifier) {
        if (values.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(PulseColors.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Not enough samples yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
            return@Column
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { this.contentDescription = contentDescription },
        ) {
            val min = values.min()
            val max = values.max()
            val span = (max - min).coerceAtLeast(0.0001f)
            val top = 6f
            val usableHeight = size.height - 12f
            val points = values.mapIndexed { index, value ->
                val x = size.width * index / (values.size - 1).toFloat()
                val y = top + (1f - (value - min) / span) * usableHeight
                Offset(x, y)
            }
            val line = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            if (fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, size.height)
                    lineTo(points.first().x, size.height)
                    close()
                }
                drawPath(area, color = color.copy(alpha = 0.14f))
            }
            drawPath(
                line,
                color = color,
                style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (latestDot) {
                drawCircle(color = color, radius = 5f, center = points.last())
                drawCircle(color = PulseColors.Surface, radius = 2.5f, center = points.last())
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    goal: Float?,
    color: Color,
    selectedIndex: Int?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val maxValue = maxOf(
                    values.maxOrNull() ?: 0f,
                    goal ?: 0f,
                ).coerceAtLeast(1f)
                val count = values.size.coerceAtLeast(1)
                val slot = size.width / count
                val barWidth = minOf(slot * 0.72f, 56.dp.toPx()).coerceAtLeast(1f)
                values.forEachIndexed { index, value ->
                    val fraction = (value / maxValue).coerceIn(0f, 1f)
                    val barHeight = if (value <= 0f) size.height * 0.008f else size.height * fraction
                    val left = slot * index + (slot - barWidth) / 2f
                    val barColor = if (selectedIndex == null || selectedIndex == index) {
                        color
                    } else {
                        color.copy(alpha = 0.38f)
                    }
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                    )
                }
                if (goal != null && goal > 0f) {
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
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun ZoneBar(
    percentages: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    contentDescription: String? = null,
) {
    val colors = PulseColors.ZoneColors
    val total = percentages.sum()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(PulseColors.RingTrack),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (total <= 0f) {
            Box(Modifier.fillMaxWidth().height(height).background(PulseColors.RingTrack))
        } else {
            percentages.forEachIndexed { index, value ->
                if (value > 0f) {
                    Box(
                        Modifier
                            .weight(value)
                            .height(height)
                            .background(colors[index % colors.size]),
                    )
                }
            }
        }
    }
}

@Composable
fun ZoneLegend(percentages: List<Float>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        percentages.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(PulseColors.ZoneColors[index % PulseColors.ZoneColors.size]),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Z${index + 1} ${value.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

/** Draws a real GPS trace when available, otherwise a real heart-rate trace. */
@Composable
fun ActivityTrace(
    records: List<RecordEntity>,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String,
) {
    val hasGps = records.count { it.latitude != null && it.longitude != null } >= 2
    val values = if (hasGps) {
        records.mapNotNull { record ->
            val lat = record.latitude ?: return@mapNotNull null
            val lon = record.longitude ?: return@mapNotNull null
            Offset(lon.toFloat(), lat.toFloat())
        }
    } else {
        records.mapNotNull { it.heartRate }.mapIndexed { index, hr ->
            Offset(index.toFloat(), hr.toFloat())
        }
    }
    if (values.size < 2) {
        Box(
            modifier = modifier.background(PulseColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Text("No trace", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
        return
    }
    Canvas(
        modifier = modifier
            .background(PulseColors.SurfaceElevated)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val minX = values.minOf { it.x }
        val maxX = values.maxOf { it.x }
        val minY = values.minOf { it.y }
        val maxY = values.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(0.000001f)
        val spanY = (maxY - minY).coerceAtLeast(0.000001f)
        val padding = 10f
        val path = Path()
        values.forEachIndexed { index, point ->
            val x = padding + (point.x - minX) / spanX * (size.width - padding * 2)
            val y = padding + (1f - (point.y - minY) / spanY) * (size.height - padding * 2)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun TinyStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = PulseColors.Text,
        )
    }
}
