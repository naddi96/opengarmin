package dev.opengarmin.app.data

import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.categoryForSport
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One decoded activity sample, including fields that are not in `records`. */
data class ActivitySample(
    val timestamp: Long,
    val elapsedSeconds: Double,
    val distanceMeters: Double?,
    val heartRate: Int?,
    val speedMps: Double?,
    val altitudeMeters: Double?,
    /** Running cadence is doubled to steps per minute, like Garmin Connect. */
    val cadenceSpm: Double?,
    val powerW: Double?,
    val strideLengthM: Double?,
    val verticalOscillationMm: Double?,
    val verticalRatioPct: Double?,
    val groundContactMs: Double?,
    val performanceCondition: Int?,
    val bodyBattery: Int?,
    val temperatureC: Double?,
)

data class ActivityZone(
    val index: Int,
    val label: String,
    val lowBoundary: Int?,
    val highBoundary: Int?,
    val seconds: Double,
    val percent: Float,
)

data class ActivityZoneSet(
    val zones: List<ActivityZone>,
    val totalSeconds: Double,
    /** True when the values came from the watch's own zone records. */
    val fromWatch: Boolean = true,
    val maxHeartRate: Int? = null,
    val thresholdPowerW: Int? = null,
)

enum class MovementKind { Run, Walk, Idle }

data class MovementSegment(
    val startElapsedSeconds: Double,
    val endElapsedSeconds: Double,
    val startDistanceMeters: Double?,
    val endDistanceMeters: Double?,
    val kind: MovementKind,
)

data class RunWalkSplit(
    val runningSeconds: Double,
    val walkingSeconds: Double,
    val idleSeconds: Double,
    /** Garmin's watch-side run/walk detection is not in the FIT file, so this is derived from speed. */
    val estimated: Boolean,
)

data class LapInsights(
    val index: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val paceSecondsPerKm: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val calories: Int?,
    val ascentMeters: Int?,
    val descentMeters: Int?,
    val cadenceSpm: Double?,
    val strideLengthM: Double?,
    val verticalOscillationMm: Double?,
    val verticalRatioPct: Double?,
    val groundContactMs: Double?,
    val powerW: Double?,
)

data class ActivitySessionMetrics(
    val timerSeconds: Double,
    val elapsedSeconds: Double,
    val movingSeconds: Double?,
    val movingDerived: Boolean,
    val distanceMeters: Double,
    val avgPaceSecondsPerKm: Double?,
    val movingPaceSecondsPerKm: Double?,
    val bestPaceSecondsPerKm: Double?,
    val avgSpeedKmh: Double?,
    val movingSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val minHeartRate: Int?,
    val avgCadenceSpm: Double?,
    val maxCadenceSpm: Double?,
    val avgStrideLengthM: Double?,
    val avgVerticalOscillationMm: Double?,
    val avgVerticalRatioPct: Double?,
    val avgGroundContactMs: Double?,
    val avgPowerW: Double?,
    val maxPowerW: Double?,
    val normalizedPowerW: Double?,
    val totalCalories: Int?,
    val fatCalories: Int?,
    val activeCalories: Int?,
    val restingCalories: Int?,
    val ascentMeters: Double?,
    val descentMeters: Double?,
    val minAltitudeMeters: Double?,
    val maxAltitudeMeters: Double?,
    val avgTemperatureC: Double?,
    val minTemperatureC: Double?,
    val maxTemperatureC: Double?,
    val aerobicTrainingEffect: Double?,
    val anaerobicTrainingEffect: Double?,
    val exerciseLoad: Double?,
    val recoveryMinutes: Int?,
    val primaryBenefit: String?,
    val performanceCondition: Int?,
    val vo2Max: Double?,
    val bodyBatteryStart: Int?,
    val bodyBatteryEnd: Int?,
    val bodyBatteryNet: Int?,
    val avgRespiration: Double?,
    val avgSpo2: Int?,
    val avgStress: Int?,
)

data class ActivityInsights(
    val samples: List<ActivitySample>,
    val session: ActivitySessionMetrics,
    val hrZones: ActivityZoneSet?,
    val powerZones: ActivityZoneSet?,
    val runWalk: RunWalkSplit?,
    val movementSegments: List<MovementSegment>,
    val laps: List<LapInsights>,
)

