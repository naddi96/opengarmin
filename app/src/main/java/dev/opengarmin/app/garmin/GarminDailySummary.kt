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
    /** Overnight sleep reported by Garmin for this wake-up date. */
    val sleep: GarminSleepSummary? = null,
    /** Overnight HRV and baseline reported by Garmin for this date. */
    val hrv: GarminHrvSummary? = null,
)

/**
 * Garmin Connect's sleep aggregate for one night. Garmin assigns a night to
 * the wake-up date, the same convention as the watch FIT importer.
 */
data class GarminSleepSummary(
    val date: String,
    /** Unix seconds; null when Garmin has no confirmed sleep window. */
    val start: Long?,
    /** Unix seconds. */
    val end: Long?,
    val score: Int?,
    val quality: String?,
    val deepSeconds: Int?,
    val lightSeconds: Int?,
    val remSeconds: Int?,
    val awakeSeconds: Int?,
    val avgSpo2: Int?,
    val lowestSpo2: Int?,
    val avgRespiration: Double?,
)

/**
 * Garmin Connect's overnight HRV summary. Values are the same rMSSD-style
 * numbers the watch writes in its HRV_STATUS_SUMMARY FIT message.
 */
data class GarminHrvSummary(
    val date: String,
    val lastNightAvg: Double?,
    val weeklyAvg: Double?,
    val status: String?,
    val baselineLow: Double?,
    val baselineHigh: Double?,
    val baselineLowUpper: Double?,
)
