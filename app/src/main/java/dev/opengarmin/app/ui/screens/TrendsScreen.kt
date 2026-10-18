package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.HistoryBucket
import dev.opengarmin.app.data.HistoryGranularity
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessHistory
import dev.opengarmin.app.data.WellnessMetrics
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.ui.components.BarChart
import dev.opengarmin.app.ui.components.CoachInsightCard
import dev.opengarmin.app.ui.components.LineChart
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.components.TinyStat
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private enum class TrendRange(val label: String, val days: Long?) {
    Week("7D", 7),
    Month("30D", 30),
    Quarter("90D", 90),
    Year("1Y", 365),
    All("All", null),
    Custom("Custom", -1),
}

private enum class ChartStyle { Bars, Line }

private enum class TrendMetric(
    val label: String,
    val unit: String,
    val color: Color,
    val chartStyle: ChartStyle,
) {
    Steps("Steps", "steps", PulseColors.Accent, ChartStyle.Bars),
    Sleep("Sleep", "h", PulseColors.Blue, ChartStyle.Bars),
    Hrv("HRV", "ms", PulseColors.Teal, ChartStyle.Line),
    RestingHr("Resting HR", "bpm", PulseColors.Coral, ChartStyle.Line),
    ActiveMinutes("Active", "min", PulseColors.Accent, ChartStyle.Bars),
    Calories("Calories", "kcal", PulseColors.Orange, ChartStyle.Bars),
    Stress("Stress", "/100", PulseColors.Yellow, ChartStyle.Line),
    Spo2("SpO₂", "%", PulseColors.Blue, ChartStyle.Line),
    Respiration("Resp.", "brpm", PulseColors.Blue, ChartStyle.Line),
    BodyBattery("Body Batt.", "/100", PulseColors.Teal, ChartStyle.Line),
    ;

    fun format(value: Double?): String {
        if (value == null) return "—"
        return when (this) {
            Steps -> "%,d".format(value.roundToInt())
            Sleep -> WellnessMetrics.formatHours(value)
            Hrv, RestingHr, ActiveMinutes, Stress, Spo2, BodyBattery -> "%.0f".format(value)
            Calories -> "%,d".format(value.roundToInt())
            Respiration -> "%.1f".format(value)
        }
    }

    fun formatWithUnit(value: Double?): String {
        val text = format(value)
        if (value == null || this == Sleep) return text
        return if (unit.isNotBlank()) "$text $unit" else text
    }
}

private data class DateRange(val start: LocalDate, val end: LocalDate) {
    val days: Long get() = end.toEpochDay() - start.toEpochDay() + 1
}

