package dev.opengarmin.protocol

import java.io.ByteArrayOutputStream

/**
 * Minimal FIT workout-file encoder.
 *
 * Garmin watches receive structured workouts as `FIT_TYPE_5` (`WORKOUTS`)
 * files over the GDI FileAccess push path. The public FIT format only needs
 * three message types for this:
 *
 *  - `file_id` (0) with type = workout (5)
 *  - `workout` (26) with sport and the number of valid steps
 *  - `workout_step` (27) for every step, plus a repeat step for each block
 *
 * A repeat block is encoded as a step with
 * `duration_type = repeat_until_steps_cmplt` immediately after the block. Its
 * `duration_value` points at the first step to repeat and `target_value` holds
 * the number of repetitions.
 *
 * Time durations are milliseconds and distance durations are centimetres on
 * the wire; the helper [Duration] values use seconds/metres.
 */
object FitWorkout : FitEncoder(profileVersion = 21184) {
    // FIT file types.
    const val FILE_WORKOUT = 5

    // wkt_step_duration.
    const val DURATION_TIME = 0
    const val DURATION_DISTANCE = 1
    const val DURATION_OPEN = 5
    const val DURATION_REPEAT_UNTIL_STEPS_CMPLT = 6

    // wkt_step_target.
    const val TARGET_OPEN = 2

    // Intensity.
    const val INTENSITY_ACTIVE = 0
    const val INTENSITY_REST = 1
    const val INTENSITY_WARMUP = 2
    const val INTENSITY_COOLDOWN = 3
    const val INTENSITY_RECOVERY = 4
    const val INTENSITY_INTERVAL = 5
    const val INTENSITY_OTHER = 6


    sealed class Duration {
        /** Seconds. */
        data class Time(val seconds: Int) : Duration()

        /** Metres. */
        data class Distance(val meters: Int) : Duration()

        data object Open : Duration()
    }

    /** One ordinary workout step (not a repeat marker). */
    data class Step(
        val name: String,
        val duration: Duration,
        val intensity: Int = INTENSITY_ACTIVE,
        val targetType: Int = TARGET_OPEN,
        val targetValue: Int = 0,
        val customTargetLow: Long = 0,
        val customTargetHigh: Long = 0,
        val notes: String = "",
    )

    /** A block of steps repeated [count] times. */
    data class Repeat(val count: Int, val children: List<Step>)

    sealed class Entry {
        data class Single(val step: Step) : Entry()
        data class Block(val repeat: Repeat) : Entry()
    }

    data class Workout(
        val name: String,
        val sport: Int,
        val subSport: Int = 0,
        val description: String = "",
        val entries: List<Entry>,
    )

    /** Encodes a complete FIT file, including header and trailing CRC. */
    fun encode(workout: Workout, nowSeconds: Long = System.currentTimeMillis() / 1000): ByteArray {
        val created = (nowSeconds - Gfdi.GARMIN_EPOCH_UNIX).coerceAtLeast(0)
        val steps = ArrayList<Step>()
        val repeatMarkers = ArrayList<Pair<Int, Pair<Int, Int>>>()
        for (entry in workout.entries) {
            when (entry) {
                is Entry.Single -> steps.add(entry.step)
                is Entry.Block -> {
                    if (entry.repeat.children.isEmpty()) continue
                    val first = steps.size
                    steps.addAll(entry.repeat.children)
                    steps.add(
                        Step(
                            name = "Repeat",
                            duration = Duration.Open,
                            intensity = INTENSITY_ACTIVE,
                        ),
                    )
                    // The repeat marker is filled in below; keep its index.
                    repeatMarkers += steps.size - 1 to (first to entry.repeat.count)
                }
            }
        }

        val data = ByteArrayOutputStream()
        // file_id
        data.definition(
            local = 0,
            global = 0,
            fields = listOf(
                FieldDef(0, 1, 0x00), // type
                FieldDef(1, 2, 0x84), // manufacturer
                FieldDef(2, 2, 0x84), // product
                FieldDef(3, 4, 0x8C), // serial_number
                FieldDef(4, 4, 0x86), // time_created
            ),
        )
        data.record(0) {
            u8(FILE_WORKOUT)
            u16(1) // Garmin
            u16(0)
            u32(created and 0xFFFFFFFFL)
            u32(created and 0xFFFFFFFFL)
        }

        // workout
        data.definition(
            local = 1,
            global = 26,
            fields = listOf(
                FieldDef(4, 1, 0x00), // sport
                FieldDef(6, 2, 0x84), // num_valid_steps
                FieldDef(8, 40, 0x07), // wkt_name
                FieldDef(11, 1, 0x00), // sub_sport
                FieldDef(17, 80, 0x07), // wkt_description
            ),
        )
        data.record(1) {
            u8(workout.sport)
            u16(steps.size)
            utf8(workout.name, 40)
            u8(workout.subSport)
            utf8(workout.description, 80)
        }

        // workout_step
        data.definition(
            local = 2,
            global = 27,
            fields = listOf(
                FieldDef(254, 2, 0x84), // message_index
                FieldDef(0, 32, 0x07), // wkt_step_name
                FieldDef(1, 1, 0x00), // duration_type
                FieldDef(2, 4, 0x86), // duration_value
                FieldDef(3, 1, 0x00), // target_type
                FieldDef(4, 4, 0x86), // target_value
                FieldDef(5, 4, 0x86), // custom_target_value_low
                FieldDef(6, 4, 0x86), // custom_target_value_high
                FieldDef(7, 1, 0x00), // intensity
                FieldDef(8, 80, 0x07), // notes
            ),
        )
        val markers = repeatMarkers.toMap()
        steps.forEachIndexed { index, step ->
            val marker = markers[index]
            val durationType: Int
            val durationValue: Long
            val targetValue: Long
            if (marker != null) {
                val (first, count) = marker
                durationType = DURATION_REPEAT_UNTIL_STEPS_CMPLT
                durationValue = first.toLong()
                targetValue = count.toLong()
            } else {
                when (val d = step.duration) {
                    is Duration.Time -> {
                        durationType = DURATION_TIME
                        durationValue = d.seconds.toLong() * 1000L
                    }
                    is Duration.Distance -> {
                        durationType = DURATION_DISTANCE
                        durationValue = d.meters.toLong() * 100L
                    }
                    Duration.Open -> {
                        durationType = DURATION_OPEN
                        durationValue = 0L
                    }
                }
                targetValue = step.targetValue.toLong()
            }
            data.record(2) {
                u16(index)
                utf8(step.name, 32)
                u8(durationType)
                u32(durationValue)
                u8(if (marker != null) TARGET_OPEN else step.targetType)
                u32(targetValue)
                u32(if (marker != null) 0L else step.customTargetLow)
                u32(if (marker != null) 0L else step.customTargetHigh)
                u8(step.intensity)
                utf8(step.notes, 80)
            }
        }

        return file(data.toByteArray())
    }
}
