package dev.opengarmin.app.data

import dev.opengarmin.protocol.Fit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
    val hrvBaselineLowUpper: Double?,
    val moderateMinutes: Int?,
    val vigorousMinutes: Int?,
    val samples: List<WellnessSampleDraft>,
    val sleepStages: List<SleepStageDraft>,
)

object WellnessParser {

    /** FIT event value the watch writes at the start and end of a sleep session. */
    private const val SLEEP_EVENT = 74

    /** FIT event_type values: 0 = start, 1 = stop. */
    private const val EVENT_START = 0
    private const val EVENT_STOP = 1

    private enum class Stage(val label: String) {
        Unmeasurable("unmeasurable"),
        Awake("awake"),
        Light("light"),
        Deep("deep"),
        Rem("rem"),
    }

    fun parse(messages: List<Fit.Message>, zone: ZoneId = ZoneId.systemDefault()): List<WellnessDayDraft> {
        if (messages.isEmpty()) return emptyList()
        val raw = collectRawWellness(messages, zone) ?: return emptyList()
        val monitoring = aggregateMonitoring(raw.monitoringByDate)
        val sleep = buildSleepSessions(raw.sleepTransitions, raw.sleepAssessments, raw.sleepEvents, zone)
        return buildDailyDrafts(raw, monitoring, sleep)
    }

    /** Per-date buckets gathered from a single pass over the FIT messages. */
    private class RawWellness(
        val samplesByDate: Map<String, MutableList<WellnessSampleDraft>>,
        val monitoringByDate: Map<String, MutableList<Fit.Message>>,
        val restingHrByDate: Map<String, Int>,
        val intensityByDate: Map<String, Pair<Int?, Int?>>,
        val bodyBatteryByDate: Map<String, MutableList<Int>>,
        val hrvSummaryByDate: Map<String, Fit.Message>,
        val lhaByDate: Map<String, Fit.Message>,
        val sleepTransitions: List<Pair<Long, Stage>>,
        val sleepAssessments: List<Pair<Long, Int?>>,
        val sleepEvents: List<Pair<Long, Int>>,
    )

    private fun collectRawWellness(messages: List<Fit.Message>, zone: ZoneId): RawWellness? {
        // Keep FIT file order: `timestamp_16` is only meaningful relative to
        // the previous full timestamp. Sorting null-timestamp messages to the
        // end would resolve them against the newest time in the file and can
        // push early-day samples into the next day.
        val ordered = messages
        val fallbackTimestamp = ordered.mapNotNull { it.timestamp }.maxOrNull() ?: return null

        val samplesByDate = LinkedHashMap<String, MutableList<WellnessSampleDraft>>()
        val monitoringByDate = LinkedHashMap<String, MutableList<Fit.Message>>()
        val restingHrByDate = HashMap<String, Int>()
        val intensityByDate = HashMap<String, Pair<Int?, Int?>>()
        val bodyBatteryByDate = HashMap<String, MutableList<Int>>()
        val hrvSummaryByDate = HashMap<String, Fit.Message>()
        val lhaByDate = HashMap<String, Fit.Message>()
        val sleepTransitions = ArrayList<Pair<Long, Stage>>()
        val sleepAssessments = ArrayList<Pair<Long, Int?>>()
        val sleepEvents = ArrayList<Pair<Long, Int>>()

        var referenceTimestamp = ordered.firstOrNull { it.timestamp != null }?.timestamp ?: fallbackTimestamp
        for (message in ordered) {
            message.timestamp?.let { referenceTimestamp = it }
            when (message.name) {
                "monitoring" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val date = messageDate(message, referenceTimestamp, zone)
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
                    val date = messageDate(message, referenceTimestamp, zone)
                    val resting = int(message, "current_day_resting_heart_rate")
                        ?: int(message, "resting_heart_rate")
                    if (resting != null && resting in 1..250) restingHrByDate[date] = resting
                }

                "stress_level" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val date = messageDate(message, referenceTimestamp, zone)
                    int(message, "stress_level_value")?.takeIf { it in 0..100 }?.let {
                        addSample(samplesByDate, date, timestamp, "stress", it.toDouble())
                    }
                    int(message, "body_battery")?.takeIf { it in 1..100 }?.let { level ->
                        addSample(samplesByDate, date, timestamp, "body_battery", level.toDouble())
                        bodyBatteryByDate.getOrPut(date) { ArrayList() }.add(level)
                    }
                }

                "spo2_data" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val date = messageDate(message, referenceTimestamp, zone)
                    int(message, "reading_spo2")?.takeIf { it in 1..100 }?.let {
                        addSample(samplesByDate, date, timestamp, "spo2", it.toDouble())
                    }
                }

