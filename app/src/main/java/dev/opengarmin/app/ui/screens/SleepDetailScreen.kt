package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.HealthWindow
import dev.opengarmin.app.data.SleepNight
import dev.opengarmin.app.data.SleepStageEntity
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import dev.opengarmin.app.util.formatHoursMinutes
import dev.opengarmin.app.util.formatWellnessDate
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private val SleepOverlays = listOf(
    "hr" to "Heart rate",
    "body_battery" to "Body Battery",
    "spo2" to "Pulse Ox",
    "respiration" to "Respiration",
    "hrv" to "HRV",
)

@Composable
internal fun SleepDetailScreen(
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    sleepStages: List<SleepStageEntity>,
    latestDate: LocalDate,
    birthYear: Int?,
    onBack: () -> Unit,
    onOpenMetric: (HealthMetric) -> Unit,
) {
    var windowName by rememberSaveable { mutableStateOf(HealthWindow.DAY.name) }
    var anchorEpoch by rememberSaveable { mutableStateOf(latestDate.toEpochDay()) }
    val window = HealthWindow.entries.firstOrNull { it.name == windowName } ?: HealthWindow.DAY
    val anchor = LocalDate.ofEpochDay(anchorEpoch).coerceAtMost(latestDate)
    val earliest = days.minOfOrNull { it.date }?.let { HealthMetrics.parseDate(it) } ?: latestDate
    val day = days.firstOrNull { it.date == anchor.toString() }
    val needHours = HealthMetrics.sleepNeedHours(birthYear)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        HealthDetailHeader(
            metric = HealthMetric.SLEEP,
            subtitle = sleepSubtitle(day, anchor, latestDate, window),
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
            label = sleepNavigatorLabel(anchor, latestDate, window),
            canGoBack = HealthMetrics.rangeStart(anchor, window.days) > earliest,
            canGoForward = anchor < latestDate,
            onBack = { anchorEpoch = shift(anchor, earliest, latestDate, -window.days).toEpochDay() },
            onForward = { anchorEpoch = shift(anchor, earliest, latestDate, window.days).toEpochDay() },
        )

        if (day == null || (day.sleepScore == null && day.sleepStart == null)) {
            EmptyMetricCard("Sleep has not synced for this date. Sync after waking with the watch nearby.")
        } else if (window == HealthWindow.DAY) {
            DaySleep(day = day, days = days, samples = samples, sleepStages = sleepStages, needHours = needHours, onOpenMetric = onOpenMetric)
        } else {
            SleepHistory(
                nights = HealthMetrics.sleepNights(days, anchor, window.days),
                window = window,
            )
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaySleep(
    day: WellnessDayEntity,
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    sleepStages: List<SleepStageEntity>,
    needHours: Double,
    onOpenMetric: (HealthMetric) -> Unit,
) {
    val asleep = dev.opengarmin.app.data.WellnessMetrics.sleepSeconds(day)
    val sleepStart = day.sleepStart
    val sleepEnd = day.sleepEnd
    val inBed = if (sleepStart != null && sleepEnd != null) {
        (sleepEnd - sleepStart).toInt().coerceAtLeast(0)
    } else {
        null
    }
    val needSeconds = (needHours * 3600).roundToInt()
    val previousDay = days.filter { it.date < day.date }.maxByOrNull { it.date }
    val overnight = if (previousDay?.bodyBatteryLatest != null && day.bodyBatteryStart != null) {
        day.bodyBatteryStart - previousDay.bodyBatteryLatest
    } else {
        null
    }
    val score = day.sleepScore
    val scoreColor = sleepScoreColor(day.sleepQuality)

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
            ) {
                ScoreGauge(
                    progress = (score ?: 0) / 100f,
                    value = score?.toString() ?: "—",
                    unit = "",
                    caption = day.sleepQuality ?: "No score",
                    color = scoreColor,
                    size = 118.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs)) {
                    Text(
                        asleep?.let { formatHoursMinutes(it.toLong()) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PulseColors.Text,
                    )
                    Text(
                        buildString {
                            append("asleep")
                            if (inBed != null) append(" · ${formatHoursMinutes(inBed.toLong())} in bed")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                    val ratio = if (asleep != null && needSeconds > 0) asleep.toFloat() / needSeconds else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(PulseColors.RingTrack),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                .height(7.dp)
                                .background(scoreColor),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Need ${"%.0f".format(needHours)}h", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        val short = if (asleep != null) (needSeconds - asleep).coerceAtLeast(0) else null
                        Text(
                            if (short == null) "—" else if (short == 0) "Need met" else "${formatHoursMinutes(short.toLong())} short",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (short == 0) PulseColors.Green else PulseColors.Orange,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    var overlayName by rememberSaveable { mutableStateOf(SleepOverlays.first().first) }
    val overlayKind = overlayName
    val overlayLabel = SleepOverlays.firstOrNull { it.first == overlayKind }?.second ?: "Heart rate"
    val overlayPoints = samples.filter { it.kind == overlayKind && it.value > 0.0 }
        .filter { if (sleepStart != null && sleepEnd != null) it.timestamp in sleepStart..sleepEnd else true }
    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            CardTitle("Sleep stages")
            SleepStagesOverlayChart(
                stages = sleepStages,
                overlay = overlayPoints,
                overlayLabel = overlayLabel,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                SleepOverlays.forEach { (kind, label) ->
                    PillChip(
                        label = label,
                        selected = kind == overlayKind,
                        onClick = { overlayName = kind },
                    )
                }
            }
            StageBreakdown(day)
        }
    }

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            ValueGrid(
                listOf(
                    "Lowest night HR" to (day.sleepLowestHeartRate?.let { "$it bpm" } ?: "—"),
                    "Resting HR" to (day.restingHeartRate?.let { "$it bpm" } ?: "—"),
                    "Body Battery" to (overnight?.let { if (it >= 0) "+$it" else "−${-it}" } ?: day.bodyBatteryStart?.toString() ?: "—"),
                    "Avg SpO₂" to (day.sleepAvgSpo2?.let { "$it%" } ?: "—"),
                    "Respiration" to (day.sleepAvgRespiration?.let { "%.0f brpm".format(it) } ?: "—"),
                    "Overnight HRV" to (day.hrvLastNight?.let { "%.0f ms".format(it) } ?: "—"),
                ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMetric(HealthMetric.HRV) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Open HRV status",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.Accent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StageBreakdown(day: WellnessDayEntity) {
    val rows = listOf(
        Triple("Deep", day.deepSleepSeconds, SleepDeep),
        Triple("Light", day.lightSleepSeconds, SleepLight),
        Triple("REM", day.remSleepSeconds, SleepRem),
        Triple("Awake", day.awakeSleepSeconds, SleepAwake),
    )
    val total = rows.sumOf { (it.second ?: 0).toLong() }.coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PulseSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        rows.forEach { (name, seconds, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(4.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                }
                Text(
                    seconds?.let { formatHoursMinutes(it.toLong()) } ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = PulseColors.Text,
                )
                Text(
                    seconds?.let { "${(it * 100.0 / total).roundToInt()}%" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SleepHistory(
    nights: List<SleepNight>,
    window: HealthWindow,
) {
    if (nights.isEmpty()) {
        EmptyMetricCard("No sleep history in this range.")
        return
    }
    val yearly = window == HealthWindow.YEAR
    val buckets = if (yearly) HealthMetrics.monthlySleepBuckets(nights) else null
    val trend = if (yearly) {
        buckets.orEmpty().map { bucket ->
            TrendBucket(
                label = bucket.start.format(java.time.format.DateTimeFormatter.ofPattern("MMM")),
                score = bucket.score?.toFloat(),
                secondary = bucket.lowestHr?.toFloat(),
            )
        }
    } else {
        nights.map { night ->
            TrendBucket(
                label = dayLabel(night.date),
                score = night.score?.toFloat(),
                secondary = (night.lowestHr ?: night.restingHr)?.toFloat(),
            )
        }
    }

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
            CardTitle("Score & night heart rate")
            Legend(
                listOf(
                    Triple("Score", PulseColors.Violet, false),
                    Triple("Night HR", PulseColors.Teal, true),
                ),
            )
            DualTrendChart(trend, PulseColors.Violet, PulseColors.Teal)
        }
    }

    MetricCard {
        ValueGrid(
            listOf(
                "Avg score" to (HealthMetrics.averageScore(nights)?.toString() ?: "—"),
                "Night HR" to (
                    HealthMetrics.average(nights.mapNotNull { (it.lowestHr ?: it.restingHr)?.toDouble() })
                        ?.roundToInt()?.let { "$it bpm" } ?: "—"
                    ),
                "Resting HR" to (HealthMetrics.averageRestingHeartRate(nights)?.let { "$it bpm" } ?: "—"),
                "Avg SpO₂" to (HealthMetrics.average(nights.mapNotNull { it.spo2?.toDouble() })?.roundToInt()?.let { "$it%" } ?: "—"),
                "Respiration" to (HealthMetrics.average(nights.mapNotNull { it.respiration })?.roundToInt()?.let { "$it brpm" } ?: "—"),
                "Avg HRV" to (HealthMetrics.average(nights.mapNotNull { it.hrvMs })?.roundToInt()?.let { "$it ms" } ?: "—"),
            ),
        )
    }

    val bedWake = if (yearly) {
        buckets.orEmpty().map { Triple(it.bedMinutes?.roundToInt(), it.wakeMinutes?.roundToInt(), dayLabel(it.start)) }
    } else {
        nights.map { Triple(it.bedMinutes, it.wakeMinutes, dayLabel(it.date)) }
    }
    val avgBed = HealthMetrics.averageBedtime(nights)
    val avgWake = HealthMetrics.averageWakeTime(nights)
    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
            CardTitle(
                "Bedtime & wake-up",
                aux = if (avgBed != null && avgWake != null) {
                    "avg ${HealthMetrics.labelFromNoonMinutes(avgBed)} → ${HealthMetrics.labelFromNoonMinutes(avgWake)}"
                } else {
                    null
                },
            )
            BedWakeRangeChart(
                nights = bedWake,
                averageBed = avgBed,
                averageWake = avgWake,
            )
            val bedDeviation = HealthMetrics.average(nights.mapNotNull { it.bedMinutes?.toDouble() })?.let { average ->
                HealthMetrics.average(nights.mapNotNull { it.bedMinutes?.toDouble() }.map { abs(it - average) })
            }
            val wakeDeviation = HealthMetrics.average(nights.mapNotNull { it.wakeMinutes?.toDouble() })?.let { average ->
                HealthMetrics.average(nights.mapNotNull { it.wakeMinutes?.toDouble() }.map { abs(it - average) })
            }
            InsightLine(
                text = buildAnnotatedString {
                    if (bedDeviation != null && wakeDeviation != null && wakeDeviation < bedDeviation) {
                        append("Wake-up is steady; ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bedtime is what moves") }
                        append(" from night to night.")
                    } else if (avgWake != null) {
                        append("Your average wake-up in this range is ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(HealthMetrics.labelFromNoonMinutes(avgWake))
                        }
                        append(". Keep bedtime consistent to protect it.")
                    } else {
                        append("Bedtime was not recorded for enough nights to compare.")
                    }
                },
            )
        }
    }
}

private fun sleepSubtitle(
    day: WellnessDayEntity?,
    anchor: LocalDate,
    latestDate: LocalDate,
    window: HealthWindow,
): String {
    if (window == HealthWindow.DAY) {
        val date = formatWellnessDate(anchor.toString())
        val sleepStart = day?.sleepStart
        val sleepEnd = day?.sleepEnd
        val windowText = if (sleepStart != null && sleepEnd != null) {
            " · ${sampleTime(sleepStart)} – ${sampleTime(sleepEnd)}"
        } else {
            ""
        }
        return "$date$windowText"
    }
    val start = HealthMetrics.rangeStart(anchor, window.days)
    return "${if (window == HealthWindow.WEEK) "7 days" else if (window == HealthWindow.YEAR) "12 months" else "4 weeks"} · ${HealthMetrics.formatRange(start, anchor)}"
}

private fun sleepNavigatorLabel(anchor: LocalDate, latestDate: LocalDate, window: HealthWindow): String {
    if (window == HealthWindow.DAY) {
        return if (anchor == latestDate) "Today" else HealthMetrics.formatRange(anchor, anchor)
    }
    return HealthMetrics.formatRange(HealthMetrics.rangeStart(anchor, window.days), anchor)
}

private fun shift(anchor: LocalDate, earliest: LocalDate, latest: LocalDate, days: Int): LocalDate =
    anchor.plusDays(days.toLong()).coerceIn(earliest, latest)