/**
 * Derives the metrics Garmin Connect shows on an activity from the FIT
 * messages that are already stored locally. Nothing is estimated unless the
 * field is explicitly marked as derived ([ActivitySessionMetrics.movingDerived]
 * and [RunWalkSplit.estimated]); absent fields stay null so the UI can omit
 * them instead of inventing values.
 */
object ActivityAnalysis {

    private val HR_ZONE_LABELS = listOf("Warm up", "Easy", "Aerobic", "Threshold", "Maximum")
    private val POWER_ZONE_LABELS = listOf("Easy", "Moderate", "Tempo", "Long interval", "Short interval")

    fun build(
        activity: ActivityEntity,
        records: List<RecordEntity>,
        laps: List<LapEntity>,
        messages: List<FitMessageEntity>,
    ): ActivityInsights {
        val isRunning = categoryForSport(activity.sport, activity.subSport) == SportCategory.Run
        val session = messages.lastOrNull { it.messageName == "session" }?.jsonOrNull()
        val metrics = messages.lastOrNull { it.messageName in METRIC_MESSAGES }?.jsonOrNull()
        val user = messages.lastOrNull { it.messageName in USER_METRIC_MESSAGES }?.jsonOrNull()
        val zoneMessages = messages
            .filter { it.messageName == "time_in_zone" }
            .mapNotNull { it.jsonOrNull() }
        // Session-level zone records use reference_mesg 18. Lap zone records
        // (reference_mesg 19) describe only one lap and would be misleading.
        val zoneMessage = zoneMessages.lastOrNull { it.num("reference_mesg")?.roundToInt() == 18 }
        val samples = buildSamples(
            records = records,
            recordMessages = messages.filter { it.messageName == "record" },
            isRunning = isRunning,
        )
        val runWalkResult = buildRunWalk(samples)
        val context = AnalysisContext(
            activity = activity,
            session = session,
            metrics = metrics,
            user = user,
            zoneMessage = zoneMessage,
            samples = samples,
            runWalk = runWalkResult?.first,
            isRunning = isRunning,
        )

        val sessionMetrics = buildSessionMetrics(context)

        val hrZones = buildZoneSet(
            context.zoneSource,
            "time_in_hr_zone",
            "hr_zone_high_boundary",
            HR_ZONE_LABELS,
            maxHeartRate = zoneMessage.num("max_heart_rate")?.roundToInt()
                ?: user.num("max_hr")?.roundToInt(),
            thresholdPowerW = null,
        )
        val powerZones = buildZoneSet(
            context.zoneSource,
            "time_in_power_zone",
            "power_zone_high_boundary",
            POWER_ZONE_LABELS,
            maxHeartRate = null,
            thresholdPowerW = zoneMessage.num("functional_threshold_power")?.roundToInt(),
        )

        return ActivityInsights(
            samples = samples,
            session = sessionMetrics,
            hrZones = hrZones,
            powerZones = powerZones,
            runWalk = context.runWalk,
            movementSegments = runWalkResult?.second.orEmpty(),
            laps = buildLaps(laps, messages.filter { it.messageName == "lap" }, isRunning),
        )
    }

