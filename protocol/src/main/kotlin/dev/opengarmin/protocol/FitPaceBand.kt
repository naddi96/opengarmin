package dev.opengarmin.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Minimal FIT pace-band encoder.
 *
 * Garmin watches receive PacePro strategies as `FIT_TYPE_56` files. The public
 * FIT SDK does not describe this file type; the message and field layout below
 * was recovered from the FIT encoder bundled with Garmin Connect 5.19.1:
 *
 *  - `file_id` (0) with type = pace band (56)
 *  - `pace_band` (310) with the goal and the strategy metadata
 *  - `pace_band_split` (311) once per split
 *
 * Values are stored in the same units as the Garmin encoder: times in
 * milliseconds, distances in centimetres, speeds in millimetres per second and
 * elevations as `metres * 5 + 500`.
 */
object FitPaceBand {
    const val FILE_PACE_BAND = 56

    const val PACE_BAND = 310
    const val PACE_BAND_SPLIT = 311

    private const val HEADER_SIZE = 14
    private const val PROTOCOL_VERSION = 0x20
    private const val PROFILE_VERSION = 21218

    private const val INVALID_U32 = 0xFFFFFFFFL
    private const val INVALID_S32 = 0x7FFFFFFF

    /** Fixed-distance segmentation (the same mode Garmin uses for km/mile splits). */
    const val SEGMENTATION_FIXED_DISTANCE = 0
    const val SEGMENTATION_ELEVATION = 1

    data class Split(
        val index: Int,
        /** Cumulative seconds from the start. */
        val cumulativeTimeSeconds: Double,
        /** Cumulative metres from the start. */
        val cumulativeDistanceMeters: Double,
        /** Target time for this split, in seconds. */
        val targetSplitTimeSeconds: Double,
        /** Length of this split, in metres. */
        val targetSplitDistanceMeters: Double,
        /** Average speed needed for this split, in metres per second. */
        val targetSplitAvgSpeedMps: Double,
        val startElevationMeters: Double? = null,
        val endElevationMeters: Double? = null,
        val startLatitude: Double? = null,
        val startLongitude: Double? = null,
        val endLatitude: Double? = null,
        val endLongitude: Double? = null,
        val endBearingDegrees: Double? = null,
    )

    data class Plan(
        val name: String,
        val goalTotalTimeSeconds: Double,
        val goalDistanceMeters: Double,
        val goalAvgSpeedMps: Double,
        val splitCount: Int,
        val userFixedSplitDistanceMeters: Double = 1000.0,
        val segmentationType: Int = SEGMENTATION_FIXED_DISTANCE,
        /** Slider value in seconds per km; negative is a negative split. */
        val pacingStrategy: Double = 0.0,
        /** -1 (easier) .. 1 (harder). */
        val hillEffort: Double = 0.0,
        val elevationSegmentTolerance: Int = 50,
        val elevationSegmentMinLength: Int = 1700,
        val paceFactor: Double = 2.0,
        val containsManualPaceEdits: Boolean = false,
        val splits: List<Split>,
    )

