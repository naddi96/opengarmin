package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class HealthMetricsTest {

    @Test
    fun `sleep need follows the age recommendation upper bound`() {
        val today = LocalDate.of(2026, 10, 22)
        assertEquals(9.0, HealthMetrics.sleepNeedHours(1990, today), 0.01)
        assertEquals(10.0, HealthMetrics.sleepNeedHours(2010, today), 0.01)
        assertEquals(8.0, HealthMetrics.sleepNeedHours(1950, today), 0.01)
        assertEquals(8.0, HealthMetrics.sleepNeedHours(null, today), 0.01)
    }

    @Test
    fun `overnight minutes keep bedtime and wake time comparable`() {
        assertEquals(660, HealthMetrics.minutesSinceNoon(LocalTime.of(23, 0)))
        assertEquals(732, HealthMetrics.minutesSinceNoon(LocalTime.of(0, 12)))
        assertEquals(1212, HealthMetrics.minutesSinceNoon(LocalTime.of(8, 12)))
        assertEquals("23:00", HealthMetrics.labelFromNoonMinutes(660))
        assertEquals("01:03", HealthMetrics.labelFromNoonMinutes(783))
        assertEquals("08:12", HealthMetrics.labelFromNoonMinutes(1212))
    }

    @Test
    fun `stress durations use the median sample interval`() {
        val start = 1_000_000L
        val samples = listOf(
            stress(start, 10.0),
            stress(start + 180, 30.0),
            stress(start + 360, 60.0),
            stress(start + 540, 90.0),
        )

        val breakdown = HealthMetrics.stressBreakdown(samples)!!

        assertEquals(180L, breakdown.restSeconds)
        assertEquals(180L, breakdown.lowSeconds)
        assertEquals(180L, breakdown.mediumSeconds)
        assertEquals(180L, breakdown.highSeconds)
        assertEquals(25, breakdown.percentFor(StressLevel.REST))
        assertEquals(720L, breakdown.totalSeconds)
    }

    @Test
    fun `stress buckets average readings inside fixed windows`() {
        val start = 1_500_000L
        val samples = listOf(
            stress(start, 10.0),
            stress(start + 120, 20.0),
            stress(start + 960, 60.0),
            stress(start + 1_020, 80.0),
        )

        val buckets = HealthMetrics.stressBuckets(samples, bucketSeconds = 900)

        assertEquals(2, buckets.size)
        assertEquals(15.0, buckets[0].value, 0.01)
        assertEquals(70.0, buckets[1].value, 0.01)
        assertEquals(start + 900, buckets[1].timestamp)
    }

    @Test
    fun `longest rest run counts consecutive low stress readings`() {
        val start = 2_000_000L
        val samples = listOf(
            stress(start, 10.0),
            stress(start + 180, 15.0),
            stress(start + 360, 15.0),
            stress(start + 540, 80.0),
            stress(start + 720, 10.0),
        )

        assertEquals(540L, HealthMetrics.longestRestSeconds(samples))
    }

    @Test
    fun `body battery days omit days without a reading`() {
        val days = listOf(
            day("2026-10-20", bodyBatteryMax = 80, bodyBatteryMin = 20),
            day("2026-10-21"),
            day("2026-10-22", bodyBatteryMax = 69, bodyBatteryMin = 25),
        )

        val result = HealthMetrics.bodyBatteryDays(days, LocalDate.of(2026, 10, 22), 7)

        assertEquals(listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22)), result.map { it.date })
        assertEquals(80, result.first().high)
        assertEquals(25, result.last().low)
    }

    @Test
    fun `vo2 band uses age and sex reference values`() {
        val today = LocalDate.of(2026, 10, 22)
        val male = HealthMetrics.vo2Reference(53.0, 1990, "male", today)!!
        assertEquals(Vo2Band.EXCELLENT, male.band)

        val female = HealthMetrics.vo2Reference(53.0, 1990, "female", today)!!
        assertEquals(Vo2Band.SUPERIOR, female.band)

        assertNull(HealthMetrics.vo2Reference(53.0, 1990, null, today))
        assertNull(HealthMetrics.vo2Reference(53.0, null, "male", today))
    }

    @Test
    fun `monthly sleep buckets average only recorded nights`() {
        val nights = listOf(
            night("2026-09-30", score = 60),
            night("2026-10-01", score = 70),
            night("2026-10-02", score = 80),
        )

        val buckets = HealthMetrics.monthlySleepBuckets(nights)

        assertEquals(2, buckets.size)
        assertEquals(60.0, buckets[0].score!!, 0.01)
        assertEquals(75.0, buckets[1].score!!, 0.01)
        assertEquals(2, buckets[1].nights)
    }

    @Test
    fun `hrv range counts values inside the baseline including status fallback`() {
        val nights = listOf(
            HrvNight(LocalDate.of(2026, 10, 20), 36.0, "Balanced", 31.0, 44.0),
            HrvNight(LocalDate.of(2026, 10, 21), 28.0, "Low", 31.0, 44.0),
            HrvNight(LocalDate.of(2026, 10, 22), 42.0, "Balanced", 31.0, 44.0),
        )

        assertEquals(2, HealthMetrics.hrvInRangeCount(nights))
        assertEquals(1, HealthMetrics.currentInRangeStreak(nights))
    }

    @Test
    fun `hrv status separates an unbalanced dip from a low value`() {
        val date = LocalDate.of(2026, 10, 24)
        val unbalanced = HrvNight(
            date = date,
            value = 31.0,
            status = null,
            baselineLow = 32.0,
            baselineHigh = 45.0,
            weeklyAverage = 30.0,
            baselineLowUpper = 28.0,
        )
        val low = unbalanced.copy(weeklyAverage = 27.0)
        val balanced = unbalanced.copy(weeklyAverage = 39.0)

        assertEquals("Unbalanced", HealthMetrics.effectiveHrvStatus(unbalanced))
        assertEquals("Low", HealthMetrics.effectiveHrvStatus(low))
        assertEquals("Balanced", HealthMetrics.effectiveHrvStatus(balanced))
    }

    @Test
    fun `hrv range follows the weekly average when a summary provides one`() {
        val date = LocalDate.of(2026, 10, 24)
        val night = HrvNight(
            date = date,
            value = 31.0,
            status = null,
            baselineLow = 32.0,
            baselineHigh = 45.0,
            weeklyAverage = 39.0,
        )

        assertEquals(1, HealthMetrics.hrvInRangeCount(listOf(night)))
        assertEquals(1, HealthMetrics.currentInRangeStreak(listOf(night)))
    }

    @Test
    fun `hrv nights prefer the watch weekly average and fall back to a trailing mean`() {
        val days = listOf(
            dayWithHrv("2026-10-18", 31.0),
            dayWithHrv("2026-10-19", 42.0),
            dayWithHrv("2026-10-20", 35.0, weekly = 39.0),
        )

        val nights = HealthMetrics.hrvNights(days, LocalDate.of(2026, 10, 20), 3)

        assertEquals(listOf(31.0, 36.5, 39.0), nights.map { it.weeklyAverage })
    }

    @Test
    fun `hrv nights estimate a baseline when the watch reports none`() {
        val start = LocalDate.of(2026, 9, 1)
        val days = (0..30).map { offset ->
            dayWithHrv(start.plusDays(offset.toLong()).toString(), 28.0 + (offset % 10))
        }

        val night = HealthMetrics.hrvNights(days, start.plusDays(30), 1).single()

        assertTrue(night.baselineEstimated)
        assertTrue(night.baselineLow != null)
        assertTrue(night.baselineHigh != null)
        assertTrue(night.baselineLowUpper != null)
        assertTrue(HealthMetrics.effectiveHrvStatus(night) != null)
    }

    @Test
    fun `body battery delta uses the samples around an activity`() {
        val start = 3_000_000L
        val samples = listOf(
            bb(start, 60.0),
            bb(start + 600, 52.0),
            bb(start + 1200, 48.0),
            bb(start + 3000, 44.0),
        )

        assertEquals(-12, HealthMetrics.bodyBatteryDelta(samples, start, start + 1200))
        assertNull(HealthMetrics.bodyBatteryDelta(samples, start + 5000, start + 6000))
    }

    @Test
    fun `vo2 window filters by timestamp`() {
        val now = 10_000_000L
        val history = listOf(
            TrainingPoint(now - 200 * 86_400L, 50.0),
            TrainingPoint(now - 10 * 86_400L, 53.0),
        )

        assertEquals(listOf(53.0), HealthMetrics.vo2HistoryInWindow(history, now, 28).map { it.value })
        assertTrue(HealthMetrics.vo2HistoryInWindow(history, now, 0).size == 1)
    }

    private fun stress(timestamp: Long, value: Double) = WellnessSampleEntity(
        dayId = 1,
        timestamp = timestamp,
        kind = "stress",
        value = value,
    )

    private fun bb(timestamp: Long, value: Double) = WellnessSampleEntity(
        dayId = 1,
        timestamp = timestamp,
        kind = "body_battery",
        value = value,
    )

    private fun day(
        date: String,
        bodyBatteryMax: Int? = null,
        bodyBatteryMin: Int? = null,
    ) = WellnessDayEntity(
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
        bodyBatteryMax = bodyBatteryMax,
        bodyBatteryMin = bodyBatteryMin,
    )

    private fun dayWithHrv(date: String, hrv: Double, weekly: Double? = null) = WellnessDayEntity(
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
        hrvLastNight = hrv,
        hrvWeeklyAvg = weekly,
    )

    private fun night(date: String, score: Int) = SleepNight(
        date = LocalDate.parse(date),
        score = score,
        quality = null,
        asleepSeconds = null,
        inBedSeconds = null,
        deepSeconds = null,
        lightSeconds = null,
        remSeconds = null,
        awakeSeconds = null,
        bedMinutes = null,
        wakeMinutes = null,
        lowestHr = null,
        restingHr = null,
        hrvMs = null,
        spo2 = null,
        respiration = null,
    )
}
