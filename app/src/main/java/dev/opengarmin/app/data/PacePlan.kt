package dev.opengarmin.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.opengarmin.protocol.FitPaceBand
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** A race distance offered by the PacePro builder. */
enum class PaceRace(
    val id: String,
    val label: String,
    val distanceMeters: Double,
) {
    FIVE_K("5K", "5K", 5_000.0),
    TEN_K("10K", "10K", 10_000.0),
    HALF("Half", "Half", 21_097.5),
    MARATHON("Marathon", "Marathon", 42_195.0),
    ;

    companion object {
        fun from(id: String?): PaceRace = entries.firstOrNull { it.id == id } ?: TEN_K
    }
}

enum class PaceAlert(val id: String, val label: String) {
    KILOMETRE("km", "Every km"),
    MILE("mi", "Every mile"),
    ELEVATION("elev", "Elevation"),
    ;

    companion object {
        fun from(id: String?): PaceAlert = entries.firstOrNull { it.id == id } ?: KILOMETRE
    }
}

/** One split in a saved PacePro plan. */
data class PacePlanSplit(
    val index: Int,
    val distanceMeters: Double,
    val cumulativeDistanceMeters: Double,
    val targetSeconds: Int,
    val cumulativeSeconds: Int,
    val gainMeters: Double,
) {
    val avgSpeedMps: Double
        get() = if (targetSeconds > 0) distanceMeters / targetSeconds else 0.0

    fun paceSecondsPerKm(): Int = if (distanceMeters > 0) {
        (targetSeconds * 1000.0 / distanceMeters).roundToInt()
    } else {
        0
    }
}

/** A course used to derive elevation-aware splits. */
data class PaceCourse(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    val gainsPerKm: List<Double>,
    val activityId: Long? = null,
) {
    val totalGainMeters: Double get() = gainsPerKm.sum()
}

/**
 * A saved PacePro strategy. Splits are stored as compact JSON so the database
 * schema stays small; the original inputs are kept so the plan can be edited
 * or recalculated without the phone.
 */
@Entity(tableName = "pace_plans")
data class PacePlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val race: String,
    val distanceMeters: Double,
    val goalSeconds: Int,
    val courseName: String,
    val courseActivityId: Long? = null,
    /** 0 = strong negative split, 50 = even, 100 = strong positive split. */
    val splitSlider: Int = 50,
    /** 0 = easier on climbs, 50 = balanced, 100 = harder on climbs. */
    val hillSlider: Int = 50,
    val alert: String = PaceAlert.KILOMETRE.id,
    val splitsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSentAt: Long? = null,
) {
    val splits: List<PacePlanSplit> get() = PacePlanCodec.decodeSplits(splitsJson)

    fun summary(): String {
        val distance = "%.2f".format(Locale.US, distanceMeters / 1000.0)
        return "$distance km · ${splits.size} splits · ${PacePlanCalculator.formatClock(goalSeconds)}"
    }

    fun toFitPaceBand(): FitPaceBand.Plan {
        val rows = splits
        val avgSpeed = if (goalSeconds > 0) distanceMeters / goalSeconds else 0.0
        var cumulativeTime = 0.0
        var cumulativeDistance = 0.0
        val fitSplits = rows.map { split ->
            cumulativeTime += split.targetSeconds.toDouble()
            cumulativeDistance += split.distanceMeters
            FitPaceBand.Split(
                index = split.index,
                cumulativeTimeSeconds = cumulativeTime,
                cumulativeDistanceMeters = cumulativeDistance,
                targetSplitTimeSeconds = split.targetSeconds.toDouble(),
                targetSplitDistanceMeters = split.distanceMeters,
                targetSplitAvgSpeedMps = split.avgSpeedMps,
            )
        }
        return FitPaceBand.Plan(
            name = name,
            goalTotalTimeSeconds = goalSeconds.toDouble(),
            goalDistanceMeters = distanceMeters,
            goalAvgSpeedMps = avgSpeed,
            splitCount = fitSplits.size,
            segmentationType = if (alert == PaceAlert.ELEVATION.id) {
                FitPaceBand.SEGMENTATION_ELEVATION
            } else {
                FitPaceBand.SEGMENTATION_FIXED_DISTANCE
            },
            pacingStrategy = PacePlanCalculator.strategySecondsPerKm(splitSlider).toDouble(),
            hillEffort = PacePlanCalculator.hillEffort(hillSlider),
            containsManualPaceEdits = false,
            splits = fitSplits,
        )
    }
}