    /** Encodes a complete FIT file, including header and trailing CRC. */
    fun encode(plan: Plan, nowSeconds: Long = System.currentTimeMillis() / 1000): ByteArray {
        val created = (nowSeconds - Gfdi.GARMIN_EPOCH_UNIX).coerceAtLeast(0)
        val data = ByteArrayOutputStream()

        // file_id ---------------------------------------------------------
        data.definition(
            local = 0,
            global = Fit.FILE_ID,
            fields = listOf(
                FieldDef(0, 1, 0x00), // type
                FieldDef(1, 2, 0x84), // manufacturer
                FieldDef(2, 2, 0x84), // product
                FieldDef(3, 4, 0x8C), // serial_number
                FieldDef(4, 4, 0x86), // time_created
            ),
        )
        data.record(0) {
            u8(FILE_PACE_BAND)
            u16(1) // Garmin
            u16(0)
            u32(created and 0xFFFFFFFFL)
            u32(created and 0xFFFFFFFFL)
        }

        // pace_band -------------------------------------------------------
        data.definition(
            local = 1,
            global = PACE_BAND,
            fields = listOf(
                FieldDef(0, 4, 0x86), // event_timestamp
                FieldDef(1, 32, 0x07), // name
                FieldDef(2, 1, 0x00), // segmentation_type
                FieldDef(3, 4, 0x86), // goal_total_time (ms)
                FieldDef(4, 2, 0x84), // goal_avg_speed (mm/s)
                FieldDef(5, 4, 0x86), // goal_distance (cm)
                FieldDef(6, 2, 0x84), // split_count
                FieldDef(7, 4, 0x86), // user_fixed_split_distance (cm)
                FieldDef(8, 4, 0x88), // pacing_strategy (float32)
                FieldDef(9, 4, 0x88), // hill_effort (float32)
                FieldDef(10, 4, 0x8C), // course_serial_number
                FieldDef(11, 4, 0x88), // elevation_segment_tolerance (float32)
                FieldDef(12, 4, 0x88), // elevation_segment_min_length (float32)
                FieldDef(13, 4, 0x88), // pace_factor (float32)
                FieldDef(14, 1, 0x00), // contains_manual_pace_edits
                FieldDef(0xFC, 1, 0x02), // checksum
                FieldDef(0xFB, 13, 0x0D), // pad
            ),
        )
        data.record(1) {
            u32(created and 0xFFFFFFFFL)
            utf8(plan.name, 32)
            u8(plan.segmentationType)
            u32((plan.goalTotalTimeSeconds * 1000.0).roundToLong().coerceAtLeast(1L))
            u16((plan.goalAvgSpeedMps * 1000.0).roundToInt().coerceIn(0, 0xFFFF))
            u32((plan.goalDistanceMeters * 100.0).roundToLong().coerceAtLeast(1L))
            u16(plan.splitCount.coerceIn(0, 0xFFFF))
            u32((plan.userFixedSplitDistanceMeters * 100.0).roundToLong().coerceAtLeast(1L))
            f32(plan.pacingStrategy)
            f32(plan.hillEffort)
            u32(0L) // no course serial number
            f32(plan.elevationSegmentTolerance.toDouble())
            f32(plan.elevationSegmentMinLength.toDouble())
            f32(plan.paceFactor)
            u8(if (plan.containsManualPaceEdits) 1 else 0)
            u8(0) // checksum
            repeat(13) { u8(0) }
        }

        // pace_band_split ------------------------------------------------
        data.definition(
            local = 2,
            global = PACE_BAND_SPLIT,
            fields = listOf(
                FieldDef(254, 2, 0x84), // message_index
                FieldDef(0, 4, 0x86), // cumulative_time (ms)
                FieldDef(1, 4, 0x86), // cumulative_distance (cm)
                FieldDef(2, 4, 0x86), // target_split_time (ms)
                FieldDef(3, 4, 0x86), // target_split_distance (cm)
                FieldDef(4, 2, 0x84), // target_split_avg_speed (mm/s)
                FieldDef(9, 4, 0x85), // start_long
                FieldDef(10, 4, 0x85), // start_lat
                FieldDef(11, 4, 0x85), // end_long
                FieldDef(12, 4, 0x85), // end_lat
                FieldDef(13, 4, 0x86), // start_elevation
                FieldDef(14, 4, 0x86), // end_elevation
                FieldDef(15, 4, 0x85), // end_bearing
                FieldDef(0xFC, 1, 0x02), // checksum
                FieldDef(0xFB, 13, 0x0D), // pad
            ),
        )
        plan.splits.forEachIndexed { index, split ->
            data.record(2) {
                u16(index)
                u32(millis(split.cumulativeTimeSeconds))
                u32(centimetres(split.cumulativeDistanceMeters))
                u32(millis(split.targetSplitTimeSeconds))
                u32(centimetres(split.targetSplitDistanceMeters))
                val speed = (split.targetSplitAvgSpeedMps * 1000.0).roundToInt()
                u16(speed.coerceIn(0, 0xFFFF))
                s32(semicircles(split.startLongitude))
                s32(semicircles(split.startLatitude))
                s32(semicircles(split.endLongitude))
                s32(semicircles(split.endLatitude))
                u32(elevation(split.startElevationMeters))
                u32(elevation(split.endElevationMeters))
                s32(bearing(split.endBearingDegrees))
                u8(0)
                repeat(13) { u8(0) }
            }
        }

        return file(data.toByteArray())
    }

