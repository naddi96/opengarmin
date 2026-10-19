package dev.opengarmin.app.data

import dev.opengarmin.protocol.FitWorkout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlanCodecTest {
    @Test
    fun `round trips steps and repeats`() {
        val steps = listOf(
            PlannedStep.Single(role = StepRole.WARM_UP, seconds = 300),
            PlannedStep.Repeat(
                count = 10,
                children = listOf(
                    PlannedStep.Single(role = StepRole.INTERVAL, durationType = PlannedDurationType.DISTANCE, meters = 400),
                    PlannedStep.Single(role = StepRole.RECOVERY, seconds = 90),
                ),
            ),
            PlannedStep.Single(role = StepRole.COOL_DOWN, durationType = PlannedDurationType.OPEN),
        )

        val decoded = WorkoutPlanCodec.decode(WorkoutPlanCodec.encode(steps))
        assertEquals(3, decoded.size)
        assertEquals(StepRole.WARM_UP, (decoded[0] as PlannedStep.Single).role)
        val repeat = decoded[1] as PlannedStep.Repeat
        assertEquals(10, repeat.count)
        assertEquals(2, repeat.children.size)
        assertEquals(400, repeat.children[0].meters)
        assertEquals(PlannedDurationType.OPEN, (decoded[2] as PlannedStep.Single).durationType)
    }

    @Test
    fun `totals include repeated blocks`() {
        val steps = listOf(
            PlannedStep.Single(seconds = 300),
            PlannedStep.Repeat(
                count = 4,
                children = listOf(
                    PlannedStep.Single(seconds = 60),
                    PlannedStep.Single(durationType = PlannedDurationType.DISTANCE, meters = 500),
                ),
            ),
        )
        assertEquals(300 + 4 * 60, WorkoutPlanCodec.totalSeconds(steps))
        assertEquals(4 * 500, WorkoutPlanCodec.totalMeters(steps))
    }

    @Test
    fun `maps repeats to FIT entries`() {
        val steps = listOf(
            PlannedStep.Single(role = StepRole.ACTIVE, seconds = 60),
            PlannedStep.Repeat(
                count = 5,
                children = listOf(
                    PlannedStep.Single(role = StepRole.INTERVAL, seconds = 30),
                    PlannedStep.Single(role = StepRole.REST, seconds = 30),
                ),
            ),
        )
        val entries = WorkoutPlanCodec.toFitEntries(steps)
        assertEquals(2, entries.size)
        val block = entries[1] as FitWorkout.Entry.Block
        assertEquals(5, block.repeat.count)
        assertEquals(2, block.repeat.children.size)
        assertTrue(block.repeat.children[0].duration is FitWorkout.Duration.Time)
    }

    @Test
    fun `malformed json decodes to an empty plan`() {
        assertTrue(WorkoutPlanCodec.decode("{not json").isEmpty())
        assertTrue(WorkoutPlanCodec.decode("").isEmpty())
    }
}
