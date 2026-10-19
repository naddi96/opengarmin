package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FitWorkoutTest {
    private fun workout(): FitWorkout.Workout = FitWorkout.Workout(
        name = "Interval Run",
        sport = 1,
        entries = listOf(
            FitWorkout.Entry.Single(
                FitWorkout.Step("Warm Up", FitWorkout.Duration.Time(300), FitWorkout.INTENSITY_WARMUP),
            ),
            FitWorkout.Entry.Block(
                FitWorkout.Repeat(
                    10,
                    listOf(
                        FitWorkout.Step("Run", FitWorkout.Duration.Distance(400), FitWorkout.INTENSITY_INTERVAL),
                        FitWorkout.Step("Rest", FitWorkout.Duration.Time(90), FitWorkout.INTENSITY_RECOVERY),
                    ),
                ),
            ),
            FitWorkout.Entry.Single(
                FitWorkout.Step("Cool Down", FitWorkout.Duration.Time(300), FitWorkout.INTENSITY_COOLDOWN),
            ),
        ),
    )

    @Test
    fun `encodes a workout the FIT parser can read`() {
        val bytes = FitWorkout.encode(workout(), nowSeconds = 1_700_000_000)
        val parsed = Fit.parse(bytes)

        val fileId = assertNotNull(parsed.fileId)
        assertEquals(FitWorkout.FILE_WORKOUT, fileId.type)
        assertEquals(1, fileId.manufacturer)
        assertEquals(1_700_000_000L - Gfdi.GARMIN_EPOCH_UNIX, fileId.timeCreated)

        val workoutMessage = parsed.messages.first { it.name == "workout" }
        val stepCount = workoutMessage.fields.first { it.number == 6 }.value as Number
        assertEquals(5, stepCount.toInt())
        assertEquals("Interval Run", workoutMessage.fields.first { it.number == 8 }.value)

        val steps = parsed.messages.filter { it.name == "workout_step" }
        assertEquals(5, steps.size)

        // Warm up: time 300 s -> 300_000 ms.
        assertEquals(0, (steps[0].fields.first { it.number == 1 }.value as Number).toInt())
        assertEquals(300_000L, (steps[0].fields.first { it.number == 2 }.value as Number).toLong())

        // First interval: distance 400 m -> 40_000 cm.
        assertEquals(1, (steps[1].fields.first { it.number == 1 }.value as Number).toInt())
        assertEquals(40_000L, (steps[1].fields.first { it.number == 2 }.value as Number).toLong())

        // Repeat marker: back to step 1, ten repetitions.
        val repeat = steps[3]
        assertEquals(FitWorkout.DURATION_REPEAT_UNTIL_STEPS_CMPLT, (repeat.fields.first { it.number == 1 }.value as Number).toInt())
        assertEquals(1L, (repeat.fields.first { it.number == 2 }.value as Number).toLong())
        assertEquals(10L, (repeat.fields.first { it.number == 4 }.value as Number).toLong())
    }

    @Test
    fun `header and data CRCs are valid`() {
        val bytes = FitWorkout.encode(workout(), nowSeconds = 1_700_000_000)
        assertEquals(14, bytes[0].toInt() and 0xFF)
        assertEquals(".FIT", String(bytes, 8, 4, Charsets.US_ASCII))

        val headerCrc = Crc16.compute(bytes, 0, 12)
        assertEquals(headerCrc and 0xFF, bytes[12].toInt() and 0xFF)
        assertEquals((headerCrc ushr 8) and 0xFF, bytes[13].toInt() and 0xFF)

        val payloadStart = 14
        val payloadEnd = bytes.size - 2
        val payloadCrc = Crc16.compute(bytes, payloadStart, payloadEnd - payloadStart)
        assertEquals(payloadCrc and 0xFF, bytes[payloadEnd].toInt() and 0xFF)
        assertEquals((payloadCrc ushr 8) and 0xFF, bytes[payloadEnd + 1].toInt() and 0xFF)
    }

    @Test
    fun `empty workouts still produce a valid FIT file`() {
        val bytes = FitWorkout.encode(
            FitWorkout.Workout("Empty", sport = 1, entries = emptyList()),
            nowSeconds = 1_700_000_000,
        )
        val parsed = Fit.parse(bytes)
        assertEquals(FitWorkout.FILE_WORKOUT, parsed.fileId?.type)
        val workoutMessage = parsed.messages.first { it.name == "workout" }
        assertEquals(0, (workoutMessage.fields.first { it.number == 6 }.value as Number).toInt())
        assertTrue(parsed.messages.none { it.name == "workout_step" })
    }
}
