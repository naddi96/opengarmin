package dev.opengarmin.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opengarmin.app.coach.ChartPoint
import dev.opengarmin.app.data.MovementKind
import dev.opengarmin.app.data.MovementSegment
import dev.opengarmin.app.ui.theme.PulseColors
import kotlin.math.abs
import kotlin.math.roundToInt

data class InteractiveSeries(
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
)

enum class InteractiveChartStyle { Line, Bar }

/**
 * A line/bar chart with a clear, labelled scale and touch navigation:
 * pinch to zoom, drag horizontally to pan, tap to inspect values and
 * double-tap (or the Reset chip) to return to the full range.
 *
 * Vertical drags are deliberately not consumed, so the chart can live inside
 * a scrolling screen or chat without trapping the scroll gesture.
 */
@Composable
fun InteractiveChart(
    series: List<InteractiveSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    style: InteractiveChartStyle = InteractiveChartStyle.Line,
    unit: String? = null,
    xTitle: String? = null,
    xValueFormatter: (Float) -> String = { value -> formatChartAxis(value) },
    yValueFormatter: (Float) -> String = { value -> formatChartAxis(value) },
    xRange: ClosedFloatingPointRange<Float>? = null,
    goal: Float? = null,
    baseline: Float? = null,
    yRange: ClosedFloatingPointRange<Float>? = null,
    invertY: Boolean = false,
    fill: Boolean = true,
    accessibility: String? = null,
    showHint: Boolean = true,
    title: String? = null,
    showFullscreen: Boolean = true,
    fullscreenLegend: List<Pair<String, Color>> = emptyList(),
) {
    val visibleSeries = series.filter { it.points.isNotEmpty() }
    if (visibleSeries.isEmpty()) return
    val allPoints = visibleSeries.flatMap { it.points }
    val dataMinX = xRange?.start ?: allPoints.minOf { it.x }
    val dataMaxX = xRange?.endInclusive ?: allPoints.maxOf { it.x }
    val dataSpanX = (dataMaxX - dataMinX).coerceAtLeast(0.0001f)
    val dataKey = remember(visibleSeries, style, dataMinX, dataMaxX) {
        buildString {
            append(style).append('|').append(dataMinX).append('|').append(dataMaxX)
            visibleSeries.forEach { append('|').append(it.name).append(':').append(it.points.size) }
        }
    }
    val viewStartState = remember(dataKey) { mutableFloatStateOf(0f) }
    val viewEndState = remember(dataKey) { mutableFloatStateOf(1f) }
    val selectedXState = remember(dataKey) { mutableStateOf<Float?>(null) }
    var fullscreen by rememberSaveable(dataKey) { mutableStateOf(false) }
    var viewStart by viewStartState
    var viewEnd by viewEndState
    var selectedX by selectedXState
    val isZoomed = (viewEnd - viewStart) < 0.999f

    val viewXMin = dataMinX + dataSpanX * viewStart
    val viewXMax = dataMinX + dataSpanX * viewEnd
    val spanX = (viewXMax - viewXMin).coerceAtLeast(0.0001f)

    val visibleValues = visibleSeries.flatMap { item ->
        item.points
            .filter { it.x in viewXMin..viewXMax }
            .map { it.y }
    }.toMutableList()
    if (goal != null && goal.isFinite()) visibleValues.add(goal)
    if (baseline != null) visibleValues.add(baseline)
    if (visibleValues.isEmpty()) visibleValues.addAll(allPoints.map { it.y })
    var low = visibleValues.min()
    var high = visibleValues.max()
    if (high - low < 0.0001f) {
        val pad = if (abs(high) < 1f) 1f else abs(high) * 0.05f
        low -= pad
        high += pad
    } else {
        val pad = ((high - low) * 0.09f).coerceAtLeast(0.0001f)
        low -= pad
        high += pad
    }
    if (baseline != null) {
        low = minOf(low, baseline)
        high = maxOf(high, baseline)
        if (baseline == 0f && visibleValues.all { it >= 0f }) {
            low = 0f
        }
    }
    if (yRange != null) {
        low = yRange.start
        high = yRange.endInclusive
    }
    val spanY = (high - low).coerceAtLeast(0.0001f)
    val tickValues = List(4) { index -> low + spanY * index / 3f }
    val displayTicks = if (invertY) tickValues else tickValues.reversed()

    val snap: (Float) -> Float = if (style == InteractiveChartStyle.Bar) {
        { value -> value.roundToInt().toFloat() }
    } else {
        { value -> value }
    }
    val lineStrokeWidth = if (height >= 260.dp) 3.2f else 2.2f
    val axisTextStyle = if (height >= 260.dp) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelSmall
    }

    if (fullscreen && showFullscreen) {
        ChartFullscreen(
            title = title ?: accessibility ?: "Chart",
            subtitle = listOfNotNull(
                unit?.takeIf { it.isNotBlank() },
                xTitle?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            legend = fullscreenLegend,
            onDismiss = { fullscreen = false },
        ) { chartHeight ->
            InteractiveChart(
                series = visibleSeries,
                modifier = Modifier.fillMaxWidth(),
                height = chartHeight,
                style = style,
                unit = unit,
                xTitle = xTitle,
                xValueFormatter = xValueFormatter,
                yValueFormatter = yValueFormatter,
                xRange = xRange,
                goal = goal,
                baseline = baseline,
                yRange = yRange,
                invertY = invertY,
                fill = fill,
                accessibility = accessibility,
                showHint = true,
                title = title,
                showFullscreen = false,
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .chartTransformGestures(
                            viewStart = viewStartState,
                            viewEnd = viewEndState,
                            selectedX = selectedXState,
                            dataMinX = dataMinX,
                            dataMaxX = dataMaxX,
                        )
                        .pointerInput(dataKey, style) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val start = viewStartState.floatValue
                                    val end = viewEndState.floatValue
                                    val currentMin = dataMinX + (dataMaxX - dataMinX) * start
                                    val currentMax = dataMinX + (dataMaxX - dataMinX) * end
                                    selectedX = snap(currentMin + fraction * (currentMax - currentMin))
                                },
                                onDoubleTap = {
                                    viewStart = 0f
                                    viewEnd = 1f
                                    selectedX = null
                                },
                            )
                        }
                        .semantics { contentDescription = accessibility ?: "Interactive chart" },
                ) {
                    val width = size.width
                    val chartHeight = size.height
                    val xSpan = (viewXMax - viewXMin).coerceAtLeast(0.0001f)
                    fun xPx(value: Float): Float =
                        ((value - viewXMin) / xSpan).coerceIn(0f, 1f) * width
                    fun yPx(value: Float): Float {
                        val fraction = ((value - low) / (high - low)).coerceIn(0f, 1f)
                        return if (invertY) fraction * chartHeight else (1f - fraction) * chartHeight
                    }

                    clipRect(0f, 0f, width, chartHeight) {
                        // Horizontal grid at the labelled values.
                        displayTicks.forEach { value ->
                            val y = yPx(value)
                            drawLine(
                                color = PulseColors.Border,
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 7f)),
                            )
                        }
                        // A light baseline makes zero/negative metrics readable.
                        baseline?.let { value ->
                            drawLine(
                                color = PulseColors.TextMuted.copy(alpha = 0.55f),
                                start = Offset(0f, yPx(value)),
                                end = Offset(width, yPx(value)),
                                strokeWidth = 1.2f,
                            )
                        }
                        goal?.takeIf { it.isFinite() }?.let { value ->
                            drawLine(
                                color = PulseColors.GoalLine,
                                start = Offset(0f, yPx(value)),
                                end = Offset(width, yPx(value)),
                                strokeWidth = 1.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                            )
                        }

                        when (style) {
                            InteractiveChartStyle.Line -> drawLineSeries(
                                visibleSeries = visibleSeries,
                                viewXMin = viewXMin,
                                viewXMax = viewXMax,
                                xPx = ::xPx,
                                yPx = ::yPx,
                                chartHeight = chartHeight,
                                fill = fill && visibleSeries.size == 1,
                                strokeWidth = lineStrokeWidth,
                            )
                            InteractiveChartStyle.Bar -> drawBarSeries(
                                visibleSeries = visibleSeries,
                                viewXMin = viewXMin,
                                viewXMax = viewXMax,
                                xPx = ::xPx,
                                yPx = ::yPx,
                                baseline = baseline,
                                fallbackBaseline = low,
                            )
                        }

                        selectedX?.let { selected ->
                            if (selected in viewXMin..viewXMax) {
                                val x = xPx(selected)
                                drawLine(
                                    color = PulseColors.Text.copy(alpha = 0.55f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, chartHeight),
                                    strokeWidth = 1.2f,
                                )
                                visibleSeries.forEach { item ->
                                    val nearest = nearestPoint(item.points, selected)
                                    if (nearest != null) {
                                        drawCircle(
                                            color = PulseColors.Background,
                                            radius = 6f,
                                            center = Offset(xPx(nearest.x), yPx(nearest.y)),
                                        )
                                        drawCircle(
                                            color = item.color,
                                            radius = 4.2f,
                                            center = Offset(xPx(nearest.x), yPx(nearest.y)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .padding(start = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start,
                ) {
                    displayTicks.forEach { value ->
                        Text(
                            text = yValueFormatter(value),
                            modifier = Modifier
                                .background(
                                    PulseColors.Background.copy(alpha = 0.84f),
                                    RoundedCornerShape(3.dp),
                                )
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            style = axisTextStyle,
                            color = PulseColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isZoomed) {
                        Text(
                            text = "Reset zoom",
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(PulseColors.SurfaceElevated.copy(alpha = 0.92f))
                                .clickable(role = Role.Button) {
                                    viewStart = 0f
                                    viewEnd = 1f
                                    selectedX = null
                                }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PulseColors.Accent,
                        )
                    }
                    if (showFullscreen) {
                        ChartFullscreenButton(onClick = { fullscreen = true })
                    }
                }
            }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            List(4) { index -> viewXMin + spanX * index / 3f }.forEachIndexed { index, value ->
                Text(
                    text = xValueFormatter(value),
                    modifier = Modifier.weight(1f),
                    style = axisTextStyle,
                    color = PulseColors.TextMuted,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        3 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 1,
                )
            }
        }
        if (xTitle != null) {
            Text(
                text = xTitle,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                style = axisTextStyle,
                color = PulseColors.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        val selected = selectedX
        if (selected != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = xValueFormatter(selected),
                    style = axisTextStyle,
                    fontWeight = FontWeight.Bold,
                    color = PulseColors.Text,
                    maxLines = 1,
                )
                visibleSeries.forEach { item ->
                    val nearest = nearestPoint(item.points, selected)
                    if (nearest != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(item.color),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${item.name} ${yValueFormatter(nearest.y)}${unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}",
                                style = axisTextStyle,
                                color = PulseColors.TextMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        } else if (showHint) {
            Text(
                text = "Pinch to zoom · drag to pan · tap for values",
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Run / walk / idle timeline. Three labelled lanes share one x axis and the
 * same pinch/pan gestures as [InteractiveChart].
 */
@Composable
fun InteractiveMovementChart(
    segments: List<MovementSegment>,
    useDistance: Boolean,
    xValueFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 116.dp,
    xTitle: String? = null,
    showHint: Boolean = true,
    title: String? = null,
    showFullscreen: Boolean = true,
) {
    val usable = segments.mapNotNull { segment ->
        val start = if (useDistance) segment.startDistanceMeters else segment.startElapsedSeconds
        val end = if (useDistance) segment.endDistanceMeters else segment.endElapsedSeconds
        if (start == null || end == null || end <= start) null else Triple(start, end, segment.kind)
    }
    if (usable.isEmpty()) return
    val dataMinX = usable.minOf { it.first }.toFloat()
    val dataMaxX = usable.maxOf { it.second }.toFloat()
    val dataSpanX = (dataMaxX - dataMinX).coerceAtLeast(0.0001f)
    val viewStartState = remember(usable.size, dataMinX, dataMaxX) { mutableFloatStateOf(0f) }
    val viewEndState = remember(usable.size, dataMinX, dataMaxX) { mutableFloatStateOf(1f) }
    var viewStart by viewStartState
    var viewEnd by viewEndState
    val dummySelection = remember { mutableStateOf<Float?>(null) }
    val isZoomed = (viewEnd - viewStart) < 0.999f
    val axisTextStyle = if (height >= 260.dp) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelSmall
    }
    var fullscreen by rememberSaveable(usable.size, dataMinX, dataMaxX) { mutableStateOf(false) }

    val colors = mapOf(
        MovementKind.Run to PulseColors.Blue,
        MovementKind.Walk to PulseColors.Teal,
        MovementKind.Idle to PulseColors.Yellow,
    )
    val labels = mapOf(
        MovementKind.Run to "Run",
        MovementKind.Walk to "Walk",
        MovementKind.Idle to "Idle",
    )
    val lanes = listOf(MovementKind.Run, MovementKind.Walk, MovementKind.Idle)

    if (fullscreen && showFullscreen) {
        ChartFullscreen(
            title = title ?: "Run / walk",
            subtitle = if (useDistance) "Distance (km)" else "Elapsed",
            onDismiss = { fullscreen = false },
        ) { chartHeight ->
            InteractiveMovementChart(
                segments = segments,
                useDistance = useDistance,
                xValueFormatter = xValueFormatter,
                modifier = Modifier.fillMaxWidth(),
                height = chartHeight,
                xTitle = xTitle,
                showHint = true,
                title = title,
                showFullscreen = false,
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .chartTransformGestures(
                            viewStart = viewStartState,
                            viewEnd = viewEndState,
                            selectedX = dummySelection,
                            dataMinX = dataMinX,
                            dataMaxX = dataMaxX,
                        )
                        .semantics { contentDescription = "Run, walk and idle timeline" },
                ) {
                    val laneHeight = size.height / lanes.size
                    val viewXMin = dataMinX + dataSpanX * viewStart
                    val viewXMax = dataMinX + dataSpanX * viewEnd
                    val xSpan = (viewXMax - viewXMin).coerceAtLeast(0.0001f)
                    lanes.forEachIndexed { laneIndex, kind ->
                        val top = laneIndex * laneHeight + 3f
                        val laneDrawHeight = laneHeight - 6f
                        drawRoundRect(
                            color = PulseColors.RingTrack.copy(alpha = 0.55f),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, laneDrawHeight),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                        clipRect(0f, top, size.width, top + laneDrawHeight) {
                            usable.forEach { (start, end, segmentKind) ->
                                if (segmentKind != kind) return@forEach
                                val startX = ((start.toFloat() - viewXMin) / xSpan * size.width)
                                val endX = ((end.toFloat() - viewXMin) / xSpan * size.width)
                                if (endX < 0f || startX > size.width) return@forEach
                                val left = startX.coerceIn(0f, size.width)
                                val right = endX.coerceIn(0f, size.width)
                                drawRoundRect(
                                    color = colors.getValue(kind),
                                    topLeft = Offset(left, top),
                                    size = Size((right - left).coerceAtLeast(1.4f), laneDrawHeight),
                                    cornerRadius = CornerRadius(3f, 3f),
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .padding(start = 4.dp),
                    verticalArrangement = Arrangement.SpaceAround,
                    horizontalAlignment = Alignment.Start,
                ) {
                    lanes.forEach { kind ->
                        Text(
                            text = labels.getValue(kind),
                            modifier = Modifier
                                .background(
                                    PulseColors.Background.copy(alpha = 0.84f),
                                    RoundedCornerShape(3.dp),
                                )
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            style = axisTextStyle,
                            color = colors.getValue(kind),
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isZoomed) {
                        Text(
                            text = "Reset zoom",
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(PulseColors.SurfaceElevated.copy(alpha = 0.92f))
                                .clickable(role = Role.Button) {
                                    viewStart = 0f
                                    viewEnd = 1f
                                }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PulseColors.Accent,
                        )
                    }
                    if (showFullscreen) {
                        ChartFullscreenButton(onClick = { fullscreen = true })
                    }
                }
            }
        val viewXMin = dataMinX + dataSpanX * viewStart
        val viewXMax = dataMinX + dataSpanX * viewEnd
        val spanX = (viewXMax - viewXMin).coerceAtLeast(0.0001f)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            List(4) { index -> viewXMin + spanX * index / 3f }.forEachIndexed { index, value ->
                Text(
                    text = xValueFormatter(value),
                    modifier = Modifier.weight(1f),
                    style = axisTextStyle,
                    color = PulseColors.TextMuted,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        3 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 1,
                )
            }
        }
        if (xTitle != null) {
            Text(
                text = xTitle,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                style = axisTextStyle,
                color = PulseColors.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lanes.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.getValue(kind)))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = labels.getValue(kind),
                        style = axisTextStyle,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }
        if (showHint) {
            Text(
                text = "Pinch to zoom · drag to pan",
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineSeries(
    visibleSeries: List<InteractiveSeries>,
    viewXMin: Float,
    viewXMax: Float,
    xPx: (Float) -> Float,
    yPx: (Float) -> Float,
    chartHeight: Float,
    fill: Boolean,
    strokeWidth: Float,
) {
    visibleSeries.forEach { item ->
        val sorted = item.points.sortedBy { it.x }
        val windowStart = sorted.indexOfLast { it.x < viewXMin }.coerceAtLeast(0)
        val windowEnd = sorted.indexOfFirst { it.x > viewXMax }.let {
            if (it < 0) sorted.lastIndex else it
        }
        if (windowEnd < 0 || windowStart > windowEnd) return@forEach
        val window = sorted.subList(windowStart, windowEnd + 1)
        if (window.isEmpty()) return@forEach

        // Break the line where samples are missing instead of drawing a
        // misleading straight line across a pause or an uncovered day.
        val intervals = sorted.zipWithNext { first, second -> second.x - first.x }
            .filter { it > 0f }
            .sorted()
        val median = intervals.getOrNull(intervals.size / 2) ?: 1f
        val seriesSpan = (sorted.last().x - sorted.first().x).coerceAtLeast(0f)
        val gapThreshold = maxOf(median * 4f, seriesSpan * 0.0005f, 0.0001f)
        val segments = ArrayList<List<ChartPoint>>()
        var current = ArrayList<ChartPoint>()
        window.forEach { point ->
            val previous = current.lastOrNull()
            if (previous != null && point.x - previous.x > gapThreshold) {
                if (current.isNotEmpty()) segments.add(current)
                current = ArrayList()
            }
            current.add(point)
        }
        if (current.isNotEmpty()) segments.add(current)

        segments.forEach segmentLoop@ { segment ->
            val drawn = segment.map { point -> Offset(xPx(point.x), yPx(point.y)) }
            if (drawn.size == 1) {
                drawCircle(item.color, radius = 3.5f, center = drawn.first())
                return@segmentLoop
            }
            val line = Path().apply {
                drawn.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            if (fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(drawn.last().x, chartHeight)
                    lineTo(drawn.first().x, chartHeight)
                    close()
                }
                drawPath(area, color = item.color.copy(alpha = 0.12f))
            }
            drawPath(
                line,
                color = item.color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (sorted.size <= 30) {
                drawn.forEach { drawCircle(item.color.copy(alpha = 0.6f), radius = 2f, center = it) }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBarSeries(
    visibleSeries: List<InteractiveSeries>,
    viewXMin: Float,
    viewXMax: Float,
    xPx: (Float) -> Float,
    yPx: (Float) -> Float,
    baseline: Float?,
    fallbackBaseline: Float,
) {
    val firstCategory = kotlin.math.floor(viewXMin + 0.5f).toInt().coerceAtLeast(0)
    val lastCategory = kotlin.math.ceil(viewXMax - 0.5f).toInt()
    val count = (lastCategory - firstCategory + 1).coerceAtLeast(1)
    val slot = size.width / count
    val groupWidth = (slot * 0.7f).coerceAtLeast(1f)
    val barWidth = (groupWidth / visibleSeries.size.coerceAtLeast(1)).coerceAtLeast(1f)
    val baseY = yPx(baseline ?: fallbackBaseline)
    visibleSeries.forEachIndexed { seriesIndex, item ->
        item.points.forEach { point ->
            val category = point.x.roundToInt()
            if (category < firstCategory || category > lastCategory) return@forEach
            val valueY = yPx(point.y)
            val top = minOf(valueY, baseY)
            val barHeight = kotlin.math.abs(baseY - valueY)
            if (barHeight <= 0f) return@forEach
            val left = xPx(point.x) - groupWidth / 2f + barWidth * seriesIndex
            drawRoundRect(
                color = item.color,
                topLeft = Offset(left, top),
                size = Size((barWidth * 0.86f).coerceAtLeast(1f), barHeight),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

internal fun Modifier.chartTransformGestures(
    viewStart: MutableFloatState,
    viewEnd: MutableFloatState,
    selectedX: MutableState<Float?>?,
    dataMinX: Float,
    dataMaxX: Float,
    onHorizontalScrub: ((Float) -> Unit)? = null,
): Modifier = pointerInput(dataMinX, dataMaxX) {
    val minSpan = 0.01f
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var mode = 0 // 0 undecided, 1 horizontal pan, 2 zoom, 3 vertical scroll
        var totalX = 0f
        var totalY = 0f
        do {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val width = size.width.toFloat().coerceAtLeast(1f)
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount > 1 || zoom != 1f) {
                mode = 2
                val centroid = event.calculateCentroid(useCurrent = true)
                val span = (viewEnd.floatValue - viewStart.floatValue).coerceAtLeast(minSpan)
                val newSpan = (span / zoom).coerceIn(minSpan, 1f)
                val anchor = ((centroid.x / width).coerceIn(0f, 1f) - viewStart.floatValue) / span
                var newStart = viewStart.floatValue + anchor * span - anchor * newSpan
                newStart -= pan.x / width * newSpan
                newStart = newStart.coerceIn(0f, 1f - newSpan)
                viewStart.floatValue = newStart
                viewEnd.floatValue = newStart + newSpan
                event.changes.forEach { it.consume() }
            } else {
                totalX += pan.x
                totalY += pan.y
                if (mode == 0) {
                    if (abs(totalX) > viewConfiguration.touchSlop && abs(totalX) > abs(totalY)) {
                        mode = 1
                    } else if (abs(totalY) > viewConfiguration.touchSlop) {
                        mode = 3
                    }
                }
                if (mode == 1) {
                    val span = viewEnd.floatValue - viewStart.floatValue
                    if (span < 0.999f) {
                        var newStart = viewStart.floatValue - pan.x / width * span
                        newStart = newStart.coerceIn(0f, 1f - span)
                        viewStart.floatValue = newStart
                        viewEnd.floatValue = newStart + span
                    } else if (onHorizontalScrub != null) {
                        val x = event.changes.firstOrNull { it.pressed }?.position?.x
                            ?: event.changes.first().position.x
                        onHorizontalScrub((x / width).coerceIn(0f, 1f))
                    }
                    event.changes.forEach { it.consume() }
                }
            }
            if (selectedX != null && selectedX.value != null) {
                val x = selectedX.value!!
                val viewXMin = dataMinX + (dataMaxX - dataMinX) * viewStart.floatValue
                val viewXMax = dataMinX + (dataMaxX - dataMinX) * viewEnd.floatValue
                if (x < viewXMin || x > viewXMax) selectedX.value = null
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun nearestPoint(points: List<ChartPoint>, x: Float): ChartPoint? {
    if (points.isEmpty()) return null
    return points.minByOrNull { abs(it.x - x) }
}

@Composable
private fun ChartFullscreenButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(PulseColors.SurfaceElevated.copy(alpha = 0.92f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Fullscreen,
            contentDescription = "Open chart full screen",
            tint = PulseColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ChartFullscreen(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    legend: List<Pair<String, Color>> = emptyList(),
    chart: @Composable (Dp) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = PulseColors.Background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = PulseColors.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = PulseColors.TextMuted,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PulseColors.SurfaceElevated)
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close full-screen chart",
                            tint = PulseColors.Text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (legend.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        legend.forEach { (name, color) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(color),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PulseColors.TextMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val reserved = if (legend.isNotEmpty()) 110.dp else 80.dp
                    val chartHeight = (maxHeight - reserved).coerceAtLeast(160.dp)
                    chart(chartHeight)
                }
            }
        }
    }
}

/** Number formatting shared by activity and coach charts. */
fun formatChartAxis(value: Float): String = when {
    !value.isFinite() -> "–"
    value == 0f -> "0"
    abs(value) >= 1000f -> {
        val scaled = value / 1000f
        if (abs(scaled) >= 10f || scaled == scaled.toInt().toFloat()) {
            "${scaled.toInt()}k"
        } else {
            String.format(java.util.Locale.US, "%.1fk", scaled)
        }
    }
    value == value.toInt().toFloat() -> value.toInt().toString()
    abs(value) >= 100f -> value.toInt().toString()
    else -> String.format(java.util.Locale.US, "%.1f", value)
}
