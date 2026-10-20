package dev.opengarmin.app.data

import dev.opengarmin.protocol.Fit
import dev.opengarmin.protocol.Gfdi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/**
 * Integration check against a real Forerunner 255 activity capture. The file is
 * part of the repository, so this catches profile/message-name regressions that
 * a hand-written fixture would miss. It is skipped when the capture is absent.
 */
class ActivityInsightsRealFitTest {

    @Test
    fun `parses the captured forerunner activity`() {
        val file = sequenceOf(
            File("../captures/fr255_activity.fit"),
            File("captures/fr255_activity.fit"),
        ).firstOrNull { it.isFile }
        assumeNotNull(file)
        val parsed = Fit.parse(file!!.readBytes())
        val session = parsed.session ?: return
        val start = (session.startTime ?: session.timestamp ?: return) + Gfdi.GARMIN_EPOCH_UNIX
        val activity = ActivityEntity(
            id = 1L,
            sourceKey = "capture",
            fileName = file.name,
            startTime = start,
            sport = session.sport ?: 0,
            subSport = session.subSport ?: 0,
            distanceMeters = session.distanceMeters ?: 0.0,
            durationSeconds = session.totalTimerSeconds ?: 0.0,
            elapsedSeconds = session.totalElapsedSeconds ?: 0.0,
            avgHeartRate = session.avgHeartRate,
            maxHeartRate = session.maxHeartRate,
            calories = session.calories,
            ascentMeters = session.ascentMeters,
            descentMeters = session.descentMeters,
            importedAt = 0L,
        )
        val records = parsed.records.map { record ->
            RecordEntity(
                activityId = 1L,
                timestamp = record.timestamp + Gfdi.GARMIN_EPOCH_UNIX,
                distanceMeters = record.distanceMeters,
                heartRate = record.heartRate,
                speedMps = record.speedMps,
                altitudeMeters = record.altitudeMeters,
                cadence = record.cadence,
                latitude = record.latitude,
                longitude = record.longitude,
                bodyBattery = record.bodyBattery,
            )
        }
        val laps = parsed.laps.mapIndexed { index, lap ->
            LapEntity(
                activityId = 1L,
                lapIndex = index,
                startTime = (lap.startTime ?: lap.timestamp)?.plus(Gfdi.GARMIN_EPOCH_UNIX),
                durationSeconds = lap.totalTimerSeconds ?: 0.0,
                distanceMeters = lap.distanceMeters ?: 0.0,
                avgHeartRate = lap.avgHeartRate,
                maxHeartRate = lap.maxHeartRate,
                avgSpeedMps = lap.avgSpeedMps,
                calories = lap.calories,
                ascentMeters = lap.ascentMeters,
                descentMeters = lap.descentMeters,
            )
        }
        val messages = parsed.messages.mapIndexed { index, message ->
            val fields = JSONObject()
            val units = JSONObject()
            for (field in message.fields) {
                val value = field.value
                when {
                    value is Double && value.isFinite() -> fields.put(field.name, value)
                    value is Float && value.isFinite() -> fields.put(field.name, value.toDouble())
                    value is Number -> fields.put(field.name, value)
                    value == null -> Unit
                    else -> fields.put(field.name, value.toString())
                }
                field.units?.let { units.put(field.name, it) }
            }
            FitMessageEntity(
                activityId = 1L,
                orderIndex = index,
                globalNumber = message.globalNumber,
                messageName = message.name,
                timestamp = message.timestamp,
                fieldsJson = fields.toString(),
                unitsJson = units.toString(),
            )
        }

        val insights = ActivityAnalysis.build(activity, records, laps, messages)
        val metrics = insights.session

        assertEquals(2487.17, metrics.timerSeconds, 0.01)
        assertEquals(5500.21, metrics.distanceMeters, 0.01)
        assertEquals(142, metrics.avgHeartRate)
        assertEquals(157, metrics.maxHeartRate)
        assertEquals(432, metrics.totalCalories)
        assertEquals(15.0, metrics.ascentMeters ?: 0.0, 0.01)
        assertEquals(12.0, metrics.descentMeters ?: 0.0, 0.01)
        assertEquals(240.0, metrics.avgPowerW ?: 0.0, 0.01)
        assertEquals(464.0, metrics.maxPowerW ?: 0.0, 0.01)
        assertEquals(262.0, metrics.normalizedPowerW ?: 0.0, 0.01)
        assertEquals(94.1, metrics.avgVerticalOscillationMm ?: 0.0, 0.01)
        assertEquals(9.64, metrics.avgVerticalRatioPct ?: 0.0, 0.01)
        assertEquals(313.6, metrics.avgGroundContactMs ?: 0.0, 0.01)
        assertEquals(0.9793, metrics.avgStrideLengthM ?: 0.0, 0.0001)
        assertEquals(134.859, metrics.avgCadenceSpm ?: 0.0, 0.01)
        assertEquals("Base (aerobic low)", metrics.primaryBenefit)
        assertEquals(-3, metrics.performanceCondition)
        assertEquals(41, metrics.bodyBatteryStart)
        assertEquals(36, metrics.bodyBatteryEnd)
        assertEquals(-5, metrics.bodyBatteryNet)
        assertEquals(47.072, metrics.exerciseLoad ?: 0.0, 0.01)

        assertEquals(2488, insights.samples.size)
        assertEquals(100.0, insights.samples.first().cadenceSpm ?: 0.0, 0.01)
        assertEquals(97.0, insights.samples.first().powerW ?: 0.0, 0.01)
        assertEquals(41, insights.samples.first().bodyBattery)

        assertNotNull(insights.hrZones)
        assertEquals(5, insights.hrZones?.zones?.size)
        assertEquals(2460.663, insights.hrZones?.totalSeconds ?: 0.0, 0.01)
        assertNotNull(insights.powerZones)
        assertEquals(379, insights.powerZones?.thresholdPowerW)
        assertEquals(5, insights.powerZones?.zones?.size)
        assertEquals(6, insights.laps.size)
        assertNotNull(insights.runWalk)
        assertTrue((insights.runWalk?.estimated ?: false))
    }
}
