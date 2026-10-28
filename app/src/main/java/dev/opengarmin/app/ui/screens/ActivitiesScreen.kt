package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.categoryForSport
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.util.formatHoursMinutes
import dev.opengarmin.app.util.formatDate
import dev.opengarmin.app.util.formatDistanceKm
import dev.opengarmin.app.util.formatElapsedTime
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.ActivityTrace
import dev.opengarmin.app.ui.components.CompactCard
import dev.opengarmin.app.ui.components.EmptyState
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.TinyStat
import dev.opengarmin.app.ui.components.ZoneBar
import dev.opengarmin.app.ui.components.ZoneLegend
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing

@Composable
fun ActivitiesScreen(
    activities: List<ActivityEntity>,
    selectedActivity: ActivityEntity?,
    selectedRecords: List<RecordEntity>,
    maxHeartRate: Int?,
    onSelect: (ActivityEntity) -> Unit,
    onOpenDetail: (ActivityEntity) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val sorted = activities.sortedByDescending { it.startTime }
    val filtered = if (filter == "All") {
        sorted
    } else {
        sorted.filter { categoryForSport(it.sport, it.subSport).label == filter }
    }
    val featured = filtered.firstOrNull { it.id == selectedActivity?.id } ?: filtered.firstOrNull()
    LaunchedEffect(filtered.firstOrNull()?.id) {
        val first = filtered.firstOrNull()
        if (first != null && (selectedActivity == null || filtered.none { it.id == selectedActivity.id })) {
            onSelect(first)
        }
    }
    val weekStart = (System.currentTimeMillis() / 1000) - 7L * 86_400L
    val week = sorted.filter { it.startTime >= weekStart }
    val weekSeconds = week.sumOf { it.durationSeconds }
    val weekCalories = week.sumOf { it.calories ?: 0 }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = PulseSpacing.xxl,
            end = PulseSpacing.xxl,
            top = PulseSpacing.screenTop,
            bottom = PulseSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        item {
            Column {
                Text("Activities", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
                Text(
                    "Recorded on your watch and synced automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
        item {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.lgPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Text("Last 7 days", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("workouts", "${week.size}", Modifier.weight(1f))
                        TinyStat("active time", formatHoursMinutes(weekSeconds.toLong()), Modifier.weight(1f))
                        TinyStat("kcal burned", "%,d".format(weekCalories), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                (listOf("All") + SportCategory.entries.map { it.label }).forEach { label ->
                    PillChip(
                        label = label,
                        selected = filter == label,
                        onClick = { filter = label },
                    )
                }
            }
        }
        if (featured != null) {
            item(key = "featured-${featured.id}") {
                FeaturedActivity(
                    activity = featured,
                    records = if (selectedActivity?.id == featured.id) selectedRecords else emptyList(),
                    maxHeartRate = maxHeartRate,
                    onOpen = { onOpenDetail(featured) },
                    onOpenSettings = onOpenSettings,
                )
            }
        } else if (activities.isEmpty()) {
            item {
                EmptyState(
                    title = "No activities yet",
                    message = "Sync your watch to import workouts. Activities will appear here automatically.",
                )
            }
        } else {
            item {
                EmptyState(
                    title = tr("No %s activities", tr(filter)),
                    message = tr("Try another filter. Other recorded sports remain available under All."),
                )
            }
        }
        if (filtered.isNotEmpty()) {
            item {
                Text(
                    "Recent activities",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
            }
            items(filtered, key = { it.id }) { activity ->
                ActivityRow(
                    activity = activity,
                    selected = activity.id == featured?.id,
                    onClick = { onSelect(activity) },
                    onOpen = { onOpenDetail(activity) },
                )
            }
        }
    }
}

@Composable
private fun FeaturedActivity(
    activity: ActivityEntity,
    records: List<RecordEntity>,
    maxHeartRate: Int?,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val color = PulseColors.sportColor(activity.sport, activity.subSport)
    val zones = zoneSummary(records, maxHeartRate)
    PulseCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        activityTitle(activity),
                        style = MaterialTheme.typography.titleMedium,
                        color = PulseColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr("%s · %s", formatDate(activity.startTime), activityPaceLabel(activity)),
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextMuted,
                    )
                }
                Text(
                    text = categoryForSport(activity.sport, activity.subSport).label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(PulseColors.SurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 138.dp, height = 100.dp)
                        .clip(RoundedCornerShape(14.dp)),
                ) {
                    ActivityTrace(
                        records = records,
                        color = color,
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentDescription = if (records.any { it.latitude != null }) {
                            "Route for ${activityTitle(activity)}"
                        } else {
                            "Heart-rate trace for ${activityTitle(activity)}"
                        },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("Distance", formatDistanceKm(activity.distanceMeters), Modifier.weight(1f))
                        TinyStat("Duration", formatElapsedTime(activity.durationSeconds), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("Avg HR", activity.avgHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
                        TinyStat("Calories", activity.calories?.let { "$it kcal" } ?: "—", Modifier.weight(1f))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                Text("Time in heart-rate zones", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                if (zones != null) {
                    ZoneBar(percentages = zones.percentages, contentDescription = "Time in heart-rate zones")
                    ZoneLegend(percentages = zones.percentages)
                } else {
                    ZoneBar(percentages = List(5) { 0f })
                    Text(
                        if (maxHeartRate == null) {
                            "Set your max heart rate to see zones."
                        } else {
                            "No heart-rate samples in this activity."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (maxHeartRate == null) {
                        PrimaryButton("Set max heart rate", onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: ActivityEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
) {
    val color = PulseColors.sportColor(activity.sport, activity.subSport)
    CompactCard(
        modifier = Modifier.fillMaxWidth(),
        selected = selected,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PulseSpacing.lgPlus, vertical = PulseSpacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PulseColors.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DirectionsRun,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activityTitle(activity),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = PulseColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatDate(activity.startTime)} · ${formatDistanceKm(activity.distanceMeters)} · ${formatElapsedTime(activity.durationSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    activity.avgHeartRate?.toString() ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Coral,
                )
                Text("avg bpm", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
            IconButtonRound(
                icon = Icons.Outlined.ChevronRight,
                contentDescription = "Open ${activityTitle(activity)} details",
                onClick = onOpen,
                background = Color.Transparent,
                size = 40.dp,
            )
        }
    }
}
