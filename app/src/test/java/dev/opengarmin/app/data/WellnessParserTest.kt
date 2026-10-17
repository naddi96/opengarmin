package dev.opengarmin.app.data

import dev.opengarmin.protocol.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WellnessParserTest {
    private val zone = ZoneId.of("Europe/Rome")

    @Test
    fun `parses monitoring, stress, body battery and sensors`() {
        val messages = listOf(
            monitoring(epoch(2026, 10, 17, 8, 0), cycles16 = 1_000, distance = 2_500.0, activeSeconds = 600.0, calories = 50),
            monitoring(epoch(2026, 10, 17, 12, 0), cycles16 = 2_000, distance = 5_000.0, activeSeconds = 1_200.0, calories = 100),
            message(
                "monitoring_hr_data",
                epoch(2026, 10, 17, 12, 0),
                field(0, "current_day_resting_heart_rate", 57),
            ),
            message(
                "stress_level",
                epoch(2026, 10, 17, 9, 0),
                field(0, "stress_level_value", 20),
                field(3, "body_battery", 70),
            ),
            message(
                "stress_level",
                epoch(2026, 10, 17, 10, 0),
                field(0, "stress_level_value", 30),
                field(3, "body_battery", 60),
            ),
            message("spo2_data", epoch(2026, 10, 17, 9, 0), field(0, "reading_spo2", 97)),
            message("respiration_rate", epoch(2026, 10, 17, 9, 0), field(0, "respiration_rate", 14.5)),
        )

        val day = WellnessParser.parse(messages, zone).single()

        assertEquals("2026-10-17", day.date)
        assertEquals(4_000, day.steps)
        assertEquals(5_000.0, day.distanceMeters, 0.01)
        assertEquals(1_200, day.activeSeconds)
        assertEquals(100, day.activeCalories)
        assertEquals(57, day.restingHeartRate)
        assertEquals(25, day.stressAvg)
        assertEquals(70, day.bodyBatteryStart)
        assertEquals(60, day.bodyBatteryLatest)
        assertEquals(60, day.bodyBatteryMin)
        assertEquals(70, day.bodyBatteryMax)
        assertEquals(2, day.samples.count { it.kind == "body_battery" })
        assertEquals(1, day.samples.count { it.kind == "spo2" })
        assertNull(day.sleepScore)
    }

    @Test
    fun `assigns overnight sleep and hrv to the wake date`() {
        val messages = listOf(
            sleep(epoch(2026, 10, 17, 23, 20), 3),
            sleep(epoch(2026, 10, 18, 0, 0), 2),
            sleep(epoch(2026, 10, 18, 3, 0), 4),
            sleep(epoch(2026, 10, 18, 6, 50), 1),
            message(
                "sleep_assessment",
                epoch(2026, 10, 18, 6, 54),
                field(6, "overall_sleep_score", 82),
            ),
            message(
                "hrv_status_summary",
                epoch(2026, 10, 18, 6, 55),
                field(0, "weekly_average", 49.0),
                field(1, "last_night_average", 48.0),
                field(4, "baseline_balanced_lower", 44.0),
                field(5, "baseline_balanced_upper", 55.0),
                field(6, "status", 4),
            ),
        )

        val day = WellnessParser.parse(messages, zone).single()

        assertEquals("2026-10-18", day.date)
        assertEquals(epoch(2026, 10, 17, 23, 20), day.sleepStart)
        assertEquals(epoch(2026, 10, 18, 6, 54), day.sleepEnd)
        assertEquals(82, day.sleepScore)
        assertEquals("Good", day.sleepQuality)
        assertEquals(40 * 60, day.deepSleepSeconds)
        assertEquals(3 * 3600, day.lightSleepSeconds)
        assertEquals(3 * 3600 + 50 * 60, day.remSleepSeconds)
        assertEquals(4 * 60, day.awakeSleepSeconds)
        assertEquals(48.0, day.hrvLastNight!!, 0.01)
        assertEquals(49.0, day.hrvWeeklyAvg!!, 0.01)
        assertEquals("Balanced", day.hrvStatus)
        assertEquals(4, day.sleepStages.size)
        assertNotNull(day.sleepStages.firstOrNull { it.stage == "deep" })
    }

    private fun monitoring(
        timestamp: Long,
        cycles16: Int,
        distance: Double,
        activeSeconds: Double,
        calories: Int,
    ) = message(
        "monitoring",
        timestamp,
        field(5, "activity_type", 0),
        field(9, "cycles_16", cycles16),
        field(2, "distance", distance),
        field(4, "active_time", activeSeconds),
        field(19, "active_calories", calories),
    )

    private fun sleep(timestamp: Long, stage: Int) =
        message("sleep_level", timestamp, field(0, "sleep_level", stage))

    private fun message(name: String, timestamp: Long, vararg fields: Fit.FieldValue) =
        Fit.Message(0, name, timestamp, fields.toList())

    private fun field(number: Int, name: String, value: Any?) =
        Fit.FieldValue(number, name, value, null)

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toEpochSecond()
}
