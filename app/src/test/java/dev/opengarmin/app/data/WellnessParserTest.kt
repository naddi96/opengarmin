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
            sleepEvent(epoch(2026, 10, 17, 23, 20), 0),
            sleep(epoch(2026, 10, 17, 23, 50), 3),
            sleep(epoch(2026, 10, 18, 0, 20), 2),
            sleep(epoch(2026, 10, 18, 1, 0), 4),
            sleep(epoch(2026, 10, 18, 3, 0), 2),
            sleep(epoch(2026, 10, 18, 3, 40), 3),
            sleep(epoch(2026, 10, 18, 4, 10), 4),
            sleep(epoch(2026, 10, 18, 4, 15), 1),
            sleep(epoch(2026, 10, 18, 5, 0), 2),
            sleep(epoch(2026, 10, 18, 6, 50), 4),
            sleepEvent(epoch(2026, 10, 18, 6, 54), 1),
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
        assertEquals((30 + 40) * 60, day.deepSleepSeconds)
        assertEquals((30 + 120 + 45) * 60, day.lightSleepSeconds)
        assertEquals((40 + 30 + 110) * 60, day.remSleepSeconds)
        assertEquals((5 + 4) * 60, day.awakeSleepSeconds)
        assertEquals(48.0, day.hrvLastNight!!, 0.01)
        assertEquals(49.0, day.hrvWeeklyAvg!!, 0.01)
        assertEquals("Balanced", day.hrvStatus)
        assertEquals(10, day.sleepStages.size)
        assertNotNull(day.sleepStages.firstOrNull { it.stage == "deep" })
    }

    @Test
    fun `sleep level messages mark stage ends so short awakenings stay short`() {
        // Pattern written by an FR255: the first stage starts at the sleep
        // event, and every level message closes the interval it names. The
        // old start-based parse shifted all labels and reported ~4h18m asleep
        // with 1h09m awake; the watch showed 5h15m asleep.
        val messages = listOf(
            sleepEvent(epoch(2026, 10, 22, 0, 38), 0),
            sleep(epoch(2026, 10, 22, 0, 41), 2),
            sleep(epoch(2026, 10, 22, 1, 21), 3),
            sleep(epoch(2026, 10, 22, 1, 22), 1),
            sleep(epoch(2026, 10, 22, 1, 39), 2),
            sleep(epoch(2026, 10, 22, 1, 50), 3),
            sleep(epoch(2026, 10, 22, 2, 27), 2),
            sleep(epoch(2026, 10, 22, 2, 28), 1),
            sleep(epoch(2026, 10, 22, 3, 9), 2),
            sleep(epoch(2026, 10, 22, 3, 21), 3),
            sleep(epoch(2026, 10, 22, 4, 4), 2),
            sleep(epoch(2026, 10, 22, 4, 17), 1),
            sleep(epoch(2026, 10, 22, 4, 53), 2),
            sleep(epoch(2026, 10, 22, 5, 2), 4),
            sleep(epoch(2026, 10, 22, 5, 47), 2),
            sleep(epoch(2026, 10, 22, 6, 8), 4),
            sleepEvent(epoch(2026, 10, 22, 6, 8), 1),
        )

        val day = WellnessParser.parse(messages, zone).single()

        assertEquals("2026-10-22", day.date)
        assertEquals(epoch(2026, 10, 22, 0, 38), day.sleepStart)
        assertEquals(epoch(2026, 10, 22, 6, 8), day.sleepEnd)
        assertEquals(15 * 60, day.awakeSleepSeconds)
        assertEquals(5 * 3600 + 15 * 60, day.deepSleepSeconds!! + day.lightSleepSeconds!! + day.remSleepSeconds!!)
    }

    @Test
    fun `prefers the watch local timestamp for the monitoring date`() {
        // 00:10 in Rome on 19 Oct is 22:10 UTC on 18 Oct. The watch's own
        // local_timestamp says 23:50 on 18 Oct, which is the day the steps
        // belong to.
        val utcTimestamp = java.time.LocalDateTime.of(2026, 10, 18, 22, 10)
            .toInstant(java.time.ZoneOffset.UTC).epochSecond
        val wallClock = java.time.LocalDateTime.of(2026, 10, 18, 23, 50)
            .toInstant(java.time.ZoneOffset.UTC).epochSecond
        val day = WellnessParser.parse(
            listOf(
                message(
                    "monitoring",
                    utcTimestamp,
                    field(5, "activity_type", 0),
                    field(9, "cycles_16", 500),
                    field(11, "local_timestamp", wallClock),
                ),
            ),
            zone,
        ).single()

        assertEquals("2026-10-18", day.date)
        assertEquals(1_000, day.steps)
    }

    @Test
    fun `drops the all-zero midnight rollover row`() {
        val drafts = WellnessParser.parse(
            listOf(
                monitoring(epoch(2026, 10, 19, 0, 0), cycles16 = 0, distance = 0.0, activeSeconds = 0.0, calories = 0),
            ),
            zone,
        )

        assertEquals(0, drafts.size)
    }

    @Test
    fun `resolves timestamp 16 against the previous file timestamp`() {
        val tenAm = epoch(2026, 10, 18, 10, 0)
        val drafts = WellnessParser.parse(
            listOf(
                monitoring(epoch(2026, 10, 18, 8, 0), cycles16 = 100, distance = 0.0, activeSeconds = 0.0, calories = 0),
                Fit.Message(
                    0,
                    "monitoring",
                    null,
                    listOf(
                        field(5, "activity_type", 0),
                        field(9, "cycles_16", 200),
                        // FIT timestamp_16 matches the low 16 bits of the
                        // watch's Unix-based monitoring time.
                        field(26, "timestamp_16", (tenAm.toInt() and 0xFFFF)),
                    ),
                ),
                message(
                    "monitoring_hr_data",
                    epoch(2026, 10, 18, 23, 59),
                    field(1, "current_day_resting_heart_rate", 55),
                ),
            ),
            zone,
        )

        val day = drafts.single()
        assertEquals("2026-10-18", day.date)
        assertEquals(400, day.steps)
    }

    @Test
    fun `uses hrv samples when the watch has no status summary`() {
        val day = WellnessParser.parse(
            listOf(
                message("hrv_value", epoch(2026, 10, 18, 2, 0), field(0, "value", 44.0)),
                message("hrv_value", epoch(2026, 10, 18, 3, 0), field(0, "value", 48.0)),
            ),
            zone,
        ).single()

        assertEquals(46.0, day.hrvLastNight!!, 0.01)
    }

    @Test
    fun `reads overnight rmssd from firstbeat daily data`() {
        val day = WellnessParser.parse(
            listOf(
                message(
                    "fbt_lha_daily_data",
                    epoch(2026, 10, 18, 6, 3),
                    field(2, "sleep_score", 75),
                    field(7, "rmssd_average_sleep", 40.5),
                ),
            ),
            zone,
        ).single()

        assertEquals("2026-10-18", day.date)
        assertEquals(75, day.sleepScore)
        assertEquals(40.5, day.hrvLastNight!!, 0.01)
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

    private fun sleepEvent(timestamp: Long, type: Int) =
        message(
            "event",
            timestamp,
            field(0, "event", 74),
            field(1, "event_type", type),
        )

    private fun message(name: String, timestamp: Long, vararg fields: Fit.FieldValue) =
        Fit.Message(0, name, timestamp, fields.toList())

    private fun field(number: Int, name: String, value: Any?) =
        Fit.FieldValue(number, name, value, null)

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toEpochSecond()
}
