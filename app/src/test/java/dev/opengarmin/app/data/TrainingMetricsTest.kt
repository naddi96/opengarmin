package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingMetricsTest {

    @Test
    fun `parses legacy activity and user metric fields`() {
        val start = 1_800_000_000L
        val activity = activity(id = 1, start = start, duration = 3_600.0)
        val rows = listOf(
            TrainingRow(
                activityId = 1,
                startTime = start,
                messageName = "msg_140",
                fieldsJson = """
                    {"field_7":979198,"field_9":5760,"field_4":50,"field_20":1,
                     "field_41":5,"field_17":-5,"field_37":28151776}
                """.trimIndent(),
            ),
            TrainingRow(
                activityId = 1,
                startTime = start,
                messageName = "msg_79",
                fieldsJson = """{"field_0":15333,"field_11":179,"field_13":129}""",
            ),
            TrainingRow(
                activityId = 1,
                startTime = start,
                messageName = "session",
                fieldsJson = """{"training_load_peak":999.0}""",
            ),
        )

        val summary = TrainingMetrics.build(listOf(activity), rows, now = start + 3_600)

        assertNotNull(summary.latestMetrics)
        assertEquals(52.3, summary.latestVo2Max!!, 0.1)
        assertEquals(-5, summary.latestMetrics!!.performanceCondition)
        assertEquals(5.0, summary.latestMetrics!!.aerobicEffect!!, 0.01)
        assertEquals(0.1, summary.latestMetrics!!.anaerobicEffect!!, 0.01)
        assertEquals(429.6, summary.latestMetrics!!.trainingLoad!!, 0.1)
        assertEquals(179, summary.lthr)
        assertEquals(12.9, summary.ltSpeedKmh!!, 0.01)
        assertEquals(5_760, summary.recoveryRemainingMinutes)
        assertEquals(429.6, summary.acuteLoad!!, 0.1)
        assertEquals(107.39, summary.chronicLoad!!, 0.1)
        assertEquals(429.6, summary.focus!!.highAerobic, 0.1)
        assertEquals(0.0, summary.focus!!.lowAerobic, 0.01)
    }

    @Test
    fun `parses named fields and ignores elapsed recovery`() {
        val start = 1_800_000_000L
        val activity = activity(id = 2, start = start, duration = 1_800.0)
        val rows = listOf(
            TrainingRow(
                activityId = 2,
                startTime = start,
                messageName = "activity_metrics",
                fieldsJson = """
                    {"vo2_max":52.3,"recovery_time":60,"aerobic_training_effect":3.8,
                     "anaerobic_training_effect":0.2,"primary_benefit":3,
                     "training_load_peak_1":100.0,"final_performance_condition":2}
                """.trimIndent(),
            ),
            TrainingRow(
                activityId = 2,
                startTime = start,
                messageName = "user_metrics",
                fieldsJson = """{"vo2_max":52.4,"lthr":180,"ltspeed":13.0}""",
            ),
        )

        val summary = TrainingMetrics.build(listOf(activity), rows, now = start + 86_400)

        assertEquals(52.3, summary.latestVo2Max!!, 0.01)
        assertEquals(3.8, summary.latestMetrics!!.aerobicEffect!!, 0.01)
        assertEquals(100.0, summary.latestMetrics!!.trainingLoad!!, 0.01)
        assertEquals(180, summary.lthr)
        assertEquals(13.0, summary.ltSpeedKmh!!, 0.01)
        assertEquals(null, summary.recoveryRemainingMinutes)
        assertEquals(3, summary.latestMetrics!!.primaryBenefit)
        assertEquals(1, summary.vo2History.size)
    }

    private fun activity(id: Long, start: Long, duration: Double) = ActivityEntity(
        id = id,
        sourceKey = "key-$id",
        fileName = "file-$id.fit",
        startTime = start,
        sport = 1,
        subSport = 0,
        distanceMeters = 10_000.0,
        durationSeconds = duration,
        elapsedSeconds = duration,
        avgHeartRate = 150,
        maxHeartRate = 180,
        calories = 600,
        ascentMeters = 20,
        descentMeters = 20,
        importedAt = start,
    )
}
