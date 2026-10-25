package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WellnessMetricsTest {

    @Test
    fun `latest day ignores a future rollover row`() {
        val days = listOf(
            day("2026-10-19"),
            day("2026-10-18"),
            day("2026-10-17"),
        )

        assertEquals("2026-10-18", WellnessMetrics.latestDay(days, LocalDate.of(2026, 10, 18))?.date)
    }

    @Test
    fun `latest day falls back to the newest row when everything is in the future`() {
        val days = listOf(day("2026-10-19"), day("2026-10-20"))

        assertEquals("2026-10-20", WellnessMetrics.latestDay(days, LocalDate.of(2026, 10, 18))?.date)
    }

    @Test
    fun `resting heart rate uses the newest valid day`() {
        val days = listOf(
            day("2026-10-17").copy(restingHeartRate = 49),
            day("2026-10-18").copy(restingHeartRate = 51),
            day("2026-10-19"),
            day("2026-10-20").copy(restingHeartRate = 10),
        )

        assertEquals(51, WellnessMetrics.latestRestingHeartRate(days))
    }

    @Test
    fun `resting heart rate is null without a valid value`() {
        assertNull(WellnessMetrics.latestRestingHeartRate(listOf(day("2026-10-18"))))
    }

    @Test
    fun `sleep duration sums recorded stages and stays null with no stages`() {
        val day = day("2026-10-18").copy(
            deepSleepSeconds = 3600,
            lightSleepSeconds = 7200,
            remSleepSeconds = 1800,
        )

        assertEquals(12_600, WellnessMetrics.sleepSeconds(day))
        assertNull(WellnessMetrics.sleepSeconds(day("2026-10-18")))
    }

    @Test
    fun `sleep duration falls back to the firstbeat window minus awake time`() {
        val day = day("2026-10-18").copy(
            sleepStart = 1_000_000,
            sleepEnd = 1_025_400,
            awakeSleepSeconds = 888,
        )

        assertEquals(24_512, WellnessMetrics.sleepSeconds(day))
    }

    @Test
    fun `hrv falls back to recorded samples when the summary is missing`() {
        val day = day("2026-10-18")
        val samples = listOf(
            sample("hrv", 44.0),
            sample("hrv", 48.0),
            sample("hr", 60.0),
        )

        assertEquals(46.0, WellnessMetrics.hrvMs(day, samples)!!, 0.01)
    }

    @Test
    fun `hrv summary wins over samples`() {
        val day = day("2026-10-18").copy(hrvLastNight = 52.0)

        assertEquals(52.0, WellnessMetrics.hrvMs(day, listOf(sample("hrv", 40.0)))!!, 0.01)
    }

    private fun day(date: String) = WellnessDayEntity(
        date = date,
        steps = 0,
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

    private fun sample(kind: String, value: Double) = WellnessSampleEntity(
        dayId = 1,
        timestamp = 0,
        kind = kind,
        value = value,
    )
}
