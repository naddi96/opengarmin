package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import dev.opengarmin.app.util.formatHoursMinutes
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.util.formatWellnessDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
internal fun StressDetailScreen(
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
    val stress = samples.filter { it.kind == "stress" && it.value in 0.0..100.0 }.sortedBy { it.timestamp }
    val dayActivities = activities.filter { it.date() == anchor }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        HealthDetailHeader(
            metric = HealthMetric.STRESS,
            subtitle = stressSubtitle(anchor, window),
            onBack = onBack,
        )
        RangeSelector(
            options = HealthWindow.entries.map { it.label },
            selectedIndex = window.ordinal,
            onSelected = {
                windowName = HealthWindow.entries[it].name
                anchorEpoch = latestDate.toEpochDay()
            },
        )
        DateNavigator(
            label = stressNavigatorLabel(anchor, latestDate, window),
            canGoBack = HealthMetrics.rangeStart(anchor, window.days) > earliest,
            canGoForward = anchor < latestDate,
            onBack = { anchorEpoch = shift(anchor, earliest, latestDate, -window.days).toEpochDay() },
            onForward = { anchorEpoch = shift(anchor, earliest, latestDate, window.days).toEpochDay() },
        )

        if (day == null) {
            EmptyMetricCard("No stress reading for this date.")
        } else if (window == HealthWindow.DAY) {
            StressDay(
                day = day,
                days = days,
                stress = stress,
                activities = dayActivities,
                hasTimeline = anchor == latestDate && stress.size >= 2,
            )
        } else {
            StressHistory(
                days = days,
                window = window,
                anchor = anchor,
            )
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun StressDay(
    day: WellnessDayEntity,
    days: List<WellnessDayEntity>,
    stress: List<WellnessSampleEntity>,
    activities: List<ActivityEntity>,
    hasTimeline: Boolean,
) {
    val breakdown = HealthMetrics.stressBreakdown(stress)
    val averageValue = day.stressAvg
        ?: stress.map { it.value }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xl),
            ) {
                BreakdownRing(breakdown, averageValue)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    StressLevel.entries.forEach { level ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(stressColor(level)))
                            Spacer(Modifier.width(PulseSpacing.sm))
                            Text(level.label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, modifier = Modifier.weight(1f))
                            Text(
                                breakdown?.let { formatHoursMinutes(it.secondsFor(level)) } ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = PulseColors.Text,
                            )
                            Text(
                                breakdown?.let { " ${it.percentFor(level)}%" } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = PulseColors.TextMuted,
                                modifier = Modifier.width(34.dp),
                            )
                        }
                    }
                }
            }
            InsightLine(
                text = buildAnnotatedString {
                    if (breakdown == null) {
                        append(tr("The watch only stored an average for this day; its detailed stress timeline is not available."))
                        return@buildAnnotatedString
                    }
                    val rest = breakdown.percentFor(StressLevel.REST) + breakdown.percentFor(StressLevel.LOW)
                    append(tr("Rest or low stress covered "))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$rest%") }
                    append(tr(" of the day. "))
                    val peak = stress.maxByOrNull { it.value }
                    val activity = peak?.let { point -> activities.firstOrNull { point.timestamp in it.startTime..(it.startTime + it.durationSeconds.roundToInt()) } }
                    if (activity != null) {
                        append(tr("The highest reading came during your "))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(tr(sportLabel(activity.sport)).lowercase())
                        }
                        append(tr("."))
                    } else if (peak != null) {
                        append(
                            tr(
                                "The highest reading was %s at %s.",
                                peak.value.roundToInt(),
                                sampleTime(peak.timestamp),
                            ),
                        )
                    }
                },
            )
        }
    }

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            CardTitle("Daily timeline")
            if (hasTimeline) {
                StressDayTimeline(stress = stress, markers = stressMarkers(day, activities))
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    StressLevel.entries.forEach { level ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(stressColor(level)))
                            Spacer(Modifier.width(4.dp))
                            Text(level.label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        }
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

    if (hasTimeline) {
        val seven = HealthMetrics.stressDays(days, HealthMetrics.parseDate(day.date) ?: LocalDate.now(), 7)
        MetricCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("7-day average", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    Text(
                        averageValue?.toString() ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PulseColors.Text,
                    )
                }
                Sparkline(
                    values = seven.map { it.value.toFloat() },
                    color = PulseColors.Yellow,
                    bars = true,
                    height = 34.dp,
                )
            }
        }
    }
}

