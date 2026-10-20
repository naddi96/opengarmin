package dev.opengarmin.app.ui.screens

import dev.opengarmin.app.data.RecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ActivityMetricsTest {

    @Test
    fun `heart rate zones use the configured max heart rate`() {
        val records = listOf(
            record(1_000L, 100),
            record(1_001L, 120),
            record(1_002L, 140),
            record(1_003L, 160),
            record(1_004L, 180),
            record(1_005L, 180),
        )

        val zones = heartRateZoneSet(records, maxHeartRate = 200)

        assertNotNull(zones)
        assertFalse(zones!!.fromWatch)
        assertEquals(200, zones.maxHeartRate)
        assertEquals(5, zones.zones.size)
        assertEquals(5.0, zones.totalSeconds, 0.001)
        zones.zones.forEach { zone ->
            assertEquals(20f, zone.percent, 0.001f)
        }
        assertEquals(100, zones.zones[0].lowBoundary)
        assertEquals(120, zones.zones[0].highBoundary)
        assertEquals(180, zones.zones[4].lowBoundary)
        assertEquals(200, zones.zones[4].highBoundary)
    }

    @Test
    fun `zone summary reflects the same configured zones`() {
        val records = (0 until 5).map { index ->
            record(1_000L + index, 120 + index * 20)
        }

        val summary = zoneSummary(records, maxHeartRate = 200)

        assertNotNull(summary)
        assertEquals(200, summary!!.maxHeartRate)
        assertEquals(5, summary.percentages.size)
        assertEquals(100f, summary.percentages.sum(), 0.001f)
    }

    private fun record(timestamp: Long, heartRate: Int) = RecordEntity(
        activityId = 1L,
        timestamp = timestamp,
        distanceMeters = null,
        heartRate = heartRate,
        speedMps = null,
        altitudeMeters = null,
        cadence = null,
    )
}
