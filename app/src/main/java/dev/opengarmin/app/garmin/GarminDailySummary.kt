package dev.opengarmin.app.garmin

/**
 * One day of Garmin Connect's daily wellness summary. Values that the account
 * does not expose stay null; the importer never zero-fills existing rows.
 */
data class GarminDailySummary(
    /** Local calendar date, YYYY-MM-DD. */
    val date: String,
    val steps: Int,
    val distanceMeters: Double,
    val activeCalories: Int,
    val activeSeconds: Int,
    val restingHeartRate: Int?,
    val stressAvg: Int?,
    val bodyBatteryMin: Int?,
    val bodyBatteryMax: Int?,
    val bodyBatteryLatest: Int?,
)
