package dev.opengarmin.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Windows offered by the Health metric detail screens. */
enum class HealthWindow(val label: String, val days: Int) {
    DAY("Day", 1),
    WEEK("7d", 7),
    FOUR_WEEKS("4w", 28),
    YEAR("1y", 365),
}

/** VO₂ max uses fitness-oriented window names. */
enum class Vo2Window(val label: String, val days: Int) {
    NOW("Now", 0),
    FOUR_WEEKS("4w", 28),
    TWELVE_WEEKS("12w", 84),
    SIX_MONTHS("6m", 182),
}

data class BodyBatteryDay(
    val date: LocalDate,
    val high: Int?,
    val low: Int?,
    val latest: Int?,
)

data class SleepNight(
    val date: LocalDate,
    val score: Int?,
    val quality: String?,
    val asleepSeconds: Int?,
    val inBedSeconds: Int?,
    val deepSeconds: Int?,
    val lightSeconds: Int?,
    val remSeconds: Int?,
    val awakeSeconds: Int?,
    /** Minutes since noon, so 23:00 is 660 and 08:12 is 1212. */
    val bedMinutes: Int?,
    val wakeMinutes: Int?,
    val lowestHr: Int?,
    val restingHr: Int?,
    val hrvMs: Double?,
    val spo2: Int?,
    val respiration: Double?,
)

data class HrvNight(
    val date: LocalDate,
    val value: Double?,
    val status: String?,
    val baselineLow: Double?,
    val baselineHigh: Double?,
)

data class DailyAverage(val date: LocalDate, val value: Double)

data class SleepBucket(
    val start: LocalDate,
    val end: LocalDate,
    val nights: Int,
    val score: Double?,
    val lowestHr: Double?,
    val bedMinutes: Double?,
    val wakeMinutes: Double?,
    val hrv: Double?,
    val spo2: Double?,
    val respiration: Double?,
    val bodyBatteryHigh: Double?,
)

enum class StressLevel(val label: String) {
    REST("Rest"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    ;

    companion object {
        fun of(value: Double): StressLevel = when {
            value <= 25.0 -> REST
            value <= 50.0 -> LOW
            value <= 75.0 -> MEDIUM
            else -> HIGH
        }
    }
}

data class StressBreakdown(
    val restSeconds: Long,
    val lowSeconds: Long,
    val mediumSeconds: Long,
    val highSeconds: Long,
) {
    val totalSeconds: Long get() = restSeconds + lowSeconds + mediumSeconds + highSeconds
    fun secondsFor(level: StressLevel): Long = when (level) {
        StressLevel.REST -> restSeconds
        StressLevel.LOW -> lowSeconds
        StressLevel.MEDIUM -> mediumSeconds
        StressLevel.HIGH -> highSeconds
    }
    fun percentFor(level: StressLevel): Int {
        if (totalSeconds <= 0L) return 0
        return (secondsFor(level) * 100.0 / totalSeconds).roundToInt()
    }
}

data class Vo2Thresholds(
    val superior: Double,
    val excellent: Double,
    val good: Double,
    val fair: Double,
)

enum class Vo2Band(val label: String) {
    NEEDS_WORK("Needs work"),
    FAIR("Fair"),
    GOOD("Good"),
    EXCELLENT("Excellent"),
    SUPERIOR("Superior"),
}

data class Vo2Reference(val thresholds: Vo2Thresholds, val band: Vo2Band)

/**
 * Pure derivations for the Health hub and metric detail screens.
 *
 * Nothing here fills a missing watch value with a guess: days without a
 * reading are omitted, and screens show an explicit empty state instead.
 * The only reference data is the age/sex VO₂ band and the age-based sleep
 * need, both documented next to the tables below.
 */
object HealthMetrics {

    fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    /** Newest day that is not after [today], matching the other screens. */
    fun latestDate(days: List<WellnessDayEntity>, today: LocalDate = LocalDate.now()): LocalDate? =
        WellnessMetrics.latestDay(days, today)?.let { parseDate(it.date) }

    fun daysInWindow(
        days: List<WellnessDayEntity>,
        end: LocalDate,
        windowDays: Int,
    ): List<WellnessDayEntity> {
        val start = end.minusDays((windowDays - 1).coerceAtLeast(1).toLong())
        return days.mapNotNull { day ->
            val date = parseDate(day.date) ?: return@mapNotNull null
            day.takeIf { !date.isBefore(start) && !date.isAfter(end) }
        }.sortedBy { it.date }
    }

    fun rangeStart(end: LocalDate, days: Int): LocalDate =
        end.minusDays((days - 1).coerceAtLeast(1).toLong())

    fun formatRange(start: LocalDate, end: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        return if (start == end) {
            start.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
        } else {
            "${start.format(formatter)} – ${end.format(formatter)}"
        }
    }

    fun formatDayName(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))

    fun formatDayDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))

    fun bodyBatteryDays(days: List<WellnessDayEntity>, end: LocalDate, windowDays: Int): List<BodyBatteryDay> =
        daysInWindow(days, end, windowDays).mapNotNull { day ->
            val date = parseDate(day.date) ?: return@mapNotNull null
            if (day.bodyBatteryMax == null && day.bodyBatteryMin == null && day.bodyBatteryLatest == null) {
                return@mapNotNull null
            }
            BodyBatteryDay(
                date = date,
                high = day.bodyBatteryMax,
                low = day.bodyBatteryMin,
                latest = day.bodyBatteryLatest,
            )
        }

    fun sleepNights(days: List<WellnessDayEntity>, end: LocalDate, windowDays: Int): List<SleepNight> {
        val zone = ZoneId.systemDefault()
        return daysInWindow(days, end, windowDays).mapNotNull { day ->
            val date = parseDate(day.date) ?: return@mapNotNull null
            if (day.sleepScore == null && day.sleepStart == null) return@mapNotNull null
            val asleep = WellnessMetrics.sleepSeconds(day)
            val inBed = if (day.sleepStart != null && day.sleepEnd != null) {
                (day.sleepEnd - day.sleepStart).toInt().takeIf { it > 0 }
            } else {
                null
            }
            SleepNight(
                date = date,
                score = day.sleepScore,
                quality = day.sleepQuality,
                asleepSeconds = asleep,
                inBedSeconds = inBed,
                deepSeconds = day.deepSleepSeconds,
                lightSeconds = day.lightSleepSeconds,
                remSeconds = day.remSleepSeconds,
                awakeSeconds = day.awakeSleepSeconds,
                bedMinutes = day.sleepStart?.let { minutesSinceNoon(it, zone) },
                wakeMinutes = day.sleepEnd?.let { minutesSinceNoon(it, zone) },
                lowestHr = day.sleepLowestHeartRate,
                restingHr = day.restingHeartRate,
                hrvMs = day.hrvLastNight,
                spo2 = day.sleepAvgSpo2,
                respiration = day.sleepAvgRespiration,
            )
        }
    }

    fun hrvNights(days: List<WellnessDayEntity>, end: LocalDate, windowDays: Int): List<HrvNight> =
        daysInWindow(days, end, windowDays).mapNotNull { day ->
            val date = parseDate(day.date) ?: return@mapNotNull null
            if (day.hrvLastNight == null && day.hrvStatus == null) return@mapNotNull null
            HrvNight(
                date = date,
                value = day.hrvLastNight,
                status = day.hrvStatus,
                baselineLow = day.hrvBaselineLow,
                baselineHigh = day.hrvBaselineHigh,
            )
        }

    fun stressDays(days: List<WellnessDayEntity>, end: LocalDate, windowDays: Int): List<DailyAverage> =
        daysInWindow(days, end, windowDays).mapNotNull { day ->
            val date = parseDate(day.date) ?: return@mapNotNull null
            day.stressAvg?.let { DailyAverage(date, it.toDouble()) }
        }

    /** Monthly buckets used by the one-year sleep and stress views. */
    fun monthlySleepBuckets(nights: List<SleepNight>): List<SleepBucket> {
        val grouped = LinkedHashMap<LocalDate, MutableList<SleepNight>>()
        for (night in nights) {
            val key = night.date.withDayOfMonth(1)
            grouped.getOrPut(key) { ArrayList() }.add(night)
        }
        return grouped.map { (start, values) ->
            SleepBucket(
                start = start,
                end = start.with(TemporalAdjusters.lastDayOfMonth()),
                nights = values.size,
                score = average(values.mapNotNull { it.score?.toDouble() }),
                lowestHr = average(values.mapNotNull { (it.lowestHr ?: it.restingHr)?.toDouble() }),
                bedMinutes = average(values.mapNotNull { it.bedMinutes?.toDouble() }),
                wakeMinutes = average(values.mapNotNull { it.wakeMinutes?.toDouble() }),
                hrv = average(values.mapNotNull { it.hrvMs }),
                spo2 = average(values.mapNotNull { it.spo2?.toDouble() }),
                respiration = average(values.mapNotNull { it.respiration }),
                bodyBatteryHigh = null,
            )
        }.sortedBy { it.start }
    }

    fun monthlyStressBuckets(days: List<DailyAverage>): List<DailyAverage> {
        val grouped = LinkedHashMap<LocalDate, MutableList<Double>>()
        for (day in days) {
            grouped.getOrPut(day.date.withDayOfMonth(1)) { ArrayList() }.add(day.value)
        }
        return grouped.map { (start, values) -> DailyAverage(start, values.average()) }.sortedBy { it.date }
    }

    fun average(values: List<Double>): Double? =
        values.takeIf { it.isNotEmpty() }?.average()

    fun averageInt(values: List<Int>): Double? =
        values.takeIf { it.isNotEmpty() }?.average()

    /** Bedtime/wake times are stored as minutes since noon for overnight charts. */
    fun minutesSinceNoon(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val time = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime()
        return minutesSinceNoon(time)
    }

    fun minutesSinceNoon(time: LocalTime): Int =
        ((time.hour - 12 + 24) % 24) * 60 + time.minute

    fun labelFromNoonMinutes(minutes: Int): String {
        val normalized = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60)
        val hour = ((normalized / 60) + 12) % 24
        val minute = normalized % 60
        return "%02d:%02d".format(hour, minute)
    }

    /**
     * Sleep need from the age-based recommendation ranges published by the
     * National Sleep Foundation. The upper bound is used, so an adult need is
     * 9 h (the same target the mockup shows). Unknown age falls back to 8 h.
     */
    fun sleepNeedHours(birthYear: Int?, today: LocalDate = LocalDate.now()): Double {
        val age = birthYear?.let { today.year - it } ?: return 8.0
        return when {
            age < 6 -> 12.0
            age < 14 -> 11.0
            age < 18 -> 10.0
            age < 65 -> 9.0
            else -> 8.0
        }
    }

    fun stressBreakdown(samples: List<WellnessSampleEntity>): StressBreakdown? {
        val values = samples.filter { it.kind == "stress" && it.value in 0.0..100.0 }
        if (values.isEmpty()) return null
        val interval = medianIntervalSeconds(values.map { it.timestamp })
        var rest = 0L
        var low = 0L
        var medium = 0L
        var high = 0L
        for (sample in values) {
            when (StressLevel.of(sample.value)) {
                StressLevel.REST -> rest += interval
                StressLevel.LOW -> low += interval
                StressLevel.MEDIUM -> medium += interval
                StressLevel.HIGH -> high += interval
            }
        }
        return StressBreakdown(rest, low, medium, high)
    }

    /**
     * Averages stress readings into fixed buckets so a day timeline reads as
     * bars instead of hundreds of one-pixel slivers. Missing buckets are not
     * created.
     */
    fun stressBuckets(
        samples: List<WellnessSampleEntity>,
        bucketSeconds: Long = 900L,
    ): List<WellnessSampleEntity> {
        val points = samples.filter { it.kind == "stress" && it.value in 0.0..100.0 }.sortedBy { it.timestamp }
        if (points.isEmpty()) return emptyList()
        val origin = points.first().timestamp
        return points.groupBy { (it.timestamp - origin) / bucketSeconds }
            .toSortedMap()
            .map { (bucket, values) ->
                WellnessSampleEntity(
                    dayId = values.first().dayId,
                    timestamp = origin + bucket * bucketSeconds,
                    kind = "stress",
                    value = values.map { it.value }.average(),
                )
            }
    }

    /** Falls back to three minutes between readings when timestamps are too sparse. */
    fun medianIntervalSeconds(timestamps: List<Long>): Long {
        val sorted = timestamps.sorted()
        if (sorted.size < 2) return 180L
        val diffs = sorted.zipWithNext { a, b -> (b - a).coerceAtLeast(1L) }.sorted()
        val median = diffs[diffs.size / 2]
        return median.coerceIn(30L, 900L)
    }

    fun longestRestSeconds(samples: List<WellnessSampleEntity>): Long {
        val values = samples.filter { it.kind == "stress" && it.value in 0.0..100.0 }
        if (values.isEmpty()) return 0L
        val interval = medianIntervalSeconds(values.map { it.timestamp })
        var best = 0L
        var current = 0L
        for (sample in values.sortedBy { it.timestamp }) {
            if (sample.value <= 25.0) {
                current += interval
                best = maxOf(best, current)
            } else {
                current = 0L
            }
        }
        return best
    }

    fun bodyBatteryDelta(samples: List<WellnessSampleEntity>, start: Long, end: Long): Int? {
        val points = samples
            .filter { it.kind == "body_battery" && it.value in 1.0..100.0 }
            .filter { it.timestamp in start..end }
            .sortedBy { it.timestamp }
        if (points.size < 2) return null
        return (points.last().value - points.first().value).roundToInt()
    }

    fun bodyBatteryAt(samples: List<WellnessSampleEntity>, timestamp: Long, toleranceSeconds: Long = 900L): Double? {
        val points = samples.filter { it.kind == "body_battery" && it.value in 1.0..100.0 }
        return points.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= toleranceSeconds }
            ?.value
    }

    fun peakBodyBattery(samples: List<WellnessSampleEntity>): WellnessSampleEntity? =
        samples.filter { it.kind == "body_battery" && it.value in 1.0..100.0 }
            .maxByOrNull { it.value }

    fun lowestStressAverage(days: List<WellnessDayEntity>, windowDays: Int = 7): Int? {
        val values = days.sortedByDescending { it.date }
            .take(windowDays)
            .mapNotNull { it.stressAvg }
        return values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    }

    fun hrvInRangeCount(nights: List<HrvNight>): Int = nights.count { night ->
        val low = night.baselineLow
        val high = night.baselineHigh
        val value = night.value
        if (low == null || high == null || value == null) {
            night.status.equals("Balanced", ignoreCase = true)
        } else {
            value in low..high
        }
    }

    fun currentInRangeStreak(nights: List<HrvNight>): Int {
        var streak = 0
        for (night in nights.sortedByDescending { it.date }) {
            val low = night.baselineLow
            val high = night.baselineHigh
            val value = night.value
            val inRange = if (low != null && high != null && value != null) {
                value in low..high
            } else {
                night.status.equals("Balanced", ignoreCase = true)
            }
            if (!inRange) break
            streak++
        }
        return streak
    }

    fun averageBedtime(nights: List<SleepNight>): Int? =
        average(nights.mapNotNull { it.bedMinutes?.toDouble() })?.roundToInt()

    fun averageWakeTime(nights: List<SleepNight>): Int? =
        average(nights.mapNotNull { it.wakeMinutes?.toDouble() })?.roundToInt()

    fun averageSleepSeconds(nights: List<SleepNight>): Int? =
        average(nights.mapNotNull { it.asleepSeconds?.toDouble() })?.roundToInt()

    fun averageScore(nights: List<SleepNight>): Int? =
        average(nights.mapNotNull { it.score?.toDouble() })?.roundToInt()

    fun averageLowestHeartRate(nights: List<SleepNight>): Int? =
        average(nights.mapNotNull { it.lowestHr?.toDouble() })?.roundToInt()

    fun averageRestingHeartRate(nights: List<SleepNight>): Int? =
        average(nights.mapNotNull { it.restingHr?.toDouble() })?.roundToInt()

    /**
     * Five-band VO₂ reference from the Cooper Institute age/sex norms. These
     * are reference bands, not Garmin's proprietary percentile table, so the
     * UI never claims a percentile.
     */
    fun vo2Reference(
        value: Double?,
        birthYear: Int?,
        biologicalSex: String?,
        today: LocalDate = LocalDate.now(),
    ): Vo2Reference? {
        if (value == null || birthYear == null) return null
        val age = (today.year - birthYear).coerceAtLeast(0)
        val male = maleThresholds(age)
        val female = femaleThresholds(age)
        val thresholds = when (biologicalSex?.lowercase(Locale.getDefault())) {
            "male" -> male
            "female" -> female
            "intersex" -> Vo2Thresholds(
                superior = (male.superior + female.superior) / 2.0,
                excellent = (male.excellent + female.excellent) / 2.0,
                good = (male.good + female.good) / 2.0,
                fair = (male.fair + female.fair) / 2.0,
            )
            else -> return null
        }
        val band = when {
            value >= thresholds.superior -> Vo2Band.SUPERIOR
            value >= thresholds.excellent -> Vo2Band.EXCELLENT
            value >= thresholds.good -> Vo2Band.GOOD
            value >= thresholds.fair -> Vo2Band.FAIR
            else -> Vo2Band.NEEDS_WORK
        }
        return Vo2Reference(thresholds, band)
    }

    private fun maleThresholds(age: Int): Vo2Thresholds = when {
        age < 30 -> Vo2Thresholds(55.4, 51.1, 45.4, 38.5)
        age < 40 -> Vo2Thresholds(54.0, 48.3, 43.9, 36.4)
        age < 50 -> Vo2Thresholds(52.0, 46.4, 41.0, 33.8)
        age < 60 -> Vo2Thresholds(48.2, 42.4, 36.7, 30.1)
        else -> Vo2Thresholds(45.7, 39.5, 33.0, 26.1)
    }

    private fun femaleThresholds(age: Int): Vo2Thresholds = when {
        age < 30 -> Vo2Thresholds(49.7, 43.9, 39.5, 33.0)
        age < 40 -> Vo2Thresholds(47.4, 42.4, 37.8, 30.7)
        age < 50 -> Vo2Thresholds(45.3, 39.7, 34.6, 28.0)
        age < 60 -> Vo2Thresholds(41.1, 36.7, 31.0, 24.6)
        else -> Vo2Thresholds(37.8, 33.0, 27.5, 21.4)
    }

    fun vo2HistoryInWindow(
        history: List<TrainingPoint>,
        nowSeconds: Long,
        days: Int,
    ): List<TrainingPoint> {
        if (days <= 0) return history.takeLast(1)
        val cutoff = nowSeconds - days * 86_400L
        return history.filter { it.startTime >= cutoff }
    }

    fun vo2Average(history: List<TrainingPoint>): Double? =
        average(history.map { it.value })
}
