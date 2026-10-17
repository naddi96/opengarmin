package dev.opengarmin.app.data

import org.json.JSONObject
import kotlin.math.roundToInt

/** Raw rows joined from `fit_messages` for the training tab. */
data class TrainingRow(
    val activityId: Long,
    val startTime: Long,
    val messageName: String,
    val fieldsJson: String,
)

data class TrainingPoint(val startTime: Long, val value: Double)

data class LoadFocus(
    val lowAerobic: Double,
    val highAerobic: Double,
    val anaerobic: Double,
) {
    val total: Double get() = lowAerobic + highAerobic + anaerobic
}

data class ActivityTrainingMetrics(
    val activityId: Long,
    val startTime: Long,
    val trainingLoad: Double?,
    val aerobicEffect: Double?,
    val anaerobicEffect: Double?,
    val primaryBenefit: Int?,
    val recoveryMinutes: Int?,
    val vo2Max: Double?,
    val performanceCondition: Int?,
    val finalBodyBattery: Int?,
    val lthr: Int?,
    val ltSpeedKmh: Double?,
    val initialBodyBattery: Int?,
)

data class TrainingSummary(
    val latestVo2Max: Double? = null,
    val vo2History: List<TrainingPoint> = emptyList(),
    val vo2Change: Double? = null,
    val recoveryRemainingMinutes: Int? = null,
    val recoveryActivityStart: Long? = null,
    val acuteLoad: Double? = null,
    val chronicLoad: Double? = null,
    val loadRatio: Double? = null,
    val focus: LoadFocus? = null,
    val latestActivity: ActivityEntity? = null,
    val latestMetrics: ActivityTrainingMetrics? = null,
    val lthr: Int? = null,
    val ltSpeedKmh: Double? = null,
) {
    val ltPaceSecondsPerKm: Int?
        get() = ltSpeedKmh?.takeIf { it > 0.0 }?.let { (3600.0 / it).roundToInt() }

    val hasAnyData: Boolean
        get() = latestVo2Max != null || latestMetrics != null || acuteLoad != null
}

/**
 * Parses Garmin's undocumented `activity_metrics` (140) and `user_metrics`
 * (79) fields. Both the new named fields and the legacy `field_<n>` keys are
 * supported so activities imported before the profile overlay still work.
 */
object TrainingMetrics {