    private fun buildSessionMetrics(context: AnalysisContext): ActivitySessionMetrics {
        val session = context.session
        val samples = context.samples
        val activity = context.activity
        val derivedMoving = context.runWalk?.let {
            (it.runningSeconds + it.walkingSeconds).takeIf { seconds -> seconds > 0.0 }
        }

        val timer = session.num("total_timer_time")?.takeIf { it > 0.0 } ?: activity.durationSeconds
        val elapsed = session.num("total_elapsed_time")?.takeIf { it > 0.0 } ?: activity.elapsedSeconds
        val distance = session.num("total_distance")?.takeIf { it > 0.0 } ?: activity.distanceMeters
        val recordedMoving = session.num("total_moving_time")?.takeIf { it > 0.0 }
        val moving = recordedMoving ?: derivedMoving
        val movingDerived = recordedMoving == null && derivedMoving != null

        val speed = speedMetrics(session, samples, timer, distance)
        val cadence = cadenceMetrics(session, samples, context.isRunning)
        val elevation = elevationMetrics(session, samples, activity)
        val training = trainingMetrics(context)
        val bodyBattery = bodyBatteryMetrics(context.metrics, context.user, samples)
        val temperature = temperatureMetrics(session, samples)

        val avgStride = (session.num("avg_step_length")?.div(1000.0)
            ?: samples.mapNotNull { it.strideLengthM }.filter { it > 0.0 }.averageOrNull())
            ?.takeIf { it > 0.0 }
        val avgVerticalOscillation = (session.num("avg_vertical_oscillation")
            ?: samples.mapNotNull { it.verticalOscillationMm }.filter { it > 0.0 }.averageOrNull())
            ?.takeIf { it > 0.0 }
        val avgVerticalRatio = (session.num("avg_vertical_ratio")
            ?: samples.mapNotNull { it.verticalRatioPct }.filter { it > 0.0 }.averageOrNull())
            ?.takeIf { it > 0.0 }
        val avgGroundContact = (session.num("avg_stance_time")
            ?: samples.mapNotNull { it.groundContactMs }.filter { it > 0.0 }.averageOrNull())
            ?.takeIf { it > 0.0 }

        val avgPower = (session.num("avg_power")
            ?: samples.mapNotNull { it.powerW }.filter { it > 0.0 }.averageOrNull())
            ?.takeIf { it > 0.0 }
        val maxPower = (session.num("max_power")
            ?: samples.mapNotNull { it.powerW }.maxOrNull())?.takeIf { it > 0.0 }
        val normalizedPower = session.num("normalized_power")?.takeIf { it > 0.0 }

        val totalCalories = session.num("total_calories")?.roundToInt()?.takeIf { it > 0 }
            ?: activity.calories
        val fatCalories = session.num("total_fat_calories")?.roundToInt()
        val activeCalories = context.metrics.num("active_calories")?.roundToInt()
            ?: context.metrics.num("active_calorie")?.roundToInt()
        val restingCalories = context.metrics.num("resting_calories")?.roundToInt()
            ?: context.metrics.num("resting_calorie")?.roundToInt()

        return ActivitySessionMetrics(
            timerSeconds = timer,
            elapsedSeconds = elapsed,
            movingSeconds = moving,
            movingDerived = movingDerived,
            distanceMeters = distance,
            avgPaceSecondsPerKm = pace(timer, distance),
            movingPaceSecondsPerKm = moving?.let { pace(it, distance) },
            bestPaceSecondsPerKm = speed.maxSpeed?.takeIf { it > 0.1 }?.let { 1000.0 / it },
            avgSpeedKmh = speed.avgSpeed?.times(3.6),
            movingSpeedKmh = moving?.takeIf { it > 0.0 && distance > 0.0 }?.let { distance / it * 3.6 },
            maxSpeedKmh = speed.maxSpeed?.times(3.6),
            avgHeartRate = session.num("avg_heart_rate")?.roundToInt()?.takeIf { it in 1..250 }
                ?: activity.avgHeartRate?.takeIf { it in 1..250 },
            maxHeartRate = session.num("max_heart_rate")?.roundToInt()?.takeIf { it in 1..250 }
                ?: activity.maxHeartRate?.takeIf { it in 1..250 },
            minHeartRate = session.num("min_heart_rate")?.roundToInt()?.takeIf { it in 1..250 }
                ?: samples.mapNotNull { it.heartRate }.minOrNull(),
            avgCadenceSpm = cadence.avgCadence,
            maxCadenceSpm = cadence.maxCadence,
            avgStrideLengthM = avgStride,
            avgVerticalOscillationMm = avgVerticalOscillation,
            avgVerticalRatioPct = avgVerticalRatio,
            avgGroundContactMs = avgGroundContact,
            avgPowerW = avgPower,
            maxPowerW = maxPower,
            normalizedPowerW = normalizedPower,
            totalCalories = totalCalories,
            fatCalories = fatCalories,
            activeCalories = activeCalories,
            restingCalories = restingCalories,
            ascentMeters = elevation.ascent,
            descentMeters = elevation.descent,
            minAltitudeMeters = elevation.minAltitude,
            maxAltitudeMeters = elevation.maxAltitude,
            avgTemperatureC = temperature.avg,
            minTemperatureC = temperature.min,
            maxTemperatureC = temperature.max,
            aerobicTrainingEffect = training.aerobic,
            anaerobicTrainingEffect = training.anaerobic,
            exerciseLoad = training.exerciseLoad,
            recoveryMinutes = training.recoveryMinutes,
            primaryBenefit = training.primaryBenefit,
            performanceCondition = training.performanceCondition,
            vo2Max = training.vo2Max,
            bodyBatteryStart = bodyBattery.start,
            bodyBatteryEnd = bodyBattery.end,
            bodyBatteryNet = bodyBattery.net,
            avgRespiration = (session.num("enhanced_avg_respiration_rate")
                ?: session.num("avg_respiration_rate"))?.takeIf { it > 0.0 },
            avgSpo2 = session.num("avg_spo2")?.roundToInt()?.takeIf { it in 1..100 },
            avgStress = session.num("avg_stress")?.roundToInt()?.takeIf { it in 0..100 },
        )
    }

