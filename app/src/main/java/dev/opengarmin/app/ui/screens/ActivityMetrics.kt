package dev.opengarmin.app.ui.screens

import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityZoneSet
import dev.opengarmin.app.data.HeartRateZoneSample
import dev.opengarmin.app.data.HeartRateZones
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.formatPace
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

fun activityPaceLabel(activity: ActivityEntity): String = when (
    categoryForSport(activity.sport, activity.subSport)
) {
    SportCategory.Ride -> {
        if (activity.durationSeconds > 0 && activity.distanceMeters > 0) {
            "Avg speed %.1f km/h".format(activity.distanceMeters / activity.durationSeconds * 3.6)
        } else {
            "Recorded on watch"
        }
    }
    SportCategory.Cardio -> "Cardio session"
    SportCategory.Strength -> "Strength session"
    SportCategory.Walk, SportCategory.Run ->
        if (activity.distanceMeters > 0) {
            "Avg pace ${formatPace(activity.durationSeconds, activity.distanceMeters)}"
        } else {
            "Recorded on watch"
        }
    else -> "Recorded on watch"
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
