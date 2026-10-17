package dev.opengarmin.app.coach

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * A small, renderer-agnostic chart description. Charts are built from data the
 * tools actually read from Room; the language model never supplies raw points
 * for a tool-rendered chart. The same format is accepted from a fenced
 * ```chart block as a fallback for providers without tool calling.
 */
enum class ChartType { Line, Bar }

data class ChartPoint(val x: Float, val y: Float)

data class ChartSeries(
    val name: String,
    val points: List<ChartPoint>,
    /** Optional palette key (accent, teal, coral, blue, orange, yellow) or #RRGGBB. */
    val color: String? = null,
)

data class ChartSpec(
    val type: ChartType,
    val title: String,
    val xLabel: String? = null,
    val yLabel: String? = null,
    val unit: String? = null,
    /** Optional explicit first/last labels for the x axis (e.g. dates). */
    val xStartLabel: String? = null,
    val xEndLabel: String? = null,
    val categories: List<String> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    val goal: Float? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", if (type == ChartType.Line) "line" else "bar")
        put("title", title)
        xLabel?.let { put("xLabel", it) }
        yLabel?.let { put("yLabel", it) }
        unit?.let { put("unit", it) }
        xStartLabel?.let { put("xStartLabel", it) }
        xEndLabel?.let { put("xEndLabel", it) }
        goal?.takeIf { it.isFinite() }?.let { put("goal", it.toDouble()) }
        if (categories.isNotEmpty()) {
            put("categories", JSONArray().apply { categories.forEach(::put) })
        }
        put(
            "series",
            JSONArray().apply {
                series.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("name", item.name)
                            item.color?.let { put("color", it) }
                            put(
                                "points",
                                JSONArray().apply {
                                    item.points.forEach { point ->
                                        put(
                                            JSONArray()
                                                .put(point.x.toDouble())
                                                .put(point.y.toDouble()),
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    /** Stable identity used to avoid rendering the same chart twice in one turn. */
    fun key(): String = "$type|$title|$xLabel|$yLabel|" +
        series.joinToString("|") { "${it.name}:${it.points.size}" }

    fun accessibilitySummary(): String = buildString {
        append(title)
        if (series.isNotEmpty()) {
            append(". ")
            append(
                series.joinToString("; ") { item ->
                    val values = item.points.map { it.y }
                    val suffix = unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                    val range = if (values.isEmpty()) {
                        "no values"
                    } else {
                        "from ${formatAxis(values.min())}$suffix to ${formatAxis(values.max())}$suffix"
                    }
                    "${item.name}: ${item.points.size} points, $range"
                },
            )
        }
    }

    companion object {
        private const val MAX_SERIES = 8
        private const val MAX_POINTS = 500
        private const val MAX_CATEGORIES = 60

        fun fromJson(raw: String): ChartSpec? = runCatching { fromJson(JSONObject(raw)) }.getOrNull()

        fun fromJson(json: JSONObject): ChartSpec? {
            val type = when (json.optString("type").lowercase()) {
                "bar", "column", "histogram" -> ChartType.Bar
                else -> ChartType.Line
            }
            val categories = json.optJSONArray("categories")
                ?.let(::stringList)
                ?: json.optJSONArray("labels")?.let(::stringList)
                ?: emptyList()
            val rawSeries = json.optJSONArray("series") ?: JSONArray()
            val series = buildList {
                for (index in 0 until rawSeries.length()) {
                    val item = rawSeries.optJSONObject(index) ?: continue
                    val itemPoints = item.optJSONArray("points")
                        ?.let(::points)
                        ?: item.optJSONArray("values")
                            ?.let { values -> pointsFromValues(values) }
                        ?: emptyList()
                    val name = item.optString("name")
                        .ifBlank { item.optString("label") }
                        .ifBlank { "Series ${this.size + 1}" }
                    add(
                        ChartSeries(
                            name = name.take(80),
                            points = itemPoints,
                            color = item.optString("color").takeIf { it.isNotBlank() },
                        ),
                    )
                }
                if (isEmpty()) {
                    val values = json.optJSONArray("values")
                    if (values != null) {
                        add(
                            ChartSeries(
                                name = json.optString("name").ifBlank { "Series 1" },
                                points = pointsFromValues(values),
                                color = json.optString("color").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
                .take(MAX_SERIES)
                .map { item ->
                    val sorted = if (type == ChartType.Line) item.points.sortedBy { it.x } else item.points
                    item.copy(
                        points = sorted.take(MAX_POINTS),
                        name = item.name.ifBlank { "Series" },
                    )
                }
                .filter { it.points.isNotEmpty() }

            val categoriesTrimmed = categories.take(MAX_CATEGORIES)
            if (type == ChartType.Bar) {
                val count = when {
                    categoriesTrimmed.isNotEmpty() -> categoriesTrimmed.size
                    else -> series.maxOfOrNull { item ->
                        item.points.maxOfOrNull { it.x.roundToInt() + 1 } ?: 0
                    } ?: 0
                }
                if (count <= 0) return null
                // Bars may be sparse (missing days are not zero-filled), so a
                // series does not need one point per category.
                val aligned = series.mapNotNull { item ->
                    val points = item.points.filter { it.x.roundToInt() in 0 until count }
                    if (points.isEmpty()) null else item.copy(points = points)
                }
                if (aligned.isEmpty()) return null
                return ChartSpec(
                    type = ChartType.Bar,
                    title = json.optString("title").ifBlank { "Chart" }.take(120),
                    xLabel = json.optString("xLabel").takeIf { it.isNotBlank() }?.take(40),
                    yLabel = json.optString("yLabel").takeIf { it.isNotBlank() }?.take(40),
                    unit = json.optString("unit").takeIf { it.isNotBlank() }?.take(16),
                    xStartLabel = json.optString("xStartLabel").takeIf { it.isNotBlank() }?.take(40),
                    xEndLabel = json.optString("xEndLabel").takeIf { it.isNotBlank() }?.take(40),
                    categories = if (categoriesTrimmed.isNotEmpty()) {
                        categoriesTrimmed
                    } else {
                        List(count) { formatAxis(it.toFloat()) }
                    },
                    series = aligned,
                    goal = json.optDouble("goal").takeIf { it.isFinite() }?.toFloat(),
                )
            }

            val usable = series.filter { it.points.size >= 2 }
            if (usable.isEmpty()) return null
            return ChartSpec(
                type = ChartType.Line,
                title = json.optString("title").ifBlank { "Chart" }.take(120),
                xLabel = json.optString("xLabel").takeIf { it.isNotBlank() }?.take(40),
                yLabel = json.optString("yLabel").takeIf { it.isNotBlank() }?.take(40),
                unit = json.optString("unit").takeIf { it.isNotBlank() }?.take(16),
                xStartLabel = json.optString("xStartLabel").takeIf { it.isNotBlank() }?.take(40),
                xEndLabel = json.optString("xEndLabel").takeIf { it.isNotBlank() }?.take(40),
                series = usable,
                goal = json.optDouble("goal").takeIf { it.isFinite() }?.toFloat(),
            )
        }

        private fun stringList(array: JSONArray): List<String> =
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }

        private fun points(array: JSONArray): List<ChartPoint> = buildList {
            for (index in 0 until array.length()) {
                val point = array.optJSONArray(index)
                if (point != null) {
                    val x = point.optDouble(0).toFloat()
                    val y = point.optDouble(1).toFloat()
                    if (x.isFinite() && y.isFinite()) add(ChartPoint(x, y))
                    continue
                }
                val item = array.optJSONObject(index) ?: continue
                val x = item.optDouble("x").toFloat()
                val y = item.optDouble("y").toFloat()
                if (x.isFinite() && y.isFinite()) add(ChartPoint(x, y))
            }
        }

        private fun pointsFromValues(values: JSONArray): List<ChartPoint> = buildList {
            for (index in 0 until values.length()) {
                val y = values.optDouble(index).toFloat()
                if (y.isFinite()) add(ChartPoint(index.toFloat(), y))
            }
        }
    }
}

/** Compact number formatting shared by chart labels and accessibility text. */
fun formatAxis(value: Float): String = when {
    !value.isFinite() -> "–"
    value == 0f -> "0"
    kotlin.math.abs(value) >= 1000f -> {
        val scaled = value / 1000f
        if (kotlin.math.abs(scaled) >= 10f || scaled == scaled.toInt().toFloat()) {
            "${scaled.toInt()}k"
        } else {
            String.format(java.util.Locale.US, "%.1fk", scaled)
        }
    }
    value == value.toInt().toFloat() -> value.toInt().toString()
    kotlin.math.abs(value) >= 100f -> value.toInt().toString()
    else -> String.format(java.util.Locale.US, "%.1f", value)
}
