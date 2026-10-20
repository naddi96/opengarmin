package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityInsightsTest {

    @Test
    fun `derives garmin style metrics from fit messages`() {
        val activity = ActivityEntity(
            id = 1L,
            sourceKey = "test",
            fileName = "test.fit",
            startTime = 1_000L,
            sport = 1,
            subSport = 0,
            distanceMeters = 1_000.0,
            durationSeconds = 400.0,
            elapsedSeconds = 410.0,
            avgHeartRate = 140,
            maxHeartRate = 160,
            calories = 100,
            ascentMeters = 10,
            descentMeters = 5,
            importedAt = 2_000L,
        )
        val records = listOf(
            record(1_000L, 0.0, 100, 0.0, 10.0, 60, 50),
            record(1_001L, 100.0, 120, 0.1, 11.0, 65, 49),
            record(1_002L, 500.0, 140, 3.0, 12.0, 70, 48),
            record(1_003L, 1_000.0, 160, 2.0, 13.0, 75, 45),
        )
        val messages = buildList {
            add(message("session", 1_000L, SESSION_JSON))
            add(message("activity_metrics", 1_003L, ACTIVITY_METRICS_JSON))
            add(message("user_metrics", 1_003L, USER_METRICS_JSON))
            add(message("time_in_zone", 1_000L, ZONES_JSON))
            records.forEachIndexed { index, record ->
                add(message("record", record.timestamp, RECORD_EXTRA_JSON, orderIndex = 10 + index))
            }
            add(message("lap", 1_000L, LAP_JSON, orderIndex = 20))
            add(message("lap", 1_001L, LAP_JSON_2, orderIndex = 21))
        }
        val laps = listOf(
            LapEntity(
                activityId = 1L,
                lapIndex = 0,
                startTime = 1_000L,
                durationSeconds = 200.0,
                distanceMeters = 500.0,
                avgHeartRate = 130,
                maxHeartRate = 145,
                avgSpeedMps = 2.5,
                calories = 50,
                ascentMeters = 5,
                descentMeters = 2,
            ),
            LapEntity(
                activityId = 1L,
                lapIndex = 1,
                startTime = 1_200L,
                durationSeconds = 200.0,
                distanceMeters = 500.0,
                avgHeartRate = 150,
                maxHeartRate = 160,
                avgSpeedMps = 2.5,
                calories = 50,
                ascentMeters = 5,
                descentMeters = 3,
            ),
        )

        val insights = ActivityAnalysis.build(activity, records, laps, messages)
        val session = insights.session

        assertEquals(400.0, session.timerSeconds, 0.001)
        assertEquals(410.0, session.elapsedSeconds, 0.001)
        assertEquals(300.0, session.movingSeconds ?: 0.0, 0.001)
        assertEquals(400.0, session.avgPaceSecondsPerKm ?: 0.0, 0.001)
        assertEquals(300.0, session.movingPaceSecondsPerKm ?: 0.0, 0.001)
        assertEquals(1_000.0 / 3.0, session.bestPaceSecondsPerKm ?: 0.0, 0.001)
        assertEquals(9.0, session.avgSpeedKmh ?: 0.0, 0.001)
        assertEquals(10.8, session.maxSpeedKmh ?: 0.0, 0.001)
        assertEquals(140.0, session.avgCadenceSpm ?: 0.0, 0.001)
        assertEquals(160.0, session.maxCadenceSpm ?: 0.0, 0.001)
        assertEquals(0.9, session.avgStrideLengthM ?: 0.0, 0.001)
        assertEquals(80.0, session.avgVerticalOscillationMm ?: 0.0, 0.001)
        assertEquals(8.0, session.avgVerticalRatioPct ?: 0.0, 0.001)
        assertEquals(300.0, session.avgGroundContactMs ?: 0.0, 0.001)
        assertEquals(220.0, session.avgPowerW ?: 0.0, 0.001)
        assertEquals(300.0, session.maxPowerW ?: 0.0, 0.001)
        assertEquals(240.0, session.normalizedPowerW ?: 0.0, 0.001)
        assertEquals("Base (aerobic low)", session.primaryBenefit)
        assertEquals(-3, session.performanceCondition)
        assertEquals(52.0, session.vo2Max ?: 0.0, 0.001)
        assertEquals(50, session.bodyBatteryStart)
        assertEquals(45, session.bodyBatteryEnd)
        assertEquals(-5, session.bodyBatteryNet)
        assertEquals(20.0, session.avgTemperatureC ?: 0.0, 0.001)
        assertEquals(30.0, session.avgRespiration ?: 0.0, 0.001)
        assertEquals(97, session.avgSpo2)
        assertEquals(20, session.avgStress)
        assertTrue(session.movingDerived.not())

        val runWalk = insights.runWalk
        assertNotNull(runWalk)
        assertEquals(2.0, runWalk?.runningSeconds ?: 0.0, 0.001)
        assertEquals(0.0, runWalk?.walkingSeconds ?: 0.0, 0.001)
        assertEquals(1.0, runWalk?.idleSeconds ?: 0.0, 0.001)
        assertTrue(runWalk?.estimated == true)

        val hrZones = insights.hrZones
        assertNotNull(hrZones)
        assertEquals(5, hrZones?.zones?.size)
        assertEquals(101, hrZones?.zones?.first()?.lowBoundary)
        assertEquals(119, hrZones?.zones?.first()?.highBoundary)
        assertEquals(140.0, hrZones?.totalSeconds ?: 0.0, 0.001)
        assertEquals(14.2857f, hrZones?.zones?.first()?.percent ?: 0f, 0.001f)

        val powerZones = insights.powerZones
        assertNotNull(powerZones)
        assertEquals(300, powerZones?.thresholdPowerW)
        assertEquals(5, powerZones?.zones?.size)

        assertEquals(2, insights.laps.size)
        assertEquals(146.0, insights.laps.first().cadenceSpm ?: 0.0, 0.001)
        assertEquals(0.98, insights.laps.first().strideLengthM ?: 0.0, 0.001)
        // Best pace is computed from the maximum recorded speed.
        assertEquals(333.333, session.bestPaceSecondsPerKm ?: 0.0, 0.01)
    }

    private fun record(
        timestamp: Long,
        distance: Double,
        heartRate: Int,
        speed: Double,
        altitude: Double,
        cadence: Int,
        bodyBattery: Int,
    ) = RecordEntity(
        activityId = 1L,
        timestamp = timestamp,
        distanceMeters = distance,
        heartRate = heartRate,
        speedMps = speed,
        altitudeMeters = altitude,
        cadence = cadence,
        bodyBattery = bodyBattery,
    )

    private var order = 0
    private fun message(
        name: String,
        timestamp: Long?,
        fields: String,
        orderIndex: Int = order++,
    ) = FitMessageEntity(
        activityId = 1L,
        orderIndex = orderIndex,
        globalNumber = 0,
        messageName = name,
        timestamp = timestamp,
        fieldsJson = fields,
        unitsJson = "{}",
    )

    private companion object {
        const val SESSION_JSON = """
            {
              "total_timer_time": 400.0,
              "total_elapsed_time": 410.0,
              "total_distance": 1000.0,
              "total_moving_time": 300.0,
              "avg_speed": 2.5,
              "max_speed": 3.0,
              "avg_heart_rate": 140,
              "max_heart_rate": 160,
              "min_heart_rate": 100,
              "total_calories": 100,
              "total_ascent": 10,
              "total_descent": 5,
              "avg_cadence": 70,
              "max_cadence": 80,
              "avg_power": 220,
              "max_power": 300,
              "normalized_power": 240,
              "avg_step_length": 900.0,
              "avg_vertical_oscillation": 80.0,
              "avg_vertical_ratio": 8.0,
              "avg_stance_time": 300.0,
              "total_training_effect": 2.5,
              "total_anaerobic_training_effect": 0.2,
              "training_load_peak": 50.0,
              "avg_temperature": 20.0,
              "min_temperature": 18.0,
              "max_temperature": 22.0,
              "avg_respiration_rate": 30.0,
              "avg_spo2": 97,
              "avg_stress": 20
            }
        """

        const val ACTIVITY_METRICS_JSON = """
            {
              "aerobic_training_effect": 2.5,
              "anaerobic_training_effect": 0.2,
              "training_load_peak_1": 50.0,
              "recovery_time": 120,
              "primary_benefit": 2,
              "final_performance_condition": -3,
              "final_body_battery": 45,
              "vo2_max": 52.0
            }
        """

        const val USER_METRICS_JSON = """
            {
              "initial_body_battery": 50,
              "max_hr": 190
            }
        """

        const val ZONES_JSON = """
            {
              "reference_mesg": 18,
              "time_in_hr_zone": "10.0, 20.0, 30.0, 40.0, 50.0, 0.0, 0.0",
              "hr_zone_high_boundary": "101, 119, 139, 161, 181, 201",
              "time_in_power_zone": "10.0, 20.0, 30.0, 40.0, 50.0, 0.0, 0.0, 0.0",
              "power_zone_high_boundary": "247, 303, 340, 379, 436, 4000",
              "max_heart_rate": 190,
              "functional_threshold_power": 300
            }
        """

        const val RECORD_EXTRA_JSON = """
            {
              "power": 220.0,
              "step_length": 900.0,
              "vertical_oscillation": 80.0,
              "vertical_ratio": 8.0,
              "stance_time": 300.0,
              "fractional_cadence": 0.0,
              "performance_condition": -2
            }
        """

        const val LAP_JSON = """
            {
              "message_index": 0,
              "avg_cadence": 70,
              "avg_fractional_cadence": 3,
              "avg_step_length": 980.0,
              "avg_vertical_oscillation": 79.0,
              "avg_vertical_ratio": 8.1,
              "avg_stance_time": 301.0,
              "avg_power": 210.0
            }
        """

        const val LAP_JSON_2 = """
            {
              "message_index": 1,
              "avg_cadence": 75,
              "avg_step_length": 990.0,
              "avg_vertical_oscillation": 81.0,
              "avg_vertical_ratio": 8.2,
              "avg_stance_time": 299.0,
              "avg_power": 230.0
            }
        """
    }
}
