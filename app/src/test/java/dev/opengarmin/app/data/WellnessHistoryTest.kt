package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WellnessHistoryTest {

    @Test
    fun `range filtering is inclusive and never zero-fills missing days`() {
        val days = listOf(
            day("2026-10-10", steps = 100),
            day("2026-10-12", steps = 300),
            day("2026-10-13", steps = 400),
        )

        val inRange = WellnessHistory.daysInRange(
            days,
            LocalDate.of(2026, 10, 11),
            LocalDate.of(2026, 10, 13),
        )

        assertEquals(listOf("2026-10-12", "2026-10-13"), inRange.map { it.date })
        assertEquals(350.0, WellnessHistory.average(inRange) { it.steps.toDouble() }!!, 0.01)
    }

    @Test
    fun `granularity switches with the span`() {
        val start = LocalDate.of(2026, 10, 1)
        assertEquals(HistoryGranularity.Daily, WellnessHistory.granularityFor(start, start.plusDays(6)))
        assertEquals(HistoryGranularity.Weekly, WellnessHistory.granularityFor(start, start.plusDays(60)))
        assertEquals(HistoryGranularity.Monthly, WellnessHistory.granularityFor(start, start.plusDays(364)))
    }

    @Test
    fun `weekly buckets average only the days that have data`() {
        val days = listOf(
            day("2026-10-12", steps = 100), // Monday
            day("2026-10-14", steps = 300),
            day("2026-10-20", steps = 500), // next Monday
        )

        val buckets = WellnessHistory.buckets(days, HistoryGranularity.Weekly) { it.steps.toDouble() }

        assertEquals(2, buckets.size)
        assertEquals(listOf(100.0, 300.0), buckets[0].values)
        assertEquals(200.0, buckets[0].average, 0.01)
        assertEquals(LocalDate.of(2026, 10, 12), buckets[0].start)
        assertEquals(500.0, buckets[1].average, 0.01)
    }

    @Test
    fun `monthly buckets group across month boundaries`() {
        val days = listOf(
            day("2026-10-31", steps = 100),
            day("2026-11-01", steps = 300),
        )

        val buckets = WellnessHistory.buckets(days, HistoryGranularity.Monthly) { it.steps.toDouble() }

        assertEquals(2, buckets.size)
        assertEquals(LocalDate.of(2026, 10, 1), buckets[0].start)
        assertEquals(LocalDate.of(2026, 10, 31), buckets[0].end)
        assertTrue(buckets.all { it.daysWithData == 1 })
    }

    private fun day(date: String, steps: Int) = WellnessDayEntity(
        date = date,
        steps = steps,
        distanceMeters = 0.0,
        activeCalories = 0,
        activeSeconds = 0,
        restingHeartRate = null,
        avgHeartRate = null,
        maxHeartRate = null,
        stressAvg = null,
        spo2Avg = null,
        respirationAvg = null,
        updatedAt = 0,
    )
}
