package dev.opengarmin.app.data

import java.time.LocalDate

/**
 * Derived wellness values and the selection rules shared by the screens.
 *
 * A watch can occasionally write a monitoring rollover for the next local day
 * (or its clock can be slightly ahead). That row has no real health data and
 * must never shadow today's row, so screens select the newest day that is not
 * after the phone's local date.
 */
object WellnessMetrics {

    /** Newest day that is not after [today]; falls back to the newest row. */
    fun latestDay(days: List<WellnessDayEntity>, today: LocalDate = LocalDate.now()): WellnessDayEntity? {
        val todayText = today.toString()
        return days.filter { it.date <= todayText }.maxByOrNull { it.date }
            ?: days.maxByOrNull { it.date }
    }

    /**
     * Sleep time from the recorded stages. Returns null when no stage was
     * imported so the UI can show an honest empty state instead of 0 h.
     */
    fun sleepSeconds(day: WellnessDayEntity?): Int? {
        if (day == null) return null
        val stages = listOfNotNull(day.deepSleepSeconds, day.lightSleepSeconds, day.remSleepSeconds)
        return stages.takeIf { it.isNotEmpty() }?.sum()
    }

    /**
     * Overnight HRV in milliseconds. Uses the status summary when present and
     * falls back to `hrv_value` samples, which some watches expose without an
     * `hrv_status_summary` message.
     */
    fun hrvMs(day: WellnessDayEntity?, samples: List<WellnessSampleEntity> = emptyList()): Double? {
        if (day == null) return null
        day.hrvLastNight?.takeIf { it > 0.0 }?.let { return it }
        val values = samples.filter { it.kind == "hrv" && it.value > 0.0 }.map { it.value }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    /** 7-day HRV average, using stored summaries and the sample fallback. */
    fun hrvWeeklyAverage(
        days: List<WellnessDayEntity>,
        samples: List<WellnessSampleEntity> = emptyList(),
        today: LocalDate = LocalDate.now(),
        sampleDate: String? = today.toString(),
    ): Double? {
        val recent = days
            .filter { it.date <= today.toString() }
            .sortedByDescending { it.date }
            .take(7)
        val daily = recent.mapNotNull { day ->
            hrvMs(day, if (day.date == sampleDate) samples else emptyList())
        }
        if (daily.size >= 2) return daily.average()
        return recent.firstNotNullOfOrNull { it.hrvWeeklyAvg?.takeIf { value -> value > 0.0 } }
    }

    fun formatSleepDuration(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    fun formatHours(hours: Double): String {
        val totalMinutes = (hours * 60.0).toInt().coerceAtLeast(0)
        return formatSleepDuration(totalMinutes * 60)
    }
}
