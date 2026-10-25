package dev.opengarmin.app.data

import dev.opengarmin.app.util.formatHoursMinutes
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
     * Newest valid resting heart rate. Days without a resting HR are skipped so
     * a partially imported day does not erase the last value from the watch.
     */
    fun latestRestingHeartRate(days: List<WellnessDayEntity>): Int? =
        days
            .sortedByDescending { it.date }
            .firstNotNullOfOrNull { it.restingHeartRate?.takeIf { hr -> hr in 30..120 } }

    /**
     * Sleep time from the recorded stages. Returns null when no stage was
     * imported so the UI can show an honest empty state instead of 0 h.
     */
    fun sleepSeconds(day: WellnessDayEntity?): Int? {
        if (day == null) return null
        val stages = listOfNotNull(day.deepSleepSeconds, day.lightSleepSeconds, day.remSleepSeconds)
        if (stages.isNotEmpty()) return stages.sum()
        // The FR255's daily Firstbeat file carries the in-bed window and the
        // awake duration, but not the individual stages. Use that to show a
        // real duration on days without a SLEEP_DATA file instead of "—".
        val start = day.sleepStart ?: return null
        val end = day.sleepEnd ?: return null
        if (end <= start) return null
        val awake = day.awakeSleepSeconds ?: 0
        return (end - start).toInt().minus(awake).takeIf { it > 0 }
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

    fun formatSleepDuration(seconds: Int): String = formatHoursMinutes(seconds.toLong())

    fun formatHours(hours: Double): String {
        val totalMinutes = (hours * 60.0).toInt().coerceAtLeast(0)
        return formatSleepDuration(totalMinutes * 60)
    }
}
