package dev.opengarmin.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitInsightsTest {
    private val session = """
        {"start_position_lat":43.712586192414165,"start_position_long":10.407902542501688,
         "end_position_lat":43.712814431637526,"end_position_long":10.407886616885662,
         "avg_cadence":67,"avg_fractional_cadence":0.4296875,"max_cadence":84,
         "max_fractional_cadence":0.5,"avg_stance_time":313.6,
         "avg_vertical_oscillation":94.1,"avg_vertical_ratio":9.64,
         "avg_step_length":979.3,"total_training_effect":2.5,
         "total_anaerobic_training_effect":0.1,"training_load_peak":47.072021484375,
         "enhanced_avg_speed":2.211,"enhanced_max_speed":3.42}
    """.trimIndent()

    private val records = listOf(
        """{"position_lat":43.712586192414165,"position_long":10.407902542501688,
            "cadence":50,"fractional_cadence":0,"stance_time":"",
            "vertical_oscillation":58.4,"vertical_ratio":7.1,"step_length":822}""",
        """{"position_lat":43.71258149854839,"position_long":10.407932382076979,
            "cadence":52,"fractional_cadence":0.5,"stance_time":"",
            "vertical_oscillation":60.1,"vertical_ratio":7.4,"step_length":840}""",
    )

    @Test
    fun `parses gps summary and sampled route from session and records`() {
        val points = records.mapNotNull { json ->
            val obj = org.json.JSONObject(json)
            GeoPoint(obj.getDouble("position_lat"), obj.getDouble("position_long"))
        }
        val gps = FitInsights.gps(points, session)
        assertTrue(gps.available)
        assertEquals(2, gps.pointCount)
        assertEquals(43.712586, gps.start!!.latitude, 0.000001)
        assertEquals(43.712814, gps.end!!.latitude, 0.000001)
        assertEquals(2, gps.route.size)
    }

    @Test
    fun `parses running dynamics and doubles running cadence`() {
        val dynamics = FitInsights.dynamics(records, session, isRunning = true)!!
        assertTrue(dynamics.available)
        assertEquals(134.859375, dynamics.cadenceSpm!!, 0.0001)
        assertEquals(169.0, dynamics.maxCadenceSpm!!, 0.0001)
        assertEquals(313.6, dynamics.groundContactTimeMs!!, 0.0001)
        assertEquals(94.1, dynamics.verticalOscillationMm!!, 0.0001)
        assertEquals(9.64, dynamics.verticalRatioPct!!, 0.0001)
        assertEquals(979.3, dynamics.strideLengthMm!!, 0.0001)
        assertEquals(2.5, dynamics.trainingEffect!!, 0.0001)
        assertEquals(7.9596, dynamics.avgSpeedKmh!!, 0.001)
    }

    @Test
    fun `falls back to record averages for missing session fields`() {
        val dynamics = FitInsights.dynamics(records, null, isRunning = true)!!
        assertEquals(102.5, dynamics.cadenceSpm!!, 0.1)
        assertEquals(59.25, dynamics.verticalOscillationMm!!, 0.1)
        assertEquals(831.0, dynamics.strideLengthMm!!, 0.1)
        assertNull(dynamics.groundContactTimeMs)
    }

    @Test
    fun `parses lap dynamics and rejects empty data`() {
        val laps = FitInsights.lapDynamics(
            listOf(
                """{"message_index":0,"avg_cadence":60,"avg_fractional_cadence":0.5,
                    "avg_stance_time":320,"avg_vertical_oscillation":92,"avg_step_length":970}""",
            ),
            isRunning = true,
        )
        assertEquals(1, laps.size)
        assertEquals(121.0, laps.single().cadenceSpm!!, 0.1)
        assertEquals(320.0, laps.single().groundContactTimeMs!!, 0.1)
        assertNull(FitInsights.dynamics(emptyList(), null, isRunning = true))
        assertFalse(FitInsights.gps(emptyList(), null).available)
    }
}
