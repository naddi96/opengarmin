package dev.opengarmin.app.coach.tools

import dev.opengarmin.app.coach.DailyHealth
import dev.opengarmin.app.coach.ChartPoint
import dev.opengarmin.app.coach.ChartSeries
import dev.opengarmin.app.coach.ChartSpec
import dev.opengarmin.app.coach.ChartType
import dev.opengarmin.app.coach.HealthDataSource
import dev.opengarmin.app.coach.LOCAL_PROFILE_ID
import dev.opengarmin.app.coach.RecordPoint
import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.ToolScope
import dev.opengarmin.app.coach.WorkoutWriter
import dev.opengarmin.app.coach.categoryForSport
import dev.opengarmin.app.coach.epochRange
import dev.opengarmin.app.coach.sportLabel
import dev.opengarmin.app.coach.unixToLocalDate
import dev.opengarmin.app.data.PlannedDurationType
import dev.opengarmin.app.data.PlannedStep
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.StepRole
import dev.opengarmin.app.data.WorkoutPlanCodec
import dev.opengarmin.app.data.WorkoutSport
import dev.opengarmin.app.data.newStepId
import dev.opengarmin.app.llm.LlmToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ToolException(message: String) : Exception(message)

data class ToolResult(
    val json: String,
    val evidence: List<String>,
    val charts: List<ChartSpec> = emptyList(),
)

interface HealthTool {
    val definition: LlmToolDefinition
    suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult
}

