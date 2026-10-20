package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessMetrics
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.formatWellnessDate
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.ui.components.CoachInsightCard
import dev.opengarmin.app.util.formatFileSize
import dev.opengarmin.app.ui.components.LineChart
import dev.opengarmin.app.ui.components.ProgressRing
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.StatTile
import dev.opengarmin.app.ui.components.StatusPill
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TodayScreen(
    days: List<WellnessDayEntity>,
    hrSamples: List<WellnessSampleEntity>,
    settings: PulseSettings,
    syncLabel: String,
    syncColor: Color,
    activitySyncProgress: ActivitySyncProgress?,
    lastImportedLabel: String?,
    syncError: String?,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onAskCoach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = WellnessMetrics.latestDay(days)
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    val hrvValue = WellnessMetrics.hrvMs(day, hrSamples)
    val dateLabel = now.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
    val greeting = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    val samples = hrSamples
        .filter { it.kind == "hr" && it.value in 1.0..250.0 }
        .sortedBy { it.timestamp }
    val latestHr = samples.lastOrNull()?.value?.roundToInt()
    val minHr = samples.minOfOrNull { it.value }?.roundToInt()
    val maxHr = samples.maxOfOrNull { it.value }?.roundToInt()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dateLabel, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                Text(greeting, style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                dev.opengarmin.app.ui.components.IconButtonRound(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Sync watch now",
                    onClick = onSync,
                    background = PulseColors.Surface,
                )
                Spacer(Modifier.width(PulseSpacing.xs))
                StatusPill(
                    text = syncLabel,
                    dotColor = syncColor,
                    onClick = onOpenSettings,
                )
            }
        }

        activitySyncProgress?.let { progress ->
            ActivitySyncCard(progress = progress, lastImported = lastImportedLabel)
        }

        if (!syncError.isNullOrBlank()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text(
                        "Sync needs attention",
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Coral,
                    )
                    Text(
                        syncError,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    dev.opengarmin.app.ui.components.PrimaryButton("Try again", onSync)
                }
            }
        }

        if (day == null) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text("No health data yet", style = MaterialTheme.typography.titleMedium, color = PulseColors.Text)
                    Text(
                        "Sync your watch to import steps, heart rate and wellness data. Nothing is invented or filled in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                    )
                }
            }
            return@Column
        }

        val isToday = day.date == today.toString()
        val dayLabel = if (isToday) "today" else formatWellnessDate(day.date)
        val goal = settings.stepGoal.coerceAtLeast(1)
        val restingHr = day.restingHeartRate?.takeIf { it in 1..250 }
        val progress = (day.steps.toFloat() / goal).coerceIn(0f, 1f)
        val remaining = (goal - day.steps).coerceAtLeast(0)
        if (!isToday && day.date > today.toString()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "The watch date is ahead of the phone. Sync the watch clock so new health data lands on the right day.",
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.Coral,
                )
            }
        }
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(PulseSpacing.xlPlus),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xlPlus),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(
                    progress = progress,
                    label = "${(progress * 100).roundToInt()}%",
                    caption = "of goal",
                    contentDescription = "Steps: ${day.steps} of $goal, ${(progress * 100).roundToInt()} percent",
                )
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs)) {
                    Text("Steps $dayLabel", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Text(
                        "%,d".format(day.steps),
                        style = MaterialTheme.typography.displayLarge,
                        color = PulseColors.Accent,
                    )
                    Text(
                        if (remaining > 0) "%,d to reach %,d".format(remaining, goal) else "Goal reached",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xl)) {
                        Row {
                            Text(
                                "%.1f".format(day.distanceMeters / 1000.0),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = PulseColors.Text,
                            )
                            Text(" km", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        }
                        Row {
                            Text(
                                "${day.activeSeconds / 60}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = PulseColors.Text,
                            )
                            Text(" active min", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        }
                    }
                }
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.xl, bottom = PulseSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                            Icon(
                                Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = PulseColors.Coral,
                                modifier = Modifier.height(16.dp),
                            )
                            Text("Heart rate", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = latestHr?.toString() ?: "—",
                                style = MaterialTheme.typography.displayMedium,
                                color = PulseColors.Text,
                            )
                            Text(
                                if (isToday) " bpm now" else " bpm last",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.TextMuted,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Resting ${restingHr ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                        Text(
                            "Range ${minHr ?: "—"}–${maxHr ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                }
                LineChart(
                    values = samples.map { it.value.toFloat() },
                    labels = chartTimeLabels(samples, isToday),
                    color = PulseColors.Coral,
                    contentDescription = "Heart rate today: ${minHr ?: "no minimum"} to ${maxHr ?: "no maximum"} bpm",
                    height = 64.dp,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
            StatTile(
                label = "Sleep",
                value = day.sleepScore?.toString() ?: "—",
                unit = if (day.sleepScore != null) "score" else null,
                icon = Icons.Outlined.Bedtime,
                iconColor = PulseColors.Blue,
                footnote = day.sleepQuality ?: "Not synced yet",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "HRV",
                value = hrvValue?.let { "%.0f".format(it) } ?: "—",
                unit = if (hrvValue != null) "ms" else null,
                icon = Icons.Outlined.MonitorHeart,
                iconColor = PulseColors.Teal,
                footnote = day.hrvStatus ?: if (hrvValue != null) "Overnight" else "Not synced yet",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
            StatTile(
                label = "Active time",
                value = "${day.activeSeconds / 60}",
                unit = "min",
                icon = Icons.Outlined.Bolt,
                iconColor = PulseColors.Accent,
                footnote = "From wellness totals",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Calories",
                value = "%,d".format(day.activeCalories),
                unit = "kcal",
                icon = Icons.Outlined.LocalFireDepartment,
                iconColor = PulseColors.Orange,
                footnote = "Active calories",
                modifier = Modifier.weight(1f),
            )
        }

        CoachInsightCard(
            text = localInsight(days, samples, goal, isToday),
            onAskCoach = onAskCoach,
        )
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun ActivitySyncCard(progress: ActivitySyncProgress, lastImported: String?) {
    val current = (progress.done + 1).coerceAtMost(progress.total)
    val fraction = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = PulseSpacing.xlPlus, vertical = PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Syncing activities",
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Text,
                )
                Text(
                    "$current / ${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.Accent,
                )
            }
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = PulseColors.Accent,
                trackColor = PulseColors.Border,
            )
            Text(
                if (progress.currentSize != null) {
                    "Downloading activity $current of ${progress.total} · " +
                        formatFileSize(progress.currentSize)
                } else {
                    "Finishing activity imports…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
            lastImported?.let {
                Text(
                    "Last imported: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

internal fun chartTimeLabels(samples: List<WellnessSampleEntity>, isToday: Boolean): List<String> {
    if (samples.size < 2) return emptyList()
    val zone = ZoneId.systemDefault()
    fun format(seconds: Long) = Instant.ofEpochSecond(seconds).atZone(zone)
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    return listOf(
        format(samples.first().timestamp),
        format(samples[samples.size / 2].timestamp),
        format(samples.last().timestamp),
        if (isToday) "Now" else "Last",
    )
}

private fun localInsight(
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    goal: Int,
    isToday: Boolean,
): String {
    val today = WellnessMetrics.latestDay(days) ?: return "Sync your watch to see an evidence-based summary."
    val previousDays = days.sortedByDescending { it.date }.filter { it.date < today.date }
    val todayResting = today.restingHeartRate?.takeIf { it in 1..250 }
    val previous = previousDays.take(7).mapNotNull { it.restingHeartRate?.takeIf { value -> value in 1..250 } }
    val restingDelta = if (previous.isNotEmpty() && todayResting != null) {
        todayResting - previous.average().roundToInt()
    } else {
        null
    }
    return when {
        restingDelta != null && restingDelta <= -2 ->
            "Your resting heart rate is $restingDelta bpm vs your recent average, while you logged ${"%,d".format(today.steps)} steps today. Keep the easy sessions easy and re-check the trend over the next week."
        today.steps < goal ->
            "You're ${"%,d".format(goal - today.steps)} steps from your ${"%,d".format(goal)} goal. Your last recorded heart rate was ${samples.lastOrNull()?.value?.roundToInt() ?: "not available"} bpm."
        else ->
            "You reached ${"%,d".format(today.steps)} steps ${if (isToday) "today" else "on the latest recorded day"} and logged ${today.activeSeconds / 60} active minutes. The values above come from your watch's wellness records."
    }
}
