package dev.opengarmin.app.data

import dev.opengarmin.protocol.Fit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Turns the messages of a watch wellness file into per-day rows.
 *
 * The FR255 (and similar watches) put everything in a monitoring file:
 * steps/calories, heart rate, stress (with Body Battery in stress_level field
 * 3), SpO2 and respiration. Some watches also expose separate SLEEP_DATA
 * (`sleep_level` + `sleep_assessment`) and HRV (`hrv_status_summary`) files;
 * those are handled here as well so the same importer works for both.
 *
 * The parser never invents values: a metric with no message stays `null`.
 */
data class WellnessSampleDraft(
    val timestamp: Long,
    val kind: String,
    val value: Double,
)

data class SleepStageDraft(
    val startTime: Long,
    val endTime: Long,
    val stage: String,
)

data class WellnessDayDraft(
    val date: String,
    val hasMonitoring: Boolean,
    val steps: Int,
    val distanceMeters: Double,
    val activeCalories: Int,
    val activeSeconds: Int,
    val restingHeartRate: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val stressAvg: Int?,
    val spo2Avg: Int?,
    val respirationAvg: Double?,
    val bodyBatteryStart: Int?,
    val bodyBatteryMin: Int?,
    val bodyBatteryMax: Int?,
    val bodyBatteryLatest: Int?,
    val sleepStart: Long?,
    val sleepEnd: Long?,
    val sleepScore: Int?,
    val sleepQuality: String?,
    val deepSleepSeconds: Int?,
    val lightSleepSeconds: Int?,
    val remSleepSeconds: Int?,
    val awakeSleepSeconds: Int?,
    val sleepAvgSpo2: Int?,
    val sleepLowestSpo2: Int?,
    val sleepAvgRespiration: Double?,
    val sleepLowestHeartRate: Int?,
    val hrvLastNight: Double?,
    val hrvWeeklyAvg: Double?,
    val hrvStatus: String?,
    val hrvBaselineLow: Double?,
    val hrvBaselineHigh: Double?,
    val moderateMinutes: Int?,
    val vigorousMinutes: Int?,
    val samples: List<WellnessSampleDraft>,
    val sleepStages: List<SleepStageDraft>,
)

object WellnessParser {

    private enum class Stage(val label: String) {
        Unmeasurable("unmeasurable"),
        Awake("awake"),
        Light("light"),
        Deep("deep"),
        Rem("rem"),
    }

    fun parse(messages: List<Fit.Message>, zone: ZoneId = ZoneId.systemDefault()): List<WellnessDayDraft> {
        if (messages.isEmpty()) return emptyList()
        val ordered = messages.sortedBy { it.timestamp ?: Long.MAX_VALUE }
        val fallbackTimestamp = ordered.mapNotNull { it.timestamp }.maxOrNull() ?: return emptyList()

        val samplesByDate = LinkedHashMap<String, MutableList<WellnessSampleDraft>>()
        val monitoringByDate = LinkedHashMap<String, MutableList<Fit.Message>>()
        val restingHrByDate = HashMap<String, Int>()
        val intensityByDate = HashMap<String, Pair<Int?, Int?>>()
        val bodyBatteryByDate = HashMap<String, MutableList<Int>>()
        val hrvSummaryByDate = HashMap<String, Fit.Message>()
        val sleepTransitions = ArrayList<Pair<Long, Stage>>()
        val sleepAssessments = ArrayList<Pair<Long, Int?>>()

        var referenceTimestamp = fallbackTimestamp
        for (message in ordered) {
            message.timestamp?.let { referenceTimestamp = it }
            when (message.name) {
                "monitoring" -> {
                    val timestamp = message.timestamp
                        ?: int(message, "timestamp_16")?.let { resolveTimestamp16(referenceTimestamp, it) }
                        ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    monitoringByDate.getOrPut(date) { ArrayList() }.add(message)
                    int(message, "heart_rate")?.takeIf { it in 1..250 }?.let {
                        addSample(samplesByDate, date, timestamp, "hr", it.toDouble())
                    }
                    int(message, "moderate_activity_minutes")?.let { value ->
                        val current = intensityByDate[date] ?: (null to null)
                        intensityByDate[date] = value to current.second
                    }
                    int(message, "vigorous_activity_minutes")?.let { value ->
                        val current = intensityByDate[date] ?: (null to null)
                        intensityByDate[date] = current.first to value
                    }
                }

                "monitoring_hr_data" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    val resting = int(message, "current_day_resting_heart_rate")
                        ?: int(message, "resting_heart_rate")
                    if (resting != null && resting in 1..250) restingHrByDate[date] = resting
                }

                "stress_level" -> {
                    val timestamp = int(message, "stress_level_time")?.toLong()
                        ?: message.timestamp
                        ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    int(message, "stress_level_value")?.takeIf { it in 0..100 }?.let {
                        addSample(samplesByDate, date, timestamp, "stress", it.toDouble())
                    }
                    int(message, "body_battery")?.takeIf { it in 1..100 }?.let { level ->
                        addSample(samplesByDate, date, timestamp, "body_battery", level.toDouble())
                        bodyBatteryByDate.getOrPut(date) { ArrayList() }.add(level)
                    }
                }

                "spo2_data" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    int(message, "reading_spo2")?.takeIf { it in 1..100 }?.let {
                        addSample(samplesByDate, date, timestamp, "spo2", it.toDouble())
                    }
                }

                "respiration_rate" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    double(message, "respiration_rate")?.takeIf { it > 0.0 }?.let {
                        addSample(samplesByDate, date, timestamp, "respiration", it)
                    }
                }

