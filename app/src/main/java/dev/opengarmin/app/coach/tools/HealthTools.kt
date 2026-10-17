package dev.opengarmin.app.coach.tools

import dev.opengarmin.app.coach.DailyHealth
import dev.opengarmin.app.coach.HealthDataSource
import dev.opengarmin.app.coach.LOCAL_PROFILE_ID
import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.ToolScope
import dev.opengarmin.app.coach.categoryForSport
import dev.opengarmin.app.coach.epochRange
import dev.opengarmin.app.coach.sportLabel
import dev.opengarmin.app.coach.unixToLocalDate
import dev.opengarmin.app.llm.LlmToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class ToolException(message: String) : Exception(message)

data class ToolResult(
    val json: String,
    val evidence: List<String>,
)

interface HealthTool {
    val definition: LlmToolDefinition
    suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult
}

class ToolRegistry(
    private val dataSource: HealthDataSource,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    val tools: List<HealthTool> = listOf(
        DailySummaryTool(dataSource, today),
        StepsTool(dataSource, today),
        HeartRateTool(dataSource, today),
        SleepTool(dataSource, today),
        ListActivitiesTool(dataSource, today),
        ActivityTool(dataSource),
        ComparePeriodsTool(dataSource, today),
    )

    val definitions: List<LlmToolDefinition> = tools.map { it.definition }

    suspend fun execute(name: String, arguments: String, scope: ToolScope): ToolResult {
        if (scope.profileId != LOCAL_PROFILE_ID) throw ToolException("Unknown profile scope")
        val tool = tools.firstOrNull { it.definition.name == name }
            ?: throw ToolException("Unknown tool: $name")
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }
            .getOrElse { throw ToolException("Tool arguments must be a JSON object") }
        return tool.execute(scope, args)
    }

    suspend fun snapshot(scope: ToolScope, days: Int = 14): Snapshot {
        val safeDays = days.coerceIn(1, 30)
        val end = today()
        val start = end.minusDays(safeDays.toLong() - 1)
        val daily = dataSource.daily(scope, start, end)
        val fromEpoch = epochRange(start, end).first
        val toEpoch = epochRange(start, end).last
        val workouts = dataSource.workouts(scope, fromEpoch, toEpoch, limit = 20, offset = 0)
        val json = JSONObject().apply {
            put("from", start.toString())
            put("to", end.toString())
            put("daysWithData", daily.size)
            put(
                "daily",
                JSONArray().apply {
                    daily.forEach { put(it.toJson()) }
                },
            )
            put(
                "recentWorkouts",
                JSONArray().apply {
                    workouts.take(10).forEach { workout ->
                        put(
                            JSONObject().apply {
                                put("date", unixToLocalDate(workout.startTime).toString())
                                put("sport", sportLabel(workout.sport))
                                put("distanceKm", round1(workout.distanceMeters / 1000.0))
                                put("durationMin", (workout.durationSeconds / 60.0).roundToInt())
                                put("avgHr", workout.avgHeartRate ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            put(
                "unavailable",
                JSONArray().apply {
                    put("sleep")
                    put("hrv")
                    put("floors")
                },
            )
        }
        return Snapshot(
            json = json.toString(),
            evidence = listOf(
                "Daily summary · $safeDays ${dayWord(safeDays)}",
                if (workouts.isEmpty()) "Workouts · none in range" else "Workouts · $safeDays ${dayWord(safeDays)}",
            ),
        )
    }

    data class Snapshot(val json: String, val evidence: List<String>)
}

private abstract class BaseTool(
    protected val dataSource: HealthDataSource,
    private val today: () -> LocalDate,
) : HealthTool {
    protected fun range(args: JSONObject, defaultDays: Int, maxDays: Int): Pair<LocalDate, LocalDate> {
        val to = args.optString("to").takeIf { it.isNotBlank() }
            ?.let { parseDate(it, "to") }
            ?: today()
        val from = args.optString("from").takeIf { it.isNotBlank() }
            ?.let { parseDate(it, "from") }
            ?: to.minusDays(defaultDays.toLong() - 1)
        if (from.isAfter(to)) throw ToolException("'from' must be on or before 'to'")
        val days = ChronoUnit.DAYS.between(from, to) + 1
        if (days > maxDays) throw ToolException("Range too large: maximum is $maxDays days")
        return from to to
    }

    protected fun parseDate(value: String, field: String): LocalDate =
        runCatching { LocalDate.parse(value) }
            .getOrElse { throw ToolException("'$field' must be an ISO date (YYYY-MM-DD)") }

    protected suspend fun daily(scope: ToolScope, from: LocalDate, to: LocalDate): List<DailyHealth> =
        dataSource.daily(scope, from, to).sortedBy { it.date }

    protected suspend fun samples(scope: ToolScope, kind: String, from: LocalDate, to: LocalDate) =
        dataSource.samples(scope, kind, epochRange(from, to).first, epochRange(from, to).last)

    protected fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
}

private class DailySummaryTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    override val definition = LlmToolDefinition(
        name = "get_daily_summary",
        description = "Daily wellness totals and averages: steps, distance, active time/calories, heart rate, stress, SpO2 and respiration. Missing metrics are omitted and listed as unavailable. Use this for 'how was my week/day' questions.",
        parameters = schema(
            required = emptyList(),
            props = mapOf(
                "from" to dateProp("First day (YYYY-MM-DD), default 7 days ago"),
                "to" to dateProp("Last day (YYYY-MM-DD), default today"),
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val (from, to) = range(args, defaultDays = 7, maxDays = 366)
        val days = daily(scope, from, to)
        val json = JSONObject().apply {
            put("from", from.toString())
            put("to", to.toString())
            put("daysRequested", (ChronoUnit.DAYS.between(from, to) + 1).toInt())
            put("daysWithData", days.size)
            put(
                "days",
                JSONArray().apply {
                    days.forEach { put(it.toJson()) }
                },
            )
            put(
                "unavailable",
                JSONArray().apply {
                    put("sleep")
                    put("hrv")
                    put("floors")
                },
            )
        }
        return ToolResult(json.toString(), listOf("Daily summary · ${days.size} ${dayWord(days.size)}"))
    }
}

private class StepsTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    override val definition = LlmToolDefinition(
        name = "get_steps",
        description = "Steps by day or week. Returns only days with real data; daysWithData/daysRequested tell you whether the range is complete.",
        parameters = schema(
            required = emptyList(),
            props = mapOf(
                "from" to dateProp("First day (YYYY-MM-DD), default 7 days ago"),
                "to" to dateProp("Last day (YYYY-MM-DD), default today"),
                "granularity" to enumProp("day or week", listOf("day", "week"), "day"),
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val granularity = args.optString("granularity", "day").lowercase()
        if (granularity !in setOf("day", "week")) throw ToolException("granularity must be day or week")
        val maxDays = if (granularity == "day") 90 else 366
        val (from, to) = range(args, defaultDays = 7, maxDays = maxDays)
        val days = daily(scope, from, to)
        val buckets = if (granularity == "day") {
            days.map { day ->
                JSONObject().apply {
                    put("date", day.date)
                    put("steps", day.steps)
                }
            }
        } else {
            val grouped = days.groupBy {
                runCatching { LocalDate.parse(it.date) }.getOrNull()?.with(java.time.DayOfWeek.MONDAY)
            }
            grouped.entries.sortedBy { it.key?.toString() ?: "" }.map { (week, entries) ->
                JSONObject().apply {
                    put("weekStart", week?.toString() ?: "")
                    put("daysWithData", entries.size)
                    put("steps", entries.sumOf { it.steps })
                }
            }
        }
        val total = days.sumOf { it.steps }
        val json = JSONObject().apply {
            put("from", from.toString())
            put("to", to.toString())
            put("granularity", granularity)
            put("daysRequested", (ChronoUnit.DAYS.between(from, to) + 1).toInt())
            put("daysWithData", days.size)
            put("totalSteps", total)
            put("averageSteps", if (days.isEmpty()) 0 else (total.toDouble() / days.size).roundToInt())
            put("buckets", JSONArray(buckets))
        }
        return ToolResult(json.toString(), listOf("Steps · ${days.size} ${dayWord(days.size)}"))
    }
}

private class HeartRateTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    override val definition = LlmToolDefinition(
        name = "get_heart_rate",
        description = "Resting/average/max heart rate by day, optionally intraday samples. Returns real stored samples only.",
        parameters = schema(
            required = emptyList(),
            props = mapOf(
                "from" to dateProp("First day (YYYY-MM-DD), default 14 days ago"),
                "to" to dateProp("Last day (YYYY-MM-DD), default today"),
                "granularity" to enumProp("day or week", listOf("day", "week"), "day"),
                "include_samples" to JSONObject().apply {
                    put("type", "boolean")
                    put("description", "Include up to 168 intraday heart-rate samples")
                },
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val granularity = args.optString("granularity", "day").lowercase()
        if (granularity !in setOf("day", "week")) throw ToolException("granularity must be day or week")
        val (from, to) = range(args, defaultDays = 14, maxDays = 366)
        val days = daily(scope, from, to)
        val available = days.filter { it.restingHeartRate != null || it.avgHeartRate != null }
        val resting = available.mapNotNull { it.restingHeartRate }
        val averages = available.mapNotNull { it.avgHeartRate }
        val maxima = available.mapNotNull { it.maxHeartRate }
        val sampleList = if (args.optBoolean("include_samples", false)) {
            downSample(samples(scope, "hr", from, to), 168)
        } else {
            emptyList()
        }
        val json = JSONObject().apply {
            put("from", from.toString())
            put("to", to.toString())
            put("daysRequested", (ChronoUnit.DAYS.between(from, to) + 1).toInt())
            put("daysWithData", available.size)
            put("restingHr", if (resting.isEmpty()) JSONObject.NULL else resting.average().roundToInt())
            put("avgHr", if (averages.isEmpty()) JSONObject.NULL else averages.average().roundToInt())
            put("maxHr", maxima.maxOrNull() ?: JSONObject.NULL)
            put(
                "days",
                JSONArray().apply {
                    available.forEach { day ->
                        put(
                            JSONObject().apply {
                                put("date", day.date)
                                put("restingHr", day.restingHeartRate ?: JSONObject.NULL)
                                put("avgHr", day.avgHeartRate ?: JSONObject.NULL)
                                put("maxHr", day.maxHeartRate ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            if (sampleList.isNotEmpty()) {
                put(
                    "samples",
                    JSONArray().apply {
                        sampleList.forEach { sample ->
                            put(
                                JSONObject().apply {
                                    put("timestamp", sample.timestamp)
                                    put("bpm", sample.value.roundToInt())
                                },
                            )
                        }
                    },
                )
            }
        }
        return ToolResult(json.toString(), listOf("Heart rate · ${available.size} ${dayWord(available.size)}"))
    }

    private fun downSample(
        values: List<dev.opengarmin.app.coach.HealthSample>,
        maxPoints: Int,
    ): List<dev.opengarmin.app.coach.HealthSample> {
        if (values.size <= maxPoints) return values
        val step = values.size.toDouble() / maxPoints
        return (0 until maxPoints).map { values[(it * step).toInt().coerceAtMost(values.lastIndex)] }
    }
}

private class SleepTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    override val definition = LlmToolDefinition(
        name = "get_sleep",
        description = "Sleep duration, stages and score. Returns available=false when sleep data has not been ingested; never infer sleep from other data.",
        parameters = schema(
            required = emptyList(),
            props = mapOf(
                "from" to dateProp("First day (YYYY-MM-DD), default 7 days ago"),
                "to" to dateProp("Last day (YYYY-MM-DD), default today"),
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val (from, to) = range(args, defaultDays = 7, maxDays = 90)
        val json = JSONObject().apply {
            put("from", from.toString())
            put("to", to.toString())
            put("available", false)
            put("sessions", JSONArray())
            put(
                "reason",
                "Sleep files are not ingested yet. Ask the user to sync, or say that sleep data is missing.",
            )
        }
        return ToolResult(json.toString(), listOf("Sleep · not synced"))
    }
}

private class ListActivitiesTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    override val definition = LlmToolDefinition(
        name = "list_activities",
        description = "Recent workouts with compact stats and optional sport category filter. Use get_activity for one workout's detail.",
        parameters = schema(
            required = emptyList(),
            props = mapOf(
                "from" to dateProp("First day (YYYY-MM-DD), default 30 days ago"),
                "to" to dateProp("Last day (YYYY-MM-DD), default today"),
                "type" to enumProp("Optional sport category", listOf("Run", "Ride", "Strength", "Walk"), null),
                "limit" to JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum rows, 1-50 (default 20)")
                },
                "offset" to JSONObject().apply {
                    put("type", "integer")
                    put("description", "Rows to skip (default 0)")
                },
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val (from, to) = range(args, defaultDays = 30, maxDays = 366)
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val category = args.optString("type").takeIf { it.isNotBlank() }?.let { value ->
            SportCategory.entries.firstOrNull { it.label.equals(value, ignoreCase = true) }
                ?: throw ToolException("Unknown activity type: $value")
        }
        val range = epochRange(from, to)
        val all = dataSource.workouts(scope, range.first, range.last, limit = 500, offset = 0)
        val filtered = all.filter { category == null || categoryForSport(it.sport) == category }
        val page = filtered.drop(offset).take(limit)
        val json = JSONObject().apply {
            put("from", from.toString())
            put("to", to.toString())
            put("type", category?.label ?: "All")
            put("totalMatching", filtered.size)
            put(
                "activities",
                JSONArray().apply {
                    page.forEach { workout ->
                        put(
                            JSONObject().apply {
                                put("id", workout.id)
                                put("date", unixToLocalDate(workout.startTime).toString())
                                put("startTime", workout.startTime)
                                put("sport", sportLabel(workout.sport))
                                put("category", categoryForSport(workout.sport).label)
                                put("distanceKm", round1(workout.distanceMeters / 1000.0))
                                put("durationMin", (workout.durationSeconds / 60.0).roundToInt())
                                put("avgHr", workout.avgHeartRate ?: JSONObject.NULL)
                                put("maxHr", workout.maxHeartRate ?: JSONObject.NULL)
                                put("calories", workout.calories ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
        }
        val rangeDays = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
        return ToolResult(json.toString(), listOf("Workouts · $rangeDays ${dayWord(rangeDays)}"))
    }
}

private class ActivityTool(
    private val dataSource: HealthDataSource,
) : HealthTool {
    override val definition = LlmToolDefinition(
        name = "get_activity",
        description = "One workout's stats, laps and (when max_heart_rate is supplied) time in heart-rate zones. Does not return raw GPS points.",
        parameters = schema(
            required = listOf("id"),
            props = mapOf(
                "id" to JSONObject().apply {
                    put("type", "integer")
                    put("description", "Activity id from list_activities")
                },
                "max_heart_rate" to JSONObject().apply {
                    put("type", "integer")
                    put("description", "Optional user max HR for zone calculations (100-250)")
                },
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val id = args.optLong("id", -1)
        if (id <= 0) throw ToolException("id is required")
        val detail = dataSource.workout(scope, id) ?: throw ToolException("Activity not found")
        val summary = detail.summary
        val zones = args.optInt("max_heart_rate", 0).takeIf { it in 100..250 }
            ?.let { maxHr -> zonePercentages(detail.records.mapNotNull { it.heartRate }, maxHr) }
        val json = JSONObject().apply {
            put("id", summary.id)
            put("date", unixToLocalDate(summary.startTime).toString())
            put("sport", sportLabel(summary.sport))
            put("category", categoryForSport(summary.sport).label)
            put("distanceKm", round1(summary.distanceMeters / 1000.0))
            put("durationMin", (summary.durationSeconds / 60.0).roundToInt())
            put("elapsedMin", (summary.elapsedSeconds / 60.0).roundToInt())
            put("avgHr", summary.avgHeartRate ?: JSONObject.NULL)
            put("maxHr", summary.maxHeartRate ?: JSONObject.NULL)
            put("calories", summary.calories ?: JSONObject.NULL)
            put("ascentM", summary.ascentMeters ?: JSONObject.NULL)
            put("descentM", summary.descentMeters ?: JSONObject.NULL)
            put(
                "laps",
                JSONArray().apply {
                    detail.laps.forEach { lap ->
                        put(
                            JSONObject().apply {
                                put("index", lap.index + 1)
                                put("distanceKm", round1(lap.distanceMeters / 1000.0))
                                put("durationMin", (lap.durationSeconds / 60.0).roundToInt())
                                put("avgHr", lap.avgHeartRate ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            if (zones == null) {
                put("zonesAvailable", false)
                put("zonesReason", "No max heart rate supplied")
            } else {
                put("zonesAvailable", true)
                put("zoneBasis", "max_heart_rate=$zones.maxHr")
                put(
                    "zonePercent",
                    JSONObject().apply {
                        zones.percentages.forEachIndexed { index, value -> put("z${index + 1}", value) }
                    },
                )
            }
        }
        return ToolResult(json.toString(), listOf("Workout detail · ${sportLabel(summary.sport)}"))
    }
}

private class ComparePeriodsTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    override val definition = LlmToolDefinition(
        name = "compare_periods",
        description = "Compare a metric across two date ranges. Uses daily averages for fair comparison and reports sample counts.",
        parameters = schema(
            required = listOf("metric", "range_a_start", "range_a_end", "range_b_start", "range_b_end"),
            props = mapOf(
                "metric" to enumProp(
                    "Metric to compare (sleep/hrv are unavailable until ingested)",
                    listOf("steps", "resting_hr", "active_minutes", "calories", "sleep", "hrv"),
                    "steps",
                ),
                "range_a_start" to dateProp("A start (YYYY-MM-DD)"),
                "range_a_end" to dateProp("A end (YYYY-MM-DD)"),
                "range_b_start" to dateProp("B start (YYYY-MM-DD)"),
                "range_b_end" to dateProp("B end (YYYY-MM-DD)"),
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        val metric = args.optString("metric").lowercase()
        if (metric !in setOf("steps", "resting_hr", "active_minutes", "calories", "sleep", "hrv")) {
            throw ToolException("Unknown metric")
        }
        val aStart = parseDate(args.optString("range_a_start"), "range_a_start")
        val aEnd = parseDate(args.optString("range_a_end"), "range_a_end")
        val bStart = parseDate(args.optString("range_b_start"), "range_b_start")
        val bEnd = parseDate(args.optString("range_b_end"), "range_b_end")
        if (aStart.isAfter(aEnd) || bStart.isAfter(bEnd)) throw ToolException("Range start must be before end")
        if (ChronoUnit.DAYS.between(aStart, aEnd) + 1 > 90 ||
            ChronoUnit.DAYS.between(bStart, bEnd) + 1 > 90
        ) {
            throw ToolException("Each comparison range is limited to 90 days")
        }
        val a = daily(scope, aStart, aEnd)
        val b = daily(scope, bStart, bEnd)
        val metricA = metricValue(metric, a)
        val metricB = metricValue(metric, b)
        val json = JSONObject().apply {
            put("metric", metric)
            put(
                "rangeA",
                JSONObject().apply {
                    put("from", aStart.toString())
                    put("to", aEnd.toString())
                    put("daysWithData", a.size)
                    put("value", metricA ?: JSONObject.NULL)
                },
            )
            put(
                "rangeB",
                JSONObject().apply {
                    put("from", bStart.toString())
                    put("to", bEnd.toString())
                    put("daysWithData", b.size)
                    put("value", metricB ?: JSONObject.NULL)
                },
            )
            if (metricA == null || metricB == null) {
                put("insufficientData", true)
            } else {
                val delta = metricB - metricA
                put("delta", round1(delta))
                put("percentChange", if (abs(metricA) < 0.0001) JSONObject.NULL else round1(delta / metricA * 100.0))
                put("insufficientData", false)
            }
        }
        return ToolResult(json.toString(), listOf("${metricLabel(metric)} comparison · ${a.size}+${b.size} days"))
    }

    private fun metricValue(metric: String, days: List<DailyHealth>): Double? = when (metric) {
        "steps" -> days.takeIf { it.isNotEmpty() }?.map { it.steps }?.average()
        "resting_hr" -> days.mapNotNull { it.restingHeartRate }.takeIf { it.isNotEmpty() }?.average()
        "active_minutes" -> days.takeIf { it.isNotEmpty() }?.map { it.activeSeconds / 60.0 }?.average()
        "calories" -> days.takeIf { it.isNotEmpty() }?.map { it.activeCalories }?.average()
        "sleep", "hrv" -> null
        else -> null
    }

    private fun metricLabel(metric: String): String = when (metric) {
        "resting_hr" -> "Resting HR"
        "active_minutes" -> "Active minutes"
        "calories" -> "Calories"
        else -> metric.replaceFirstChar { it.uppercase() }
    }
}

private data class ZoneResult(val maxHr: Int, val percentages: List<Float>)

private fun zonePercentages(heartRates: List<Int>, maxHr: Int): ZoneResult {
    val counts = IntArray(5)
    heartRates.forEach { hr ->
        val percent = hr.toDouble() / maxHr
        val index = when {
            percent < 0.60 -> 0
            percent < 0.70 -> 1
            percent < 0.80 -> 2
            percent < 0.90 -> 3
            else -> 4
        }
        counts[index]++
    }
    val total = counts.sum().coerceAtLeast(1)
    return ZoneResult(maxHr, counts.map { it.toFloat() / total * 100f })
}

private fun DailyHealth.toJson(): JSONObject = JSONObject().apply {
    put("date", date)
    put("steps", steps)
    put("distanceKm", (distanceMeters / 1000.0 * 10).roundToInt() / 10.0)
    put("activeMinutes", activeSeconds / 60)
    put("activeCalories", activeCalories)
    put("restingHr", restingHeartRate ?: JSONObject.NULL)
    put("avgHr", avgHeartRate ?: JSONObject.NULL)
    put("maxHr", maxHeartRate ?: JSONObject.NULL)
    put("stress", stressAvg ?: JSONObject.NULL)
    put("spo2", spo2Avg ?: JSONObject.NULL)
    put("respiration", respirationAvg ?: JSONObject.NULL)
}

private fun schema(required: List<String>, props: Map<String, JSONObject>): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply { props.forEach { (key, value) -> put(key, value) } },
        )
        put("required", JSONArray(required))
        put("additionalProperties", false)
    }

private fun dateProp(description: String): JSONObject = JSONObject().apply {
    put("type", "string")
    put("description", "$description (YYYY-MM-DD)")
}

private fun enumProp(description: String, values: List<String>, default: String?): JSONObject =
    JSONObject().apply {
        put("type", "string")
        put("description", description)
        put("enum", JSONArray(values))
        if (default != null) put("default", default)
    }

private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0

private fun dayWord(count: Int): String = if (count == 1) "day" else "days"
