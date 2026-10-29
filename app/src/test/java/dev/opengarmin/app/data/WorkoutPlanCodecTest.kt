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
    fun `round trips step targets`() {
        val steps = listOf(
            PlannedStep.Single(
                targetType = PlannedTargetType.PACE,
                targetLow = 300,
                targetHigh = 330,
                secondaryTargetType = PlannedTargetType.POWER_ZONE,
                secondaryTargetZone = 2,
            ),
            PlannedStep.Single(targetType = PlannedTargetType.HEART_RATE_ZONE, targetZone = 3),
            PlannedStep.Single(
                targetType = PlannedTargetType.CUSTOM_POWER,
                targetLow = 220,
                targetHigh = 260,
            ),
        )
        val decoded = WorkoutPlanCodec.decode(WorkoutPlanCodec.encode(steps)).map { it as PlannedStep.Single }
        assertEquals(PlannedTargetType.PACE, decoded[0].targetType)
        assertEquals(300, decoded[0].targetLow)
        assertEquals(330, decoded[0].targetHigh)
        assertEquals(PlannedTargetType.POWER_ZONE, decoded[0].secondaryTargetType)
        assertEquals(2, decoded[0].secondaryTargetZone)
        assertEquals(3, decoded[1].targetZone)
        assertEquals(PlannedTargetType.CUSTOM_POWER, decoded[2].targetType)
        assertEquals(260, decoded[2].targetHigh)
    }

    @Test
    fun `encodes targets with FIT units and offsets`() {
        val pace = PlannedStep.Single(
            targetType = PlannedTargetType.PACE,
            targetLow = 300,
            targetHigh = 330,
        ).toFitStep()
        assertEquals(FitWorkout.TARGET_SPEED, pace.targetType)
        // Low speed is the slowest pace (330 s/km), high speed the fastest (300 s/km).
        assertEquals(3_030L, pace.customTargetLow)
        assertEquals(3_333L, pace.customTargetHigh)

        val heartRate = PlannedStep.Single(
            targetType = PlannedTargetType.CUSTOM_HEART_RATE,
            targetLow = 130,
            targetHigh = 150,
        ).toFitStep()
        assertEquals(FitWorkout.TARGET_HEART_RATE, heartRate.targetType)
        assertEquals(230L, heartRate.customTargetLow)
        assertEquals(250L, heartRate.customTargetHigh)

        val power = PlannedStep.Single(
            targetType = PlannedTargetType.CUSTOM_POWER,
            targetLow = 220,
            targetHigh = 260,
        ).toFitStep()
        assertEquals(1_220L, power.customTargetLow)
        assertEquals(1_260L, power.customTargetHigh)

        val zone = PlannedStep.Single(
            targetType = PlannedTargetType.POWER_ZONE,
            targetZone = 2,
        ).toFitStep()
        assertEquals(FitWorkout.TARGET_POWER, zone.targetType)
        assertEquals(2, zone.targetValue)
        assertEquals(0L, zone.customTargetLow)
        assertEquals(0L, zone.customTargetHigh)

        val dual = PlannedStep.Single(
            targetType = PlannedTargetType.HEART_RATE_ZONE,
            targetZone = 2,
            secondaryTargetType = PlannedTargetType.CUSTOM_POWER,
            secondaryTargetLow = 200,
            secondaryTargetHigh = 250,
        ).toFitStep()
        assertEquals(FitWorkout.TARGET_HEART_RATE, dual.targetType)
        assertEquals(2, dual.targetValue)
        assertEquals(FitWorkout.TARGET_POWER, dual.secondaryTargetType)
        assertEquals(1_200L, dual.secondaryCustomTargetLow)
        assertEquals(1_250L, dual.secondaryCustomTargetHigh)
    }

    @Test
    fun `malformed json decodes to an empty plan`() {
        assertTrue(WorkoutPlanCodec.decode("{not json").isEmpty())
        assertTrue(WorkoutPlanCodec.decode("").isEmpty())
    }
}
