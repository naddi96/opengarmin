package dev.opengarmin.app.data

import kotlin.math.roundToInt

/** One timestamped heart-rate observation used for zone calculations. */
data class HeartRateZoneSample(
    val timestamp: Long,
    val heartRate: Int?,
)

/**
 * Shared heart-rate zone calculation for the activity UI and the coach tools.
 *
 * Five zones are derived from the max heart rate configured in Settings:
 * 50–60%, 60–70%, 70–80%, 80–90% and 90–100%. Time is weighted by the interval
 * between samples; samples below zone 1 are not counted, matching the watch's
 * zone view. Sparse recordings fall back to one unit per sample so the card is
 * still useful.
 */
object HeartRateZones {
    private val LABELS = listOf("Warm up", "Easy", "Aerobic", "Threshold", "Maximum")

    fun zones(samples: List<HeartRateZoneSample>, maxHeartRate: Int?): ActivityZoneSet? {
        if (maxHeartRate == null || maxHeartRate !in 100..250) return null
        val sorted = samples.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return null
        val seconds = DoubleArray(5)
        var weighted = false
        var previous: HeartRateZoneSample? = null
        for (sample in sorted) {
            val before = previous
            if (before != null) {
                val delta = (sample.timestamp - before.timestamp).toDouble()
                val heartRate = before.heartRate
                if (heartRate != null && delta in 0.0..10.0) {
                    val index = indexOf(heartRate, maxHeartRate)
                    if (index >= 0) {
                        seconds[index] += delta
                        weighted = true
                    }
                }
            }
            previous = sample
        }
        if (!weighted) {
            sorted.forEach { sample ->
                val heartRate = sample.heartRate ?: return@forEach
                val index = indexOf(heartRate, maxHeartRate)
                if (index >= 0) seconds[index] += 1.0
            }
        }
        val total = seconds.sum()
        if (total <= 0.0) return null
        val zones = seconds.mapIndexed { index, value ->
            val lowFraction = 0.5 + index * 0.1
            val highFraction = 0.6 + index * 0.1
            ActivityZone(
                index = index + 1,
                label = LABELS[index],
                lowBoundary = (maxHeartRate * lowFraction).roundToInt(),
                highBoundary = (maxHeartRate * highFraction).roundToInt(),
                seconds = value,
                percent = (value / total * 100.0).toFloat(),
            )
        }
        return ActivityZoneSet(
            zones = zones,
            totalSeconds = total,
            fromWatch = false,
            maxHeartRate = maxHeartRate,
        )
    }

    fun percentages(samples: List<HeartRateZoneSample>, maxHeartRate: Int?): List<Float>? =
        zones(samples, maxHeartRate)?.zones?.map { it.percent }

    private fun indexOf(heartRate: Int, maxHeartRate: Int): Int {
        val fraction = heartRate.toDouble() / maxHeartRate
        return when {
            fraction < 0.50 -> -1
            fraction < 0.60 -> 0
            fraction < 0.70 -> 1
            fraction < 0.80 -> 2
            fraction < 0.90 -> 3
            else -> 4
        }
    }
}