    private fun speedMetrics(
        session: JSONObject?,
        samples: List<ActivitySample>,
        timerSeconds: Double,
        distanceMeters: Double,
    ): SpeedMetrics {
        val avgSpeed = (session.num("enhanced_avg_speed") ?: session.num("avg_speed"))
            ?.takeIf { it > 0.0 }
            ?: distanceMeters.takeIf { it > 0.0 && timerSeconds > 0.0 }?.let { it / timerSeconds }
        val maxSpeed = (session.num("enhanced_max_speed") ?: session.num("max_speed"))
            ?.takeIf { it > 0.0 }
            ?: samples.mapNotNull { it.speedMps }.maxOrNull()?.takeIf { it > 0.0 }
        return SpeedMetrics(avgSpeed = avgSpeed, maxSpeed = maxSpeed)
    }

    private fun cadenceMetrics(
        session: JSONObject?,
        samples: List<ActivitySample>,
        isRunning: Boolean,
    ): CadenceMetrics {
        val avgCadenceRaw = session.num("avg_cadence")
            ?.plus(session.num("avg_fractional_cadence") ?: 0.0)
            ?.takeIf { it > 0.0 }
        val maxCadenceRaw = session.num("max_cadence")
            ?.plus(session.num("max_fractional_cadence") ?: 0.0)
            ?.takeIf { it > 0.0 }
        val cadenceFactor = if (isRunning) 2.0 else 1.0
        val avgCadence = avgCadenceRaw?.times(cadenceFactor)
            ?: samples.mapNotNull { it.cadenceSpm }.filter { it > 0.0 }.averageOrNull()
        val maxCadence = maxCadenceRaw?.times(cadenceFactor)
            ?: samples.mapNotNull { it.cadenceSpm }.maxOrNull()
        return CadenceMetrics(avgCadence = avgCadence, maxCadence = maxCadence)
    }

    private fun elevationMetrics(
        session: JSONObject?,
        samples: List<ActivitySample>,
        activity: ActivityEntity,
    ): ElevationMetrics {
        val derived = derivedElevation(samples)
        val ascent = session.num("total_ascent")
            ?: activity.ascentMeters?.toDouble()
            ?: derived.ascent
        val descent = session.num("total_descent")
            ?: activity.descentMeters?.toDouble()
            ?: derived.descent
        val minAltitude = session.num("enhanced_min_altitude")
            ?: session.num("min_altitude")
            ?: samples.mapNotNull { it.altitudeMeters }.minOrNull()
        val maxAltitude = session.num("enhanced_max_altitude")
            ?: session.num("max_altitude")
            ?: samples.mapNotNull { it.altitudeMeters }.maxOrNull()
        return ElevationMetrics(
            ascent = ascent,
            descent = descent,
            minAltitude = minAltitude,
            maxAltitude = maxAltitude,
        )
    }

    private fun trainingMetrics(context: AnalysisContext): TrainingMetrics {
        val metrics = context.metrics
        val session = context.session
        val aerobic = metrics.num("aerobic_training_effect")
            ?: session.num("total_training_effect")
        val anaerobic = metrics.num("anaerobic_training_effect")
            ?: session.num("total_anaerobic_training_effect")
        val exerciseLoad = metrics.num("training_load_peak_1")
            ?: metrics.num("training_load_peak_2")
            ?: session.num("training_load_peak")
        val recovery = metrics.num("recovery_time")?.roundToInt()
        val primaryBenefit = primaryBenefitLabel(metrics.num("primary_benefit")?.roundToInt())
        val performanceCondition = metrics.num("final_performance_condition")?.roundToInt()
            ?: context.samples.lastOrNull { it.performanceCondition != null }?.performanceCondition
        val vo2Max = validVo2Max(metrics.num("vo2_max")) ?: validVo2Max(context.user.num("vo2_max"))
        return TrainingMetrics(
            aerobic = aerobic,
            anaerobic = anaerobic,
            exerciseLoad = exerciseLoad,
            recoveryMinutes = recovery,
            primaryBenefit = primaryBenefit,
            performanceCondition = performanceCondition,
            vo2Max = vo2Max,
        )
    }