@Composable
private fun BreakdownRing(breakdown: dev.opengarmin.app.data.StressBreakdown?, average: Int?) {
    if (breakdown != null && average != null) {
        StressRing(breakdown = breakdown, average = average)
    } else {
        Box(
            Modifier
                .size(124.dp)
                .clip(CircleShape)
                .background(PulseColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                average?.toString() ?: "—",
                style = MaterialTheme.typography.displaySmall,
                color = PulseColors.Text,
            )
        }
    }
}

@Composable
private fun StressHistory(
    days: List<WellnessDayEntity>,
    window: HealthWindow,
    anchor: LocalDate,
) {
    val points = if (window == HealthWindow.YEAR) {
        HealthMetrics.monthlyStressBuckets(HealthMetrics.stressDays(days, anchor, window.days))
    } else {
        HealthMetrics.stressDays(days, anchor, window.days)
    }
    if (points.isEmpty()) {
        EmptyMetricCard("No stress history in this range.")
        return
    }
    val average = points.map { it.value }.average().roundToInt()
    val restDays = points.count { it.value <= 25 }
    val highDays = points.count { it.value > 75 }
    val latest7 = HealthMetrics.stressDays(days, anchor, 7).map { it.value }
    val recent = latest7.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
            CardTitle("Average stress", if (window == HealthWindow.YEAR) "Monthly" else "Daily")
            StressAverageBars(
                values = points.map { it.value.toFloat() },
                labels = listOf(dayLabel(points.first().date), dayLabel(points.last().date)),
            )
            ValueGrid(
                listOf(
                    "Range average" to average.toString(),
                    "Rest days" to "$restDays",
                    "High days" to "$highDays",
                ),
                columns = 3,
            )
            if (recent != null) {
                InsightLine(
                    text = buildAnnotatedString {
                        append(tr("The last 7 days average "))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$recent") }
                        append(
                            tr(
                                " against %s for the whole range.",
                                average,
                            ),
                        )
                    },
                )
            }
        }
    }

    SectionHeader("Recent days")
    points.sortedByDescending { it.date }.take(if (window == HealthWindow.WEEK) 7 else 10).forEach { point ->
        MetricDayRow(
            name = if (window == HealthWindow.YEAR) {
                point.date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM", java.util.Locale.getDefault()))
            } else {
                HealthMetrics.formatDayName(point.date)
            },
            sub = if (window == HealthWindow.YEAR) point.date.year.toString() else HealthMetrics.formatDayDate(point.date),
            left = point.value.roundToInt().toString(),
            leftLabel = StressLevel.of(point.value).label,
            leftColor = stressColor(StressLevel.of(point.value)),
        )
    }
}

private fun stressMarkers(day: WellnessDayEntity, activities: List<ActivityEntity>): List<TimelineMarker> {
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

@Composable
private fun stressSubtitle(anchor: LocalDate, window: HealthWindow): String {
    if (window == HealthWindow.DAY) return formatWellnessDate(anchor.toString())
    val label = when (window) {
        HealthWindow.WEEK -> tr("7 days")
        HealthWindow.FOUR_WEEKS -> tr("4 weeks")
        else -> tr("12 months")
    }
    return "$label · ${HealthMetrics.formatRange(HealthMetrics.rangeStart(anchor, window.days), anchor)}"
}

@Composable
private fun stressNavigatorLabel(anchor: LocalDate, latestDate: LocalDate, window: HealthWindow): String {
    if (window == HealthWindow.DAY) {
        return if (anchor == latestDate) tr("Today") else HealthMetrics.formatRange(anchor, anchor)
    }
    return HealthMetrics.formatRange(HealthMetrics.rangeStart(anchor, window.days), anchor)
}

private fun shift(anchor: LocalDate, earliest: LocalDate, latest: LocalDate, days: Int): LocalDate =
    anchor.plusDays(days.toLong()).coerceIn(earliest, latest)

private fun ActivityEntity.date(): LocalDate =
    Instant.ofEpochSecond(startTime).atZone(ZoneId.systemDefault()).toLocalDate()
