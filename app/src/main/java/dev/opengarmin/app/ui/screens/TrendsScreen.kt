package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.ui.components.BarChart
import dev.opengarmin.app.ui.components.CoachInsightCard
import dev.opengarmin.app.ui.components.CompactCard
import dev.opengarmin.app.ui.components.LineChart
import dev.opengarmin.app.ui.components.SegmentedControl
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrendsScreen(
    days: List<WellnessDayEntity>,
    hrSamples: List<WellnessSampleEntity>,
    settings: PulseSettings,
    onAskCoach: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var rangeIndex by rememberSaveable { mutableIntStateOf(1) }
    val sorted = days.sortedBy { it.date }
    val today = sorted.lastOrNull()

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
            Spacer(Modifier.weight(1f))
            val rangeLabel = when (rangeIndex) {
                0 -> today?.let { formatShortDate(it.date) } ?: "Today"
                1 -> "Last 7 days"
                else -> "Last 4 weeks"
            }
            Text(
                text = rangeLabel,
                modifier = Modifier
                    .padding(start = PulseSpacing.md),
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
        }

        SegmentedControl(
            options = listOf("Day", "Week", "Month"),
            selectedIndex = rangeIndex,
            onSelected = { rangeIndex = it },
        )

        if (today == null) {
            Text(
                "No wellness data yet. Sync your watch to build a trend.",
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.TextMuted,
            )
            return@Column
        }

        val view = buildTrendView(rangeIndex, sorted, hrSamples, settings, LocalDate.now())
        SectionLabel(view.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            SummaryTile("Steps", view.stepsAvg, "steps", Modifier.weight(1f))
            SummaryTile("Resting HR", view.restingAvg, "bpm", Modifier.weight(1f))
            SummaryTile("Active", view.activeAvg, "min", Modifier.weight(1f))
            SummaryTile("Burn", view.burnAvg, "kcal", Modifier.weight(1f))
        }

        dev.opengarmin.app.ui.components.PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Steps", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(view.stepsSub, style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                }
                Text(view.stepsBig, style = MaterialTheme.typography.displaySmall, color = PulseColors.Accent)
                BarChart(
                    values = view.bars,
                    labels = view.barLabels,
                    goal = settings.stepGoal.toFloat(),
                    color = PulseColors.Accent,
                    selectedIndex = view.bars.lastIndex,
                    contentDescription = view.stepsContentDescription,
                    height = 120.dp,
                )
                if (settings.stepGoal > 0) {
                    Text(
                        "Dashed line: goal ${"%,d".format(settings.stepGoal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
                if (view.daysWithData < view.daysRequested) {
                    Text(
                        "${view.daysWithData} of ${view.daysRequested} days have data. Missing days are not zero-filled.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        dev.opengarmin.app.ui.components.PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(view.hrTitle, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        Text(
                            view.hrBig,
                            style = MaterialTheme.typography.headlineMedium,
                            color = PulseColors.Text,
                        )
                    }
                    Text(
                        view.hrNote,
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(start = PulseSpacing.md),
                    )
                }
                LineChart(
                    values = view.hrValues,
                    labels = view.hrLabels,
                    color = PulseColors.Coral,
                    contentDescription = view.hrContentDescription,
                    height = 80.dp,
                )
            }
        }

        CoachInsightCard(
            text = view.insight,
            onAskCoach = onAskCoach,
        )
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

private data class TrendView(
    val caption: String,
    val stepsAvg: String,
    val restingAvg: String,
    val activeAvg: String,
    val burnAvg: String,
    val stepsBig: String,
    val stepsSub: String,
    val bars: List<Float>,
    val barLabels: List<String>,
    val daysWithData: Int,
    val daysRequested: Int,
    val stepsContentDescription: String,
    val hrTitle: String,
    val hrBig: String,
    val hrNote: String,
    val hrValues: List<Float>,
    val hrLabels: List<String>,
    val hrContentDescription: String,
    val insight: String,
)

