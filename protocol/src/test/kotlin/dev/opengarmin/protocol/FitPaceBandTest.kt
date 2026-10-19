package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FitPaceBandTest {
    private fun plan(): FitPaceBand.Plan {
        val distance = 10_000.0
        val goal = 2_770
        val split = 1_000.0
        var cumulativeTime = 0.0
        var cumulativeDistance = 0.0
        val splits = (0 until 10).map { index ->
            val splitTime = if (index == 0) 280.0 else 276.0
            cumulativeTime += splitTime
            cumulativeDistance += split
            FitPaceBand.Split(
                index = index,
                cumulativeTimeSeconds = cumulativeTime,
                cumulativeDistanceMeters = cumulativeDistance,
                targetSplitTimeSeconds = splitTime,
                targetSplitDistanceMeters = split,
                targetSplitAvgSpeedMps = split / splitTime,
                startElevationMeters = 10.0 + index,
                endElevationMeters = 11.0 + index,
                startLatitude = 45.0 + index * 0.001,
                startLongitude = 9.0 + index * 0.001,
                endLatitude = 45.001 + index * 0.001,
                endLongitude = 9.001 + index * 0.001,
                endBearingDegrees = 90.0,
            )
        }
        return FitPaceBand.Plan(
            name = "10K goal",
            goalTotalTimeSeconds = goal.toDouble(),
            goalDistanceMeters = distance,
            goalAvgSpeedMps = distance / goal,
            splitCount = splits.size,
            pacingStrategy = -4.0,
            hillEffort = 0.25,
            splits = splits,
        )
    }

    @Test
    fun `encodes a pace band the FIT parser can read`() {
        val bytes = FitPaceBand.encode(plan(), nowSeconds = 1_700_000_000)
        val parsed = Fit.parse(bytes)

        val fileId = assertNotNull(parsed.fileId)
        assertEquals(FitPaceBand.FILE_PACE_BAND, fileId.type)
        assertEquals(1, fileId.manufacturer)

        val band = parsed.messages.first { it.globalNumber == FitPaceBand.PACE_BAND }
        assertEquals("pace_band", band.name)
        assertEquals(10, (band.fields.first { it.number == 6 }.value as Number).toInt())
        assertEquals(
            2_770_000L,
            (band.fields.first { it.number == 3 }.value as Number).toLong(),
        )
        assertEquals(
            -4.0f,
            (band.fields.first { it.number == 8 }.value as Number).toFloat(),
        )

        val splits = parsed.messages.filter { it.globalNumber == FitPaceBand.PACE_BAND_SPLIT }
        assertEquals(10, splits.size)
        assertEquals(280_000L, (splits[0].fields.first { it.number == 0 }.value as Number).toLong())
        assertEquals(100_000L, (splits[0].fields.first { it.number == 1 }.value as Number).toLong())
        assertEquals(280_000L, (splits[0].fields.first { it.number == 2 }.value as Number).toLong())
        assertEquals(100_000L, (splits[0].fields.first { it.number == 3 }.value as Number).toLong())
    }

    @Test
    fun `header and data CRCs are valid`() {
        val bytes = FitPaceBand.encode(plan(), nowSeconds = 1_700_000_000)
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
    fun `an empty plan still produces a valid pace band file`() {
        val bytes = FitPaceBand.encode(
            FitPaceBand.Plan(
                name = "Empty",
                goalTotalTimeSeconds = 0.0,
                goalDistanceMeters = 0.0,
                goalAvgSpeedMps = 0.0,
                splitCount = 0,
                splits = emptyList(),
            ),
            nowSeconds = 1_700_000_000,
        )
        val parsed = Fit.parse(bytes)
        assertEquals(FitPaceBand.FILE_PACE_BAND, parsed.fileId?.type)
        assertTrue(parsed.messages.none { it.globalNumber == FitPaceBand.PACE_BAND_SPLIT })
    }
}
