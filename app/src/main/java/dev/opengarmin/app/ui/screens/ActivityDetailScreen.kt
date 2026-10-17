package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.HeartRateChart
import dev.opengarmin.app.RouteMap
import dev.opengarmin.app.ElevationChart
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.coach.categoryForSport
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.LapEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.formatDate
import dev.opengarmin.app.formatDistance
import dev.opengarmin.app.formatDuration
import dev.opengarmin.app.formatPace
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.TinyStat
import dev.opengarmin.app.ui.components.ZoneBar
import dev.opengarmin.app.ui.components.ZoneLegend
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing

@Composable
fun ActivityDetailScreen(
    activity: ActivityEntity,
    records: List<RecordEntity>,
    laps: List<LapEntity>,
    dynamics: RunningDynamicsSummary?,
    gps: GpsSummary?,
    maxHeartRate: Int?,
    onBack: () -> Unit,
    onExploreData: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zones = zoneSummary(records, maxHeartRate)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            IconButtonRound(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "Back to activities",
                onClick = onBack,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(activityTitle(activity), style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
                Text(
                    "${formatDate(activity.startTime)} · ${categoryForSport(activity.sport).label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
            PrimaryButton("All data", onExploreData)
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    TinyStat("Distance", formatDistance(activity.distanceMeters), Modifier.weight(1f))
                    TinyStat("Duration", formatDuration(activity.durationSeconds), Modifier.weight(1f))
                    TinyStat("Avg HR", activity.avgHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
                    TinyStat("Calories", activity.calories?.let { "$it kcal" } ?: "—", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    TinyStat("Avg pace", formatPace(activity.durationSeconds, activity.distanceMeters), Modifier.weight(1f))
                    TinyStat("Max HR", activity.maxHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
                    TinyStat("Ascent", activity.ascentMeters?.let { "$it m" } ?: "—", Modifier.weight(1f))
                    TinyStat("Descent", activity.descentMeters?.let { "$it m" } ?: "—", Modifier.weight(1f))
                }
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Track", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                RouteMap(records, Modifier.fillMaxWidth().height(260.dp))
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Heart rate", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                HeartRateChart(records)
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Elevation", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                ElevationChart(records)
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Running dynamics", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                if (dynamics?.available == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("Cadence", dynamics.cadenceSpm?.let { "%.0f spm".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Ground contact", dynamics.groundContactTimeMs?.let { "%.0f ms".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Vertical osc.", dynamics.verticalOscillationMm?.let { "%.1f mm".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Vertical ratio", dynamics.verticalRatioPct?.let { "%.1f%%".format(it) } ?: "—", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("Stride length", dynamics.strideLengthMm?.let { "%.2f m".format(it / 1000.0) } ?: "—", Modifier.weight(1f))
                        TinyStat("Power", dynamics.powerW?.let { "$it W" } ?: "—", Modifier.weight(1f))
                        TinyStat("Training effect", dynamics.trainingEffect?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Anaerobic TE", dynamics.anaerobicTrainingEffect?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                        TinyStat("Avg km/h", dynamics.avgSpeedKmh?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Max km/h", dynamics.maxSpeedKmh?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Moving time", dynamics.movingTimeSeconds?.let { formatDuration(it) } ?: "—", Modifier.weight(1f))
                        TinyStat("Load peak", dynamics.trainingLoadPeak?.let { "%.0f".format(it) } ?: "—", Modifier.weight(1f))
                    }
                } else {
                    Text(
                        "No running-dynamics fields were recorded for this activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                if (gps?.available == true) {
                    Text(
                        "GPS: ${gps.pointCount} points · start ${"%.5f".format(gps.start?.latitude ?: 0.0)}, " +
                            "${"%.5f".format(gps.start?.longitude ?: 0.0)} → end " +
                            "${"%.5f".format(gps.end?.latitude ?: 0.0)}, ${"%.5f".format(gps.end?.longitude ?: 0.0)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                } else {
                    Text("No GPS points in this activity.", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                }
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Time in heart-rate zones", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                if (zones != null) {
                    ZoneBar(percentages = zones.percentages, contentDescription = "Time in heart-rate zones")
                    ZoneLegend(percentages = zones.percentages)
                    Text(
                        "Zones based on max HR ${zones.maxHeartRate} bpm.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                } else {
                    Text(
                        if (maxHeartRate == null) {
                            "Add your max heart rate in Settings to calculate zones. No estimate is invented."
                        } else {
                            "This activity has no heart-rate samples, so zones cannot be calculated."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    if (maxHeartRate == null) {
                        PrimaryButton("Set max heart rate", onOpenSettings)
                    }
                }
            }
        }

        if (laps.isNotEmpty()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Text("Laps", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Row {
                        Text("#", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, modifier = Modifier.weight(0.35f))
                        Text("Distance", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, modifier = Modifier.weight(1f))
                        Text("Time", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, modifier = Modifier.weight(1f))
                        Text("Pace", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, modifier = Modifier.weight(1f))
                        Text("HR", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted, modifier = Modifier.weight(0.7f))
                    }
                    laps.forEach { lap ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${lap.lapIndex + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(0.35f),
                            )
                            Text(
                                formatDistance(lap.distanceMeters),
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatDuration(lap.durationSeconds),
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatPace(lap.durationSeconds, lap.distanceMeters),
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                lap.avgHeartRate?.toString() ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.Text,
                                modifier = Modifier.weight(0.7f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(PulseSpacing.xl))
    }
}