    private fun bodyBatteryMetrics(
        metrics: JSONObject?,
        user: JSONObject?,
        samples: List<ActivitySample>,
    ): BodyBatteryMetrics {
        val start = user.num("initial_body_battery")?.roundToInt()
            ?: samples.firstNotNullOfOrNull { it.bodyBattery }
        val end = metrics.num("final_body_battery")?.roundToInt()
            ?: samples.lastOrNull { it.bodyBattery != null }?.bodyBattery
        val net = if (start != null && end != null) end - start else null
        return BodyBatteryMetrics(start = start, end = end, net = net)
    }

    private fun temperatureMetrics(
        session: JSONObject?,
        samples: List<ActivitySample>,
    ): TemperatureMetrics {
        val avg = session.num("avg_temperature")
            ?: samples.mapNotNull { it.temperatureC }.averageOrNull()
        val min = session.num("min_temperature")
            ?: samples.mapNotNull { it.temperatureC }.minOrNull()
        val max = session.num("max_temperature")
            ?: samples.mapNotNull { it.temperatureC }.maxOrNull()
        return TemperatureMetrics(avg = avg, min = min, max = max)
    }

    private class AnalysisContext(
        val activity: ActivityEntity,
        val session: JSONObject?,
        val metrics: JSONObject?,
        val user: JSONObject?,
        val zoneMessage: JSONObject?,
        val samples: List<ActivitySample>,
        val runWalk: RunWalkSplit?,
        val isRunning: Boolean,
    ) {
        val zoneSource: JSONObject? get() = zoneMessage ?: session
    }

    private data class SpeedMetrics(val avgSpeed: Double?, val maxSpeed: Double?)

    private data class CadenceMetrics(val avgCadence: Double?, val maxCadence: Double?)

    private data class ElevationMetrics(
        val ascent: Double?,
        val descent: Double?,
        val minAltitude: Double?,
        val maxAltitude: Double?,
    )

    private data class TrainingMetrics(
        val aerobic: Double?,
        val anaerobic: Double?,
        val exerciseLoad: Double?,
        val recoveryMinutes: Int?,
        val primaryBenefit: String?,
        val performanceCondition: Int?,
        val vo2Max: Double?,
    )

    private data class BodyBatteryMetrics(val start: Int?, val end: Int?, val net: Int?)

    private data class TemperatureMetrics(val avg: Double?, val min: Double?, val max: Double?)

    private fun buildSamples(
        records: List<RecordEntity>,
        recordMessages: List<FitMessageEntity>,
        isRunning: Boolean,
    ): List<ActivitySample> {
        val extras = HashMap<Long, ArrayDeque<JSONObject>>()
        for (message in recordMessages) {
            val timestamp = message.timestamp ?: continue
            val json = message.jsonOrNull() ?: continue
            extras.getOrPut(timestamp) { ArrayDeque() }.addLast(json)
        }
        val firstTimestamp = records.firstOrNull()?.timestamp ?: 0L
        if (records.isEmpty()) {
            val first = recordMessages.firstOrNull()?.timestamp ?: 0L
            return recordMessages.mapNotNull { message ->
                val json = message.jsonOrNull() ?: return@mapNotNull null
                val timestamp = message.timestamp ?: return@mapNotNull null
                sampleFrom(
                    record = null,
                    extra = json,
                    timestamp = timestamp,
                    elapsed = (timestamp - first).coerceAtLeast(0L).toDouble(),
                    isRunning = isRunning,
                )
            }
        }
        return records.mapIndexed { index, record ->
            val extra = extras[record.timestamp]?.removeFirstOrNull()
            sampleFrom(
                record = record,
                extra = extra,
                timestamp = record.timestamp,
                elapsed = if (firstTimestamp > 0L) {
                    (record.timestamp - firstTimestamp).coerceAtLeast(0L).toDouble()
                } else {
                    index.toDouble()
                },
                isRunning = isRunning,
            )
        }
    }

