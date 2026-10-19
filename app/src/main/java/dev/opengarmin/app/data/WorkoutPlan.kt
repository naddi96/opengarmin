package dev.opengarmin.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.opengarmin.protocol.FitWorkout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A workout saved locally in the Plan tab. Steps are stored as compact JSON. */
@Entity(tableName = "planned_workouts")
data class PlannedWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** FIT sport number (running = 1, cycling = 2, training = 10, walking = 11). */
    val sport: Int,
    val subSport: Int = 0,
    val notes: String = "",
    /** Local date (YYYY-MM-DD) this session is planned for, or null for a draft. */
    val scheduledDate: String? = null,
    val stepsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSentAt: Long? = null,
) {
    val steps: List<PlannedStep>
        get() = WorkoutPlanCodec.decode(stepsJson)

    fun summary(): String {
        val steps = steps
        val totalSeconds = WorkoutPlanCodec.totalSeconds(steps)
        val totalMeters = WorkoutPlanCodec.totalMeters(steps)
        return buildString {
            if (totalSeconds > 0) {
                append(WorkoutPlanCodec.formatDuration(totalSeconds))
            } else {
                append("Open")
            }
            if (totalMeters > 0) {
                if (isNotEmpty()) append(" · ")
                append("%.1f km".format(totalMeters / 1000.0))
            }
        }
    }

    fun toFitWorkout(): FitWorkout.Workout = FitWorkout.Workout(
        name = name,
        sport = sport,
        subSport = subSport,
        description = notes,
        entries = WorkoutPlanCodec.toFitEntries(steps),
    )
}

/** The kind of work in a step. Mapped to a FIT intensity when sent to the watch. */
enum class StepRole(val label: String, val fitIntensity: Int) {
    WARM_UP("Warm Up", FitWorkout.INTENSITY_WARMUP),
    ACTIVE("Active", FitWorkout.INTENSITY_ACTIVE),
    INTERVAL("Interval", FitWorkout.INTENSITY_INTERVAL),
    REST("Rest", FitWorkout.INTENSITY_REST),
    RECOVERY("Recovery", FitWorkout.INTENSITY_RECOVERY),
    COOL_DOWN("Cool Down", FitWorkout.INTENSITY_COOLDOWN),
    OTHER("Other", FitWorkout.INTENSITY_OTHER),
}

enum class PlannedDurationType(val label: String) {
    TIME("Time"),
    DISTANCE("Distance"),
    OPEN("Open"),
}

/** The sports offered by the Plan editor, with their FIT identifiers. */
enum class WorkoutSport(
    val label: String,
    val sport: Int,
    val subSport: Int,
) {
    RUN("Run", 1, 0),
    RIDE("Ride", 2, 0),
    WALK("Walk", 11, 0),
    STRENGTH("Strength", 10, 20),
    CARDIO("Cardio", 10, 26),
    OTHER("Other", 0, 0),
    ;

    companion object {
        fun from(sport: Int, subSport: Int): WorkoutSport =
            entries.firstOrNull { it.sport == sport && it.subSport == subSport }
                ?: entries.firstOrNull { it.sport == sport }
                ?: OTHER
    }
}

sealed class PlannedStep {
    abstract val id: String

    data class Single(
        override val id: String = newId(),
        val role: StepRole = StepRole.ACTIVE,
        val durationType: PlannedDurationType = PlannedDurationType.TIME,
        /** Seconds when [durationType] is TIME. */
        val seconds: Int = 300,
        /** Metres when [durationType] is DISTANCE. */
        val meters: Int = 1000,
    ) : PlannedStep() {
        fun summary(): String = when (durationType) {
            PlannedDurationType.TIME -> WorkoutPlanCodec.formatDuration(seconds)
            PlannedDurationType.DISTANCE -> "%.2f km".format(meters / 1000.0)
            PlannedDurationType.OPEN -> "Open"
        }

        fun toFitStep(): FitWorkout.Step = FitWorkout.Step(
            name = role.label,
            duration = when (durationType) {
                PlannedDurationType.TIME -> FitWorkout.Duration.Time(seconds.coerceAtLeast(1))
                PlannedDurationType.DISTANCE -> FitWorkout.Duration.Distance(meters.coerceAtLeast(1))
                PlannedDurationType.OPEN -> FitWorkout.Duration.Open
            },
            intensity = role.fitIntensity,
            targetType = FitWorkout.TARGET_OPEN,
        )
    }

