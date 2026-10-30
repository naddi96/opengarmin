package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaceTrackTest {
    @Test
    fun `gpx track keeps distance elevation ascent and descent`() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="test">
              <trk><name>Test loop</name><trkseg>
                <trkpt lat="45.0000" lon="7.0000"><ele>200</ele></trkpt>
                <trkpt lat="45.0045" lon="7.0000"><ele>205</ele></trkpt>
                <trkpt lat="45.0090" lon="7.0000"><ele>203</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val track = PaceTrackParser.parse("loop.gpx", gpx.toByteArray())
        assertEquals("Test loop", track.name)
        assertEquals(3, track.points.size)
        assertTrue(track.distanceMeters > 900.0)
        assertEquals(5.0, track.ascentMeters, 0.01)
        assertEquals(2.0, track.descentMeters, 0.01)
        assertTrue(track.hasElevation)
    }

    @Test
    fun `tcx track is detected without relying on the file extension`() {
        val tcx = """
            <?xml version="1.0"?>
            <TrainingCenterDatabase>
              <Activities><Activity><Lap><Track>
                <Trackpoint>
                  <Position><LatitudeDegrees>44.0</LatitudeDegrees><LongitudeDegrees>9.0</LongitudeDegrees></Position>
                  <AltitudeMeters>10</AltitudeMeters>
                </Trackpoint>
                <Trackpoint>
                  <Position><LatitudeDegrees>44.002</LatitudeDegrees><LongitudeDegrees>9.0</LongitudeDegrees></Position>
                  <AltitudeMeters>12</AltitudeMeters>
                </Trackpoint>
              </Track></Lap></Activity></Activities>
            </TrainingCenterDatabase>
        """.trimIndent()
        val track = PaceTrackParser.parse("route.xml", tcx.toByteArray())
        assertEquals(2, track.points.size)
        assertTrue(track.distanceMeters > 200.0)
        assertEquals(2.0, track.ascentMeters, 0.01)
    }

    @Test
    fun `gains per kilometre follow the real track shape`() {
        val points = listOf(
            PaceTrackPoint(45.0, 7.0, 100.0, 0.0),
            PaceTrackPoint(45.0045, 7.0, 120.0, 500.0),
            PaceTrackPoint(45.009, 7.0, 140.0, 1_000.0),
            PaceTrackPoint(45.0135, 7.0, 130.0, 1_500.0),
        )
        val gains = PaceTrackParser.gainsPerKm(points, 1_500.0)
        assertEquals(2, gains.size)
        assertEquals(40.0, gains.sum(), 0.01)
        assertEquals(40.0, gains[0], 0.01)
        assertEquals(0.0, gains[1], 0.01)
    }

    @Test
    fun `manual split edit keeps the finish time fixed`() {
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_500, 50, 50)
        val edited = PacePlanCalculator.adjustSplit(splits, 2, 5)
        assertEquals(1_500, edited.sumOf { it.targetSeconds })
        assertEquals(splits[2].targetSeconds + 5, edited[2].targetSeconds)
        assertEquals(edited.sumOf { it.targetSeconds }, edited.last().cumulativeSeconds)
        assertTrue(edited.filterIndexed { index, _ -> index != 2 }.any { it.targetSeconds != splits[it.index].targetSeconds })
    }

    @Test
    fun `free edit changes the finish time`() {
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_500, 50, 50)
        val edited = PacePlanCalculator.adjustSplit(splits, 1, 6, PaceSplitEditMode.FREE)
        assertEquals(1_506, edited.sumOf { it.targetSeconds })
        assertEquals(splits[1].targetSeconds + 6, edited[1].targetSeconds)
        assertEquals(splits[0].targetSeconds, edited[0].targetSeconds)
    }

    @Test
    fun `lock previous keeps earlier splits untouched`() {
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_500, 50, 50)
        val edited = PacePlanCalculator.adjustSplit(splits, 2, 4, PaceSplitEditMode.LOCK_PREVIOUS)
        assertEquals(1_500, edited.sumOf { it.targetSeconds })
        assertEquals(splits.take(2), edited.take(2))
        assertTrue(edited.drop(3).any { it.targetSeconds != splits[it.index].targetSeconds })
    }

    @Test
    fun `lock following keeps later splits untouched`() {
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_500, 50, 50)
        val edited = PacePlanCalculator.adjustSplit(splits, 2, 4, PaceSplitEditMode.LOCK_SUCCESSIVE)
        assertEquals(1_500, edited.sumOf { it.targetSeconds })
        assertEquals(splits.drop(3), edited.drop(3))
        assertTrue(edited.take(2).any { it.targetSeconds != splits[it.index].targetSeconds })
    }

    @Test
    fun `fixed total spreads repeated taps over every other split`() {
        val splits = PacePlanCalculator.buildSplits(10_000.0, 3_000, 50, 50)
        var edited = splits
        repeat(9) {
            edited = PacePlanCalculator.adjustSplit(
                splits = edited,
                index = 0,
                deltaSeconds = 1,
                mode = PaceSplitEditMode.FIXED_TOTAL,
                baseSplits = splits,
            )
        }
        assertEquals(3_000, edited.sumOf { it.targetSeconds })
        assertEquals(splits[0].targetSeconds + 9, edited[0].targetSeconds)
        assertTrue(edited.drop(1).all { it.targetSeconds == splits[it.index].targetSeconds - 1 })
    }

    @Test
    fun `lock previous spreads compensation over all following splits`() {
        val splits = PacePlanCalculator.buildSplits(10_000.0, 3_000, 50, 50)
        val once = PacePlanCalculator.adjustSplit(
            splits = splits,
            index = 2,
            deltaSeconds = 6,
            mode = PaceSplitEditMode.LOCK_PREVIOUS,
            baseSplits = splits,
        )
        assertEquals(3_000, once.sumOf { it.targetSeconds })
        assertEquals(-6, once.drop(3).sumOf { it.targetSeconds - splits[it.index].targetSeconds })
        assertTrue(once.drop(3).count { it.targetSeconds != splits[it.index].targetSeconds } >= 5)

        var edited = splits
        repeat(7) {
            edited = PacePlanCalculator.adjustSplit(
                splits = edited,
                index = 2,
                deltaSeconds = 1,
                mode = PaceSplitEditMode.LOCK_PREVIOUS,
                baseSplits = splits,
            )
        }
        assertTrue(edited.drop(3).all { it.targetSeconds == splits[it.index].targetSeconds - 1 })
    }

    @Test
    fun `ai offsets are normalised to a neutral total`() {
        val offsets = PacePlanCalculator.normalizeOffsets(
            mapOf(0 to -6, 1 to 4, 2 to 3),
            splitCount = 10,
        )
        assertEquals(0, offsets.values.sum())
        assertTrue(offsets.keys.all { it in 0 until 10 })
    }

    @Test
    fun `offsets round trip through apply`() {
        val base = PacePlanCalculator.buildSplits(10_000.0, 3_000, 50, 50)
        val offsets = mapOf(0 to -4, 3 to 4)
        val edited = PacePlanCalculator.applyOffsets(base, offsets)
        val recovered = PacePlanCalculator.offsetsFrom(base, edited)
        assertEquals(offsets, recovered)
        assertEquals(3_000, edited.sumOf { it.targetSeconds })
    }

    @Test
    fun `samples keep first and last point`() {
        val points = (0 until 20).map {
            PaceTrackPoint(45.0 + it * 0.001, 7.0, null, it * 100.0)
        }
        val sampled = PaceMapService.sample(points, 8)
        assertEquals(8, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertTrue(abs(sampled[1].latitude - points[0].latitude) > 0.0)
    }
}
