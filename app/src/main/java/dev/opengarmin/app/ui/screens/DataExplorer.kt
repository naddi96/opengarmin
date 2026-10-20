package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.ChartPoint
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.FitMessageEntity
import dev.opengarmin.app.util.formatDate
import dev.opengarmin.app.util.formatElapsedTime
import dev.opengarmin.app.sportName
import dev.opengarmin.app.ui.components.InteractiveChart
import dev.opengarmin.app.ui.components.InteractiveChartStyle
import dev.opengarmin.app.ui.components.InteractiveSeries
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun DataExplorer(
    activity: ActivityEntity,
    messages: List<FitMessageEntity>,
    loading: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(messages) {
        val preferred = listOf(
            "record", "session", "lap", "activity", "event", "device_info",
            "gps_metadata", "time_in_zone", "split",
        )
        messages.groupBy { it.messageName }.toList().sortedWith(
            compareBy(
                { (name, _) -> preferred.indexOf(name).let { if (it < 0) preferred.size else it } },
                { (_, list) -> -list.size },
            ),
        )
    }
    var selectedType by rememberSaveable { mutableStateOf("record") }
    LaunchedEffect(groups) {
        if (groups.isEmpty()) return@LaunchedEffect
        if (groups.none { it.first == selectedType }) {
            selectedType = if (groups.any { it.first == "record" }) {
                "record"
            } else {
                groups.first().first
            }
        }
    }
    val selected = groups.firstOrNull { it.first == selectedType }?.second.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("All data", style = MaterialTheme.typography.titleLarge, color = PulseColors.Text)
                    Text(
                        "${sportName(activity.sport)} · ${formatDate(activity.startTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading FIT messages…", color = PulseColors.TextMuted)
                }
            }
        } else if (messages.isEmpty()) {
            item {
                Text(
                    "No stored FIT messages for this activity. Re-import it to populate the data explorer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulseColors.TextMuted,
                )
            }
        } else {
            item {
                Text(
                    "Message types",
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.Text,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(groups) { (name, list) ->
                        FilterChip(
                            selected = name == selectedType,
                            onClick = { selectedType = name },
                            label = { Text("$name (${list.size})") },
                        )
                    }
                }
            }
            if (selected.size >= 2) {
                item { MessagePlotSection(selected) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Raw messages", style = MaterialTheme.typography.titleMedium, color = PulseColors.Text)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "showing ${minOf(selected.size, 200)} of ${selected.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(selected.take(200), key = { it.id }) { message ->
                MessageCard(message)
            }
        }
    }
}

private data class PlotSeries(
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
    val minValue: Double,
    val maxValue: Double,
    val count: Int,
    val correlation: Double,
)

private val MessagePlotColors = listOf(
    PulseColors.Accent,
    PulseColors.Teal,
    PulseColors.Coral,
    PulseColors.Blue,
    PulseColors.Orange,
    PulseColors.Yellow,
    PulseColors.Magenta,
    PulseColors.Violet,
)

@Composable
private fun MessagePlotSection(messages: List<FitMessageEntity>) {
    val rows = remember(messages) { messages.map { parseMessageFields(it) } }
    val fieldNames = remember(rows) {
        val names = LinkedHashSet<String>()
        rows.take(500).forEach { names.addAll(it.keys) }
        names.sorted()
    }
    if (fieldNames.size < 2) {
        Text(
            "Not enough numeric fields to plot.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextMuted,
        )
        return
    }
    val orderedFields = remember(fieldNames) {
        val preferred = listOf(
            "heart_rate", "enhanced_speed", "speed", "enhanced_altitude", "altitude",
            "cadence", "power", "distance", "timestamp", "temperature",
        )
        preferred.filter { it in fieldNames } + fieldNames.filter { it !in preferred }
    }

    val defaultY = remember(fieldNames) {
        val speed = if ("enhanced_speed" in fieldNames) "enhanced_speed" else "speed"
        val altitude = if ("enhanced_altitude" in fieldNames) "enhanced_altitude" else "altitude"
        listOf("heart_rate", speed, altitude, "cadence", "power")
            .filter { it in fieldNames }
            .take(3)
            .ifEmpty { fieldNames.take(2) }
    }
    var selectedY by remember(rows) { mutableStateOf(defaultY) }
    var xField by remember(rows) {
        mutableStateOf(
            when {
                "distance" in fieldNames -> "distance"
                "timestamp" in fieldNames -> "timestamp"
                else -> fieldNames.first()
            },
        )
    }
    var normalize by remember(rows) { mutableStateOf(true) }

    val useNormalized = selectedY.size > 1 && normalize
    val series = remember(rows, xField, selectedY, useNormalized) {
        val xBase = if (xField == "timestamp") {
            rows.mapNotNull { it[xField] }.minOrNull() ?: 0.0
        } else {
            0.0
        }
        val xScale = if (xField == "distance") 0.001 else 1.0
        selectedY.mapIndexedNotNull { index, name ->
            val pairs = rows.mapNotNull { row ->
                val x = row[xField] ?: return@mapNotNull null
                val y = row[name] ?: return@mapNotNull null
                x to y
            }.sortedBy { it.first }
            if (pairs.size < 2) return@mapIndexedNotNull null
            val min = pairs.minOf { it.second }
            val max = pairs.maxOf { it.second }
            val span = (max - min).coerceAtLeast(1e-9)
            val points = pairs.map { (x, y) ->
                val plotY = when {
                    !useNormalized -> y
                    max - min > 1e-9 -> ((y - min) / span).coerceIn(0.0, 1.0)
                    else -> 0.5
                }
                ChartPoint(
                    x = ((x - xBase) * xScale).toFloat(),
                    y = plotY.toFloat(),
                )
            }
            PlotSeries(
                name = fieldLabel(name),
                color = MessagePlotColors[index % MessagePlotColors.size],
                points = points,
                minValue = min,
                maxValue = max,
                count = pairs.size,
                correlation = correlation(pairs),
            )
        }
    }

    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cross-plot", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Text(
                "Choose one X axis and one or more Y metrics. Only this plot is shown.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            FieldDropdown("X", orderedFields, xField) { xField = it }
            Text("Y metrics", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(orderedFields) { name ->
                    FilterChip(
                        selected = name in selectedY,
                        onClick = {
                            selectedY = if (name in selectedY) {
                                selectedY - name
                            } else {
                                selectedY + name
                            }
                        },
                        label = { Text(name) },
                    )
                }
            }
            if (selectedY.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = normalize,
                        onClick = { normalize = !normalize },
                        label = { Text("Normalise") },
                    )
                    Text(
                        if (normalize) {
                            "Each metric is scaled to its own range."
                        } else {
                            "Raw values share one Y scale."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
            if (series.isEmpty()) {
                Text(
                    "Select at least one Y metric with two or more samples.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            } else {
                val chartSeries = series.map { InteractiveSeries(it.name, it.color, it.points) }
                InteractiveChart(
                    series = chartSeries,
                    style = InteractiveChartStyle.Line,
                    xTitle = fieldLabel(xField),
                    xValueFormatter = { value -> formatPlotX(value, xField) },
                    yValueFormatter = { value ->
                        if (useNormalized) {
                            "${(value * 100f).roundToInt()}%"
                        } else {
                            formatCompact(value.toDouble())
                        }
                    },
                    yRange = if (useNormalized) 0f..1f else null,
                    fill = chartSeries.size == 1,
                    title = "Cross-plot",
                    accessibility = "Cross-plot. X ${fieldLabel(xField)}. " +
                        series.joinToString("; ") { "${it.name}: ${it.count} samples" },
                    fullscreenLegend = if (chartSeries.size > 1) {
                        series.map {
                            "${it.name} ${formatCompact(it.minValue)}–${formatCompact(it.maxValue)}" to it.color
                        }
                    } else {
                        emptyList()
                    },
                )
                MessagePlotLegend(series)
                if (useNormalized) {
                    Text(
                        "Multiple metrics are normalised to 0–100% of their own range so they can share one graph. The legend shows each metric's real range.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagePlotLegend(series: List<PlotSeries>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(item.color),
                )
                Text(
                    item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.Text,
                    maxLines = 1,
                )
                Text(
                    "${formatCompact(item.minValue)}–${formatCompact(item.maxValue)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                Text(
                    if (item.correlation.isNaN()) "r=n/a" else "r=%.2f".format(item.correlation),
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

private fun fieldLabel(name: String): String = when (name) {
    "heart_rate" -> "heart rate (bpm)"
    "speed", "enhanced_speed" -> "speed (m/s)"
    "altitude", "enhanced_altitude" -> "altitude (m)"
    "cadence" -> "cadence (rpm)"
    "power" -> "power (W)"
    "temperature" -> "temperature (°C)"
    "distance" -> "distance (km)"
    "timestamp" -> "elapsed"
    else -> name.replace('_', ' ')
}

private fun formatPlotX(value: Float, xField: String): String =
    if (xField == "timestamp") {
        formatElapsedTime(value.toDouble())
    } else {
        formatCompact(value.toDouble())
    }

@Composable
private fun FieldDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $selected", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: FitMessageEntity) {
    val fields = remember(message.fieldsJson) { JSONObject(message.fieldsJson) }
    val units = remember(message.unitsJson) { JSONObject(message.unitsJson) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row {
                Text(
                    message.messageName,
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Text,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    message.timestamp?.let { formatDate(it) } ?: "#${message.orderIndex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val text = fields.keys().asSequence().sorted().joinToString(" · ") { key ->
                val value = formatValue(fields.opt(key))
                val unit = units.optString(key).takeIf { it.isNotEmpty() }
                "$key=$value" + (unit?.let { " $it" } ?: "")
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        }
    }
}

private fun parseMessageFields(message: FitMessageEntity): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    val obj = JSONObject(message.fieldsJson)
    obj.keys().forEach { key ->
        val value = obj.opt(key)
        if (value is Number) out[key] = value.toDouble()
    }
    return out
}

private fun correlation(points: List<Pair<Double, Double>>): Double {
    if (points.size < 2) return Double.NaN
    val n = points.size
    val meanX = points.sumOf { it.first } / n
    val meanY = points.sumOf { it.second } / n
    var covariance = 0.0
    var varianceX = 0.0
    var varianceY = 0.0
    for ((x, y) in points) {
        val dx = x - meanX
        val dy = y - meanY
        covariance += dx * dy
        varianceX += dx * dx
        varianceY += dy * dy
    }
    val denominator = sqrt(varianceX * varianceY)
    return if (denominator == 0.0) Double.NaN else covariance / denominator
}


private fun formatCompact(value: Double): String =
    if (value.isFinite() && value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.1f".format(value)
    }

private fun formatValue(value: Any?): String = when (value) {
    null -> "—"
    is Double -> if (value.isFinite() && value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.3f".format(value)
    }
    is Float -> formatValue(value.toDouble())
    else -> value.toString()
}
