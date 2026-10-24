package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.sportLabel
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.HealthWindow
import dev.opengarmin.app.data.StressLevel
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import dev.opengarmin.app.util.formatElapsedTime
import dev.opengarmin.app.util.formatHoursMinutes
import dev.opengarmin.app.util.formatWellnessDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
internal fun BodyBatteryDetailScreen(
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    activities: List<ActivityEntity>,
    latestDate: LocalDate,
    onBack: () -> Unit,
) {
    var windowName by rememberSaveable { mutableStateOf(HealthWindow.DAY.name) }
    var anchorEpoch by rememberSaveable { mutableStateOf(latestDate.toEpochDay()) }
    val window = HealthWindow.entries.firstOrNull { it.name == windowName } ?: HealthWindow.DAY
    val anchor = LocalDate.ofEpochDay(anchorEpoch).coerceAtMost(latestDate)
    val earliest = days.minOfOrNull { it.date }?.let { HealthMetrics.parseDate(it) } ?: latestDate
    val day = days.firstOrNull { it.date == anchor.toString() }
    val battery = samples.filter { it.kind == "body_battery" && it.value in 1.0..100.0 }.sortedBy { it.timestamp }
    val stress = samples.filter { it.kind == "stress" && it.value in 0.0..100.0 }.sortedBy { it.timestamp }
    val dayActivities = activities.filter { it.date() == anchor }
    val latestDayEntity = days.firstOrNull { it.date == latestDate.toString() }
    val previousDay = days.filter { it.date < anchor.toString() }.maxByOrNull { it.date }
    val overnight = if (previousDay?.bodyBatteryLatest != null && day?.bodyBatteryStart != null) {
        day.bodyBatteryStart - previousDay.bodyBatteryLatest
    } else {
        null
    }
    val hasTimeline = anchor == latestDate && battery.size >= 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        HealthDetailHeader(
            metric = HealthMetric.BODY_BATTERY,
            subtitle = subtitleFor(
                anchor = anchor,
                latestDate = latestDate,
                window = window,
                updatedAt = if (hasTimeline) battery.lastOrNull()?.timestamp else null,
            ),
            onBack = onBack,
        )
        RangeSelector(
            options = listOf(HealthWindow.DAY, HealthWindow.WEEK, HealthWindow.FOUR_WEEKS).map { it.label },
            selectedIndex = window.ordinal,
            onSelected = {
                windowName = HealthWindow.entries[it].name
                anchorEpoch = latestDate.toEpochDay()
            },
        )
        DateNavigator(
            label = navigatorLabel(anchor, latestDate, window),
            canGoBack = HealthMetrics.rangeStart(anchor, window.days) > earliest,
            canGoForward = anchor < latestDate,
            onBack = { anchorEpoch = shiftAnchor(anchor, earliest, latestDate, -window.days).toEpochDay() },
            onForward = { anchorEpoch = shiftAnchor(anchor, earliest, latestDate, window.days).toEpochDay() },
        )

        if (day == null) {
            EmptyMetricCard("No Body Battery reading for this day.")
        } else if (window == HealthWindow.DAY) {
            val current = day.bodyBatteryLatest ?: battery.lastOrNull()?.value?.roundToInt()
            val deltas = bodyBatteryDeltas(battery.map { it.value })
            MetricCard {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
                    ) {
                        ScoreGauge(
                            progress = (current ?: 0) / 100f,
                            value = current?.toString() ?: "—",
                            unit = "",
                            caption = "of 100 · ${bodyBatteryLabel(current)}",
                            color = PulseColors.Teal,
                            size = 140.dp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
                            BatteryTotal("Charged", deltas.second?.let { "+$it" } ?: "—", PulseColors.Teal)
                            BatteryTotal("Drained", deltas.first?.let { "−$it" } ?: "—", PulseColors.Coral)
                        }
                    }
                    InsightLine(
                        text = bodyBatteryInsight(
                            current = current,
                            activities = dayActivities,
                            stress = stress,
                            hasTimeline = hasTimeline,
                        ),
                    )
                }
            }

            MetricCard {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    CardTitle(
                        title = "Daily timeline",
                        aux = HealthMetrics.peakBodyBattery(battery)?.let {
                            "Peak ${it.value.roundToInt()} at ${sampleTime(it.timestamp)}"
                        },
                    )
                    if (hasTimeline) {
                        var overlayName by rememberSaveable { mutableStateOf(TimelineOverlay.BODY_BATTERY.name) }
                        val overlay = TimelineOverlay.entries.firstOrNull { it.name == overlayName } ?: TimelineOverlay.BODY_BATTERY
                        BodyBatteryTimeline(
                            battery = battery,
                            stress = stress,
                            markers = timelineMarkers(day, dayActivities),
                            overlay = overlay,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                            TimelineOverlay.entries.forEach { option ->
                                PillChip(
                                    label = option.label,
                                    selected = option == overlay,
                                    onClick = { overlayName = option.name },
                                )
                            }
                        }
                    } else {
                        Text(
                            "The detailed timeline is only stored for the most recent day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                }
            }

            val factors = bodyBatteryFactors(day, overnight, dayActivities, samples, stress, hasTimeline, latestDayEntity)
            if (factors.isNotEmpty()) {
                SectionHeader("What moved it")
                factors.forEach { factor ->
                    MetricDayRow(
                        name = factor.name,
                        sub = factor.sub,
                        left = factor.amount,
                        leftLabel = factor.kind,
                        leftColor = factor.color,
                    )
                }
            }
        } else {
            val history = HealthMetrics.bodyBatteryDays(days, anchor, window.days)
            if (history.isEmpty()) {
                EmptyMetricCard("No Body Battery history in this range.")
            } else {
                MetricCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        CardTitle("Daily high & low")
                        Legend(
                            listOf(
                                Triple("Daily high", PulseColors.Teal, false),
                                Triple("Daily low", PulseColors.Text, false),
                            ),
                        )
                        HighLowRangeChart(
                            days = history.map { it.high to it.low },
                            labels = listOf(dayLabel(history.first().date), dayLabel(history.last().date)),
                        )
                        val avgHigh = HealthMetrics.average(history.mapNotNull { it.high?.toDouble() })
                        val avgLow = HealthMetrics.average(history.mapNotNull { it.low?.toDouble() })
                        val best = history.filter { it.high != null }.maxByOrNull { it.high ?: 0 }
                        ValueGrid(
                            listOf(
                                "Avg high" to (avgHigh?.roundToInt()?.toString() ?: "—"),
                                "Avg low" to (avgLow?.roundToInt()?.toString() ?: "—"),
                                "Best day" to (best?.high?.let { "$it ${dayLabel(best.date)}" } ?: "—"),
                            ),
                            columns = 3,
                        )
                        val recharged = history.count { (it.high ?: 0) >= 80 }
                        InsightLine(
                            text = buildAnnotatedString {
                                append("You reached ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("80+") }
                                append(" on $recharged of ${history.size} recorded days.")
                            },
                        )
                    }
                }

                SectionHeader("Recent days")
                history.sortedByDescending { it.date }.take(if (window == HealthWindow.WEEK) 7 else 10).forEach { entry ->
                    val entity = days.firstOrNull { it.date == entry.date.toString() }
                    val current = entry.latest ?: entity?.bodyBatteryLatest
                    MetricDayRow(
                        name = HealthMetrics.formatDayName(entry.date),
                        sub = HealthMetrics.formatDayDate(entry.date),
                        left = entry.high?.toString() ?: current?.toString() ?: "—",
                        leftLabel = "High",
                        right = entry.low?.toString() ?: "—",
                        rightLabel = "Low",
                        leftColor = PulseColors.Teal,
                        onClick = {
                            windowName = HealthWindow.DAY.name
                            anchorEpoch = entry.date.toEpochDay()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

private data class BatteryFactor(val name: String, val sub: String, val amount: String, val kind: String, val color: Color)

private fun bodyBatteryFactors(
    day: WellnessDayEntity,
    overnight: Int?,
    activities: List<ActivityEntity>,
    samples: List<WellnessSampleEntity>,
    stress: List<WellnessSampleEntity>,
    hasTimeline: Boolean,
    latestDay: WellnessDayEntity?,
): List<BatteryFactor> {
    val rows = ArrayList<BatteryFactor>()
    activities.forEach { activity ->
        val end = (activity.startTime + activity.durationSeconds.roundToInt()).toLong()
        val delta = if (hasTimeline) HealthMetrics.bodyBatteryDelta(samples, activity.startTime, end) else null
        rows.add(
            BatteryFactor(
                name = sportLabel(activity.sport),
                sub = formatElapsedTime(activity.durationSeconds),
                amount = when {
                    delta == null -> "—"
                    delta >= 0 -> "+$delta"
                    else -> "−${-delta}"
                },
                kind = if (delta != null) "Body Battery" else "Activity",
                color = when {
                    delta == null -> PulseColors.TextMuted
                    delta >= 0 -> PulseColors.Teal
                    else -> PulseColors.Coral
                },
            ),
        )
    }
    if (overnight != null) {
        rows.add(
            BatteryFactor(
                name = "Sleep",
                sub = HealthMetrics.sleepNights(listOf(day), HealthMetrics.parseDate(day.date) ?: LocalDate.now(), 1)
                    .firstOrNull()?.asleepSeconds?.let { formatHoursMinutes(it.toLong()) } ?: "Overnight recharge",
                amount = if (overnight >= 0) "+$overnight" else "−${-overnight}",
                kind = "Body Battery",
                color = if (overnight >= 0) PulseColors.Teal else PulseColors.Coral,
            ),
        )
    }
    if (hasTimeline) {
        val rest = HealthMetrics.longestRestSeconds(stress)
        if (rest > 0) {
            rows.add(
                BatteryFactor(
                    name = "Low stress",
                    sub = formatHoursMinutes(rest),
                    amount = "",
                    kind = "",
                    color = PulseColors.TextMuted,
                ),
            )
        }
    }
    if (rows.isEmpty() && latestDay?.bodyBatteryMax != null) {
        rows.add(
            BatteryFactor(
                name = "Peak",
                sub = "Highest reading",
                amount = latestDay.bodyBatteryMax.toString(),
                kind = "Body Battery",
                color = PulseColors.Teal,
            ),
        )
    }
    return rows
}

@Composable
private fun BatteryTotal(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

private fun bodyBatteryInsight(
    current: Int?,
    activities: List<ActivityEntity>,
    stress: List<WellnessSampleEntity>,
    hasTimeline: Boolean,
): AnnotatedString = buildAnnotatedString {
    when {
        activities.isNotEmpty() -> {
            append("Training day. ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(sportLabel(activities.first().sport)) }
            append(" plus rest is good for body and mind. Now wind down and aim for a good night's sleep.")
        }
        current != null && current >= 60 -> {
            append("Energy is in a good place at ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$current") }
            append(". Keep the rest of the day easy if you want to stay here.")
        }
        current != null -> {
            append("Energy is low at ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$current") }
            append(". Rest and an early night will recharge it.")
        }
        else -> append("No Body Battery reading for this day.")
    }
    if (hasTimeline && stress.isNotEmpty()) {
        val breakdown = HealthMetrics.stressBreakdown(stress)
        if (breakdown != null) {
            append(" Stress was rest or low for ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("${breakdown.percentFor(StressLevel.REST) + breakdown.percentFor(StressLevel.LOW)}%")
            }
            append(" of the day.")
        }
    }
}

private fun timelineMarkers(day: WellnessDayEntity, activities: List<ActivityEntity>): List<TimelineMarker> {
    val markers = ArrayList<TimelineMarker>()
    day.sleepStart?.let { markers.add(TimelineMarker(it, null, "Sleep", PulseColors.Blue)) }
    day.sleepEnd?.let { markers.add(TimelineMarker(it, null, "Wake", PulseColors.Blue)) }
    activities.forEach { activity ->
        markers.add(
            TimelineMarker(
                start = activity.startTime,
                end = (activity.startTime + activity.durationSeconds.roundToInt()).toLong(),
                label = sportLabel(activity.sport),
                color = PulseColors.sportColor(activity.sport, activity.subSport),
            ),
        )
    }
    return markers
}

private fun subtitleFor(
    anchor: LocalDate,
    latestDate: LocalDate,
    window: HealthWindow,
    updatedAt: Long?,
): String {
    if (window == HealthWindow.DAY) {
        val date = formatWellnessDate(anchor.toString())
        val updated = if (anchor == latestDate && updatedAt != null) " · updated ${sampleTime(updatedAt)}" else ""
        return "$date$updated"
    }
    val start = HealthMetrics.rangeStart(anchor, window.days)
    return "${if (window == HealthWindow.WEEK) "7 days" else "4 weeks"} · ${HealthMetrics.formatRange(start, anchor)}"
}

private fun navigatorLabel(anchor: LocalDate, latestDate: LocalDate, window: HealthWindow): String {
    if (window == HealthWindow.DAY) {
        return if (anchor == latestDate) "Today" else HealthMetrics.formatRange(anchor, anchor)
    }
    return HealthMetrics.formatRange(HealthMetrics.rangeStart(anchor, window.days), anchor)
}

private fun shiftAnchor(anchor: LocalDate, earliest: LocalDate, latest: LocalDate, days: Int): LocalDate =
    anchor.plusDays(days.toLong()).coerceIn(earliest, latest)

private fun bodyBatteryLabel(value: Int?): String = when {
    value == null -> "No reading"
    value >= 76 -> "High"
    value >= 51 -> "Medium"
    value >= 26 -> "Low"
    else -> "Very low"
}

private fun bodyBatteryDeltas(values: List<Double>): Pair<Int?, Int?> {
    if (values.size < 2) return null to null
    var drained = 0
    var recharged = 0
    for (index in 1 until values.size) {
        val diff = (values[index] - values[index - 1]).roundToInt()
        if (diff < 0) drained += -diff else recharged += diff
    }
    return drained to recharged
}

private fun ActivityEntity.date(): LocalDate =
    Instant.ofEpochSecond(startTime).atZone(ZoneId.systemDefault()).toLocalDate()