    data class Repeat(
        override val id: String = newId(),
        val count: Int = 4,
        val children: List<Single> = listOf(
            Single(role = StepRole.ACTIVE, durationType = PlannedDurationType.TIME, seconds = 60),
            Single(role = StepRole.REST, durationType = PlannedDurationType.TIME, seconds = 60),
        ),
    ) : PlannedStep()
}

fun newStepId(): String = "s" + UUID.randomUUID().toString().take(8)

private fun newId(): String = newStepId()

/** JSON codec shared by Room and the Plan editor. */
object WorkoutPlanCodec {
    private const val VERSION = 1

    fun encode(steps: List<PlannedStep>): String {
        val root = JSONObject().put("version", VERSION)
        root.put("steps", JSONArray().apply { steps.forEach { put(encodeStep(it)) } })
        return root.toString()
    }

    fun decode(json: String): List<PlannedStep> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(json)
            val array = root.optJSONArray("steps") ?: JSONArray()
            ArrayList<PlannedStep>().apply {
                for (i in 0 until array.length()) {
                    decodeStep(array.optJSONObject(i))?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeStep(step: PlannedStep): JSONObject = when (step) {
        is PlannedStep.Single -> JSONObject()
            .put("type", "single")
            .put("id", step.id)
            .put("role", step.role.name)
            .put("duration", step.durationType.name)
            .put("seconds", step.seconds)
            .put("meters", step.meters)

        is PlannedStep.Repeat -> JSONObject()
            .put("type", "repeat")
            .put("id", step.id)
            .put("count", step.count)
            .put(
                "children",
                JSONArray().apply {
                    step.children.forEach { child ->
                        put(encodeStep(child))
                    }
                },
            )
    }

    private fun decodeStep(obj: JSONObject?): PlannedStep? {
        if (obj == null) return null
        return when (obj.optString("type", "single")) {
            "repeat" -> {
                val children = ArrayList<PlannedStep.Single>()
                val array = obj.optJSONArray("children") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val child = decodeStep(array.optJSONObject(i))
                    if (child is PlannedStep.Single) children.add(child)
                }
                PlannedStep.Repeat(
                    id = obj.optString("id").ifBlank { newStepId() },
                    count = obj.optInt("count", 4).coerceIn(1, 99),
                    children = children,
                )
            }

            else -> PlannedStep.Single(
                id = obj.optString("id").ifBlank { newStepId() },
                role = runCatching { StepRole.valueOf(obj.optString("role", "ACTIVE")) }
                    .getOrDefault(StepRole.ACTIVE),
                durationType = runCatching {
                    PlannedDurationType.valueOf(obj.optString("duration", "TIME"))
                }.getOrDefault(PlannedDurationType.TIME),
                seconds = obj.optInt("seconds", 300).coerceAtLeast(1),
                meters = obj.optInt("meters", 1000).coerceAtLeast(1),
            )
        }
    }

    fun totalSeconds(steps: List<PlannedStep>): Int = steps.sumOf { step ->
        when (step) {
            is PlannedStep.Single -> if (step.durationType == PlannedDurationType.TIME) step.seconds else 0
            is PlannedStep.Repeat -> step.count * step.children.sumOf {
                if (it.durationType == PlannedDurationType.TIME) it.seconds else 0
            }
        }
    }

    fun totalMeters(steps: List<PlannedStep>): Int = steps.sumOf { step ->
        when (step) {
            is PlannedStep.Single -> if (step.durationType == PlannedDurationType.DISTANCE) step.meters else 0
            is PlannedStep.Repeat -> step.count * step.children.sumOf {
                if (it.durationType == PlannedDurationType.DISTANCE) it.meters else 0
            }
        }
    }

    fun toFitEntries(steps: List<PlannedStep>): List<FitWorkout.Entry> = steps.mapNotNull { step ->
        when (step) {
            is PlannedStep.Single -> FitWorkout.Entry.Single(step.toFitStep())
            is PlannedStep.Repeat -> if (step.children.isEmpty()) {
                null
            } else {
                FitWorkout.Entry.Block(
                    FitWorkout.Repeat(
                        count = step.count.coerceIn(1, 99),
                        children = step.children.map { it.toFitStep() },
                    ),
                )
            }
        }
    }

    fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 && secs > 0 -> "${minutes}m ${secs}s"
            minutes > 0 -> "${minutes} min"
            else -> "${secs}s"
        }
    }
}

/** UI-facing state for the asynchronous upload to the watch. */
data class WorkoutSendStatus(
    val workoutId: Long,
    val name: String,
    val sending: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
)
