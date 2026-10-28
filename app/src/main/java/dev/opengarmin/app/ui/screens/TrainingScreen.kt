package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.LoadFocus
import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PaceRace
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.util.formatDate
import dev.opengarmin.app.util.formatPacePerKm
import dev.opengarmin.app.util.formatElapsedTime
import dev.opengarmin.app.util.formatWellnessDate
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.CoachInsightCard
import dev.opengarmin.app.ui.components.LineChart
import dev.opengarmin.app.ui.components.PrimaryButton
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
    onOpenActivity: (ActivityEntity) -> Unit,
    onAskCoach: () -> Unit,
    onOpenPlan: () -> Unit = {},
    plannedWorkoutsSummary: String = "",
    onOpenPace: () -> Unit = {},
    pacePlanSummary: String = "",
    racePredictions: Map<PaceRace, Int> = emptyMap(),
    onOpenVo2Max: () -> Unit = {},
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
                "Load and performance · from your watch",
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
                modifier = Modifier.weight(1f).clickable(onClick = onOpenVo2Max),
                footnote = summary.vo2Change?.let { change ->
                    tr(
                        "%s in 6 weeks",
                        "${if (change >= 0) "+" else ""}${"%.1f".format(change)}",
                    )
                } ?: tr("Running estimate"),
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
                footnote = summary.ltPaceSecondsPerKm?.let { tr("LTHR · %s /km", formatPacePerKm(it)) } ?: tr("Watch estimate"),
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
        PulseCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenVo2Max) {
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
                            tr(
                                "%s since %s",
                                "${if (change >= 0) "+" else ""}${"%.1f".format(change)}",
                                sixWeeksAgoLabel(),
                            ),
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
                            tr("Latest training effect · %s", tr(sportLabel(latest))),
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                        Text(
                            tr(
                                "Aerobic %s · Anaerobic %s",
                                metrics.aerobicEffect?.let { "%.1f".format(it) } ?: "—",
                                metrics.anaerobicEffect?.let { "%.1f".format(it) } ?: "—",
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                        )
                        Text(
                            "${formatWellnessDate(dateOf(latest.startTime))} · ${formatElapsedTime(latest.durationSeconds)}",
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

        if (racePredictions.isNotEmpty()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(
                        start = PulseSpacing.xlPlus,
                        end = PulseSpacing.xlPlus,
                        top = PulseSpacing.xl,
                        bottom = PulseSpacing.md,
                    ),
                ) {
                    Text("Race predictor", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    PaceRace.entries.forEach { race ->
                        val seconds = racePredictions[race] ?: return@forEach
                        val pace = (seconds / (race.distanceMeters / 1000.0)).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = PulseSpacing.touchTarget),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (race == PaceRace.HALF) "Half marathon" else race.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${formatPacePerKm(pace)} /km",
                                style = MaterialTheme.typography.labelMedium,
                                color = PulseColors.TextMuted,
                            )
                            Text(
                                PacePlanCalculator.formatClock(seconds),
                                style = MaterialTheme.typography.titleSmall,
                                color = PulseColors.Text,
                                modifier = Modifier.width(88.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        }

        PulseCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenPace,
        ) {
            Row(
                modifier = Modifier.padding(PulseSpacing.lgPlus),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.DirectionsRun,
                    contentDescription = null,
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(PulseSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "PacePro strategy",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    Text(
                        pacePlanSummary.ifBlank { "Build a split-by-split racing plan" },
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Open PacePro strategy",
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        PulseCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenPlan,
        ) {
            Row(
                modifier = Modifier.padding(PulseSpacing.lgPlus),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(PulseSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Workout plan",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    Text(
                        plannedWorkoutsSummary.ifBlank { "Build a session and send it to the watch" },
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Open workout plan",
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
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

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

private fun performanceLabel(value: Int): String = when {
    value >= 5 -> "Fresh and strong"
    value >= 1 -> "Above baseline"
    value == 0 -> "At baseline"
    value >= -5 -> "Slightly below baseline"
    else -> "Fatigued"
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

@Composable
private fun trainingInsight(summary: TrainingSummary): String {
    val parts = ArrayList<String>()
    summary.acuteLoad?.let { load ->
        parts.add(tr("Your 7-day training load is %s.", "%.0f".format(load)))
        summary.loadRatio?.let { ratio ->
            parts.add(
                tr(
                    "That is %s against your 4-week weekly average.",
                    tr(loadLabel(ratio)).lowercase(Locale.getDefault()),
                ),
            )
        }
    }
    summary.recoveryRemainingMinutes?.let { minutes ->
        parts.add(tr("You still have %s of recovery from the last hard session.", formatRecovery(minutes)))
    } ?: run {
        if (summary.latestMetrics != null) parts.add(tr("Your last session's recovery window has elapsed."))
    }
    summary.vo2Change?.let { change ->
        parts.add(
            tr(
                "VO₂ max is %s %s over six weeks.",
                if (change >= 0) tr("up") else tr("down"),
                "%.1f".format(abs(change)),
            ),
        )
    }
    summary.latestMetrics?.aerobicEffect?.let { effect ->
        parts.add(tr("The latest aerobic training effect is %s.", "%.1f".format(effect)))
    }
    if (parts.isEmpty()) parts.add(tr("Import an activity to see load, recovery and VO₂ max insights."))
    return parts.joinToString(" ")
}
