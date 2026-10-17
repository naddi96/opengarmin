package dev.opengarmin.app.coach

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

data class GeoPoint(val latitude: Double, val longitude: Double)

data class GeoBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

data class GpsSummary(
    val available: Boolean,
    val pointCount: Int,
    val start: GeoPoint?,
    val end: GeoPoint?,
    val bounds: GeoBounds?,
    val route: List<GeoPoint>,
)

data class RunningDynamicsSummary(
    val available: Boolean,
    val cadenceRaw: Double? = null,
    val cadenceSpm: Double? = null,
    val maxCadenceSpm: Double? = null,
    val groundContactTimeMs: Double? = null,
    val groundContactPercent: Double? = null,
    val groundContactBalanceLeftPct: Double? = null,
    val verticalOscillationMm: Double? = null,
    val verticalRatioPct: Double? = null,
    val strideLengthMm: Double? = null,
    val powerW: Int? = null,
    val trainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val trainingLoadPeak: Double? = null,
    val movingTimeSeconds: Double? = null,
    val standingTimeSeconds: Double? = null,
    val avgSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val avgRespiration: Double? = null,
    val avgStress: Double? = null,
    val avgSpo2: Double? = null,
)

data class LapDynamics(
    val index: Int,
    val cadenceSpm: Double? = null,
    val groundContactTimeMs: Double? = null,
    val verticalOscillationMm: Double? = null,
    val strideLengthMm: Double? = null,
)

/**
 * Pure parser for the extra FIT fields we store in `fit_messages`. The record
 * and session messages contain GPS start/end positions, running dynamics and
 * training-effect fields that are not in the compact Room `records` columns.
 */
object FitInsights {
    private const val MAX_ROUTE_POINTS = 60

    fun gps(points: List<GeoPoint>, sessionJson: String?): GpsSummary {
        val session = runCatching { sessionJson?.let(::JSONObject) }.getOrNull()
        val start = session?.let {
            geo(it.num("start_position_lat"), it.num("start_position_long"))
        } ?: points.firstOrNull()
        val end = session?.let {
            geo(it.num("end_position_lat"), it.num("end_position_long"))
        } ?: points.lastOrNull()
        val bounds = if (points.isEmpty()) {
            null
        } else {
            GeoBounds(
                north = points.maxOf { it.latitude },
                south = points.minOf { it.latitude },
                east = points.maxOf { it.longitude },
                west = points.minOf { it.longitude },
            )
        }
        val route = if (points.isEmpty()) {
            emptyList()
        } else {
            val step = ceil(points.size.toDouble() / MAX_ROUTE_POINTS).toInt().coerceAtLeast(1)
            buildList {
                var index = 0
                while (index < points.size) {
                    add(points[index])
                    index += step
                }
                if (lastOrNull() != points.last()) add(points.last())
            }
        }
        return GpsSummary(
            available = points.size >= 2,
            pointCount = points.size,
            start = start,
            end = end,
            bounds = bounds,
            route = route,
        )
    }