    private fun millis(seconds: Double): Long =
        (seconds * 1000.0).roundToLong().coerceIn(0L, INVALID_U32 - 1)

    private fun centimetres(meters: Double): Long =
        (meters * 100.0).roundToLong().coerceIn(0L, INVALID_U32 - 1)

    private fun elevation(meters: Double?): Long {
        if (meters == null) return INVALID_U32
        return (meters * 5.0 + 500.0).roundToLong().coerceIn(0L, INVALID_U32 - 1)
    }

    private fun semicircles(degrees: Double?): Int {
        if (degrees == null) return INVALID_S32
        val value = (degrees / 180.0 * 2147483648.0).roundToLong()
        return value.coerceIn(Int.MIN_VALUE.toLong() + 1, Int.MAX_VALUE.toLong() - 1).toInt()
    }

    private fun bearing(degrees: Double?): Int {
        if (degrees == null) return INVALID_S32
        val value = (degrees / 180.0 * 2147483648.0).roundToLong()
        return value.coerceIn(Int.MIN_VALUE.toLong() + 1, Int.MAX_VALUE.toLong() - 1).toInt()
    }

    private data class FieldDef(val number: Int, val size: Int, val baseType: Int)

    private fun file(payload: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = HEADER_SIZE.toByte()
        header[1] = PROTOCOL_VERSION.toByte()
        header[2] = (PROFILE_VERSION and 0xFF).toByte()
        header[3] = ((PROFILE_VERSION ushr 8) and 0xFF).toByte()
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        val headerCrc = Crc16.compute(header, 0, 12)
        header[12] = (headerCrc and 0xFF).toByte()
        header[13] = ((headerCrc ushr 8) and 0xFF).toByte()
        val crc = Crc16.compute(payload)
        val out = ByteArray(header.size + payload.size + 2)
        header.copyInto(out, 0)
        payload.copyInto(out, header.size)
        out[out.size - 2] = (crc and 0xFF).toByte()
        out[out.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    private fun ByteArrayOutputStream.definition(local: Int, global: Int, fields: List<FieldDef>) {
        u8(0x40 or (local and 0x0F))
        u8(0)
        u8(0) // little endian
        u16(global)
        u8(fields.size)
        fields.forEach { f ->
            u8(f.number)
            u8(f.size)
            u8(f.baseType)
        }
    }

    private fun ByteArrayOutputStream.record(local: Int, block: ByteArrayOutputStream.() -> Unit) {
        u8(local and 0x0F)
        block()
    }

    private fun ByteArrayOutputStream.u8(value: Int) {
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        repeat(4) { u8((value ushr (8 * it)).toInt()) }
    }

    private fun ByteArrayOutputStream.s32(value: Int) {
        u32(value.toLong() and 0xFFFFFFFFL)
    }

    private fun ByteArrayOutputStream.f32(value: Double) {
        val bits = java.lang.Float.floatToRawIntBits(value.toFloat()).toLong() and 0xFFFFFFFFL
        u32(bits)
    }

    private fun ByteArrayOutputStream.utf8(value: String, size: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val count = minOf(bytes.size, size - 1)
        write(bytes, 0, count)
        repeat(size - count) { write(0) }
    }
}