private fun buildTrendView(
    rangeIndex: Int,
    sortedDays: List<WellnessDayEntity>,
    hrSamples: List<WellnessSampleEntity>,
    settings: PulseSettings,
    today: LocalDate,
): TrendView {
    val todayDay = sortedDays.lastOrNull()
    if (rangeIndex == 0) {
        val samples = hrSamples.filter { it.kind == "hr" }.sortedBy { it.timestamp }
        val minHr = samples.minOfOrNull { it.value }?.roundToInt()
        val maxHr = samples.maxOfOrNull { it.value }?.roundToInt()
        val latestHr = samples.lastOrNull()?.value?.roundToInt()
        val steps = todayDay?.steps ?: 0
        val insight = if (todayDay == null) {
            "No wellness data for today yet."
        } else {
            "You logged ${"%,d".format(todayDay.steps)} steps and ${todayDay.activeSeconds / 60} active minutes today. " +
                if (samples.size >= 2) {
                    "The heart-rate chart uses ${samples.size} real samples from your watch."
                } else {
                    "No intraday heart-rate samples are stored for today."
                }
        }
        val latestIsToday = todayDay?.date == LocalDate.now().toString()
        return TrendView(
            caption = if (latestIsToday) "Today so far" else "Latest recorded day",
            stepsAvg = todayDay?.let { "%,d".format(it.steps) } ?: "—",
            restingAvg = todayDay?.restingHeartRate?.takeIf { it in 1..250 }?.toString() ?: "—",
            activeAvg = todayDay?.let { "${it.activeSeconds / 60}" } ?: "—",
            burnAvg = todayDay?.let { "%,d".format(it.activeCalories) } ?: "—",
            stepsBig = "%,d".format(steps),
            stepsSub = if (latestIsToday) "steps today (no hourly step samples)" else "steps on latest day",
            bars = listOf(steps.toFloat()),
            barLabels = listOf("Today"),
            daysWithData = if (todayDay != null) 1 else 0,
            daysRequested = 1,
            stepsContentDescription = "Steps today: $steps",
            hrTitle = "Heart rate today",
            hrBig = if (latestHr != null) "$latestHr bpm" else "— bpm",
            hrNote = if (minHr != null && maxHr != null) "Day range $minHr–$maxHr bpm" else "No samples",
            hrValues = samples.map { it.value.toFloat() },
            hrLabels = if (samples.size >= 2) listOf("Start", "Mid", "End", if (latestIsToday) "Now" else "Last") else emptyList(),
            hrContentDescription = if (minHr != null && maxHr != null) {
                "Heart rate today from $minHr to $maxHr bpm"
            } else {
                "No heart-rate samples today"
            },
            insight = insight,
        )
    }

    val requested = if (rangeIndex == 1) 7 else 28
    val start = today.minusDays(requested.toLong() - 1)
    val inRange = sortedDays.filter {
        val date = runCatching { LocalDate.parse(it.date) }.getOrNull()
        date != null && !date.isBefore(start)
    }
    val avgSteps = if (inRange.isEmpty()) 0.0 else inRange.map { it.steps }.average()
    val restingValues = inRange.mapNotNull { it.restingHeartRate?.takeIf { value -> value in 1..250 } }
    val avgResting = restingValues.takeIf { it.isNotEmpty() }?.average()
    val avgActive = if (inRange.isEmpty()) 0.0 else inRange.map { it.activeSeconds / 60.0 }.average()
    val avgBurn = if (inRange.isEmpty()) 0.0 else inRange.map { it.activeCalories.toDouble() }.average()
    val goal = settings.stepGoal.coerceAtLeast(1)
    val goalDays = inRange.count { it.steps >= goal }

    val bars: List<Float>
    val barLabels: List<String>
    val stepsBig: String
    val stepsSub: String
    val hrValues: List<Float>
    val hrLabels: List<String>
    val hrTitle: String
    val hrBig: String
    val hrNote: String
    val caption: String

    if (rangeIndex == 1) {
        bars = inRange.map { it.steps.toFloat() }
        barLabels = inRange.map {
            runCatching { LocalDate.parse(it.date).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) }
                .getOrDefault(it.date)
        }
        val total = inRange.sumOf { it.steps }
        stepsBig = "%,d".format(total)
        stepsSub = "steps in $requested days" + if (inRange.size < requested) " · ${inRange.size} with data" else ""
        hrValues = inRange.mapNotNull { it.restingHeartRate?.takeIf { value -> value in 1..250 }?.toFloat() }
        hrLabels = inRange.mapNotNull { day ->
            day.restingHeartRate?.let {
                runCatching { LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) }
                    .getOrDefault(day.date)
            }
        }
        hrTitle = "Resting heart rate"
        hrBig = todayDay?.restingHeartRate?.takeIf { it in 1..250 }?.let { "$it bpm today" } ?: "— bpm today"
        hrNote = deltaNote(inRange, "since the first day")
        caption = "Daily average, last 7 days"
    } else {
        val weeks = inRange
            .groupBy { day ->
                runCatching { LocalDate.parse(day.date).with(DayOfWeek.MONDAY) }.getOrNull()
            }
            .toSortedMap(compareBy { it?.toString() ?: "" })
        bars = weeks.values.map { entries -> entries.map { it.steps }.average().toFloat() }
        barLabels = weeks.keys.map { it?.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) ?: "—" }
        stepsBig = "%,d".format(avgSteps.roundToInt())
        stepsSub = "avg steps per day" + if (inRange.size < requested) " · ${inRange.size} days with data" else ""
        hrValues = weeks.values.mapNotNull { entries ->
            entries.mapNotNull { it.restingHeartRate?.takeIf { value -> value in 1..250 } }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
        }
        hrLabels = weeks.keys.map { it?.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) ?: "" }
        hrTitle = "Resting heart rate"
        hrBig = todayDay?.restingHeartRate?.takeIf { it in 1..250 }?.let { "$it bpm" } ?: "— bpm"
        hrNote = deltaNote(inRange, "over the period")
        caption = "Daily average, last 4 weeks"
    }

    val insight = if (inRange.isEmpty()) {
        "No wellness data in this range. Sync your watch to build a trend."
    } else {
        "You averaged ${"%,d".format(avgSteps.roundToInt())} steps a day across ${inRange.size} ${if (inRange.size == 1) "day" else "days"} with data, and reached your " +
            "${"%,d".format(goal)} goal on $goalDays of them. " +
            (avgResting?.let { "Average resting heart rate was ${formatOneDecimal(it)} bpm." }
                ?: "Resting heart rate is not available in this range.")
    }

    return TrendView(
        caption = caption,
        stepsAvg = "%,d".format(avgSteps.roundToInt()),
        restingAvg = avgResting?.let { formatOneDecimal(it) } ?: "—",
        activeAvg = avgActive.roundToInt().toString(),
        burnAvg = "%,d".format(avgBurn.roundToInt()),
        stepsBig = stepsBig,
        stepsSub = stepsSub,
        bars = bars,
        barLabels = barLabels,
        daysWithData = inRange.size,
        daysRequested = requested,
        stepsContentDescription = "Steps trend: ${bars.joinToString { it.roundToInt().toString() }} across ${bars.size} buckets",
        hrTitle = hrTitle,
        hrBig = hrBig,
        hrNote = hrNote,
        hrValues = hrValues,
        hrLabels = if (hrValues.size >= 2) hrLabels else emptyList(),
        hrContentDescription = if (hrValues.isEmpty()) {
            "No resting heart-rate data in this range"
        } else {
            "Resting heart rate trend over ${hrValues.size} days"
        },
        insight = insight,
    )
}

@Composable
private fun SummaryTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    CompactCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = PulseSpacing.mdPlus, vertical = PulseSpacing.md)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, maxLines = 1)
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = PulseColors.Text,
                maxLines = 1,
            )
            Text(unit, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, maxLines = 1)
        }
    }
}

private fun deltaNote(days: List<WellnessDayEntity>, suffix: String): String {
    val values = days.mapNotNull { it.restingHeartRate?.takeIf { value -> value in 1..250 } }
    if (values.size < 2) return "Not enough resting HR data"
    val delta = values.last() - values.first()
    return when {
        delta < 0 -> "Down ${-delta} bpm $suffix"
        delta > 0 -> "Up $delta bpm $suffix"
        else -> "Stable $suffix"
    }
}

private fun formatShortDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
}.getOrDefault(date)

private fun formatOneDecimal(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.1f".format(value)
