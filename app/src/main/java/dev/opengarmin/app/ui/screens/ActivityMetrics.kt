package dev.opengarmin.app.ui.screens

import androidx.compose.runtime.Composable
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.data.ActivityZoneSet
import dev.opengarmin.app.data.HeartRateZoneSample
import dev.opengarmin.app.data.HeartRateZones
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.util.formatPaceWithUnit
import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.categoryForSport

data class ZoneSummary(
    val maxHeartRate: Int,
    val percentages: List<Float>,
)

/**
 * Builds five heart-rate zones from the max heart rate configured in Settings.
 * The shared [HeartRateZones] calculation is also used by the coach tools.
 */
fun heartRateZoneSet(records: List<RecordEntity>, maxHeartRate: Int?): ActivityZoneSet? =
    HeartRateZones.zones(
        samples = records.map { HeartRateZoneSample(it.timestamp, it.heartRate) },
        maxHeartRate = maxHeartRate,
    )

fun zoneSummary(records: List<RecordEntity>, maxHeartRate: Int?): ZoneSummary? =
    heartRateZoneSet(records, maxHeartRate)?.let { set ->
        ZoneSummary(
            maxHeartRate = set.maxHeartRate ?: maxHeartRate ?: return null,
            percentages = set.zones.map { it.percent },
        )
    }

@Composable
fun activityPaceLabel(activity: ActivityEntity): String = when (
    categoryForSport(activity.sport, activity.subSport)
) {
    SportCategory.Ride -> {
        if (activity.durationSeconds > 0 && activity.distanceMeters > 0) {
            tr(
                "Avg speed %s km/h",
                "%.1f".format(activity.distanceMeters / activity.durationSeconds * 3.6),
            )
        } else {
            tr("Recorded on watch")
        }
    }
    SportCategory.Cardio -> tr("Cardio session")
    SportCategory.Strength -> tr("Strength session")
    SportCategory.Walk, SportCategory.Run ->
        if (activity.distanceMeters > 0) {
            tr("Avg pace %s", formatPaceWithUnit(activity.durationSeconds, activity.distanceMeters))
        } else {
            tr("Recorded on watch")
        }
    else -> tr("Recorded on watch")
}

fun activityTitle(activity: ActivityEntity): String = when (
    categoryForSport(activity.sport, activity.subSport)
) {
    SportCategory.Run -> "Run"
    SportCategory.Ride -> "Ride"
    SportCategory.Cardio -> "Cardio"
    SportCategory.Strength -> "Strength"
    SportCategory.Walk -> "Walk"
    else -> dev.opengarmin.app.coach.sportLabel(activity.sport)
}
