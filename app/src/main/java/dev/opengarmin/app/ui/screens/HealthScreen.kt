package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.SleepStageEntity
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessMetrics
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.formatWellnessDate
import dev.opengarmin.app.ui.components.BarChart
import dev.opengarmin.app.ui.components.CoachInsightCard
import dev.opengarmin.app.ui.components.LineChart
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.ProgressRing
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.StatTile
import dev.opengarmin.app.ui.components.TinyStat
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val SleepDeep = Color(0xFF5662E8)
private val SleepLight = Color(0xFF9DB4FF)
private val SleepRem = Color(0xFFD79BFF)
private val SleepAwake = Color(0xFFFFB45E)

@Composable
fun HealthScreen(
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    sleepStages: List<SleepStageEntity>,
    training: TrainingSummary,
    onOpenTrends: () -> Unit,
    onOpenTraining: () -> Unit,
    onAskCoach: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = WellnessMetrics.latestDay(days)
    val dateLabel = day?.let { formatWellnessDate(it.date) }
        ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
    val hrvValue = WellnessMetrics.hrvMs(day, samples)
    val hrvWeekly = WellnessMetrics.hrvWeeklyAverage(days, samples, sampleDate = day?.date)
    val hrvSamples = samples.filter { it.kind == "hrv" && it.value > 0.0 }.sortedBy { it.timestamp }
    val bodyBattery = samples
        .filter { it.kind == "body_battery" && it.value in 1.0..100.0 }
        .sortedBy { it.timestamp }
    val stress = samples
        .filter { it.kind == "stress" && it.value in 0.0..100.0 }
        .sortedBy { it.timestamp }
    val spo2 = samples.filter { it.kind == "spo2" && it.value in 1.0..100.0 }
    val currentBodyBattery = day?.bodyBatteryLatest ?: bodyBattery.lastOrNull()?.value?.roundToInt()
    val bodyBatteryStart = day?.bodyBatteryStart ?: bodyBattery.firstOrNull()?.value?.roundToInt()
    val delta = bodyBatteryDeltas(bodyBattery.map { it.value })
    val previousDay = day?.let { latest ->
        days.filter { it.date < latest.date }.maxByOrNull { it.date }
    }
    val overnight = if (previousDay?.bodyBatteryLatest != null && bodyBatteryStart != null) {
        bodyBatteryStart - previousDay.bodyBatteryLatest
    } else {
        null
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
            Column(modifier = Modifier.weight(1f)) {
                Text("Health", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
                Text(
                    "$dateLabel · from your watch",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
            PillChip(
                label = "Trends",
                selected = false,
                onClick = onOpenTrends,
                leading = Icons.Outlined.TrendingUp,
            )
        }

        if (day == null) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text("No health data yet", style = MaterialTheme.typography.titleMedium, color = PulseColors.Text)
                    Text(
                        "Sync your watch to import steps, heart rate, Body Battery, stress, SpO₂ and respiration. Nothing is estimated or zero-filled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                    )
                    PrimaryButton("Sync watch", onSync)
                }
            }
            return@Column
        }

        // Body Battery -------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                            Icon(Icons.Outlined.Bolt, null, tint = PulseColors.Teal, modifier = Modifier.size(16.dp))
                            Text("Body Battery", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                currentBodyBattery?.toString() ?: "—",
                                style = MaterialTheme.typography.displayMedium,
                                color = PulseColors.Teal,
                            )
                            Text(
                                " / 100 · ${bodyBatteryLabel(currentBodyBattery)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.TextMuted,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (day.sleepStart != null) "Woke up at" else "First reading",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                        Text(
                            bodyBatteryStart?.toString() ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                        )
                    }
                }
                if (bodyBattery.size >= 2) {
                    LineChart(
                        values = bodyBattery.map { it.value.toFloat() },
                        labels = chartLabels(bodyBattery.map { it.timestamp }),
                        color = PulseColors.Teal,
                        contentDescription = "Body Battery through the day, current ${currentBodyBattery ?: "unknown"}",
                        height = 100.dp,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TinyStat(
                            "Overnight",
                            overnight?.let { if (it >= 0) "+$it" else "−${-it}" } ?: "—",
                            Modifier.weight(1f),
                        )
                        TinyStat("Drained", delta.first?.let { "−$it" } ?: "—", Modifier.weight(1f))
                        TinyStat("Recharged", delta.second?.let { "+$it" } ?: "—", Modifier.weight(1f))
                    }
                } else {
                    Text(
                        "Body Battery needs a few stress readings. Leave the watch on and sync again later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        // Stress -------------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    Icon(Icons.Outlined.MonitorHeart, null, tint = PulseColors.Yellow, modifier = Modifier.size(16.dp))
                    Text("Stress", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                }
                if (stress.isEmpty() && day.stressAvg == null) {
                    Text("No stress readings yet.", style = MaterialTheme.typography.bodyMedium, color = PulseColors.TextMuted)
                } else {
                    val avg = day.stressAvg ?: stress.map { it.value }.average().roundToInt()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$avg", style = MaterialTheme.typography.displaySmall, color = PulseColors.Text)
                        Text(
                            " avg · ${stressLabel(avg)} (0–100)",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    StressDistribution(stress.map { it.value })
                    if (stress.size >= 2) {
                        val buckets = hourlyBuckets(stress)
                        BarChart(
                            values = buckets,
                            labels = emptyList(),
                            goal = null,
                            color = PulseColors.Yellow,
                            selectedIndex = null,
                            contentDescription = "Stress through the day",
                            height = 64.dp,
                        )
                    }
                }
            }
        }

        // Sleep --------------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    Icon(Icons.Outlined.Bedtime, null, tint = PulseColors.Blue, modifier = Modifier.size(16.dp))
                    Text("Sleep", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                }
                if (day.sleepScore == null && day.sleepStart == null) {
                    Text("Sleep not synced yet", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Text(
                        "The watch writes sleep to a SLEEP_DATA file. Sync after waking with the watch nearby; this app never estimates sleep from heart rate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xl)) {
                        ProgressRing(
                            progress = ((day.sleepScore ?: 0) / 100f).coerceIn(0f, 1f),
                            label = day.sleepScore?.toString() ?: "—",
                            caption = "score",
                            color = PulseColors.Blue,
                            size = 76.dp,
                            strokeWidth = 7.dp,
                            contentDescription = "Sleep score ${day.sleepScore ?: "unknown"}",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xxs)) {
                            val asleep = WellnessMetrics.sleepSeconds(day) ?: 0
                            Text(
                                formatSleepDuration(asleep.coerceAtLeast(0)),
                                style = MaterialTheme.typography.headlineSmall,
                                color = PulseColors.Text,
                            )
                            Text(
                                "${day.sleepQuality ?: "Sleep"} · ${sleepWindow(day.sleepStart, day.sleepEnd)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.TextMuted,
                            )
                        }
                    }
                    if (sleepStages.isNotEmpty()) {
                        SleepStageChart(sleepStages)
                    }
                    val stages = listOf(
                        Triple("Deep", day.deepSleepSeconds, SleepDeep),
                        Triple("Light", day.lightSleepSeconds, SleepLight),
                        Triple("REM", day.remSleepSeconds, SleepRem),
                        Triple("Awake", day.awakeSleepSeconds, SleepAwake),
                    )
                    stages.forEach { (name, seconds, color) ->
                        if (seconds != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                                Spacer(Modifier.width(PulseSpacing.md))
                                Text(name, style = MaterialTheme.typography.bodySmall, color = PulseColors.Text, modifier = Modifier.weight(1f))
                                Text(formatSleepDuration(seconds), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = PulseColors.Text)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("Avg SpO₂", day.sleepAvgSpo2?.let { "$it%" } ?: "—", Modifier.weight(1f))
                        TinyStat("Respiration", day.sleepAvgRespiration?.let { "%.0f /min".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Lowest HR", day.sleepLowestHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
                    }
                }
            }
        }

        // HRV ----------------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = PulseColors.Teal, modifier = Modifier.size(16.dp))
                        Text("HRV status", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    }
                    Spacer(Modifier.weight(1f))
                    day.hrvStatus?.let { StatusLabel(it) }
                }
                if (hrvValue == null) {
                    Text("HRV not synced yet", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Text(
                        "HRV comes from the watch's overnight recording. It will appear after a night with the watch worn and a sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "%.0f".format(hrvValue),
                            style = MaterialTheme.typography.displaySmall,
                            color = PulseColors.Text,
                        )
                        Text(
                            " ms last night",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    hrvWeekly?.let {
                        Text("7-day average %.0f ms".format(it), style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    }
                    if (hrvSamples.size >= 2) {
                        LineChart(
                            values = hrvSamples.map { it.value.toFloat() },
                            labels = chartLabels(hrvSamples.map { it.timestamp }),
                            color = PulseColors.Teal,
                            contentDescription = "HRV through the night, average %.0f milliseconds".format(hrvValue),
                            height = 64.dp,
                        )
                    }
                    HrvBaseline(day.hrvBaselineLow, day.hrvBaselineHigh, hrvValue)
                }
            }
        }

        // Tiles --------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            StatTile(
                label = "Resting HR",
                value = day.restingHeartRate?.toString() ?: "—",
                unit = "bpm",
                icon = Icons.Outlined.FavoriteBorder,
                iconColor = PulseColors.Coral,
                modifier = Modifier.weight(1f),
                footnote = day.avgHeartRate?.let { "Avg $it bpm" },
            )
            StatTile(
                label = "Pulse Ox",
                value = day.spo2Avg?.toString() ?: "—",
                unit = "% SpO₂",
                icon = Icons.Outlined.Bloodtype,
                iconColor = PulseColors.Blue,
                modifier = Modifier.weight(1f),
                footnote = spo2.minOfOrNull { it.value }?.let { "Low %.0f%%".format(it) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            StatTile(
                label = "Respiration",
                value = day.respirationAvg?.let { "%.0f".format(it) } ?: "—",
                unit = "brpm",
                icon = Icons.Outlined.Air,
                iconColor = PulseColors.Blue,
                modifier = Modifier.weight(1f),
                footnote = "All-day average",
            )
            StatTile(
                label = "VO₂ max",
                value = training.latestVo2Max?.let { "%.1f".format(it) } ?: "—",
                unit = "ml/kg/min",
                icon = Icons.Outlined.Speed,
                iconColor = PulseColors.Accent,
                modifier = Modifier.weight(1f).clickable(onClick = onOpenTraining),
                footnote = if (training.latestVo2Max != null) "Tap Training for the trend" else "Not synced",
            )
        }

        // Movement -----------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xlPlus),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Daily movement", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                Row {
                    TinyStat("steps", "%,d".format(day.steps), Modifier.weight(1f))
                    TinyStat("distance", "%.1f km".format(day.distanceMeters / 1000.0), Modifier.weight(1f))
                    TinyStat("active kcal", "%,d".format(day.activeCalories), Modifier.weight(1f))
                }
            }
        }

        // Active minutes -----------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Active minutes · this week", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                val week = weekBuckets(days)
                val total = week.sumOf { it.second }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$total", style = MaterialTheme.typography.displaySmall, color = PulseColors.Accent)
                    Text(
                        " min · from your watch",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                BarChart(
                    values = week.map { it.second.toFloat() },
                    labels = week.map { it.first },
                    goal = null,
                    color = PulseColors.Accent,
                    selectedIndex = null,
                    contentDescription = "Active minutes for each of the last seven days",
                    height = 76.dp,
                )
                day.moderateMinutes?.let { moderate ->
                    Text(
                        "Moderate $moderate min · Vigorous ${day.vigorousMinutes ?: 0} min (vigorous counts double)",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        CoachInsightCard(
            text = healthInsight(day, currentBodyBattery, bodyBatteryStart, stress, training, hrvValue),
            onAskCoach = onAskCoach,
        )
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun StressDistribution(values: List<Double>) {
    val bins = listOf(
        "Rest" to values.count { it <= 25 },
        "Low" to values.count { it > 25 && it <= 50 },
        "Medium" to values.count { it > 50 && it <= 75 },
        "High" to values.count { it > 75 },
    )
    val colors = listOf(PulseColors.Blue, PulseColors.Teal, PulseColors.Yellow, PulseColors.Coral)
    val total = bins.sumOf { it.second }.coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(PulseColors.RingTrack),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        bins.forEachIndexed { index, (_, count) ->
            if (count > 0) {
                Box(
                    Modifier
                        .weight(count.toFloat() / total)
                        .height(10.dp)
                        .background(colors[index]),
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        bins.forEachIndexed { index, (label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors[index]))
                Spacer(Modifier.width(4.dp))
                Text(
                    "$label ${count * 100 / total}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SleepStageChart(stages: List<SleepStageEntity>) {
    val total = (stages.lastOrNull()?.endTime ?: 1L) - (stages.firstOrNull()?.startTime ?: 0L)
    if (total <= 0L) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PulseColors.SurfaceElevated)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        stages.forEach { stage ->
            val duration = (stage.endTime - stage.startTime).coerceAtLeast(0)
            if (duration <= 0) return@forEach
            val color = when (stage.stage) {
                "deep" -> SleepDeep
                "light" -> SleepLight
                "rem" -> SleepRem
                "awake" -> SleepAwake
                else -> PulseColors.TextMuted
            }
            val height = when (stage.stage) {
                "awake" -> 20
                "rem" -> 58
                "light" -> 40
                "deep" -> 76
                else -> 30
            }
            Box(
                Modifier
                    .weight(duration.toFloat() / total)
                    .height(height.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun HrvBaseline(low: Double?, high: Double?, value: Double) {
    if (low == null || high == null) return
    val min = 30.0
    val max = 70.0
    val lowFraction = ((low - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    val highFraction = ((high - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    val valueFraction = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs)) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val barHeight = 10.dp.toPx()
            val top = (size.height - barHeight) / 2f
            drawRoundRect(
                color = PulseColors.RingTrack,
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f),
            )
            drawRoundRect(
                color = PulseColors.Teal.copy(alpha = 0.35f),
                topLeft = Offset(size.width * lowFraction, top),
                size = Size(size.width * (highFraction - lowFraction), barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f),
            )
            drawRoundRect(
                color = PulseColors.Text,
                topLeft = Offset((size.width * valueFraction).coerceIn(0f, size.width - 4f), 0f),
                size = Size(4.dp.toPx(), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("30 ms", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            Text(
                "Baseline %.0f–%.0f ms".format(low, high),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            Text("70 ms", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

@Composable
private fun StatusLabel(text: String) {
    val color = when (text.lowercase(Locale.getDefault())) {
        "balanced" -> PulseColors.Teal
        "unbalanced" -> PulseColors.Yellow
        "low", "poor" -> PulseColors.Coral
        else -> PulseColors.TextMuted
    }
    Box(
        Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PulseColors.TextOnAccent)
    }
}

private fun bodyBatteryLabel(value: Int?): String = when {
    value == null -> "No reading"
    value >= 76 -> "High"
    value >= 51 -> "Medium"
    value >= 26 -> "Low"
    else -> "Very low"
}

private fun stressLabel(value: Int): String = when {
    value <= 25 -> "Rest"
    value <= 50 -> "Low"
    value <= 75 -> "Medium"
    else -> "High"
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

private fun hourlyBuckets(samples: List<WellnessSampleEntity>): List<Float> {
    val zone = ZoneId.systemDefault()
    val buckets = Array(12) { mutableListOf<Double>() }
    for (sample in samples) {
        val hour = Instant.ofEpochSecond(sample.timestamp).atZone(zone).hour
        buckets[(hour / 2).coerceIn(0, 11)].add(sample.value)
    }
    return buckets.map { if (it.isEmpty()) 0f else it.average().toFloat() }
}

private fun chartLabels(timestamps: List<Long>): List<String> {
    if (timestamps.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    fun label(index: Int): String =
        Instant.ofEpochSecond(timestamps[(timestamps.size - 1) * index / 3]).atZone(zone)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    return listOf(label(0), label(1), label(2), "Now")
}

private fun sleepWindow(start: Long?, end: Long?): String {
    if (start == null || end == null) return "time not recorded"
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return "${Instant.ofEpochSecond(start).atZone(zone).format(formatter)} – " +
        Instant.ofEpochSecond(end).atZone(zone).format(formatter)
}

private fun formatSleepDuration(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun weekBuckets(days: List<WellnessDayEntity>): List<Pair<String, Int>> {
    val byDate = days.associateBy { it.date }
    val today = LocalDate.now()
    return (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val label = date.dayOfWeek.name.take(1)
        label to ((byDate[date.toString()]?.activeSeconds ?: 0) / 60)
    }
}

private fun healthInsight(
    day: WellnessDayEntity,
    current: Int?,
    start: Int?,
    stress: List<WellnessSampleEntity>,
    training: TrainingSummary,
    hrvValue: Double?,
): String {
    val parts = ArrayList<String>()
    if (current != null && start != null) {
        parts.add("Body Battery went from $start at the first reading to $current now.")
    }
    if (stress.isNotEmpty()) {
        val avg = stress.map { it.value }.average().roundToInt()
        parts.add("Stress is averaging $avg (${stressLabel(avg).lowercase(Locale.getDefault())}).")
    }
    day.sleepScore?.let { parts.add("Last night's sleep score is $it (${day.sleepQuality ?: "recorded"}).") }
    training.latestVo2Max?.let { parts.add("VO₂ max is %.1f.".format(it)) }
    if (day.sleepScore == null || hrvValue == null) {
        parts.add("Sleep and HRV will populate after the next overnight sync.")
    }
    return parts.joinToString(" ")
}