/** UI-facing state for the asynchronous upload to the watch. */
data class PacePlanSendStatus(
    val planId: Long,
    val name: String,
    val sending: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
)

object PacePlanCodec {
    private const val VERSION = 1

    fun encodeSplits(splits: List<PacePlanSplit>): String {
        val root = JSONObject().put("version", VERSION)
        root.put(
            "splits",
            JSONArray().apply {
                splits.forEach { split ->
                    put(
                        JSONObject()
                            .put("index", split.index)
                            .put("distance", split.distanceMeters)
                            .put("cumulativeDistance", split.cumulativeDistanceMeters)
                            .put("targetSeconds", split.targetSeconds)
                            .put("cumulativeSeconds", split.cumulativeSeconds)
                            .put("gain", split.gainMeters),
                    )
                }
            },
        )
        return root.toString()
    }

    fun decodeSplits(json: String): List<PacePlanSplit> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(json).optJSONArray("splits") ?: JSONArray()
            ArrayList<PacePlanSplit>().apply {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        PacePlanSplit(
                            index = obj.optInt("index", i),
                            distanceMeters = obj.optDouble("distance", 0.0),
                            cumulativeDistanceMeters = obj.optDouble("cumulativeDistance", 0.0),
                            targetSeconds = obj.optInt("targetSeconds", 0),
                            cumulativeSeconds = obj.optInt("cumulativeSeconds", 0),
                            gainMeters = obj.optDouble("gain", 0.0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** Deterministic split maths shared by the builder, preview and watch encoder. */
object PacePlanCalculator {
    private const val RIEGEL_EXPONENT = 1.06
    private const val PREDICTION_WINDOW_SECONDS = 180L * 24L * 60L * 60L

    /** Riegel prediction in seconds for [race] from one activity, if usable. */
    fun predict(activity: ActivityEntity, race: PaceRace): Int? {
        if (activity.sport != 1) return null
        val distance = activity.distanceMeters
        val duration = activity.durationSeconds
        if (distance < 1_000.0 || duration < 60.0) return null
        if (duration > 24.0 * 60.0 * 60.0) return null
        val ratio = race.distanceMeters / distance
        if (ratio < 0.2 || ratio > 8.0) return null
        return (duration * ratio.pow(RIEGEL_EXPONENT)).roundToInt()
    }

    /** Best Riegel prediction per race from recent runs on this phone. */
    fun predictions(
        activities: List<ActivityEntity>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Map<PaceRace, Int> {
        val recent = activities.filter { activity ->
            activity.startTime >= nowSeconds - PREDICTION_WINDOW_SECONDS
        }
        return PaceRace.entries.associateWith { race ->
            recent.mapNotNull { predict(it, race) }.minOrNull()
        }.filterValues { it != null }
            .mapValues { it.value ?: 0 }
    }

    /**
     * Estimates a goal for a custom distance. Uses the closest race prediction
     * as a Riegel baseline; returns null when there is no recent run to scale.
     */
    fun estimateGoal(
        distanceMeters: Double,
        predictions: Map<PaceRace, Int>,
    ): Int? {
        if (distanceMeters <= 0.0 || predictions.isEmpty()) return null
        val exact = predictions.entries.firstOrNull {
            kotlin.math.abs(it.key.distanceMeters - distanceMeters) < 1.0
        }
        if (exact != null) return exact.value
        val baseline = predictions.entries.minByOrNull {
            kotlin.math.abs(it.key.distanceMeters - distanceMeters)
        } ?: return null
        val ratio = distanceMeters / baseline.key.distanceMeters
        return (baseline.value * ratio.pow(RIEGEL_EXPONENT)).roundToInt()
    }

    fun strategySecondsPerKm(slider: Int): Int =
        (((slider.coerceIn(0, 100) - 50) / 50.0) * 10.0).roundToInt()

    fun hillEffort(slider: Int): Double =
        (slider.coerceIn(0, 100) - 50) / 50.0

    fun splitSliderLabel(slider: Int): String {
        val seconds = strategySecondsPerKm(slider)
        return when {
            seconds < 0 -> "Negative ${-seconds} s/km"
            seconds > 0 -> "Positive $seconds s/km"
            else -> "Even"
        }
    }

    fun hillSliderLabel(slider: Int): String = when {
        slider <= 30 -> "Easier"
        slider >= 70 -> "Harder"
        else -> "Balanced"
    }

    /**
     * Builds splits for [raceDistanceMeters] at [goalSeconds]. The strategy and
     * hill sliders follow the behaviour of Garmin's own PacePro builder: the
     * first half of the race is slowed for a negative split, and climbs add
     * time unless the athlete asks for a harder effort. The result is scaled so
     * the target times add up exactly to the goal.
     */
    fun buildSplits(
        raceDistanceMeters: Double,
        goalSeconds: Int,
        splitSlider: Int,
        hillSlider: Int,
        course: PaceCourse? = null,
        splitDistanceMeters: Double = 1_000.0,
    ): List<PacePlanSplit> {
        if (raceDistanceMeters <= 0.0 || goalSeconds <= 0) return emptyList()
        val splitCount = max(1, ceil(raceDistanceMeters / splitDistanceMeters).toInt())
        if (goalSeconds < splitCount) return emptyList()
        val gains = course?.let { resampleGains(it, raceDistanceMeters, splitCount, splitDistanceMeters) }
            ?: List(splitCount) { 0.0 }
        val basePace = goalSeconds / (raceDistanceMeters / splitDistanceMeters)
        val strategy = strategySecondsPerKm(splitSlider).toDouble()
        val hill = hillEffort(hillSlider)
        val half = splitCount / 2

        val raw = ArrayList<Double>(splitCount)
        for (i in 0 until splitCount) {
            val splitStart = i * splitDistanceMeters
            val splitLength = minOf(splitDistanceMeters, raceDistanceMeters - splitStart)
            if (splitLength <= 0.0) continue
            val weight = splitLength / splitDistanceMeters
            val strategyAdjust = if (i < half) -strategy else strategy
            val climbAdjust = gains[i] * 0.4 * (1.0 - hill)
            raw.add((basePace + strategyAdjust + climbAdjust) * weight)
        }
        if (raw.isEmpty()) return emptyList()

        val scale = goalSeconds / raw.sum()
        val exact = raw.map { it * scale }
        val seconds = exact.map { kotlin.math.floor(it).toInt().coerceAtLeast(1) }.toMutableList()
        distributeRemainder(exact, seconds, goalSeconds - seconds.sum())

        var cumulativeTime = 0
        var cumulativeDistance = 0.0
        return seconds.mapIndexed { index, targetSeconds ->
            cumulativeTime += targetSeconds
            val splitStart = index * splitDistanceMeters
            val splitLength = minOf(splitDistanceMeters, raceDistanceMeters - splitStart)
            cumulativeDistance += splitLength
            PacePlanSplit(
                index = index,
                distanceMeters = splitLength,
                cumulativeDistanceMeters = cumulativeDistance,
                targetSeconds = targetSeconds,
                cumulativeSeconds = cumulativeTime,
                gainMeters = gains.getOrElse(index) { 0.0 },
            )
        }
    }

    /**
     * Spreads [remainder] whole seconds across the floored [seconds] so they add
     * up to the goal. Positive remainders go to the largest fractional parts
     * first, negative remainders come off the smallest, wrapping as needed.
     */
    private fun distributeRemainder(
        exact: List<Double>,
        seconds: MutableList<Int>,
        remainder: Int,
    ) {
        if (remainder == 0) return
        val adding = remainder > 0
        val order = if (adding) {
            exact.indices.sortedByDescending { exact[it] - kotlin.math.floor(exact[it]) }
        } else {
            exact.indices.sortedBy { exact[it] - kotlin.math.floor(exact[it]) }
        }
        var remaining = remainder
        var cursor = 0
        while (remaining != 0) {
            val index = order[cursor % order.size]
            if (adding) {
                seconds[index] = seconds[index] + 1
                remaining--
            } else if (seconds[index] > 1) {
                seconds[index] = seconds[index] - 1
                remaining++
            }
            cursor++
        }
    }

    /** Resamples a course's per-kilometre climb profile onto race splits. */
    fun resampleGains(
        course: PaceCourse,
        raceDistanceMeters: Double,
        splitCount: Int,
        splitDistanceMeters: Double = 1_000.0,
    ): List<Double> {
        if (course.distanceMeters <= 0.0 || course.gainsPerKm.isEmpty()) {
            return List(splitCount) { 0.0 }
        }
        var cumulative = 0.0
        val cumulativeGains = ArrayList<Double>(course.gainsPerKm.size + 1)
        cumulativeGains.add(0.0)
        course.gainsPerKm.forEach { gain ->
            cumulative += max(0.0, gain)
            cumulativeGains.add(cumulative)
        }
        return (0 until splitCount).map { index ->
            val start = index * splitDistanceMeters
            val end = minOf((index + 1) * splitDistanceMeters, raceDistanceMeters)
            val courseStart = start / raceDistanceMeters * course.distanceMeters
            val courseEnd = end / raceDistanceMeters * course.distanceMeters
            max(0.0, gainAt(cumulativeGains, course.distanceMeters, courseEnd) -
                gainAt(cumulativeGains, course.distanceMeters, courseStart))
        }
    }

    private fun gainAt(cumulativeGains: List<Double>, courseDistance: Double, distance: Double): Double {
        if (courseDistance <= 0.0) return 0.0
        val position = (distance / courseDistance * (cumulativeGains.size - 1))
            .coerceIn(0.0, (cumulativeGains.size - 1).toDouble())
        val lower = position.toInt().coerceIn(0, cumulativeGains.lastIndex)
        val upper = (lower + 1).coerceAtMost(cumulativeGains.lastIndex)
        val fraction = position - lower
        return cumulativeGains[lower] + (cumulativeGains[upper] - cumulativeGains[lower]) * fraction
    }

    fun formatClock(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, secs)
        } else {
            "%d:%02d".format(Locale.US, minutes, secs)
        }
    }

    fun formatPace(secondsPerKm: Int): String {
        val safe = secondsPerKm.coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, safe / 60, safe % 60)
    }

    /** Elevation gain per split for drawing the preview bars. */
    fun gainsFor(plan: PacePlanEntity): List<Double> = plan.splits.map { it.gainMeters }

    /**
     * Builds a course from a recorded run. Records without coordinates or
     * altitude are ignored; the caller shows an honest empty state when no
     * usable course exists.
     */
    fun courseFromActivity(activity: ActivityEntity, records: List<RecordEntity>): PaceCourse? {
        if (activity.sport != 1) return null
        val points = records
            .filter { it.latitude != null && it.longitude != null }
            .sortedBy { it.timestamp }
        if (points.size < 2) return null

        var distance = 0.0
        var previous = points.first()
        val samples = ArrayList<Triple<Double, Double?, Double?>>(points.size)
        samples.add(Triple(0.0, previous.altitudeMeters, previous.latitude))
        for (point in points.drop(1)) {
            val previousDistance = previous.distanceMeters
            val pointDistance = point.distanceMeters
            val delta = if (pointDistance != null && previousDistance != null) {
                max(0.0, pointDistance - previousDistance)
            } else {
                haversineMeters(previous.latitude!!, previous.longitude!!, point.latitude!!, point.longitude!!)
            }
            distance += delta
            samples.add(Triple(distance, point.altitudeMeters, point.latitude))
            previous = point
        }
        if (distance < 1_000.0) return null

        val gains = ArrayList<Double>()
        var boundary = 0.0
        while (boundary < distance) {
            val start = boundary
            val end = minOf(boundary + 1_000.0, distance)
            var gain = 0.0
            var lastAltitude: Double? = null
            for (sample in samples) {
                if (sample.first < start) continue
                if (sample.first > end) break
                val altitude = sample.second
                if (altitude != null) {
                    val previousAltitude = lastAltitude
                    if (previousAltitude != null) {
                        val difference = altitude - previousAltitude
                        if (difference > 0.5) gain += difference
                    }
                    lastAltitude = altitude
                }
            }
            // The final partial kilometre is normalised to a full-kilometre gain,
            // so resampling does not make short tails look artificially flat.
            val length = end - start
            gains.add(if (length > 0.0) gain * (1_000.0 / length) else 0.0)
            boundary = end
        }

        val date = Instant.ofEpochSecond(activity.startTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        val distanceLabel = "%.1f".format(Locale.US, activity.distanceMeters / 1000.0)
        return PaceCourse(
            id = "activity-${activity.id}",
            name = "Run · $date · $distanceLabel km",
            distanceMeters = activity.distanceMeters.takeIf { it > 0.0 } ?: distance,
            gainsPerKm = gains,
            activityId = activity.id,
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return earthRadius * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
