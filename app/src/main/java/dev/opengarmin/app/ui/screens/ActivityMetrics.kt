package dev.opengarmin.app.ui.screens

import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.formatPace
import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.categoryForSport

data class ZoneSummary(
    val maxHeartRate: Int,
    val percentages: List<Float>,
)

fun zoneSummary(records: List<RecordEntity>, maxHeartRate: Int?): ZoneSummary? {
    if (maxHeartRate == null || maxHeartRate !in 100..250) return null
    val heartRates = records.mapNotNull { it.heartRate }
    if (heartRates.isEmpty()) return null
    val counts = IntArray(5)
    heartRates.forEach { hr ->
        val percent = hr.toDouble() / maxHeartRate
        val index = when {
            percent < 0.60 -> 0
            percent < 0.70 -> 1
            percent < 0.80 -> 2
            percent < 0.90 -> 3
            else -> 4
        }
        counts[index]++
    }
    val total = counts.sum().coerceAtLeast(1)
    return ZoneSummary(
        maxHeartRate = maxHeartRate,
        percentages = counts.map { it.toFloat() / total * 100f },
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
