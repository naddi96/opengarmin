package dev.opengarmin.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class HistoryGranularity { Daily, Weekly, Monthly }

/** One aggregated bucket in a history chart. */
data class HistoryBucket(
    val start: LocalDate,
    val end: LocalDate,
    /** Daily values that were present in this bucket; never zero-filled. */
    val values: List<Double>,
) {
    val average: Double get() = values.average()
    val daysWithData: Int get() = values.size
}

/**
 * Pure history filtering/bucketing for the Trends screen. Kept out of the
 * Composable so it can be unit-tested without an Android runtime.
 */
object WellnessHistory {

    /** Days inside [start]..[end], inclusive, sorted by date. */
    fun daysInRange(
        days: List<WellnessDayEntity>,
        start: LocalDate,
        end: LocalDate,
    ): List<WellnessDayEntity> = days.mapNotNull { day ->
        val date = parse(day.date) ?: return@mapNotNull null
        day.takeIf { !date.isBefore(start) && !date.isAfter(end) }
    }.sortedBy { it.date }

    /** Days with a non-null value for [value], sorted by date. */
    fun daysWithValue(
        days: List<WellnessDayEntity>,
        value: (WellnessDayEntity) -> Double?,
    ): List<Pair<LocalDate, Double>> = days.mapNotNull { day ->
        val date = parse(day.date) ?: return@mapNotNull null
        value(day)?.let { date to it }
    }.sortedBy { it.first }

    fun average(days: List<WellnessDayEntity>, value: (WellnessDayEntity) -> Double?): Double? =
        days.mapNotNull(value).takeIf { it.isNotEmpty() }?.average()

    fun total(days: List<WellnessDayEntity>, value: (WellnessDayEntity) -> Double?): Double? =
        days.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()

    /** Daily for short ranges, weekly up to ~9 months, monthly for longer. */
    fun granularityFor(start: LocalDate, end: LocalDate): HistoryGranularity {
        val span = end.toEpochDay() - start.toEpochDay() + 1
        return when {
            span <= 45 -> HistoryGranularity.Daily
            span <= 270 -> HistoryGranularity.Weekly
            else -> HistoryGranularity.Monthly
        }
    }

    /**
     * Groups the days that have a value into [granularity] buckets. Empty
     * buckets are omitted so charts never imply a zero value for a day the
     * watch did not record.
     */
    fun buckets(
        days: List<WellnessDayEntity>,
        granularity: HistoryGranularity,
        value: (WellnessDayEntity) -> Double?,
    ): List<HistoryBucket> {
        val grouped = LinkedHashMap<LocalDate, MutableList<Double>>()
        for (day in days.sortedBy { it.date }) {
            val date = parse(day.date) ?: continue
            val valueForDay = value(day) ?: continue
            val key = when (granularity) {
                HistoryGranularity.Daily -> date
                HistoryGranularity.Weekly -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                HistoryGranularity.Monthly -> date.withDayOfMonth(1)
            }
            grouped.getOrPut(key) { ArrayList() }.add(valueForDay)
        }
        return grouped.map { (start, values) ->
            val end = when (granularity) {
                HistoryGranularity.Daily -> start
                HistoryGranularity.Weekly -> start.plusDays(6)
                HistoryGranularity.Monthly -> start.with(TemporalAdjusters.lastDayOfMonth())
            }
            HistoryBucket(start = start, end = end, values = values)
        }.sortedBy { it.start }
    }

    fun parse(date: String): LocalDate? =
        runCatching { LocalDate.parse(date) }.getOrNull()
}
