package dev.opengarmin.app.coach

import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PacePlanSplit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Everything the PacePro coach is allowed to see when it edits splits. */
data class PaceCoachContext(
    val raceLabel: String,
    val distanceMeters: Double,
    val goalSeconds: Int,
    val splits: List<PacePlanSplit>,
    val predictions: Map<String, Int>,
    val recentClimbMeters: Int? = null,
)

data class PaceCoachProposal(
    val message: String,
    /** Zero-based split index to second offset. Offsets always sum to zero. */
    val offsets: Map<Int, Int>,
)

class PaceCoachException(message: String) : Exception(message)

/**
 * Builds a compact, private prompt and parses the strict JSON response. The
 * model never receives health data: only the plan, its elevation profile and
 * race predictions from the user's recent runs.
 */
object PaceCoachAdvisor {
    fun systemPrompt(languageInstruction: String): String = """
        You are an experienced running coach helping an athlete tune PacePro splits.
        By default the finish time is fixed: every second added to one split must
        be taken from another split. If the athlete explicitly asks to add or
        remove finish time, set finishTimeDeltaSeconds and let the split changes
        add up to that same value. Keep the race plan realistic and only change
        the splits that need it. Never claim the plan was uploaded to a watch.

        $languageInstruction
    """.trimIndent()

    fun userPrompt(context: PaceCoachContext, request: String): String {
        val splitRows = JSONArray()
        context.splits.forEach { split ->
            splitRows.put(
                JSONObject()
                    .put("split", split.index + 1)
                    .put("distanceKm", round(split.distanceMeters / 1_000.0))
                    .put("targetSeconds", split.targetSeconds)
                    .put("targetPacePerKm", PacePlanCalculator.formatPace(split.paceSecondsPerKm()))
                    .put("elapsedSeconds", split.cumulativeSeconds)
                    .put("climbMeters", split.gainMeters.roundToInt()),
            )
        }
        val predictions = JSONObject()
        context.predictions.forEach { (race, seconds) -> predictions.put(race, seconds) }
        val payload = JSONObject()
            .put("race", context.raceLabel)
            .put("distanceKm", round(context.distanceMeters / 1_000.0))
            .put("goalSeconds", context.goalSeconds)
            .put("goalTime", PacePlanCalculator.formatClock(context.goalSeconds))
            .put("recentClimbMeters", context.recentClimbMeters)
            .put("racePredictionsSeconds", predictions)
            .put("splits", splitRows)
        return """
            Current PacePro plan:
            ${payload.toString(2)}

            Athlete request:
            $request

            Answer with JSON only, no markdown:
            {
              "message": "short explanation for the athlete",
              "finishTimeDeltaSeconds": 0,
              "changes": [
                {"split": 1, "deltaSeconds": -4},
                {"split": 2, "deltaSeconds": 4}
              ]
            }

            Rules:
            - "split" is the 1-based number from the plan.
            - "deltaSeconds" is an integer; negative means faster, positive slower.
            - "finishTimeDeltaSeconds" is the total finish-time change; positive is
              slower, negative is faster. Use 0 unless the athlete asked to change it.
            - The changes must sum to exactly finishTimeDeltaSeconds.
            - When the athlete asks to distribute extra time by elevation, give more
              seconds to the splits with the largest climbMeters.
            - Include only the splits you change, at most ${context.splits.size} entries.
            - Keep every resulting split at least 30 seconds.
        """.trimIndent()
    }