@Composable
fun TrendsScreen(
    days: List<WellnessDayEntity>,
    hrSamples: List<WellnessSampleEntity>,
    settings: PulseSettings,
    onAskCoach: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var rangeName by rememberSaveable { mutableStateOf(TrendRange.Week.name) }
    var metricName by rememberSaveable { mutableStateOf(TrendMetric.Steps.name) }
    var customStartText by rememberSaveable { mutableStateOf<String?>(null) }
    var customEndText by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    val today = LocalDate.now()
    val range = resolveRange(
        preset = TrendRange.entries.firstOrNull { it.name == rangeName } ?: TrendRange.Week,
        days = days,
        today = today,
        customStart = customStartText?.let(WellnessHistory::parse),
        customEnd = customEndText?.let(WellnessHistory::parse),
    )
    val metric = TrendMetric.entries.firstOrNull { it.name == metricName } ?: TrendMetric.Steps
    val hrvByDate = remember(hrSamples) {
        hrSamples.filter { it.kind == "hrv" && it.value > 0.0 }
            .groupBy { localDateOf(it.timestamp).toString() }
            .mapValues { (_, samples) -> samples.map { it.value }.average() }
    }
    val valueOf: (WellnessDayEntity) -> Double? = { day -> metricValue(metric, day, hrvByDate) }

    val inRange = WellnessHistory.daysInRange(days, range.start, range.end)
    val granularity = WellnessHistory.granularityFor(range.start, range.end)
    val buckets = WellnessHistory.buckets(inRange, granularity, valueOf)
    val average = WellnessHistory.average(inRange, valueOf)
    val total = WellnessHistory.total(inRange, valueOf)
    val daysWithMetric = inRange.count { valueOf(it) != null }
    val rangeLabel = formatRange(range, today)
    val granularityLabel = when (granularity) {
        HistoryGranularity.Daily -> "daily"
        HistoryGranularity.Weekly -> "weekly"
        HistoryGranularity.Monthly -> "monthly"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                dev.opengarmin.app.ui.components.IconButtonRound(
                    icon = Icons.Outlined.ArrowBack,
                    contentDescription = "Back to health",
                    onClick = onBack,
                )
                Spacer(Modifier.width(PulseSpacing.md))
            }
            Text("Trends", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
        }

        // Period presets -----------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            TrendRange.entries.forEach { preset ->
                PillChip(
                    label = preset.label,
                    selected = rangeName == preset.name,
                    onClick = {
                        if (preset == TrendRange.Custom) {
                            showDatePicker = true
                        } else {
                            rangeName = preset.name
                        }
                    },
                    leading = if (preset == TrendRange.Custom) Icons.Outlined.DateRange else null,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(rangeLabel, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
            Spacer(Modifier.weight(1f))
            if (rangeName == TrendRange.Custom.name) {
                TextButton(onClick = { showDatePicker = true }) { Text("Change period") }
            }
        }

        // Metric selector ----------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            TrendMetric.entries.forEach { candidate ->
                PillChip(
                    label = candidate.label,
                    selected = metric == candidate,
                    onClick = { metricName = candidate.name },
                )
            }
        }

        if (inRange.isEmpty()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text("No wellness data in this period", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Text(
                        "Sync your watch to build history. This app never zero-fills days the watch did not record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(PulseSpacing.lg))
            return@Column
        }

        // Selected metric ----------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(metric.label, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$granularityLabel average",
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextMuted,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        metric.format(average),
                        style = MaterialTheme.typography.displaySmall,
                        color = metric.color,
                    )
                    if (average != null && metric != TrendMetric.Sleep) {
                        Text(
                            " ${metric.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                Text(
                    selectedMetricSubtitle(metric, average, total, daysWithMetric, range.days.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                if (buckets.size >= 2 && metric.chartStyle == ChartStyle.Line) {
                    LineChart(
                        values = buckets.map { it.average.toFloat() },
                        labels = chartLabels(buckets, granularity),
                        color = metric.color,
                        contentDescription = "${metric.label} trend across ${buckets.size} $granularityLabel buckets",
                        height = 120.dp,
                    )
                } else if (buckets.isNotEmpty()) {
                    BarChart(
                        values = buckets.map { it.average.toFloat() },
                        labels = chartLabels(buckets, granularity),
                        goal = if (metric == TrendMetric.Steps) settings.stepGoal.toFloat() else null,
                        color = metric.color,
                        selectedIndex = buckets.lastIndex,
                        contentDescription = "${metric.label} trend across ${buckets.size} $granularityLabel buckets",
                        height = 120.dp,
                    )
                } else {
                    Text(
                        "No ${metric.label.lowercase(Locale.getDefault())} data in this period.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                if (metric == TrendMetric.Steps && settings.stepGoal > 0) {
                    Text(
                        "Dashed line: goal ${"%,d".format(settings.stepGoal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
                if (daysWithMetric < inRange.size) {
                    Text(
                        "$daysWithMetric of ${inRange.size} days with data. Missing values are not zero-filled.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        // Averages for every metric -----------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xlPlus),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                SectionLabel("Averages in this period")
                metricRows(inRange, hrvByDate)
            }
        }

        // Bucket breakdown ---------------------------------------------------
        if (buckets.isNotEmpty()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$granularityLabel history",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${buckets.size} buckets",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    buckets.forEach { bucket ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatBucketLabel(bucket, granularity),
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${metric.format(bucket.average)} ${metric.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = PulseColors.Text,
                            )
                        }
                        Text(
                            "${bucket.daysWithData} ${if (bucket.daysWithData == 1) "day" else "days"} with data",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                }
            }
        }

        CoachInsightCard(
            text = trendInsight(inRange, average, total, daysWithMetric, settings.stepGoal),
            onAskCoach = onAskCoach,
        )
        Spacer(Modifier.height(PulseSpacing.lg))
    }

    if (showDatePicker) {
        CustomRangeDialog(
            today = today,
            initialStart = range.start,
            initialEnd = range.end,
            onDismiss = { showDatePicker = false },
            onApply = { start, end ->
                customStartText = start.toString()
                customEndText = end.toString()
                rangeName = TrendRange.Custom.name
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun metricRows(days: List<WellnessDayEntity>, hrvByDate: Map<String, Double>) {
    MetricPairRow(TrendMetric.Steps, TrendMetric.Sleep, days, hrvByDate)
    MetricPairRow(TrendMetric.Hrv, TrendMetric.RestingHr, days, hrvByDate)
    MetricPairRow(TrendMetric.ActiveMinutes, TrendMetric.Calories, days, hrvByDate)
    MetricPairRow(TrendMetric.Stress, TrendMetric.Spo2, days, hrvByDate)
    MetricPairRow(TrendMetric.Respiration, TrendMetric.BodyBattery, days, hrvByDate)
}

@Composable
private fun MetricPairRow(
    left: TrendMetric,
    right: TrendMetric,
    days: List<WellnessDayEntity>,
    hrvByDate: Map<String, Double>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
        TinyStat(
            left.label,
            left.formatWithUnit(WellnessHistory.average(days) { metricValue(left, it, hrvByDate) }),
            Modifier.weight(1f),
        )
        TinyStat(
            right.label,
            right.formatWithUnit(WellnessHistory.average(days) { metricValue(right, it, hrvByDate) }),
            Modifier.weight(1f),
        )
    }
}

private fun resolveRange(
    preset: TrendRange,
    days: List<WellnessDayEntity>,
    today: LocalDate,
    customStart: LocalDate?,
    customEnd: LocalDate?,
): DateRange {
    val earliest = days.mapNotNull { WellnessHistory.parse(it.date) }
        .filter { !it.isAfter(today) }
        .minOrNull()
    return when (preset) {
        TrendRange.All -> DateRange(earliest ?: today.minusDays(29), today)
        TrendRange.Custom -> {
            val start = customStart ?: today.minusDays(29)
            val end = (customEnd ?: today).coerceAtMost(today)
            if (start.isAfter(end)) DateRange(end, start.coerceAtMost(today)) else DateRange(start, end)
        }
        else -> DateRange(today.minusDays((preset.days ?: 7) - 1), today)
    }
}

private fun metricValue(
    metric: TrendMetric,
    day: WellnessDayEntity,
    hrvByDate: Map<String, Double>,
): Double? = when (metric) {
    TrendMetric.Steps -> day.steps.toDouble()
    TrendMetric.Sleep -> WellnessMetrics.sleepSeconds(day)?.div(3600.0)
    TrendMetric.Hrv -> day.hrvLastNight?.takeIf { it > 0.0 } ?: hrvByDate[day.date]
    TrendMetric.RestingHr -> day.restingHeartRate?.takeIf { it in 1..250 }?.toDouble()
    TrendMetric.ActiveMinutes -> day.activeSeconds / 60.0
    TrendMetric.Calories -> day.activeCalories.toDouble()
    TrendMetric.Stress -> day.stressAvg?.takeIf { it in 0..100 }?.toDouble()
    TrendMetric.Spo2 -> day.spo2Avg?.takeIf { it in 1..100 }?.toDouble()
    TrendMetric.Respiration -> day.respirationAvg?.takeIf { it > 0.0 }
    TrendMetric.BodyBattery -> (day.bodyBatteryLatest ?: day.bodyBatteryMax)
        ?.takeIf { it in 1..100 }
        ?.toDouble()
}

private fun selectedMetricSubtitle(
    metric: TrendMetric,
    average: Double?,
    total: Double?,
    daysWithMetric: Int,
    daysRequested: Int,
): String {
    if (average == null) {
        return "No ${metric.label.lowercase(Locale.getDefault())} recorded in this period."
    }
    val coverage = "$daysWithMetric of $daysRequested days with data"
    return when (metric) {
        TrendMetric.Steps -> "%,d total steps · %s".format(total?.roundToInt() ?: 0, coverage)
        TrendMetric.Sleep -> "Average per night across $daysWithMetric ${if (daysWithMetric == 1) "day" else "days"} · $coverage"
        TrendMetric.Hrv -> "Average overnight HRV · $coverage"
        else -> "Average per day across $daysWithMetric ${if (daysWithMetric == 1) "day" else "days"} · $coverage"
    }
}

private fun chartLabels(buckets: List<HistoryBucket>, granularity: HistoryGranularity): List<String> {
    if (buckets.size > 14) return emptyList()
    return buckets.map { bucket ->
        when (granularity) {
            HistoryGranularity.Daily -> bucket.start.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            HistoryGranularity.Weekly -> bucket.start.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            HistoryGranularity.Monthly -> bucket.start.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
        }
    }
}

private fun formatBucketLabel(bucket: HistoryBucket, granularity: HistoryGranularity): String {
    val includeYear = bucket.start.year != LocalDate.now().year || bucket.end.year != LocalDate.now().year
    return when (granularity) {
        HistoryGranularity.Daily -> bucket.start.format(
            DateTimeFormatter.ofPattern(if (includeYear) "EEE d MMM yyyy" else "EEE d MMM", Locale.getDefault()),
        )
        HistoryGranularity.Weekly -> {
            val formatter = DateTimeFormatter.ofPattern(if (includeYear) "d MMM yyyy" else "d MMM", Locale.getDefault())
            "${bucket.start.format(formatter)} – ${bucket.end.format(formatter)}"
        }
        HistoryGranularity.Monthly -> bucket.start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }
}

private fun formatRange(range: DateRange, today: LocalDate): String {
    val includeYear = range.start.year != range.end.year || range.start.year != today.year
    val formatter = DateTimeFormatter.ofPattern(if (includeYear) "d MMM yyyy" else "d MMM", Locale.getDefault())
    return "${range.start.format(formatter)} – ${range.end.format(formatter)} · ${range.days} " +
        if (range.days == 1L) "day" else "days"
}

private fun trendInsight(
    days: List<WellnessDayEntity>,
    average: Double?,
    total: Double?,
    daysWithMetric: Int,
    stepGoal: Int,
): String {
    if (days.isEmpty()) {
        return "No wellness data in this range. Sync your watch to build a trend."
    }
    val goalDays = days.count { it.steps >= stepGoal.coerceAtLeast(1) }
    val sleep = WellnessHistory.average(days) { WellnessMetrics.sleepSeconds(it)?.toDouble() }
    val hrv = WellnessHistory.average(days) { it.hrvLastNight?.takeIf { value -> value > 0.0 } }
    val parts = ArrayList<String>()
    parts.add(
        "Steps averaged ${"%,d".format(WellnessHistory.average(days) { it.steps.toDouble() }?.roundToInt() ?: 0)} a day " +
            "and reached the ${"%,d".format(stepGoal.coerceAtLeast(1))} goal on $goalDays of ${days.size} days with data.",
    )
    sleep?.let { parts.add("Sleep averaged ${WellnessMetrics.formatSleepDuration(it.roundToInt())}.") }
    hrv?.let { parts.add("Overnight HRV averaged ${"%.0f".format(it)} ms.") }
    if (average == null) {
        parts.add("No values for the selected metric in this period.")
    } else if (total != null && daysWithMetric > 0) {
        parts.add("The selected metric is based on $daysWithMetric days and is never zero-filled.")
    }
    return parts.joinToString(" ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    today: LocalDate,
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onDismiss: () -> Unit,
    onApply: (LocalDate, LocalDate) -> Unit,
) {
    val todayMillis = today.toUtcMillis()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart.toUtcMillis(),
        initialSelectedEndDateMillis = initialEnd.toUtcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis?.let(::utcMillisToDate)
            val end = state.selectedEndDateMillis?.let(::utcMillisToDate)
            val canApply = start != null && end != null &&
                !start.isAfter(today) && !end.isAfter(today) && !start.isAfter(end)
            TextButton(
                onClick = {
                    if (start != null && end != null && canApply) {
                        onApply(start, end)
                    }
                },
                enabled = canApply,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DateRangePicker(
            state = state,
            showModeToggle = false,
            modifier = Modifier.heightIn(max = 460.dp),
            title = {
                Text(
                    "Select period",
                    modifier = Modifier.padding(start = PulseSpacing.xl, top = PulseSpacing.lg),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        )
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun localDateOf(timestamp: Long): LocalDate =
    Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