    private fun sampleFrom(
        record: RecordEntity?,
        extra: JSONObject?,
        timestamp: Long,
        elapsed: Double,
        isRunning: Boolean,
    ): ActivitySample {
        val json = extra ?: JSONObject()
        val rawCadence = record?.cadence?.toDouble() ?: json.num("cadence")
        val fractional = json.num("fractional_cadence") ?: 0.0
        val cadence = rawCadence?.let {
            (it + fractional) * if (isRunning) 2.0 else 1.0
        }?.takeIf { it in 0.0..300.0 }
        val speed = (record?.speedMps
            ?: json.num("enhanced_speed")
            ?: json.num("speed"))?.takeIf { it in 0.0..100.0 }
        val altitude = (record?.altitudeMeters
            ?: json.num("enhanced_altitude")
            ?: json.num("altitude"))?.takeIf { it in -500.0..9000.0 }
        val heartRate = (record?.heartRate ?: json.num("heart_rate")?.roundToInt())
            ?.takeIf { it in 20..250 }
        return ActivitySample(
            timestamp = timestamp,
            elapsedSeconds = elapsed,
            distanceMeters = (record?.distanceMeters ?: json.num("distance"))?.takeIf { it >= 0.0 },
            heartRate = heartRate,
            speedMps = speed,
            altitudeMeters = altitude,
            cadenceSpm = cadence,
            powerW = json.num("power")?.takeIf { it in 0.0..3000.0 },
            strideLengthM = json.num("step_length")?.div(1000.0)?.takeIf { it in 0.0..3.0 },
            verticalOscillationMm = json.num("vertical_oscillation")?.takeIf { it in 0.0..200.0 },
            verticalRatioPct = json.num("vertical_ratio")?.takeIf { it in 0.0..30.0 },
            groundContactMs = json.num("stance_time")?.takeIf { it in 0.0..1000.0 },
            performanceCondition = (json.num("performance_condition")
                ?: json.num("field_90"))?.roundToInt()?.takeIf { it in -20..20 },
            bodyBattery = (record?.bodyBattery
                ?: json.num("body_battery")?.roundToInt())?.takeIf { it in 0..100 },
            temperatureC = json.num("temperature")?.takeIf { it in -60.0..80.0 },
        )
    }