                "hrv_value" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    double(message, "value")?.takeIf { it > 0.0 }?.let {
                        addSample(samplesByDate, date, timestamp, "hrv", it)
                    }
                }

                "hrv_status_summary" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val date = dateOf(timestamp, zone)
                    hrvSummaryByDate[date] = message
                }

                "sleep_level" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val stage = stageOf(int(message, "sleep_level")) ?: continue
                    sleepTransitions.add(timestamp to stage)
                }

                "sleep_assessment" -> {
                    val timestamp = message.timestamp ?: referenceTimestamp
                    val score = int(message, "overall_sleep_score")
                        ?: int(message, "sleep_quality_score")
                    sleepAssessments.add(timestamp to score)
                }
            }
        }

        // Monitoring totals are cumulative, so keep the last message per
        // activity type for each day and sum those.
        val monitoringTotals = HashMap<String, MonitoringTotals>()
        for ((date, messagesForDay) in monitoringByDate) {
            val lastByType = LinkedHashMap<Int, Fit.Message>()
            for (message in messagesForDay) {
                val type = int(message, "activity_type") ?: 0
                lastByType[type] = message
            }
            val totals = MonitoringTotals()
            for (message in lastByType.values) {
                totals.steps += int(message, "steps")
                    ?: int(message, "cycles_16")?.times(2)
                    ?: double(message, "cycles")?.times(2)?.roundToInt()
                    ?: 0
                totals.distance += double(message, "distance") ?: 0.0
                totals.activeCalories += int(message, "active_calories")
                    ?: int(message, "calories")
                    ?: 0
                totals.activeSeconds += double(message, "active_time")?.roundToInt() ?: 0
            }
            monitoringTotals[date] = totals
        }

        val sleepByDate = buildSleepSessions(sleepTransitions, sleepAssessments, zone)

        val dates = LinkedHashSet<String>()
        dates.addAll(monitoringByDate.keys)
        dates.addAll(samplesByDate.keys)
        dates.addAll(bodyBatteryByDate.keys)
        dates.addAll(hrvSummaryByDate.keys)
        dates.addAll(sleepByDate.keys)

        return dates.sorted().mapNotNull { date ->
            val totals = monitoringTotals[date]
            val samples = samplesByDate[date].orEmpty().sortedBy { it.timestamp }
            val stress = samples.filter { it.kind == "stress" }.map { it.value }
            val spo2 = samples.filter { it.kind == "spo2" }.map { it.value }
            val respiration = samples.filter { it.kind == "respiration" }.map { it.value }
            val hr = samples.filter { it.kind == "hr" }.map { it.value }
            val bodyBattery = bodyBatteryByDate[date].orEmpty()
            val sleep = sleepByDate[date]?.resolve(samples)
            val hrvSummary = hrvSummaryByDate[date]
            val intensity = intensityByDate[date]

            if (totals == null && samples.isEmpty() && sleep == null && hrvSummary == null) {
                return@mapNotNull null
            }

            WellnessDayDraft(
                date = date,
                hasMonitoring = totals != null,
                steps = totals?.steps ?: 0,
                distanceMeters = totals?.distance ?: 0.0,
                activeCalories = totals?.activeCalories ?: 0,
                activeSeconds = totals?.activeSeconds ?: 0,
                restingHeartRate = restingHrByDate[date],
                avgHeartRate = hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                maxHeartRate = hr.maxOrNull()?.roundToInt(),
                stressAvg = stress.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                spo2Avg = spo2.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                respirationAvg = respiration.takeIf { it.isNotEmpty() }?.average(),
                bodyBatteryStart = bodyBattery.firstOrNull(),
                bodyBatteryMin = bodyBattery.minOrNull(),
                bodyBatteryMax = bodyBattery.maxOrNull(),
                bodyBatteryLatest = bodyBattery.lastOrNull(),
                sleepStart = sleep?.start,
                sleepEnd = sleep?.end,
                sleepScore = sleep?.score,
                sleepQuality = sleep?.quality,
                deepSleepSeconds = sleep?.deepSeconds,
                lightSleepSeconds = sleep?.lightSeconds,
                remSleepSeconds = sleep?.remSeconds,
                awakeSleepSeconds = sleep?.awakeSeconds,
                sleepAvgSpo2 = sleep?.avgSpo2,
                sleepLowestSpo2 = sleep?.lowestSpo2,
                sleepAvgRespiration = sleep?.avgRespiration,
                sleepLowestHeartRate = sleep?.lowestHeartRate,
                hrvLastNight = hrvSummary?.let { double(it, "last_night_average") }?.takeIf { it > 0.0 },
                hrvWeeklyAvg = hrvSummary?.let { double(it, "weekly_average") }?.takeIf { it > 0.0 },
                hrvStatus = hrvSummary?.let { hrvStatusName(int(it, "status")) },
                hrvBaselineLow = hrvSummary?.let { double(it, "baseline_balanced_lower") }?.takeIf { it > 0.0 },
                hrvBaselineHigh = hrvSummary?.let { double(it, "baseline_balanced_upper") }?.takeIf { it > 0.0 },
                moderateMinutes = intensity?.first,
                vigorousMinutes = intensity?.second,
                samples = samples,
                sleepStages = sleep?.stages.orEmpty(),
            )
        }
    }

    private class MonitoringTotals {
        var steps = 0
        var distance = 0.0
        var activeCalories = 0
        var activeSeconds = 0
    }

    /**
     * Garmin assigns a night's sleep to the wake-up date. Transitions can
     * start before midnight, so sessions are split on a >6 h gap and then
     * dated by their end. The longest session wins when a day has both a nap
     * and a night.
     */
    private fun buildSleepSessions(
        transitions: List<Pair<Long, Stage>>,
        assessments: List<Pair<Long, Int?>>,
        zone: ZoneId,
    ): Map<String, SleepDraft> {
        if (transitions.isEmpty()) return emptyMap()
        val sorted = transitions.sortedBy { it.first }
        val sessions = ArrayList<MutableList<Pair<Long, Stage>>>()
        var current = ArrayList<Pair<Long, Stage>>()
        var previous = sorted.first().first
        for (transition in sorted) {
            if (current.isNotEmpty() && transition.first - previous > 6 * 3600L) {
                sessions.add(current)
                current = ArrayList()
            }
            current.add(transition)
            previous = transition.first
        }
        if (current.isNotEmpty()) sessions.add(current)

        val byDate = HashMap<String, SleepDraft>()
        for (session in sessions) {
            val last = session.last().first
            val assessment = assessments
                .filter { it.first in session.first().first..(last + 3 * 3600L) }
                .minByOrNull { kotlin.math.abs(it.first - last) }
            val end = assessment?.first?.takeIf { it >= last } ?: last
            val date = dateOf(end, zone)
            val candidate = SleepDraft().apply {
                for (transition in session) this.transitions.add(transition)
                assessmentTimestamp = assessment?.first
                score = assessment?.second
            }
            val existing = byDate[date]
            if (existing == null || candidate.duration() > existing.duration()) {
                byDate[date] = candidate
            }
        }
        return byDate
    }

    private class SleepDraft {
        val transitions = ArrayList<Pair<Long, Stage>>()
        var assessmentTimestamp: Long? = null
        var score: Int? = null

        fun duration(): Long =
            (transitions.lastOrNull()?.first ?: 0L) - (transitions.firstOrNull()?.first ?: 0L)

        fun resolve(samples: List<WellnessSampleDraft>): ResolvedSleep? {
            if (transitions.isEmpty()) return null
            transitions.sortBy { it.first }
            val start = transitions.first().first
            val end = assessmentTimestamp
                ?: transitions.last().first
            val resolvedEnd = maxOf(end, transitions.last().first)
            val stages = ArrayList<SleepStageDraft>()
            var deep = 0
            var light = 0
            var rem = 0
            var awake = 0
            transitions.forEachIndexed { index, (timestamp, stage) ->
                val next = transitions.getOrNull(index + 1)?.first ?: resolvedEnd
                val duration = (next - timestamp).coerceAtLeast(0).toInt()
                if (duration <= 0) return@forEachIndexed
                stages.add(SleepStageDraft(timestamp, next, stage.label))
                when (stage) {
                    Stage.Deep -> deep += duration
                    Stage.Light -> light += duration
                    Stage.Rem -> rem += duration
                    Stage.Awake -> awake += duration
                    Stage.Unmeasurable -> Unit
                }
            }
            if (stages.isEmpty()) return null
            val inSleep = samples.filter { it.timestamp in start..resolvedEnd }
            val spo2 = inSleep.filter { it.kind == "spo2" }.map { it.value.roundToInt() }
            val respiration = inSleep.filter { it.kind == "respiration" }.map { it.value }
            val hr = inSleep.filter { it.kind == "hr" }.map { it.value.roundToInt() }
            return ResolvedSleep(
                start = start,
                end = resolvedEnd,
                score = score,
                quality = sleepQuality(score),
                deepSeconds = deep,
                lightSeconds = light,
                remSeconds = rem,
                awakeSeconds = awake,
                avgSpo2 = spo2.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                lowestSpo2 = spo2.minOrNull(),
                avgRespiration = respiration.takeIf { it.isNotEmpty() }?.average(),
                lowestHeartRate = hr.minOrNull(),
                stages = stages,
            )
        }
    }

    private data class ResolvedSleep(
        val start: Long,
        val end: Long,
        val score: Int?,
        val quality: String?,
        val deepSeconds: Int,
        val lightSeconds: Int,
        val remSeconds: Int,
        val awakeSeconds: Int,
        val avgSpo2: Int?,
        val lowestSpo2: Int?,
        val avgRespiration: Double?,
        val lowestHeartRate: Int?,
        val stages: List<SleepStageDraft>,
    )

    private fun addSample(
        samples: MutableMap<String, MutableList<WellnessSampleDraft>>,
        date: String,
        timestamp: Long,
        kind: String,
        value: Double,
    ) {
        samples.getOrPut(date) { ArrayList() }.add(WellnessSampleDraft(timestamp, kind, value))
    }

    private fun dateOf(timestamp: Long, zone: ZoneId): String =
        Instant.ofEpochSecond(timestamp).atZone(zone).toLocalDate().toString()

    private fun resolveTimestamp16(reference: Long, raw: Int): Long {
        val mask = 0xFFFFL
        var value = (reference and mask.inv()) or (raw.toLong() and mask)
        if (value < reference - 0x8000L) value += 0x10000L
        else if (value > reference + 0x8000L) value -= 0x10000L
        return value
    }

    private fun stageOf(value: Int?): Stage? = when (value) {
        0 -> Stage.Unmeasurable
        1 -> Stage.Awake
        2 -> Stage.Light
        3 -> Stage.Deep
        4 -> Stage.Rem
        else -> null
    }

    private fun sleepQuality(score: Int?): String? = when {
        score == null -> null
        score >= 90 -> "Excellent"
        score >= 80 -> "Good"
        score >= 60 -> "Fair"
        else -> "Poor"
    }

    private fun hrvStatusName(value: Int?): String? = when (value) {
        0 -> null
        1 -> "Poor"
        2 -> "Low"
        3 -> "Unbalanced"
        4 -> "Balanced"
        else -> null
    }

    private fun field(message: Fit.Message, name: String): Any? =
        message.fields.firstOrNull { it.name == name }?.value

    private fun int(message: Fit.Message, name: String): Int? =
        (field(message, name) as? Number)?.toInt()

    private fun double(message: Fit.Message, name: String): Double? =
        (field(message, name) as? Number)?.toDouble()
}