    /**
     * Parses the model's proposal. JSON is preferred, but the parser also
     * recovers split/delta pairs from slightly malformed JSON or a simple
     * `CHANGE 3 -4` line, which some providers emit instead.
     */
    fun parse(response: String, splitCount: Int): PaceCoachProposal {
        val jsonText = extractJsonObject(response)
        val json = jsonText?.let { text ->
            runCatching { JSONObject(text) }.getOrElse {
                runCatching { JSONObject(repairJson(text)) }.getOrNull()
            }
        }
        val message = json?.optString("message")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Here is a more balanced split distribution."
        val targetDelta = json?.optInt("finishTimeDeltaSeconds", 0) ?: 0
        val offsets = mutableMapOf<Int, Int>()
        val changes = json?.optJSONArray("changes") ?: json?.optJSONArray("deltas")
        if (changes != null) {
            for (index in 0 until changes.length()) {
                val item = changes.optJSONObject(index) ?: continue
                addChange(
                    offsets = offsets,
                    split = item.optInt("split", item.optInt("splitNumber", 0)),
                    delta = item.optInt("deltaSeconds", item.optInt("delta", 0)),
                    splitCount = splitCount,
                )
            }
        }
        if (offsets.isEmpty()) {
            // Fallback for providers that ignore the JSON instruction.
            val objectPattern = Regex(
                """(?is)"?split"?\s*[:=]\s*(\d+)[^{}]*?"?delta(?:Seconds)?"?\s*[:=]\s*(-?\d+)""",
            )
            objectPattern.findAll(response).forEach { match ->
                addChange(offsets, match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2].toIntOrNull() ?: 0, splitCount)
            }
        }
        if (offsets.isEmpty()) {
            val linePattern = Regex("""(?im)^\s*CHANGE\s+(\d+)\s+([+-]?\d+)\s*$""")
            linePattern.findAll(response).forEach { match ->
                addChange(offsets, match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2].toIntOrNull() ?: 0, splitCount)
            }
        }
        val normalized = if (targetDelta == 0) {
            PacePlanCalculator.normalizeOffsets(offsets, splitCount)
        } else {
            PacePlanCalculator.balanceOffsets(offsets, splitCount, targetDelta)
        }
        if (normalized.isEmpty()) {
            throw PaceCoachException("The coach did not suggest any usable changes. Try again.")
        }
        return PaceCoachProposal(message = message, offsets = normalized)
    }

    /**
     * Deterministic proposal used when the provider is unreachable, returns
     * blank content or sends something the parser cannot recover. It keeps the
     * finish time fixed, so the normal Apply flow still works.
     */
    fun localProposal(
        splits: List<PacePlanSplit>,
        request: String,
    ): PaceCoachProposal {
        if (splits.size < 2) {
            throw PaceCoachException("Add at least two splits first.")
        }
        val count = splits.size
        val requested = requestedTimeChange(request)
        if (requested != null) {
            val weights = if (requested.byElevation) {
                splits.map { 1.0 + maxOf(0.0, it.gainMeters) }
            } else {
                List(count) { 1.0 }
            }
            val weightSum = weights.sum().coerceAtLeast(1.0)
            val exact = weights.map { requested.seconds * it / weightSum }
            val values = exact.map { kotlin.math.floor(it).toInt() }.toMutableList()
            var remainder = requested.seconds - values.sum()
            val order = exact.indices.sortedByDescending {
                exact[it] - kotlin.math.floor(exact[it])
            }
            var cursor = 0
            while (remainder != 0 && order.isNotEmpty()) {
                val index = order[cursor % order.size]
                values[index]++
                remainder--
                cursor++
            }
            val offsets = values.mapIndexedNotNull { index, value ->
                if (value == 0) null else index to value
            }.toMap()
            return PaceCoachProposal(
                message = "",
                offsets = PacePlanCalculator.balanceOffsets(
                    offsets = offsets,
                    splitCount = count,
                    targetDelta = requested.seconds,
                ),
            )
        }

        val offsets = mutableMapOf<Int, Int>()
        val lower = request.lowercase()
        when {
            lower.contains("cautious") || lower.contains("prudente") -> {
                val edge = (count / 4).coerceAtLeast(1)
                for (index in 0 until edge) {
                    offsets[index] = (offsets[index] ?: 0) + 2
                }
                for (index in count - edge until count) {
                    offsets[index] = (offsets[index] ?: 0) - 2
                }
            }
            lower.contains("aggressive") || lower.contains("aggressivo") -> {
                val edge = (count / 4).coerceAtLeast(1)
                for (index in 0 until edge) {
                    offsets[index] = (offsets[index] ?: 0) - 2
                }
                for (index in count - edge until count) {
                    offsets[index] = (offsets[index] ?: 0) + 2
                }
            }
            lower.contains("climb") || lower.contains("salita") -> {
                val high = splits.indices.maxByOrNull { splits[it].gainMeters }
                val low = splits.indices.minByOrNull { splits[it].gainMeters }
                if (high != null && low != null && high != low) {
                    offsets[high] = -2
                    offsets[low] = 2
                } else {
                    offsets[0] = 2
                    offsets[count - 1] = -2
                }
            }
            else -> {
                val edge = (count / 4).coerceAtLeast(1)
                for (index in 0 until edge) {
                    offsets[index] = (offsets[index] ?: 0) + 1
                }
                for (index in count - edge until count) {
                    offsets[index] = (offsets[index] ?: 0) - 1
                }
            }
        }
        val normalized = PacePlanCalculator.normalizeOffsets(offsets, count)
        if (normalized.isEmpty()) {
            throw PaceCoachException("The coach did not suggest any usable changes. Try again.")
        }
        return PaceCoachProposal(message = "", offsets = normalized)
    }

    private data class RequestedTimeChange(
        val seconds: Int,
        val byElevation: Boolean,
    )

    /**
     * Recognises explicit requests such as "increase the time by 5 minutes and
     * spread it by elevation". Returns null when the request should keep the
     * finish time fixed.
     */
    private fun requestedTimeChange(request: String): RequestedTimeChange? {
        val lower = request.lowercase()
        val increase = listOf(
            "aument", "increase", "add ", "aggiung", "più lento", "piu lento",
            "slower", "rallent", "more time", "extra time",
        ).any { lower.contains(it) }
        val decrease = listOf(
            "diminu", "decrease", "reduce", "riduc", "togli", "remove",
            "più veloce", "piu veloce", "faster", "less time",
        ).any { lower.contains(it) }
        val direction = when {
            increase && !decrease -> 1
            decrease && !increase -> -1
            else -> return null
        }
        val seconds = Regex("""(\d+(?:[.,]\d+)?)\s*(?:h|ora|ore|hour|hours?)\b""")
            .find(lower)
            ?.groupValues
            ?.get(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.times(3_600)
            ?.roundToInt()
            ?: Regex("""(\d+(?:[.,]\d+)?)\s*(?:min|minut\w*|minutes?)\b""")
                .find(lower)
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.times(60)
                ?.roundToInt()
            ?: Regex("""(\d+)\s*(?:sec|second|secondi)\b""")
                .find(lower)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        if (seconds == null || seconds <= 0) return null
        val byElevation = listOf(
            "dislivello", "salita", "climb", "elevation", "hill", "terreno",
        ).any { lower.contains(it) }
        return RequestedTimeChange(direction * seconds, byElevation)
    }

    private fun addChange(
        offsets: MutableMap<Int, Int>,
        split: Int,
        delta: Int,
        splitCount: Int,
    ) {
        val index = split - 1
        if (index in 0 until splitCount && delta != 0) {
            offsets[index] = (offsets[index] ?: 0) + delta
        }
    }

    /** Finds the outermost JSON object, ignoring braces inside strings. */
    private fun extractJsonObject(response: String): String? {
        val candidates = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        response.forEachIndexed { index, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (character) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        candidates.add(response.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
        return candidates.firstOrNull { it.contains("\"changes\"") || it.contains("\"deltas\"") }
            ?: candidates.firstOrNull()
    }

    /** Escapes raw line breaks inside strings and removes trailing commas. */
    private fun repairJson(json: String): String {
        val builder = StringBuilder(json.length)
        var inString = false
        var escaped = false
        json.forEach { character ->
            if (inString) {
                when {
                    escaped -> {
                        builder.append(character)
                        escaped = false
                    }
                    character == '\\' -> {
                        builder.append(character)
                        escaped = true
                    }
                    character == '"' -> {
                        builder.append(character)
                        inString = false
                    }
                    character == '\n' -> builder.append("\\n")
                    character == '\r' -> builder.append("\\r")
                    character == '\t' -> builder.append("\\t")
                    else -> builder.append(character)
                }
            } else {
                if (character == '"') inString = true
                builder.append(character)
            }
        }
        return builder.toString()
            .replace(Regex(""",\s*([}\]])"""), "$1")
            .replace('\u201c', '"')
            .replace('\u201d', '"')
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
}