    private fun buildRunWalk(samples: List<ActivitySample>): Pair<RunWalkSplit, List<MovementSegment>>? {
        if (samples.size < 2) return null
        val speedSamples = samples.count { it.speedMps != null }
        if (speedSamples < samples.size / 2) return null
        var running = 0.0
        var walking = 0.0
        var idle = 0.0
        val segments = ArrayList<MovementSegment>()
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val duration = (current.elapsedSeconds - previous.elapsedSeconds).coerceIn(0.0, 10.0)
            if (duration <= 0.0) continue
            val speed = current.speedMps ?: previous.speedMps ?: continue
            val kind = when {
                speed >= 2.0 -> MovementKind.Run
                speed >= 0.3 -> MovementKind.Walk
                else -> MovementKind.Idle
            }
            when (kind) {
                MovementKind.Run -> running += duration
                MovementKind.Walk -> walking += duration
                MovementKind.Idle -> idle += duration
            }
            val last = segments.lastOrNull()
            if (last != null &&
                last.kind == kind &&
                kotlin.math.abs(last.endElapsedSeconds - previous.elapsedSeconds) < 0.001
            ) {
                segments[segments.lastIndex] = last.copy(
                    endElapsedSeconds = current.elapsedSeconds,
                    endDistanceMeters = current.distanceMeters,
                )
            } else {
                segments.add(
                    MovementSegment(
                        startElapsedSeconds = previous.elapsedSeconds,
                        endElapsedSeconds = current.elapsedSeconds,
                        startDistanceMeters = previous.distanceMeters,
                        endDistanceMeters = current.distanceMeters,
                        kind = kind,
                    ),
                )
            }
        }
        if (running + walking + idle <= 0.0) return null
        return RunWalkSplit(
            runningSeconds = running,
            walkingSeconds = walking,
            idleSeconds = idle,
            estimated = true,
        ) to segments
    }

    private fun buildLaps(
        laps: List<LapEntity>,
        lapMessages: List<FitMessageEntity>,
        isRunning: Boolean,
    ): List<LapInsights> {
        val jsons = lapMessages.mapNotNull { it.jsonOrNull() }
        val byIndex = jsons.mapNotNull { json ->
            val index = json.optInt("message_index", -1)
            if (index < 0) null else index to json
        }.toMap()
        val cadenceFactor = if (isRunning) 2.0 else 1.0
        return laps.sortedBy { it.lapIndex }.mapIndexed { position, lap ->
            val json = byIndex[lap.lapIndex] ?: jsons.getOrNull(position) ?: JSONObject()
            val cadence = json.num("avg_cadence")
                ?.plus(json.num("avg_fractional_cadence") ?: 0.0)
                ?.times(cadenceFactor)
            LapInsights(
                index = lap.lapIndex,
                distanceMeters = lap.distanceMeters,
                durationSeconds = lap.durationSeconds,
                paceSecondsPerKm = pace(lap.durationSeconds, lap.distanceMeters),
                avgHeartRate = lap.avgHeartRate,
                maxHeartRate = lap.maxHeartRate,
                calories = lap.calories,
                ascentMeters = lap.ascentMeters,
                descentMeters = lap.descentMeters,
                cadenceSpm = cadence,
                strideLengthM = json.num("avg_step_length")?.div(1000.0),
                verticalOscillationMm = json.num("avg_vertical_oscillation"),
                verticalRatioPct = json.num("avg_vertical_ratio"),
                groundContactMs = json.num("avg_stance_time"),
                powerW = json.num("avg_power"),
            )
        }
    }

    private fun buildZoneSet(
        json: JSONObject?,
        valuesKey: String,
        boundaryKey: String,
        labels: List<String>,
        maxHeartRate: Int?,
        thresholdPowerW: Int?,
    ): ActivityZoneSet? {
        if (json == null) return null
        val values = json.numList(valuesKey)
        if (values.size < 5) return null
        // Watch FIT zone arrays include one bucket below zone 1 and may include
        // buckets above zone 5. Garmin Connect displays the five middle zones.
        val selected = if (values.size >= 6) values.subList(1, 6) else values.subList(0, 5)
        val total = selected.sum()
        if (total <= 0.0) return null
        val boundaries = json.numList(boundaryKey)
        val zones = selected.mapIndexed { index, seconds ->
            ActivityZone(
                index = index + 1,
                label = labels.getOrElse(index) { "Zone ${index + 1}" },
                lowBoundary = boundaries.getOrNull(index)?.roundToInt(),
                highBoundary = boundaries.getOrNull(index + 1)?.roundToInt(),
                seconds = seconds,
                percent = (seconds / total * 100.0).toFloat(),
            )
        }
        return ActivityZoneSet(
            zones = zones,
            totalSeconds = total,
            fromWatch = true,
            maxHeartRate = maxHeartRate,
            thresholdPowerW = thresholdPowerW,
        )
    }

    private fun primaryBenefitLabel(value: Int?): String? = when (value) {
        0 -> "No benefit"
        1 -> "Recovery"
        2 -> "Base (aerobic low)"
        3 -> "Tempo"
        4 -> "Threshold"
        5 -> "VO2 max"
        6 -> "Anaerobic"
        7 -> "Sprint"
        else -> null
    }

    private fun pace(seconds: Double, meters: Double): Double? =
        if (seconds > 0.0 && meters > 0.0) seconds / (meters / 1000.0) else null

    private data class ElevationChange(val ascent: Double?, val descent: Double?)

    private fun derivedElevation(samples: List<ActivitySample>): ElevationChange {
        var ascent = 0.0
        var descent = 0.0
        var previous: Double? = null
        for (sample in samples) {
            val altitude = sample.altitudeMeters ?: continue
            previous?.let {
                if (altitude > it) ascent += altitude - it else descent += it - altitude
            }
            previous = altitude
        }
        return ElevationChange(
            ascent = ascent.takeIf { it > 0.0 },
            descent = descent.takeIf { it > 0.0 },
        )
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()

    private fun FitMessageEntity.jsonOrNull(): JSONObject? =
        runCatching { JSONObject(fieldsJson) }.getOrNull()

    private fun JSONObject?.num(key: String): Double? {
        val json = this ?: return null
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
    }

    private fun JSONObject?.numList(key: String): List<Double> {
        val json = this ?: return emptyList()
        if (!json.has(key) || json.isNull(key)) return emptyList()
        return when (val value = json.opt(key)) {
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                value.optDouble(index).takeIf { it.isFinite() }
            }
            is String -> value.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            is Number -> listOf(value.toDouble())
            else -> emptyList()
        }
    }

    private val METRIC_MESSAGES = setOf("activity_metrics", "msg_140")
    private val USER_METRIC_MESSAGES = setOf("user_metrics", "msg_79")
}