    fun build(
        activities: List<ActivityEntity>,
        rows: List<TrainingRow>,
        now: Long = System.currentTimeMillis() / 1000,
    ): TrainingSummary {
        if (activities.isEmpty()) return TrainingSummary()

        val metricsByActivity = HashMap<Long, ActivityTrainingMetrics>()
        val sessionLoadByActivity = HashMap<Long, Double>()
        val zonesLthrByActivity = HashMap<Long, Int>()
        for (row in rows) {
            val json = runCatching { JSONObject(row.fieldsJson) }.getOrNull() ?: continue
            when (row.messageName) {
                "activity_metrics", "msg_140" -> {
                    metricsByActivity[row.activityId] = parseActivityMetrics(row, json)
                }
                "user_metrics", "msg_79" -> {
                    val existing = metricsByActivity[row.activityId]
                    val vo2 = number(json, "vo2_max", "field_0")?.let {
                        if (json.has("field_0")) it / 292.57142857142856 else it
                    }
                    val lthr = number(json, "lthr", "field_11")?.roundToInt()
                    val ltSpeed = number(json, "ltspeed", "field_13")?.let {
                        if (json.has("field_13")) it / 10.0 else it
                    }
                    val initial = number(json, "initial_body_battery", "field_15")?.roundToInt()
                    metricsByActivity[row.activityId] = (existing ?: ActivityTrainingMetrics(
                        activityId = row.activityId,
                        startTime = row.startTime,
                        trainingLoad = null,
                        aerobicEffect = null,
                        anaerobicEffect = null,
                        primaryBenefit = null,
                        recoveryMinutes = null,
                        vo2Max = null,
                        performanceCondition = null,
                        finalBodyBattery = null,
                        lthr = null,
                        ltSpeedKmh = null,
                        initialBodyBattery = null,
                    )).copy(
                        vo2Max = existing?.vo2Max ?: vo2,
                        lthr = existing?.lthr ?: lthr,
                        ltSpeedKmh = existing?.ltSpeedKmh ?: ltSpeed,
                        initialBodyBattery = existing?.initialBodyBattery ?: initial,
                    )
                }
                "session" -> {
                    val load = number(json, "training_load_peak")
                    if (load != null) sessionLoadByActivity[row.activityId] = load
                }
                "zones_target" -> {
                    val lthr = number(json, "threshold_heart_rate")?.roundToInt()
                    if (lthr != null && lthr in 1..250) zonesLthrByActivity[row.activityId] = lthr
                }
            }
        }

        // Use the session load when the activity_metrics message is absent.
        for ((activityId, load) in sessionLoadByActivity) {
            val metrics = metricsByActivity[activityId] ?: continue
            if (metrics.trainingLoad == null) {
                metricsByActivity[activityId] = metrics.copy(trainingLoad = load)
            }
        }
        for ((activityId, lthr) in zonesLthrByActivity) {
            val metrics = metricsByActivity[activityId] ?: continue
            if (metrics.lthr == null) metricsByActivity[activityId] = metrics.copy(lthr = lthr)
        }

        val sorted = activities.sortedByDescending { it.startTime }
        val latestWithVo2 = sorted.firstNotNullOfOrNull { activity ->
            metricsByActivity[activity.id]?.vo2Max?.let { activity to it }
        }
        val latestMetrics = sorted.firstNotNullOfOrNull { activity ->
            metricsByActivity[activity.id]
        }

        val sixWeeksAgo = now - 42L * 86_400L
        val vo2History = sorted
            .asReversed()
            .mapNotNull { activity ->
                val value = metricsByActivity[activity.id]?.vo2Max ?: return@mapNotNull null
                if (activity.startTime < sixWeeksAgo) return@mapNotNull null
                TrainingPoint(activity.startTime, value)
            }
            .distinctBy { it.startTime / 86_400L }
        val vo2Change = if (vo2History.size >= 2) {
            vo2History.last().value - vo2History.first().value
        } else {
            null
        }

        val latestRecovery = sorted.firstNotNullOfOrNull { activity ->
            val metrics = metricsByActivity[activity.id] ?: return@firstNotNullOfOrNull null
            val minutes = metrics.recoveryMinutes ?: return@firstNotNullOfOrNull null
            val end = activity.startTime + activity.durationSeconds.toLong()
            val remaining = (minutes - (now - end) / 60.0).roundToInt()
            if (remaining > 0) activity to remaining else null
        }

        val acute = sorted
            .filter { it.startTime >= now - 7L * 86_400L }
            .mapNotNull { metricsByActivity[it.id]?.trainingLoad }
            .takeIf { it.isNotEmpty() }
            ?.sum()
        val chronic = sorted
            .filter { it.startTime >= now - 28L * 86_400L }
            .mapNotNull { metricsByActivity[it.id]?.trainingLoad }
            .takeIf { it.isNotEmpty() }
            ?.sum()
            ?.div(4.0)

        val focus = if (acute != null && acute > 0.0) {
            val week = sorted.filter { it.startTime >= now - 7L * 86_400L }
            var low = 0.0
            var high = 0.0
            var anaerobic = 0.0
            for (activity in week) {
                val metrics = metricsByActivity[activity.id] ?: continue
                val load = metrics.trainingLoad ?: continue
                when {
                    metrics.primaryBenefit == 1 || metrics.primaryBenefit == 2 -> low += load
                    metrics.primaryBenefit == 6 || metrics.primaryBenefit == 7 -> anaerobic += load
                    metrics.primaryBenefit == 3 ||
                        metrics.primaryBenefit == 4 ||
                        metrics.primaryBenefit == 5 -> high += load
                    (metrics.anaerobicEffect ?: 0.0) >= 2.0 -> anaerobic += load
                    (metrics.aerobicEffect ?: 0.0) > 0.0 -> high += load
                    else -> low += load
                }
            }
            LoadFocus(low, high, anaerobic).takeIf { it.total > 0.0 }
        } else {
            null
        }

        val latestMetricsWithData = latestMetrics ?: metricsByActivity[sorted.first().id]
        return TrainingSummary(
            latestVo2Max = latestWithVo2?.second,
            vo2History = vo2History,
            vo2Change = vo2Change,
            recoveryRemainingMinutes = latestRecovery?.second,
            recoveryActivityStart = latestRecovery?.first?.startTime,
            acuteLoad = acute,
            chronicLoad = chronic,
            loadRatio = if (acute != null && chronic != null && chronic > 0.0) acute / chronic else null,
            focus = focus,
            latestActivity = sorted.firstOrNull(),
            latestMetrics = latestMetricsWithData,
            lthr = latestMetricsWithData?.lthr,
            ltSpeedKmh = latestMetricsWithData?.ltSpeedKmh,
        )
    }

    private fun parseActivityMetrics(row: TrainingRow, json: JSONObject): ActivityTrainingMetrics {
        val vo2 = number(json, "vo2_max") ?: number(json, "field_7")?.let { it * 3.5 / 65536.0 }
        val load = number(json, "training_load_peak_1")
            ?: number(json, "training_load_peak_2")
            ?: number(json, "field_37")?.div(65536.0)
            ?: number(json, "field_39")?.div(65536.0)
        return ActivityTrainingMetrics(
            activityId = row.activityId,
            startTime = row.startTime,
            trainingLoad = load,
            aerobicEffect = number(json, "aerobic_training_effect")
                ?: number(json, "field_4")?.div(10.0),
            anaerobicEffect = number(json, "anaerobic_training_effect")
                ?: number(json, "field_20")?.div(10.0),
            primaryBenefit = number(json, "primary_benefit", "field_41")?.roundToInt(),
            recoveryMinutes = number(json, "recovery_time", "field_9")?.roundToInt(),
            vo2Max = vo2,
            performanceCondition = number(json, "final_performance_condition", "field_17")?.roundToInt(),
            finalBodyBattery = number(json, "final_body_battery", "field_25")?.roundToInt(),
            lthr = number(json, "lthr", "field_14")?.roundToInt(),
            ltSpeedKmh = number(json, "ltspeed", "field_16")?.let {
                if (json.has("field_16")) it / 10.0 else it
            },
            initialBodyBattery = null,
        )
    }

    private fun number(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.optDouble(key, Double.NaN)
            if (value.isFinite()) return value
        }
        return null
    }
}
