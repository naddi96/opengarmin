package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacePlanCalculatorTest {
    private fun run(
        id: Long,
        distance: Double,
        duration: Double,
        daysAgo: Long = 7,
        sport: Int = 1,
    ): ActivityEntity {
        val now = 1_700_000_000L
        return ActivityEntity(
            id = id,
            sourceKey = "run-$id",
            fileName = "run-$id.fit",
            startTime = now - daysAgo * 24 * 60 * 60,
            sport = sport,
            subSport = 0,
            distanceMeters = distance,
            durationSeconds = duration,
            elapsedSeconds = duration,
            avgHeartRate = 150,
            maxHeartRate = 180,
            calories = 500,
            ascentMeters = 20,
            descentMeters = 20,
            importedAt = now,
        )
    }

    @Test
    fun `predictions use a recent run and the Riegel formula`() {
        val activity = run(1, distance = 10_000.0, duration = 2_700.0)
        val predictions = PacePlanCalculator.predictions(
            activities = listOf(activity),
            nowSeconds = 1_700_000_000L,
        )
        val tenK = predictions.getValue(PaceRace.TEN_K)
        assertEquals(2_700, tenK)
        val fiveK = predictions.getValue(PaceRace.FIVE_K)
        assertTrue(fiveK in 1_270..1_350)
    }

    @Test
    fun `stale or non-running activities are ignored`() {
        val stale = run(1, 10_000.0, 2_700.0, daysAgo = 400)
        val ride = run(2, 30_000.0, 3_600.0, sport = 2)
        val predictions = PacePlanCalculator.predictions(
            activities = listOf(stale, ride),
            nowSeconds = 1_700_000_000L,
        )
        assertTrue(predictions.isEmpty())
    }

    @Test
    fun `even splits add up to the goal`() {
        val splits = PacePlanCalculator.buildSplits(
            raceDistanceMeters = 10_000.0,
            goalSeconds = 2_770,
            splitSlider = 50,
            hillSlider = 50,
        )
        assertEquals(10, splits.size)
        assertEquals(2_770, splits.sumOf { it.targetSeconds })
        assertEquals(10_000.0, splits.last().cumulativeDistanceMeters, 0.001)
        assertEquals(2_770, splits.last().cumulativeSeconds)
    }

    @Test
    fun `negative split is slower at the start and faster at the end`() {
        val splits = PacePlanCalculator.buildSplits(
            raceDistanceMeters = 10_000.0,
            goalSeconds = 2_770,
            splitSlider = 0,
            hillSlider = 50,
        )
        assertTrue(splits.first().targetSeconds > splits.last().targetSeconds)
        assertEquals(2_770, splits.sumOf { it.targetSeconds })
        assertTrue(PacePlanCalculator.splitSliderLabel(0).startsWith("Negative"))
    }

    @Test
    fun `positive split is faster at the start and slower at the end`() {
        val splits = PacePlanCalculator.buildSplits(
            raceDistanceMeters = 10_000.0,
            goalSeconds = 2_770,
            splitSlider = 100,
            hillSlider = 50,
        )
        assertTrue(splits.first().targetSeconds < splits.last().targetSeconds)
        assertTrue(PacePlanCalculator.splitSliderLabel(100).startsWith("Positive"))
    }

    @Test
    fun `climbs add time and easier effort adds more`() {
        val course = PaceCourse(
            id = "hill",
            name = "Hill loop",
            distanceMeters = 10_000.0,
            gainsPerKm = listOf(0.0, 20.0, 50.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        val easier = PacePlanCalculator.buildSplits(10_000.0, 2_770, 50, 0, course)
        val harder = PacePlanCalculator.buildSplits(10_000.0, 2_770, 50, 100, course)
        assertTrue(easier[2].targetSeconds > harder[2].targetSeconds)
        assertEquals(50.0, easier[2].gainMeters, 0.001)
    }

    @Test
    fun `half marathon has a partial final split`() {
        val splits = PacePlanCalculator.buildSplits(21_097.5, 6_150, 50, 50)
        assertEquals(22, splits.size)
        assertEquals(1_000.0, splits.first().distanceMeters, 0.001)
        assertEquals(97.5, splits.last().distanceMeters, 0.001)
        assertEquals(21_097.5, splits.last().cumulativeDistanceMeters, 0.001)
        assertEquals(6_150, splits.sumOf { it.targetSeconds })
    }

    @Test
    fun `custom distance estimate scales from the closest race`() {
        val predictions = mapOf(
            PaceRace.TEN_K to 2_700,
            PaceRace.HALF to 6_000,
        )
        val fifteenK = PacePlanCalculator.estimateGoal(15_000.0, predictions)
        assertTrue(fifteenK != null && fifteenK > 2_700 && fifteenK < 6_000)
        assertEquals(2_700, PacePlanCalculator.estimateGoal(10_000.0, predictions))
        assertNull(PacePlanCalculator.estimateGoal(15_000.0, emptyMap()))
    }

    @Test
    fun `plan codec round trips splits`() {
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_325, 50, 50)
        val decoded = PacePlanCodec.decodeSplits(PacePlanCodec.encodeSplits(splits))
        assertEquals(splits.size, decoded.size)
        assertEquals(splits[2].targetSeconds, decoded[2].targetSeconds)
        assertEquals(splits.last().cumulativeDistanceMeters, decoded.last().cumulativeDistanceMeters, 0.001)
        assertTrue(PacePlanCodec.decodeSplits("{bad").isEmpty())
    }

    @Test
    fun `plan maps to the pace band encoder`() {
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_325, 50, 50)
        val plan = PacePlanEntity(
            name = "5K",
            race = PaceRace.FIVE_K.id,
            distanceMeters = 5_000.0,
            goalSeconds = 1_325,
            courseName = "Flat, no course",
            splitsJson = PacePlanCodec.encodeSplits(splits),
            createdAt = 0,
            updatedAt = 0,
        )
        val band = plan.toFitPaceBand()
        assertEquals(5, band.splitCount)
        assertEquals(5, band.splits.size)
        assertEquals(1_325.0, band.goalTotalTimeSeconds, 0.001)
        assertEquals(5_000.0, band.splits.last().cumulativeDistanceMeters, 0.001)
    }

    @Test
    fun `course builder ignores activities without a track`() {
        val activity = run(1, 10_000.0, 2_700.0)
        assertNull(PacePlanCalculator.courseFromActivity(activity, emptyList()))
    }
}