class ToolRegistry(
    private val dataSource: HealthDataSource,
    private val workoutWriter: WorkoutWriter? = null,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    val tools: List<HealthTool> = buildList {
        add(DailySummaryTool(dataSource, today))
        add(StepsTool(dataSource, today))
        add(HeartRateTool(dataSource, today))
        add(SleepTool(dataSource, today))
        add(ListActivitiesTool(dataSource, today))
        add(ActivityTool(dataSource))
        add(ComparePeriodsTool(dataSource, today))
        add(RenderChartTool(dataSource, today))
        if (workoutWriter != null) {
            add(ListPlannedWorkoutsTool(workoutWriter))
            add(CreateWorkoutTool(workoutWriter, today))
        }
    }

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
            workouts.firstOrNull()?.let { latest ->
                dataSource.workout(scope, latest.id)?.let { detail ->
                    put("latestWorkout", workoutDetailJson(detail, includeRoute = false))
                }
            }
            put(
                "unavailable",
                JSONArray().apply {
                    missingMetrics(daily).forEach { put(it) }
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
                    missingMetrics(days).forEach { put(it) }
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
        description = "Sleep duration, stages, score and overnight HRV by day. Returns available=false when sleep data has not been ingested; never infer sleep from other data.",
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
        val sessions = daily(scope, from, to).filter {
            it.sleepSeconds != null || it.sleepScore != null || it.hrvLastNight != null
        }
        val json = JSONObject().apply {
            put("from", from.toString())
            put("to", to.toString())
            put("available", sessions.isNotEmpty())
            put(
                "sessions",
                JSONArray().apply {
                    sessions.forEach { day ->
                        put(
                            JSONObject().apply {
                                put("date", day.date)
                                put("sleepHours", day.sleepSeconds?.let { round1(it / 3600.0) } ?: JSONObject.NULL)
                                put("sleepScore", day.sleepScore ?: JSONObject.NULL)
                                put("deepMinutes", day.deepSleepSeconds?.div(60) ?: JSONObject.NULL)
                                put("lightMinutes", day.lightSleepSeconds?.div(60) ?: JSONObject.NULL)
                                put("remMinutes", day.remSleepSeconds?.div(60) ?: JSONObject.NULL)
                                put("awakeMinutes", day.awakeSleepSeconds?.div(60) ?: JSONObject.NULL)
                                put("hrvMs", day.hrvLastNight?.let { round1(it) } ?: JSONObject.NULL)
                                put("hrvStatus", day.hrvStatus ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            if (sessions.isEmpty()) {
                put("reason", "Sleep and HRV have not been synced for this range. Ask the user to sync after waking.")
            }
        }
        val evidence = if (sessions.isEmpty()) {
            "Sleep · not synced"
        } else {
            "Sleep · ${sessions.size} ${dayWord(sessions.size)}"
        }
        return ToolResult(json.toString(), listOf(evidence))
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
        val filtered = all.filter { category == null || categoryForSport(it.sport, it.subSport) == category }
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
                                put("category", categoryForSport(workout.sport, workout.subSport).label)
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
        description = "One workout's stats, laps, GPS summary (start/end, bounds, point count; set include_route=true for a sampled route), running dynamics (cadence in steps/min, ground contact/stance time, vertical oscillation, vertical ratio, stride length), training effect, and heart-rate zones when max_heart_rate is supplied.",
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
                "include_route" to JSONObject().apply {
                    put("type", "boolean")
                    put("description", "Include up to 60 sampled GPS points when the user asks about the route or location")
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
        val json = workoutDetailJson(
            detail = detail,
            includeRoute = args.optBoolean("include_route", false),
        ).apply {
            if (zones == null) {
                put("zonesAvailable", false)
                put("zonesReason", "No max heart rate supplied")
            } else {
                put("zonesAvailable", true)
                put("zoneBasis", "max_heart_rate=${zones.maxHr}")
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
                    "Metric to compare",
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
        "sleep" -> days.mapNotNull { it.sleepSeconds }.takeIf { it.isNotEmpty() }?.average()?.div(3600.0)
        "hrv" -> days.mapNotNull { it.hrvLastNight }.takeIf { it.isNotEmpty() }?.average()
        else -> null
    }

    private fun metricLabel(metric: String): String = when (metric) {
        "resting_hr" -> "Resting HR"
        "active_minutes" -> "Active minutes"
        "calories" -> "Calories"
        "sleep" -> "Sleep"
        "hrv" -> "HRV"
        else -> metric.replaceFirstChar { it.uppercase() }
    }
}

private class RenderChartTool(
    dataSource: HealthDataSource,
    today: () -> LocalDate,
) : BaseTool(dataSource, today) {
    private val dailyMetrics = listOf(
        DailyChartMetric("steps", "Steps", "steps", ChartType.Bar) { it.steps.toDouble() },
        DailyChartMetric("distance", "Distance", "km", ChartType.Bar) { it.distanceMeters / 1000.0 },
        DailyChartMetric("active_minutes", "Active minutes", "min", ChartType.Bar) {
            it.activeSeconds / 60.0
        },
        DailyChartMetric("calories", "Calories", "kcal", ChartType.Bar) { it.activeCalories.toDouble() },
        DailyChartMetric("resting_hr", "Resting HR", "bpm", ChartType.Line) {
            it.restingHeartRate?.toDouble()
        },
        DailyChartMetric("avg_hr", "Average HR", "bpm", ChartType.Line) { it.avgHeartRate?.toDouble() },
        DailyChartMetric("max_hr", "Max HR", "bpm", ChartType.Line) { it.maxHeartRate?.toDouble() },
        DailyChartMetric("stress", "Stress", "", ChartType.Line) { it.stressAvg?.toDouble() },
        DailyChartMetric("spo2", "SpO2", "%", ChartType.Line) { it.spo2Avg?.toDouble() },
        DailyChartMetric("respiration", "Respiration", "brpm", ChartType.Line) { it.respirationAvg },
        DailyChartMetric("sleep", "Sleep", "h", ChartType.Bar) {
            it.sleepSeconds?.div(3600.0)
        },
        DailyChartMetric("hrv", "HRV", "ms", ChartType.Line) { it.hrvLastNight },
        DailyChartMetric("body_battery", "Body Battery", "/100", ChartType.Line) {
            it.bodyBatteryLatest?.toDouble()
        },
    )

    private val activityMetrics = listOf(
        "heart_rate",
        "speed",
        "pace",
        "cadence",
        "altitude",
        "body_battery",
    )

    override val definition = LlmToolDefinition(
        name = "render_chart",
        description = "Draw a chart in the chat from data already stored on the device. " +
            "Use source=daily for a metric trend over a date range, or source=activities to chart " +
            "the time series of one or more workouts (up to five) and compare them. The app builds the chart from Room, " +
            "so never pass raw points. Reference the chart in your answer. Only chart real data returned " +
            "by tools; if it fails or has too few points, say a chart is unavailable.",
        parameters = schema(
            required = listOf("source", "metric"),
            props = mapOf(
                "source" to enumProp(
                    "Where the chart data comes from",
                    listOf("daily", "activities"),
                    null,
                ),
                "metric" to enumProp(
                    "Daily: steps, distance, active_minutes, calories, resting_hr, avg_hr, max_hr, " +
                        "stress, spo2, respiration, sleep, hrv, body_battery. Activities: heart_rate, speed, pace, cadence, " +
                        "altitude, body_battery.",
                    (dailyMetrics.map { it.key } + activityMetrics).distinct(),
                    null,
                ),
                "from" to dateProp("Daily chart first day, default 14 days ago"),
                "to" to dateProp("Daily chart last day, default today"),
                "activity_ids" to JSONObject().apply {
                    put("type", "array")
                    put("description", "Workout ids from list_activities (1-5 ids)")
                    put(
                        "items",
                        JSONObject().apply {
                            put("type", "integer")
                        },
                    )
                },
                "x_axis" to enumProp(
                    "Activity x axis: elapsed minutes or distance in km",
                    listOf("elapsed", "distance"),
                    "elapsed",
                ),
                "chart_type" to enumProp(
                    "Daily chart style; defaults to bar for counts and line for rates",
                    listOf("line", "bar"),
                    null,
                ),
                "max_points" to JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum points per activity series, 8-120 (default 60)")
                },
                "goal" to JSONObject().apply {
                    put("type", "number")
                    put("description", "Optional goal line for a daily chart (same unit as the metric)")
                },
                "title" to JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional short chart title")
                },
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        return when (args.optString("source").lowercase()) {
            "daily" -> dailyChart(scope, args)
            "activities", "activity" -> activityChart(scope, args)
            else -> throw ToolException("source must be 'daily' or 'activities'")
        }
    }

    private suspend fun dailyChart(scope: ToolScope, args: JSONObject): ToolResult {
        val key = when (val raw = args.optString("metric").lowercase()) {
            "heart_rate", "hr" -> "resting_hr"
            "distance_km" -> "distance"
            "active" -> "active_minutes"
            else -> raw
        }
        val metric = dailyMetrics.firstOrNull { it.key == key }
            ?: throw ToolException("Unknown daily metric: $key")
        val (from, to) = range(args, defaultDays = 14, maxDays = 90)
        val entries = daily(scope, from, to).mapNotNull { day ->
            val value = metric.value(day)?.takeIf { it.isFinite() } ?: return@mapNotNull null
            LocalDate.parse(day.date) to value
        }
        if (entries.isEmpty()) {
            throw ToolException("No ${metric.label.lowercase()} data was stored for that range")
        }
        val requestedType = args.optString("chart_type").lowercase()
        val requestedChartType = when (requestedType) {
            "bar" -> ChartType.Bar
            "line" -> ChartType.Line
            else -> metric.defaultType
        }
        // A 90-bar chart is unreadable. Keep the data truthful and switch the
        // long daily series to a line instead of failing the whole answer.
        val switchedToLine = requestedChartType == ChartType.Bar && entries.size > 31
        val type = if (switchedToLine) ChartType.Line else requestedChartType
        if (type == ChartType.Line && entries.size < 2) {
            throw ToolException("At least two days with ${metric.label.lowercase()} data are needed for a chart")
        }
        val goal = args.optDouble("goal", Double.NaN).takeIf { it.isFinite() && it > 0 }?.toFloat()
        val fallbackTitle = "${metric.label} · ${dateLabel(from)} – ${dateLabel(to)}"
        val title = prefixedTitle(metric.label, args.optString("title"), fallbackTitle)
        val points = entries.map { entry ->
            ChartPoint(
                x = ChronoUnit.DAYS.between(from, entry.first).toFloat(),
                y = entry.second.toFloat(),
            )
        }
        val chart = ChartSpec(
            type = type,
            title = title,
            yLabel = metric.label,
            unit = metric.unit,
            xStartLabel = dateLabel(from),
            xEndLabel = dateLabel(to),
            categories = if (type == ChartType.Bar) {
                generateSequence(from) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(to) }
                    .map { dateLabel(it) }
                    .toList()
            } else {
                emptyList()
            },
            series = listOf(ChartSeries(metric.label, points, color = "accent")),
            goal = goal,
        )
        val values = points.map { it.y.toDouble() }
        val json = JSONObject().apply {
            put("chart", metric.label)
            put("source", "daily")
            put("from", from.toString())
            put("to", to.toString())
            put("points", points.size)
            put("unit", metric.unit)
            put("chartType", if (type == ChartType.Line) "line" else "bar")
            if (switchedToLine) {
                put(
                    "switchedToLine",
                    "The range is too long for readable bars; the app drew a line chart instead.",
                )
            }
            put("min", round1(values.min()))
            put("max", round1(values.max()))
            put("average", round1(values.average()))
            put("note", "The chart is displayed with the answer. Cite these values, not a verbal guess.")
        }
        return ToolResult(
            json = json.toString(),
            evidence = listOf("Chart · ${metric.label} · ${points.size} ${dayWord(points.size)}"),
            charts = listOf(chart),
        )
    }

    private suspend fun activityChart(scope: ToolScope, args: JSONObject): ToolResult {
        val idsArray = args.optJSONArray("activity_ids") ?: args.optJSONArray("ids")
            ?: throw ToolException("activity_ids is required for source=activities")
        val ids = buildList {
            for (index in 0 until idsArray.length()) {
                val id = idsArray.optLong(index, -1)
                if (id > 0 && id !in this) add(id)
            }
        }.take(5)
        if (ids.isEmpty()) throw ToolException("activity_ids must contain at least one workout id")

        val metricKey = normalizeActivityMetric(args.optString("metric"))
        val xAxis = if (args.optString("x_axis").lowercase() in setOf("distance", "km", "distance_km")) {
            "distance"
        } else {
            "elapsed"
        }
        val maxPoints = args.optInt("max_points", 60).coerceIn(8, 120)

        val details = mutableListOf<dev.opengarmin.app.coach.WorkoutDetail>()
        val unavailable = mutableListOf<Long>()
        for (id in ids) {
            val detail = dataSource.workout(scope, id)
            if (detail == null) {
                unavailable += id
            } else {
                details += detail
            }
        }
        if (details.isEmpty()) throw ToolException("No matching activities were found for those ids")

        val allRunning = details.all {
            categoryForSport(it.summary.sport, it.summary.subSport) == SportCategory.Run
        }
        val anyRunning = details.any {
            categoryForSport(it.summary.sport, it.summary.subSport) == SportCategory.Run
        }
        if (metricKey == "cadence" && anyRunning && !allRunning) {
            throw ToolException("Cadence charts need activities from the same sport category")
        }
        val cadenceScale = if (allRunning) 2 else 1
        val cadenceUnit = if (allRunning) "spm" else "rpm"

        val metricLabel = when (metricKey) {
            "heart_rate" -> "Heart rate"
            "speed" -> "Speed"
            "pace" -> "Pace"
            "cadence" -> "Cadence"
            "altitude" -> "Altitude"
            else -> "Body Battery"
        }
        val unit = when (metricKey) {
            "heart_rate" -> "bpm"
            "speed" -> "km/h"
            "pace" -> "min/km"
            "cadence" -> cadenceUnit
            "altitude" -> "m"
            else -> "%"
        }
        val yLabel = when (metricKey) {
            "pace" -> "Pace (lower is faster)"
            "body_battery" -> "Body Battery"
            else -> metricLabel
        }

        val series = mutableListOf<ChartSeries>()
        val summaries = JSONArray()
        val skipped = mutableListOf<Long>()
        val usedLabels = mutableSetOf<String>()
        val colors = listOf("accent", "teal", "coral", "blue", "orange", "yellow")

        for (detail in details) {
            val summary = detail.summary
            val records = detail.records.sortedBy { it.timestamp }
            if (records.isEmpty()) {
                skipped += summary.id
                continue
            }
            val origin = records.first().timestamp
            val speeds = derivedSpeeds(records)
            val points = mutableListOf<ChartPoint>()
            records.forEachIndexed { index, record ->
                val x = when (xAxis) {
                    "distance" -> record.distanceMeters?.div(1000.0)
                    else -> (record.timestamp - origin) / 60.0
                } ?: return@forEachIndexed
                if (!x.isFinite() || x < 0) return@forEachIndexed
                val y = when (metricKey) {
                    "heart_rate" -> record.heartRate?.toDouble()?.takeIf { it in 20.0..250.0 }
                    "speed" -> speeds[index]?.times(3.6)?.takeIf { it in 0.0..100.0 }
                    "pace" -> speeds[index]?.times(3.6)?.takeIf { it >= 1.0 }?.let { 60.0 / it }
                    "cadence" -> record.cadence?.toDouble()?.times(cadenceScale)?.takeIf { it in 0.0..300.0 }
                    "altitude" -> record.altitudeMeters?.takeIf { it in -500.0..9000.0 }
                    else -> record.bodyBattery?.toDouble()?.takeIf { it in 0.0..100.0 }
                } ?: return@forEachIndexed
                if (!y.isFinite()) return@forEachIndexed
                points += ChartPoint(x.toFloat(), y.toFloat())
            }
            val sampled = downsample(points, maxPoints)
            if (sampled.size < 2) {
                skipped += summary.id
                continue
            }
            var label = "${dateLabel(unixToLocalDate(summary.startTime))} · ${sportLabel(summary.sport)}"
            if (!usedLabels.add(label)) {
                label = "$label #${summary.id}"
                usedLabels += label
            }
            series += ChartSeries(
                name = label,
                points = sampled,
                color = colors[series.size % colors.size],
            )
            val values = sampled.map { it.y }
            summaries.put(
                JSONObject().apply {
                    put("label", label)
                    put("id", summary.id)
                    put("points", sampled.size)
                    put("min", round1(values.min().toDouble()))
                    put("max", round1(values.max().toDouble()))
                    put("average", round1(values.average().toDouble()))
                },
            )
        }
        if (series.isEmpty()) {
            throw ToolException(
                "Not enough $metricLabel data to chart those activities. " +
                    "Ask for another metric or choose activities with recorded data.",
            )
        }

        val requestedTitle = args.optString("title").takeIf { it.isNotBlank() }?.take(120)
        val defaultTitle = if (series.size == 1) {
            "$metricLabel · ${series.first().name}"
        } else {
            "$metricLabel · ${series.size} activities"
        }
        val title = prefixedTitle(metricLabel, requestedTitle, defaultTitle)
        val chart = ChartSpec(
            type = ChartType.Line,
            title = title,
            xLabel = if (xAxis == "distance") "Distance (km)" else "Elapsed (min)",
            yLabel = yLabel,
            unit = unit,
            series = series,
        )
        val json = JSONObject().apply {
            put("chart", metricLabel)
            put("source", "activities")
            put("xAxis", xAxis)
            put("unit", unit)
            put("series", summaries)
            if (unavailable.isNotEmpty()) put("unavailableIds", JSONArray(unavailable))
            if (skipped.isNotEmpty()) put("skippedIds", JSONArray(skipped))
            put("note", "The chart is displayed with the answer. Compare the lines in prose; do not invent gaps.")
        }
        return ToolResult(
            json = json.toString(),
            evidence = listOf(
                "Chart · $metricLabel · ${series.size} ${if (series.size == 1) "activity" else "activities"}",
            ),
            charts = listOf(chart),
        )
    }

    private fun normalizeActivityMetric(raw: String): String = when (raw.lowercase()) {
        "", "heart_rate", "heartrate", "hr" -> "heart_rate"
        "speed", "speed_kmh", "velocity" -> "speed"
        "pace", "pace_min_km" -> "pace"
        "cadence", "steps_per_minute", "spm", "rpm" -> "cadence"
        "altitude", "elevation" -> "altitude"
        "body_battery", "bodybattery" -> "body_battery"
        else -> throw ToolException(
            "Unknown activity metric: $raw (use heart_rate, speed, pace, cadence, altitude, body_battery)",
        )
    }

    private fun derivedSpeeds(records: List<RecordPoint>): List<Double?> {
        val result = ArrayList<Double?>(records.size)
        var previous: RecordPoint? = null
        for (record in records) {
            val direct = record.speedMps?.takeIf { it.isFinite() && it >= 0.0 }
            val fallback = if (direct == null && previous != null) {
                val previousDistance = previous.distanceMeters
                val distance = record.distanceMeters
                val seconds = (record.timestamp - previous.timestamp).toDouble()
                if (previousDistance != null && distance != null && seconds > 0 && distance >= previousDistance) {
                    (distance - previousDistance) / seconds
                } else {
                    null
                }
            } else {
                null
            }
            result += direct ?: fallback
            previous = record
        }
        return result
    }

    private fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        if (points.size <= maxPoints) return points
        val last = points.lastIndex
        return (0 until maxPoints)
            .map { index -> points[(index.toDouble() * last / (maxPoints - 1)).roundToInt()] }
            .distinct()
    }

    private fun dateLabel(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))

    private fun prefixedTitle(metric: String, requested: String?, fallback: String): String {
        val custom = requested?.takeIf { it.isNotBlank() }?.take(120) ?: return fallback
        return if (custom.contains(metric, ignoreCase = true)) custom else "$metric · $custom"
    }

    private data class DailyChartMetric(
        val key: String,
        val label: String,
        val unit: String,
        val defaultType: ChartType,
        val value: (DailyHealth) -> Double?,
    )
}

private class ListPlannedWorkoutsTool(
    private val writer: WorkoutWriter,
) : HealthTool {
    override val definition = LlmToolDefinition(
        name = "list_planned_workouts",
        description = "List workouts saved locally in the Plan section, with id, sport, duration/distance, schedule and whether they were sent to the watch. Use this before creating a duplicate or when the user asks what is planned.",
        parameters = schema(required = emptyList(), props = emptyMap()),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        if (scope.profileId != LOCAL_PROFILE_ID) throw ToolException("Unknown profile scope")
        val workouts = writer.listWorkouts(scope)
        val json = JSONObject().apply {
            put("count", workouts.size)
            put(
                "workouts",
                JSONArray().apply {
                    workouts.forEach { workout ->
                        put(
                            JSONObject().apply {
                                put("id", workout.id)
                                put("name", workout.name)
                                put("sport", WorkoutSport.from(workout.sport, workout.subSport).label)
                                put("summary", workout.summary())
                                put("scheduledDate", workout.scheduledDate ?: JSONObject.NULL)
                                put("sentToWatch", workout.lastSentAt != null)
                            },
                        )
                    }
                },
            )
        }
        return ToolResult(json.toString(), listOf("Planned workouts · ${workouts.size}"))
    }
}

private class CreateWorkoutTool(
    private val writer: WorkoutWriter,
    private val today: () -> LocalDate,
) : HealthTool {
    override val definition = LlmToolDefinition(
        name = "create_workout",
        description = "Create a structured workout in the local Plan section. Use this ONLY when the user explicitly asks you to create, save, or plan a workout. " +
            "Steps can be time-based, distance-based or open (lap button), and a repeat block repeats its child steps. " +
            "This saves the workout on the phone; it does NOT send it to the watch. Tell the user to review and send it from Training → Workout plan.",
        parameters = schema(
            required = listOf("name", "steps"),
            props = mapOf(
                "name" to JSONObject().apply {
                    put("type", "string")
                    put("description", "Short workout name, at most 80 characters")
                },
                "sport" to enumProp(
                    "Workout sport",
                    listOf("run", "ride", "walk", "strength", "cardio", "other"),
                    "run",
                ),
                "notes" to JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional instructions or purpose, at most 500 characters")
                },
                "scheduled_date" to dateProp("Optional local date for this session; must not be in the past"),
                "steps" to JSONObject().apply {
                    put("type", "array")
                    put("description", "Ordered workout steps. Each item is a step or a repeat block.")
                    put(
                        "items",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("type", enumProp("step or repeat", listOf("step", "repeat"), "step"))
                                    put("role", enumProp("Step role", listOf("warmup", "active", "interval", "rest", "recovery", "cooldown", "other"), "active"))
                                    put("duration_type", enumProp("Duration type", listOf("time", "distance", "open"), "time"))
                                    put("minutes", JSONObject().apply {
                                        put("type", "number")
                                        put("description", "Minutes for a time step (0-1440)")
                                    })
                                    put("seconds", JSONObject().apply {
                                        put("type", "number")
                                        put("description", "Additional seconds for a time step (0-59)")
                                    })
                                    put("duration_seconds", JSONObject().apply {
                                        put("type", "integer")
                                        put("description", "Alternative total time in seconds (1-86400)")
                                    })
                                    put("distance_km", JSONObject().apply {
                                        put("type", "number")
                                        put("description", "Distance in kilometres for a distance step (0.01-300)")
                                    })
                                    put("count", JSONObject().apply {
                                        put("type", "integer")
                                        put("description", "Repetitions for a repeat block (1-50)")
                                    })
                                    put("steps", JSONObject().apply {
                                        put("type", "array")
                                        put("description", "Child steps for a repeat block; only plain steps, no nested repeats")
                                        put("items", JSONObject().apply { put("type", "object") })
                                    })
                                },
                            )
                            put("additionalProperties", false)
                        },
                    )
                },
            ),
        ),
    )

    override suspend fun execute(scope: ToolScope, args: JSONObject): ToolResult {
        if (scope.profileId != LOCAL_PROFILE_ID) throw ToolException("Unknown profile scope")
        val name = (if (args.isNull("name")) "" else args.optString("name")).trim().take(80)
        if (name.isBlank()) throw ToolException("name is required")
        val sport = if (args.isNull("sport") || args.optString("sport").isBlank()) {
            WorkoutSport.RUN
        } else {
            parseWorkoutSport(args.optString("sport"))
        }
        val notes = (if (args.isNull("notes")) "" else args.optString("notes")).trim().take(500)
        val scheduledRaw = if (args.isNull("scheduled_date")) "" else args.optString("scheduled_date").trim()
        val scheduledDate = scheduledRaw.takeIf { it.isNotEmpty() }?.let { raw ->
            val date = runCatching { LocalDate.parse(raw) }
                .getOrElse { throw ToolException("scheduled_date must be an ISO date (YYYY-MM-DD)") }
            if (date.isBefore(today())) throw ToolException("scheduled_date cannot be in the past")
            date.toString()
        }
        val stepsArray = args.optJSONArray("steps")
            ?: throw ToolException("steps must be a non-empty array")
        val steps = parseWorkoutSteps(stepsArray)
        val flattened = steps.sumOf { step ->
            if (step is PlannedStep.Repeat) step.children.size + 1 else 1
        }
        if (flattened > 100) throw ToolException("Workout has too many steps (maximum 100 including repeat markers)")

        val now = System.currentTimeMillis() / 1000
        val entity = PlannedWorkoutEntity(
            id = 0,
            name = name,
            sport = sport.sport,
            subSport = sport.subSport,
            notes = notes,
            scheduledDate = scheduledDate,
            stepsJson = WorkoutPlanCodec.encode(steps),
            createdAt = now,
            updatedAt = now,
            lastSentAt = null,
        )
        val saved = writer.createWorkout(scope, entity)
        val json = JSONObject().apply {
            put("created", true)
            put("id", saved.id)
            put("name", saved.name)
            put("sport", sport.label)
            put("summary", saved.summary())
            put("scheduledDate", saved.scheduledDate ?: JSONObject.NULL)
            put("stepCount", flattened)
            put("savedIn", "Training → Workout plan")
            put("sentToWatch", false)
            put(
                "nextStep",
                "The workout is saved on the phone. Review it and tap Send to watch in Training → Workout plan.",
            )
            put(
                "steps",
                JSONArray().apply {
                    steps.forEach { put(plannedStepJson(it)) }
                },
            )
        }
        return ToolResult(json.toString(), listOf("Workout created · ${saved.name}"))
    }
}

private fun parseWorkoutSteps(array: JSONArray): List<PlannedStep> {
    if (array.length() == 0) throw ToolException("steps must contain at least one step")
    if (array.length() > 50) throw ToolException("Too many top-level steps (maximum 50)")
    return buildList {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
                ?: throw ToolException("steps[$index] must be an object")
            val rawType = if (obj.isNull("type")) "" else obj.optString("type", "step")
            val type = if (rawType.isBlank()) "step" else normalizeToken(rawType)
            when (type) {
                "repeat" -> {
                    val count = obj.optInt("count", 0)
                    if (count !in 1..50) throw ToolException("steps[$index].count must be between 1 and 50")
                    val childrenArray = obj.optJSONArray("steps")
                        ?: throw ToolException("steps[$index] is a repeat block and needs a steps array")
                    if (childrenArray.length() == 0) {
                        throw ToolException("steps[$index] repeat block needs at least one child step")
                    }
                    if (childrenArray.length() > 20) {
                        throw ToolException("steps[$index] repeat block has too many children (maximum 20)")
                    }
                    val children = buildList {
                        for (childIndex in 0 until childrenArray.length()) {
                            val child = childrenArray.optJSONObject(childIndex)
                                ?: throw ToolException("steps[$index].steps[$childIndex] must be an object")
                            if (normalizeToken(child.optString("type", "step")) == "repeat") {
                                throw ToolException("Nested repeat blocks are not supported")
                            }
                            add(parseSingleWorkoutStep(child, "steps[$index].steps[$childIndex]"))
                        }
                    }
                    add(PlannedStep.Repeat(id = newStepId(), count = count, children = children))
                }

                "step", "" -> add(parseSingleWorkoutStep(obj, "steps[$index]"))
                else -> throw ToolException("steps[$index].type must be 'step' or 'repeat'")
            }
        }
    }
}

private fun parseSingleWorkoutStep(obj: JSONObject, field: String): PlannedStep.Single {
    val rawRole = if (obj.isNull("role")) "" else obj.optString("role")
    val role = if (rawRole.isBlank()) StepRole.ACTIVE else parseStepRole(rawRole)
    val rawDuration = if (obj.isNull("duration_type")) "" else obj.optString("duration_type")
    val durationType = if (rawDuration.isBlank()) PlannedDurationType.TIME else parseDurationType(rawDuration)
    val seconds = if (durationType == PlannedDurationType.TIME) {
        val explicit = obj.optDouble("duration_seconds", Double.NaN)
        val total = if (explicit.isFinite() && explicit > 0) {
            explicit.roundToInt()
        } else {
            val minutes = obj.optDouble("minutes", 0.0).takeIf { it.isFinite() } ?: 0.0
            val extraSeconds = obj.optDouble("seconds", 0.0).takeIf { it.isFinite() } ?: 0.0
            (minutes * 60.0 + extraSeconds).roundToInt()
        }
        if (total !in 1..86_400) throw ToolException("$field needs a duration between 1 second and 24 hours")
        total
    } else {
        300
    }
    val meters = if (durationType == PlannedDurationType.DISTANCE) {
        val km = obj.optDouble("distance_km", obj.optDouble("distance", Double.NaN))
        if (!km.isFinite() || km <= 0.0) {
            throw ToolException("$field needs distance_km for a distance step")
        }
        val value = (km * 1000.0).roundToInt()
        if (value !in 10..300_000) {
            throw ToolException("$field distance must be between 0.01 km and 300 km")
        }
        value
    } else {
        1000
    }
    return PlannedStep.Single(
        id = newStepId(),
        role = role,
        durationType = durationType,
        seconds = seconds,
        meters = meters,
    )
}

private fun parseStepRole(raw: String): StepRole = when (normalizeToken(raw)) {
    "warmup", "warm" -> StepRole.WARM_UP
    "active", "run", "work", "main" -> StepRole.ACTIVE
    "interval", "effort" -> StepRole.INTERVAL
    "rest", "recover" -> StepRole.REST
    "recovery", "easy" -> StepRole.RECOVERY
    "cooldown", "cool", "cool_down" -> StepRole.COOL_DOWN
    "other" -> StepRole.OTHER
    else -> throw ToolException("Unknown step role: $raw")
}

private fun parseDurationType(raw: String): PlannedDurationType = when (normalizeToken(raw)) {
    "time", "duration", "timed" -> PlannedDurationType.TIME
    "distance", "dist" -> PlannedDurationType.DISTANCE
    "open", "lap", "manual" -> PlannedDurationType.OPEN
    else -> throw ToolException("Unknown duration_type: $raw")
}

private fun parseWorkoutSport(raw: String): WorkoutSport = when (normalizeToken(raw)) {
    "run", "running" -> WorkoutSport.RUN
    "ride", "bike", "cycling" -> WorkoutSport.RIDE
    "walk", "walking" -> WorkoutSport.WALK
    "strength", "weights" -> WorkoutSport.STRENGTH
    "cardio" -> WorkoutSport.CARDIO
    "other", "" -> WorkoutSport.OTHER
    else -> throw ToolException("Unknown sport: $raw")
}

private fun normalizeToken(value: String): String =
    value.lowercase(Locale.US).replace("_", "").replace(" ", "")

private fun plannedStepJson(step: PlannedStep): JSONObject = when (step) {
    is PlannedStep.Single -> JSONObject().apply {
        put("type", "step")
        put("role", step.role.label)
        put("summary", step.summary())
        put("durationType", step.durationType.label)
    }

    is PlannedStep.Repeat -> JSONObject().apply {
        put("type", "repeat")
        put("count", step.count)
        put(
            "steps",
            JSONArray().apply {
                step.children.forEach { child ->
                    put(
                        JSONObject().apply {
                            put("role", child.role.label)
                            put("summary", child.summary())
                        },
                    )
                }
            },
        )
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

private fun workoutDetailJson(
    detail: dev.opengarmin.app.coach.WorkoutDetail,
    includeRoute: Boolean,
): JSONObject {
    val summary = detail.summary
    return JSONObject().apply {
        put("id", summary.id)
        put("date", unixToLocalDate(summary.startTime).toString())
        put("startTime", summary.startTime)
        put("sport", sportLabel(summary.sport))
        put("category", categoryForSport(summary.sport, summary.subSport).label)
        put("distanceKm", round1(summary.distanceMeters / 1000.0))
        put("durationMin", (summary.durationSeconds / 60.0).roundToInt())
        put("elapsedMin", (summary.elapsedSeconds / 60.0).roundToInt())
        putNumber("avgHr", summary.avgHeartRate)
        putNumber("maxHr", summary.maxHeartRate)
        putNumber("calories", summary.calories)
        putNumber("ascentM", summary.ascentMeters)
        putNumber("descentM", summary.descentMeters)

        val gps = detail.gps
        put(
            "gps",
            JSONObject().apply {
                put("available", gps?.available == true)
                put("pointCount", gps?.pointCount ?: 0)
                gps?.start?.let {
                    put("startLat", round6(it.latitude))
                    put("startLon", round6(it.longitude))
                }
                gps?.end?.let {
                    put("endLat", round6(it.latitude))
                    put("endLon", round6(it.longitude))
                }
                gps?.bounds?.let {
                    put(
                        "bounds",
                        JSONObject().apply {
                            put("north", round6(it.north))
                            put("south", round6(it.south))
                            put("east", round6(it.east))
                            put("west", round6(it.west))
                        },
                    )
                }
                if (includeRoute && gps != null && gps.route.isNotEmpty()) {
                    put(
                        "route",
                        JSONArray().apply {
                            gps.route.forEach { point ->
                                put(
                                    JSONObject().apply {
                                        put("lat", round6(point.latitude))
                                        put("lon", round6(point.longitude))
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )

        val dynamics = detail.dynamics
        put(
            "runningDynamics",
            JSONObject().apply {
                put("available", dynamics?.available == true)
                if (dynamics == null) {
                    put("reason", "No running-dynamics fields in this activity")
                } else {
                    putNumber("cadenceSpm", dynamics.cadenceSpm?.let(::round1))
                    putNumber("cadenceRaw", dynamics.cadenceRaw?.let(::round1))
                    putNumber("maxCadenceSpm", dynamics.maxCadenceSpm?.let(::round1))
                    putNumber("groundContactTimeMs", dynamics.groundContactTimeMs?.let(::round1))
                    putNumber("groundContactPercent", dynamics.groundContactPercent?.let(::round1))
                    putNumber("groundContactBalanceLeftPct", dynamics.groundContactBalanceLeftPct?.let(::round1))
                    putNumber("verticalOscillationMm", dynamics.verticalOscillationMm?.let(::round1))
                    putNumber("verticalOscillationCm", dynamics.verticalOscillationMm?.let { round1(it / 10.0) })
                    putNumber("verticalRatioPct", dynamics.verticalRatioPct?.let(::round1))
                    putNumber("strideLengthMm", dynamics.strideLengthMm?.let(::round1))
                    putNumber("strideLengthM", dynamics.strideLengthMm?.let { round1(it / 1000.0) })
                    putNumber("powerW", dynamics.powerW)
                    putNumber("trainingEffect", dynamics.trainingEffect)
                    putNumber("anaerobicTrainingEffect", dynamics.anaerobicTrainingEffect)
                    putNumber("trainingLoadPeak", dynamics.trainingLoadPeak)
                    putNumber("movingTimeSeconds", dynamics.movingTimeSeconds)
                    putNumber("standingTimeSeconds", dynamics.standingTimeSeconds)
                    putNumber("avgSpeedKmh", dynamics.avgSpeedKmh?.let(::round1))
                    putNumber("maxSpeedKmh", dynamics.maxSpeedKmh?.let(::round1))
                    putNumber("avgRespiration", dynamics.avgRespiration)
                    putNumber("avgStress", dynamics.avgStress)
                    putNumber("avgSpo2", dynamics.avgSpo2)
                }
            },
        )

        put(
            "laps",
            JSONArray().apply {
                detail.laps.forEach { lap ->
                    val lapDynamics = detail.lapDynamics.firstOrNull { it.index == lap.index }
                    put(
                        JSONObject().apply {
                            put("index", lap.index + 1)
                            put("distanceKm", round1(lap.distanceMeters / 1000.0))
                            put("durationMin", (lap.durationSeconds / 60.0).roundToInt())
                            putNumber("avgHr", lap.avgHeartRate)
                            putNumber("cadenceSpm", lapDynamics?.cadenceSpm?.let(::round1))
                            putNumber("groundContactTimeMs", lapDynamics?.groundContactTimeMs?.let(::round1))
                            putNumber("verticalOscillationMm", lapDynamics?.verticalOscillationMm?.let(::round1))
                            putNumber("strideLengthMm", lapDynamics?.strideLengthMm?.let(::round1))
                        },
                    )
                }
            },
        )
    }
}

private fun JSONObject.putNumber(key: String, value: Number?) {
    if (value != null) put(key, value)
}

private fun round6(value: Double): Double = (value * 1_000_000.0).roundToInt() / 1_000_000.0

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
    if (sleepSeconds != null) put("sleepHours", round1(sleepSeconds / 3600.0)) else put("sleepHours", JSONObject.NULL)
    put("sleepScore", sleepScore ?: JSONObject.NULL)
    put("hrvMs", hrvLastNight?.let { round1(it) } ?: JSONObject.NULL)
    put("hrvStatus", hrvStatus ?: JSONObject.NULL)
    put("bodyBattery", bodyBatteryLatest ?: JSONObject.NULL)
}

private fun missingMetrics(days: List<DailyHealth>): List<String> = buildList {
    if (days.none { it.sleepSeconds != null || it.sleepScore != null }) add("sleep")
    if (days.none { it.hrvLastNight != null }) add("hrv")
    add("floors")
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