    fun dynamics(
        recordJsons: List<String>,
        sessionJson: String?,
        isRunning: Boolean,
    ): RunningDynamicsSummary? {
        val session = runCatching { sessionJson?.let(::JSONObject) }.getOrNull()
        val records = recordJsons.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

        val avgCadenceRaw = session?.let {
            it.num("avg_cadence")?.plus(it.num("avg_fractional_cadence") ?: 0.0)
        } ?: average(records, "cadence")?.let { avg ->
            avg + (average(records, "fractional_cadence") ?: 0.0)
        }
        val maxCadenceRaw = session?.let {
            it.num("max_cadence")?.plus(it.num("max_fractional_cadence") ?: 0.0)
        } ?: max(records, "cadence")?.let { max ->
            max + (average(records, "fractional_cadence") ?: 0.0)
        }
        val groundContact = session?.num("avg_stance_time") ?: average(records, "stance_time")
        val groundContactPercent = session?.num("avg_stance_time_percent")
            ?: average(records, "stance_time_percent")
        val balance = session?.num("avg_stance_time_balance")
            ?: average(records, "stance_time_balance")
        val verticalOscillation = session?.num("avg_vertical_oscillation")
            ?: average(records, "vertical_oscillation")
        val verticalRatio = session?.num("avg_vertical_ratio") ?: average(records, "vertical_ratio")
        val stride = session?.num("avg_step_length") ?: average(records, "step_length")
        val power = (session?.num("avg_power") ?: average(records, "power"))?.roundToInt()
        val trainingEffect = session?.num("total_training_effect")
        val anaerobic = session?.num("total_anaerobic_training_effect")
        val loadPeak = session?.num("training_load_peak")
        val moving = session?.num("total_moving_time")
        val standing = session?.num("time_standing")
        val avgSpeed = (session?.num("enhanced_avg_speed") ?: session?.num("avg_speed"))?.times(3.6)
        val maxSpeed = (session?.num("enhanced_max_speed") ?: session?.num("max_speed"))?.times(3.6)
        val respiration = session?.num("enhanced_avg_respiration_rate")
            ?: session?.num("avg_respiration_rate")
        val stress = session?.num("avg_stress")
        val spo2 = session?.num("avg_spo2")

        val hasAnything = listOf(
            avgCadenceRaw,
            groundContact,
            verticalOscillation,
            verticalRatio,
            stride,
            trainingEffect,
            anaerobic,
        ).any { it != null }
        if (!hasAnything) return null

        return RunningDynamicsSummary(
            available = true,
            cadenceRaw = avgCadenceRaw,
            cadenceSpm = if (isRunning) avgCadenceRaw?.times(2) else avgCadenceRaw,
            maxCadenceSpm = if (isRunning) maxCadenceRaw?.times(2) else maxCadenceRaw,
            groundContactTimeMs = groundContact,
            groundContactPercent = groundContactPercent,
            groundContactBalanceLeftPct = balance,
            verticalOscillationMm = verticalOscillation,
            verticalRatioPct = verticalRatio,
            strideLengthMm = stride,
            powerW = power,
            trainingEffect = trainingEffect,
            anaerobicTrainingEffect = anaerobic,
            trainingLoadPeak = loadPeak,
            movingTimeSeconds = moving,
            standingTimeSeconds = standing,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            avgRespiration = respiration,
            avgStress = stress,
            avgSpo2 = spo2,
        )
    }

    fun lapDynamics(lapJsons: List<String>, isRunning: Boolean): List<LapDynamics> {
        return lapJsons.mapIndexedNotNull { fallbackIndex, json ->
            val lap = runCatching { JSONObject(json) }.getOrNull() ?: return@mapIndexedNotNull null
            val index = lap.optInt("message_index", fallbackIndex)
            val cadenceRaw = lap.num("avg_cadence")?.plus(lap.num("avg_fractional_cadence") ?: 0.0)
            val result = LapDynamics(
                index = index,
                cadenceSpm = if (isRunning) cadenceRaw?.times(2) else cadenceRaw,
                groundContactTimeMs = lap.num("avg_stance_time"),
                verticalOscillationMm = lap.num("avg_vertical_oscillation"),
                strideLengthMm = lap.num("avg_step_length"),
            )
            if (result.cadenceSpm == null && result.groundContactTimeMs == null &&
                result.verticalOscillationMm == null && result.strideLengthMm == null
            ) {
                null
            } else {
                result
            }
        }
    }

    private fun geo(lat: Double?, lon: Double?): GeoPoint? =
        if (lat != null && lon != null) GeoPoint(lat, lon) else null

    private fun average(records: List<JSONObject>, key: String): Double? {
        val values = records.mapNotNull { it.num(key) }
        return if (values.isEmpty()) null else values.average()
    }

    private fun max(records: List<JSONObject>, key: String): Double? =
        records.mapNotNull { it.num(key) }.maxOrNull()

    private fun JSONObject.num(key: String): Double? {
        val value = opt(key) ?: return null
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return null
        return number.takeIf { it.isFinite() }
    }
}
