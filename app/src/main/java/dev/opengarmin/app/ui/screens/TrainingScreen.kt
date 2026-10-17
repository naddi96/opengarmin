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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.LoadFocus
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.formatDate
import dev.opengarmin.app.formatDuration
import dev.opengarmin.app.formatWellnessDate
import dev.opengarmin.app.ui.components.CoachInsightCard
import dev.opengarmin.app.ui.components.LineChart
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.ProgressRing
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.StatTile
import dev.opengarmin.app.ui.components.TinyStat
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TrainingScreen(
    summary: TrainingSummary,
    latestDay: WellnessDayEntity?,
    onOpenActivity: (ActivityEntity) -> Unit,
    onAskCoach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Column {
            Text("Training", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
            Text(
                "Readiness, load and performance · from your watch",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
        }

        if (!summary.hasAnyData) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text("No training data yet", style = MaterialTheme.typography.titleMedium, color = PulseColors.Text)
                    Text(
                        "Import at least one recorded activity. VO₂ max, recovery, training load and training effect come from the FIT file the watch writes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                    )
                }
            }
            return@Column
        }

        ReadinessCard(summary = summary, latestDay = latestDay)

        // Training load ------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Training load", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Spacer(Modifier.weight(1f))
                    LoadStatusPill(summary.loadRatio)
                }
                Text(
                    loadSentence(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulseColors.Text,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Acute load (7 days)", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        Text(
                            summary.acuteLoad?.let { "%.0f".format(it) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            color = PulseColors.Text,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("4-week weekly avg", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        Text(
                            summary.chronicLoad?.let { "%.0f".format(it) } ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                        )
                    }
                }
                LoadRatioBar(summary.loadRatio)
                summary.focus?.let { focus ->
                    Text(
                        "Load focus · last 7 days (from training effect)",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                    FocusRow("Low aerobic", focus.lowAerobic, focus, PulseColors.Teal)
                    FocusRow("High aerobic", focus.highAerobic, focus, PulseColors.Accent)
                    FocusRow("Anaerobic", focus.anaerobic, focus, PulseColors.Coral)
                }
            }
        }

        // Tiles --------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            StatTile(
                label = "Recovery time",
                value = summary.recoveryRemainingMinutes?.let { formatRecovery(it) } ?: "Recovered",
                unit = null,
                icon = Icons.Outlined.FavoriteBorder,
                iconColor = PulseColors.Coral,
                modifier = Modifier.weight(1f),
                footnote = summary.recoveryRemainingMinutes?.let { "Until your next hard session" } ?: "No recovery debt",
            )
            StatTile(
                label = "VO₂ max",
                value = summary.latestVo2Max?.let { "%.1f".format(it) } ?: "—",
                unit = "ml/kg/min",
                icon = Icons.Outlined.Speed,
                iconColor = PulseColors.Accent,
                modifier = Modifier.weight(1f),
                footnote = summary.vo2Change?.let { change ->
                    "${if (change >= 0) "+" else ""}${"%.1f".format(change)} in 6 weeks"
                } ?: "Running estimate",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            StatTile(
                label = "Lactate threshold",
                value = summary.lthr?.toString() ?: "—",
                unit = "bpm",
                icon = Icons.Outlined.MonitorHeart,
                iconColor = PulseColors.Coral,
                modifier = Modifier.weight(1f),
                footnote = summary.ltPaceSecondsPerKm?.let { "LTHR · ${formatPace(it)} /km" } ?: "Watch estimate",
            )
            StatTile(
                label = "Performance",
                value = summary.latestMetrics?.performanceCondition?.let { signed(it) } ?: "—",
                unit = null,
                icon = Icons.Outlined.DirectionsRun,
                iconColor = PulseColors.Teal,
                modifier = Modifier.weight(1f),
                footnote = summary.latestMetrics?.performanceCondition?.let { performanceLabel(it) } ?: "Not recorded",
            )
        }

        // VO2 max trend ------------------------------------------------------
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VO₂ max · 6 weeks", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        Text(
                            summary.latestVo2Max?.let { "%.1f".format(it) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            color = PulseColors.Accent,
                        )
                    }
                    summary.vo2Change?.let { change ->
                        Text(
                            "${if (change >= 0) "+" else ""}${"%.1f".format(change)} since ${sixWeeksAgoLabel()}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (change >= 0) PulseColors.Accent else PulseColors.Coral,
                        )
                    }
                }
                if (summary.vo2History.size >= 2) {
                    LineChart(
                        values = summary.vo2History.map { it.value.toFloat() },
                        labels = trendLabels(summary.vo2History.map { it.startTime }),
                        color = PulseColors.Accent,
                        contentDescription = "VO₂ max trend over the last six weeks",
                        height = 80.dp,
                    )
                } else {
                    Text(
                        "Run a few more outdoor sessions to build a VO₂ max trend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        // Latest training effect --------------------------------------------
        val latest = summary.latestActivity
        val metrics = summary.latestMetrics
        if (latest != null && metrics != null) {
            PulseCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenActivity(latest) },
            ) {
                Row(
                    modifier = Modifier.padding(PulseSpacing.lgPlus),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Latest training effect · ${sportLabel(latest)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                        Text(
                            "Aerobic ${metrics.aerobicEffect?.let { "%.1f".format(it) } ?: "—"} · " +
                                "Anaerobic ${metrics.anaerobicEffect?.let { "%.1f".format(it) } ?: "—"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                        )
                        Text(
                            "${formatWellnessDate(dateOf(latest.startTime))} · ${formatDuration(latest.durationSeconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "Open activity detail",
                        tint = PulseColors.Accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        CoachInsightCard(
            text = trainingInsight(summary),
            onAskCoach = onAskCoach,
        )
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun ReadinessCard(summary: TrainingSummary, latestDay: WellnessDayEntity?) {
    val factors = readinessFactors(summary, latestDay)
    val hasSleep = latestDay?.sleepScore != null
    val hasHrv = latestDay?.hrvLastNight != null
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xlPlus),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Training readiness", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xl),
            ) {
                ProgressRing(
                    progress = 0f,
                    label = "—",
                    caption = "no score",
                    color = PulseColors.Yellow,
                    size = 96.dp,
                    strokeWidth = 9.dp,
                    contentDescription = "Training readiness score unavailable",
                )
                Text(
                    if (!hasSleep || !hasHrv) {
                        "A readiness score needs overnight sleep and HRV. Sync after sleeping with the watch; the factors below are already recorded from the watch."
                    } else {
                        "Sleep and HRV are available. Garmin's readiness model is not exposed in the FIT file, so this app shows the factors instead of inventing a score."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulseColors.Text,
                )
            }
            factors.forEach { factor ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(factor.color))
                    Spacer(Modifier.width(PulseSpacing.mdPlus))
                    Text(factor.name, style = MaterialTheme.typography.bodyMedium, color = PulseColors.Text, modifier = Modifier.weight(1f))
                    Text(factor.value, style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                }
            }
        }
    }
}

private data class ReadinessFactor(val name: String, val value: String, val color: Color)

private fun readinessFactors(summary: TrainingSummary, day: WellnessDayEntity?): List<ReadinessFactor> {
    val recovery = summary.recoveryRemainingMinutes
    val load = summary.loadRatio
    val factors = ArrayList<ReadinessFactor>()
    factors.add(
        ReadinessFactor(
            "Recovery time",
            recovery?.let { formatRecovery(it) } ?: "Recovered",
            if (recovery == null) PulseColors.Teal else PulseColors.Yellow,
        ),
    )
    factors.add(
        ReadinessFactor(
            "Acute load",
            summary.acuteLoad?.let { loadValue -> "%.0f · ${loadLabel(load)}".format(loadValue) } ?: "—",
            when {
                summary.acuteLoad == null -> PulseColors.TextMuted
                load == null -> PulseColors.TextMuted
                load < 0.8 -> PulseColors.Yellow
                load > 1.3 -> PulseColors.Coral
                else -> PulseColors.Teal
            },
        ),
    )
    factors.add(
        ReadinessFactor(
            "Sleep score",
            day?.sleepScore?.let { "$it · ${day.sleepQuality ?: "recorded"}" } ?: "Not synced",
            if (day?.sleepScore == null) PulseColors.TextMuted else PulseColors.Teal,
        ),
    )
    factors.add(
        ReadinessFactor(
            "HRV status",
            day?.hrvLastNight?.let { value -> "%.0f ms · ${day.hrvStatus ?: "recorded"}".format(value) }
                ?: day?.hrvStatus ?: "Not synced",
            if (day?.hrvLastNight == null && day?.hrvStatus == null) PulseColors.TextMuted else PulseColors.Teal,
        ),
    )
    factors.add(
        ReadinessFactor(
            "Stress",
            day?.stressAvg?.let { "$it · ${stressBand(it)}" } ?: "—",
            when {
                day?.stressAvg == null -> PulseColors.TextMuted
                day.stressAvg <= 50 -> PulseColors.Teal
                day.stressAvg <= 75 -> PulseColors.Yellow
                else -> PulseColors.Coral
            },
        ),
    )
    factors.add(
        ReadinessFactor(
            "VO₂ max trend",
            summary.vo2Change?.let { change ->
                "${if (change >= 0) "+" else ""}${"%.1f".format(change)} in 6 weeks"
            } ?: "Need more runs",
            if (summary.vo2Change == null) PulseColors.TextMuted else PulseColors.Teal,
        ),
    )
    return factors
}

@Composable
private fun LoadRatioBar(ratio: Double?) {
    if (ratio == null) return
    val fraction = (ratio / 2.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(PulseColors.RingTrack),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .clip(CircleShape)
                .background(if (ratio in 0.8..1.3) PulseColors.Accent else PulseColors.Yellow),
        )
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.width(2.dp).height(16.dp).background(PulseColors.Text))
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        Text(
            "Balanced 0.8–1.3",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
        Text("2.0+", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
    }
}

@Composable
private fun FocusRow(label: String, value: Double, focus: LoadFocus, color: Color) {
    if (focus.total <= 0.0) return
    val fraction = (value / focus.total).coerceIn(0.0, 1.0).toFloat()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = PulseColors.Text, modifier = Modifier.weight(1f))
        Text("%.0f".format(value), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = PulseColors.Text)
        Text(
            " · ${(fraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
            modifier = Modifier.width(46.dp),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(CircleShape)
            .background(PulseColors.RingTrack),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(7.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun LoadStatusPill(ratio: Double?) {
    val label = loadLabel(ratio)
    val color = when {
        ratio == null -> PulseColors.TextMuted
        ratio < 0.8 -> PulseColors.Yellow
        ratio > 1.3 -> PulseColors.Coral
        else -> PulseColors.Accent
    }
    Box(
        Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PulseColors.TextOnAccent)
    }
}

private fun loadLabel(ratio: Double?): String = when {
    ratio == null -> "No ratio"
    ratio < 0.8 -> "Recovering"
    ratio > 1.3 -> "Building"
    else -> "Balanced"
}

private fun loadSentence(summary: TrainingSummary): String = when {
    summary.acuteLoad == null -> "No training load has been recorded in the last seven days."
    summary.loadRatio == null -> "Acute load is the sum of your watch's training load for the last 7 days."
    summary.loadRatio < 0.8 -> "Your 7-day load is below your recent weekly average. This is a recovery-friendly block."
    summary.loadRatio > 1.3 -> "Your 7-day load is above your recent weekly average. Watch recovery and sleep before adding more intensity."
    else -> "Your 7-day load is balanced against your recent weekly average."
}

private fun formatRecovery(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

private fun formatPace(secondsPerKm: Int): String {
    val minutes = secondsPerKm / 60
    val seconds = secondsPerKm % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

private fun performanceLabel(value: Int): String = when {
    value >= 5 -> "Fresh and strong"
    value >= 1 -> "Above baseline"
    value == 0 -> "At baseline"
    value >= -5 -> "Slightly below baseline"
    else -> "Fatigued"
}

private fun stressBand(value: Int): String = when {
    value <= 25 -> "Rest"
    value <= 50 -> "Low"
    value <= 75 -> "Medium"
    else -> "High"
}

private fun sportLabel(activity: ActivityEntity): String = when (activity.sport) {
    1 -> "Run"
    2, 21 -> "Ride"
    9, 11 -> "Walk"
    10, 62 -> "Strength"
    else -> "Workout"
}

private fun dateOf(timestamp: Long): String =
    Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun trendLabels(timestamps: List<Long>): List<String> {
    if (timestamps.isEmpty()) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    fun label(index: Int): String =
        Instant.ofEpochSecond(timestamps[(timestamps.size - 1) * index / 3]).atZone(zone).format(formatter)
    return listOf(label(0), label(1), label(2), "Now")
}

private fun sixWeeksAgoLabel(): String =
    java.time.LocalDate.now().minusWeeks(6).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

private fun trainingInsight(summary: TrainingSummary): String {
    val parts = ArrayList<String>()
    summary.acuteLoad?.let { load ->
        parts.add("Your 7-day training load is %.0f.".format(load))
        summary.loadRatio?.let { ratio ->
            parts.add("That is ${loadLabel(ratio).lowercase(Locale.getDefault())} against your 4-week weekly average.")
        }
    }
    summary.recoveryRemainingMinutes?.let { minutes ->
        parts.add("You still have ${formatRecovery(minutes)} of recovery from the last hard session.")
    } ?: run {
        if (summary.latestMetrics != null) parts.add("Your last session's recovery window has elapsed.")
    }
    summary.vo2Change?.let { change ->
        parts.add(
            "VO₂ max is ${if (change >= 0) "up" else "down"} ${"%.1f".format(abs(change))} over six weeks.",
        )
    }
    summary.latestMetrics?.aerobicEffect?.let { effect ->
        parts.add("The latest aerobic training effect is ${"%.1f".format(effect)}.")
    }
    if (parts.isEmpty()) parts.add("Import an activity to see load, recovery and VO₂ max insights.")
    return parts.joinToString(" ")
}