                "respiration_rate" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val date = messageDate(message, referenceTimestamp, zone)
                    double(message, "respiration_rate")?.takeIf { it > 0.0 }?.let {
                        addSample(samplesByDate, date, timestamp, "respiration", it)
                    }
                }

                "hrv_value" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val date = messageDate(message, referenceTimestamp, zone)
                    double(message, "value")?.takeIf { it > 0.0 }?.let {
                        addSample(samplesByDate, date, timestamp, "hrv", it)
                    }
                }

                "hrv_status_summary" -> {
                    val date = messageDate(message, referenceTimestamp, zone)
                    hrvSummaryByDate[date] = message
                }

                // Firstbeat daily analytics. The FR255 writes the overnight
                // rMSSD here (field 7) instead of a HRV_STATUS_SUMMARY.
                "fbt_lha_daily_data" -> {
                    val date = messageDate(message, referenceTimestamp, zone)
                    lhaByDate[date] = message
                }

                "sleep_level" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val stage = stageOf(int(message, "sleep_level")) ?: continue
                    sleepTransitions.add(timestamp to stage)
                }

                "sleep_assessment" -> {
                    val timestamp = messageTimestamp(message, referenceTimestamp)
                    val score = int(message, "overall_sleep_score")
                        ?: int(message, "sleep_quality_score")
                    sleepAssessments.add(timestamp to score)
                }

                // Sleep files bracket the session with event(start) and
                // event(stop). They give the real in-bed window, which the
                // stage messages alone do not (the first stage message marks
                // the end of the first stage, not its start).
                "event" -> {
                    if (int(message, "event") == SLEEP_EVENT) {
                        int(message, "event_type")?.let { type ->
                            sleepEvents.add(messageTimestamp(message, referenceTimestamp) to type)
                        }
                    }
                }
            }
        }

        return RawWellness(
            samplesByDate = samplesByDate,
            monitoringByDate = monitoringByDate,
            restingHrByDate = restingHrByDate,
            intensityByDate = intensityByDate,
            bodyBatteryByDate = bodyBatteryByDate,
            hrvSummaryByDate = hrvSummaryByDate,
            lhaByDate = lhaByDate,
            sleepTransitions = sleepTransitions,
            sleepAssessments = sleepAssessments,
            sleepEvents = sleepEvents,
        )
    }

    // Monitoring totals are cumulative, so keep the last non-null value per
    // metric and activity type, then sum the types. The watch also emits
    // monitoring messages that only carry metadata (fields 35-38) and no
    // activity_type; treating those as a fresh type-0 sample used to erase the
    // day's real step total and produce a zero row.
    private fun aggregateMonitoring(
        monitoringByDate: Map<String, List<Fit.Message>>,
    ): Map<String, MonitoringTotals> {
        val monitoringTotals = HashMap<String, MonitoringTotals>()
        for ((date, messagesForDay) in monitoringByDate) {
            val byType = LinkedHashMap<Int, MonitoringTotals>()
            for (message in messagesForDay) {
                val type = int(message, "activity_type") ?: 0
                val totals = byType.getOrPut(type) { MonitoringTotals() }

                val steps = int(message, "steps")
                    ?: int(message, "cycles_16")?.times(2)
                    ?: double(message, "cycles")?.times(2)?.roundToInt()
                if (steps != null) totals.steps = steps

                double(message, "distance")?.let { totals.distance = it }
                (int(message, "active_calories") ?: int(message, "calories"))
                    ?.let { totals.activeCalories = it }
                double(message, "active_time")?.let { totals.activeSeconds = it.roundToInt() }
            }
            val totals = MonitoringTotals()
            byType.values.forEach { typeTotals ->
                totals.steps += typeTotals.steps
                totals.distance += typeTotals.distance
                totals.activeCalories += typeTotals.activeCalories
                totals.activeSeconds += typeTotals.activeSeconds
            }
            monitoringTotals[date] = totals
        }
        return monitoringTotals
    }

    private fun buildDailyDrafts(
        raw: RawWellness,
        monitoringTotals: Map<String, MonitoringTotals>,
        sleepByDate: Map<String, SleepDraft>,
    ): List<WellnessDayDraft> {
        val dates = LinkedHashSet<String>()
        dates.addAll(raw.monitoringByDate.keys)
        dates.addAll(raw.samplesByDate.keys)
        dates.addAll(raw.bodyBatteryByDate.keys)
        dates.addAll(raw.hrvSummaryByDate.keys)
        dates.addAll(raw.lhaByDate.keys)
        dates.addAll(sleepByDate.keys)

        return dates.sorted().mapNotNull { date ->
            val totals = monitoringTotals[date]
            val samples = raw.samplesByDate[date].orEmpty().sortedBy { it.timestamp }
            val stress = samples.filter { it.kind == "stress" }.map { it.value }
            val spo2 = samples.filter { it.kind == "spo2" }.map { it.value }
            val respiration = samples.filter { it.kind == "respiration" }.map { it.value }
            val hr = samples.filter { it.kind == "hr" }.map { it.value }
            val bodyBattery = raw.bodyBatteryByDate[date].orEmpty()
            val sleep = sleepByDate[date]?.resolve(samples)
            val hrvSummary = raw.hrvSummaryByDate[date]
            val lha = raw.lhaByDate[date]
            val lhaSleepScore = lha?.let { int(it, "sleep_score") }
            val hrvSamples = samples.filter { it.kind == "hrv" }.map { it.value }
            val intensity = raw.intensityByDate[date]

            if (totals == null && samples.isEmpty() && sleep == null && hrvSummary == null && lha == null) {
                return@mapNotNull null
            }
            // A monitoring rollover at midnight can produce an all-zero row for
            // the next local day. It carries no health data and would otherwise
            // become the "latest" day and hide today's real numbers.
            if (totals != null && !hasValues(totals) && samples.isEmpty() && sleep == null && hrvSummary == null) {
                return@mapNotNull null
            }

            WellnessDayDraft(
                date = date,
                hasMonitoring = totals != null,
                steps = totals?.steps ?: 0,
                distanceMeters = totals?.distance ?: 0.0,
                activeCalories = totals?.activeCalories ?: 0,
                activeSeconds = totals?.activeSeconds ?: 0,
                restingHeartRate = raw.restingHrByDate[date],
                avgHeartRate = hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                maxHeartRate = hr.maxOrNull()?.roundToInt(),
                stressAvg = stress.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                spo2Avg = spo2.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                respirationAvg = respiration.takeIf { it.isNotEmpty() }?.average(),
                bodyBatteryStart = bodyBattery.firstOrNull(),
                bodyBatteryMin = bodyBattery.minOrNull(),
                bodyBatteryMax = bodyBattery.maxOrNull(),
                bodyBatteryLatest = bodyBattery.lastOrNull(),
                sleepStart = sleep?.start ?: lha?.let { long(it, "sleep_begin_time_utc") },
                sleepEnd = sleep?.end ?: lha?.let { long(it, "sleep_end_time_utc") },
                sleepScore = sleep?.score ?: lhaSleepScore,
                sleepQuality = sleep?.quality ?: sleepQuality(lhaSleepScore),
                deepSleepSeconds = sleep?.deepSeconds,
                lightSleepSeconds = sleep?.lightSeconds,
                remSleepSeconds = sleep?.remSeconds,
                awakeSleepSeconds = sleep?.awakeSeconds
                    ?: lha?.let { int(it, "awake_duration") },
                sleepAvgSpo2 = sleep?.avgSpo2,
                sleepLowestSpo2 = sleep?.lowestSpo2,
                sleepAvgRespiration = sleep?.avgRespiration,
                sleepLowestHeartRate = sleep?.lowestHeartRate,
                hrvLastNight = hrvSummary?.let { double(it, "last_night_average") }?.takeIf { it > 0.0 }
                    ?: lha?.let { double(it, "rmssd_average_sleep") }?.takeIf { it > 0.0 }
                    ?: hrvSamples.takeIf { it.isNotEmpty() }?.average(),
                hrvWeeklyAvg = hrvSummary?.let { double(it, "weekly_average") }?.takeIf { it > 0.0 },
                hrvStatus = hrvSummary?.let { hrvStatusName(int(it, "status")) },
                hrvBaselineLow = hrvSummary?.let { double(it, "baseline_balanced_lower") }?.takeIf { it > 0.0 },
                hrvBaselineHigh = hrvSummary?.let { double(it, "baseline_balanced_upper") }?.takeIf { it > 0.0 },
                hrvBaselineLowUpper = hrvSummary?.let { double(it, "baseline_low_upper") }?.takeIf { it > 0.0 },
                moderateMinutes = intensity?.first,
                vigorousMinutes = intensity?.second,
                samples = samples,
                sleepStages = sleep?.stages.orEmpty(),
            )
        }
    }

    /**
     * Resolves a message's effective timestamp. Monitoring files may omit the
     * full timestamp and carry only `timestamp_16`, and stress samples use
     * their own `stress_level_time`.
     */
    private fun messageTimestamp(message: Fit.Message, referenceTimestamp: Long): Long = when (message.name) {
        "monitoring" -> message.timestamp
            ?: int(message, "timestamp_16")?.let { resolveTimestamp16(referenceTimestamp, it) }
            ?: referenceTimestamp

        "stress_level" -> int(message, "stress_level_time")?.toLong()
            ?: message.timestamp
            ?: referenceTimestamp

        else -> message.timestamp ?: referenceTimestamp
    }

    /**
     * Resolves the calendar date a message belongs to. Monitoring files carry
     * both UTC `timestamp` and a `local_timestamp` wall clock. Prefer the
     * watch's own local date: it avoids the date shifting when the phone and
     * watch are in different zones (and keeps a midnight rollover from
     * becoming a phantom "tomorrow" row).
     */
    private fun messageDate(message: Fit.Message, referenceTimestamp: Long, zone: ZoneId): String {
        if (message.name == "monitoring") {
            long(message, "local_timestamp")
                ?.takeIf { it in 1 until 0xFFFFFFFFL }
                ?.let { return dateOfWallClock(it) }
        }
        return dateOf(messageTimestamp(message, referenceTimestamp), zone)
    }

    private class MonitoringTotals {
        var steps = 0
        var distance = 0.0
        var activeCalories = 0
        var activeSeconds = 0
    }

    private fun hasValues(totals: MonitoringTotals): Boolean =
        totals.steps != 0 || totals.distance != 0.0 || totals.activeCalories != 0 || totals.activeSeconds != 0

    /**
     * Garmin assigns a night's sleep to the wake-up date. Transitions can
     * start before midnight, so sessions are split on a >6 h gap and then
     * dated by their end. The longest session wins when a day has both a nap
     * and a night.
     *
     * Each `sleep_level` message marks the *end* of the stage it names: the
     * last message is written at wake-up, and the first one closes the short
     * stage that starts when the watch detects sleep. Reading the timestamp as
     * a stage start shifts every label by one interval, turning short
     * awakenings into long ones and undercounting total sleep. The real
     * in-bed boundaries come from the sleep event messages.
     */
    private fun buildSleepSessions(
        transitions: List<Pair<Long, Stage>>,
        assessments: List<Pair<Long, Int?>>,
        events: List<Pair<Long, Int>>,
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
            val first = session.first().first
            val last = session.last().first
            val assessment = assessments
                .filter { it.first in first..(last + 3 * 3600L) }
                .minByOrNull { kotlin.math.abs(it.first - last) }
            val sessionStart = events
                .filter { it.second == EVENT_START && it.first <= first }
                .maxByOrNull { it.first }
                ?.first
            val sessionEnd = events
                .filter { it.second == EVENT_STOP && it.first >= last }
                .minByOrNull { it.first }
                ?.first
                ?: assessment?.first?.takeIf { it >= last }
                ?: last
            val date = dateOf(sessionEnd, zone)
            val candidate = SleepDraft().apply {
                for (transition in session) this.transitions.add(transition)
                start = sessionStart
                end = sessionEnd
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
        var start: Long? = null
        var end: Long? = null
        var score: Int? = null

        fun duration(): Long =
            (end ?: transitions.lastOrNull()?.first ?: 0L) -
                (start ?: transitions.firstOrNull()?.first ?: 0L)

        fun resolve(samples: List<WellnessSampleDraft>): ResolvedSleep? {
            if (transitions.isEmpty()) return null
            transitions.sortBy { it.first }
            val firstTransition = transitions.first().first
            val lastTransition = transitions.last().first
            val sessionStart = minOf(start ?: firstTransition, firstTransition)
            val sessionEnd = maxOf(end ?: lastTransition, lastTransition)
            val stages = ArrayList<SleepStageDraft>()
            var deep = 0
            var light = 0
            var rem = 0
            var awake = 0
            var cursor = sessionStart
            for ((timestamp, stage) in transitions) {
                val stageEnd = timestamp.coerceAtLeast(cursor)
                val duration = (stageEnd - cursor).toInt()
                if (duration > 0) {
                    stages.add(SleepStageDraft(cursor, stageEnd, stage.label))
                    when (stage) {
                        Stage.Deep -> deep += duration
                        Stage.Light -> light += duration
                        Stage.Rem -> rem += duration
                        Stage.Awake -> awake += duration
                        Stage.Unmeasurable -> Unit
                    }
                }
                cursor = stageEnd
            }
            // Time between the last transition and the stop event is awake.
            // The watch normally writes the final transition at wake-up, so
            // this tail is usually empty.
            if (sessionEnd > cursor) {
                val duration = (sessionEnd - cursor).toInt()
                stages.add(SleepStageDraft(cursor, sessionEnd, Stage.Awake.label))
                awake += duration
            }
            if (stages.isEmpty()) return null
            val resolvedStart = stages.first().startTime
            val resolvedEnd = stages.last().endTime
            val inSleep = samples.filter { it.timestamp in resolvedStart..resolvedEnd }
            val spo2 = inSleep.filter { it.kind == "spo2" }.map { it.value.roundToInt() }
            val respiration = inSleep.filter { it.kind == "respiration" }.map { it.value }
            val hr = inSleep.filter { it.kind == "hr" }.map { it.value.roundToInt() }
            return ResolvedSleep(
                start = resolvedStart,
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

    /**
     * `local_timestamp` is a wall clock encoded as Garmin-epoch seconds. The
     * FIT parser already adds the Garmin epoch, so read it as UTC to recover
     * the watch's local calendar date without shifting it twice.
     */
    private fun dateOfWallClock(timestamp: Long): String =
        Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString()

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

    private fun long(message: Fit.Message, name: String): Long? =
        (field(message, name) as? Number)?.toLong()

    private fun double(message: Fit.Message, name: String): Double? =
        (field(message, name) as? Number)?.toDouble()
}
