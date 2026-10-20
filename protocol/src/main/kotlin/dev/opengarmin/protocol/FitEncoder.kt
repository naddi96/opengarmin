package dev.opengarmin.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared FIT file framing and primitive encoders.
 *
 * Both the structured-workout encoder ([FitWorkout]) and the PacePro encoder
 * ([FitPaceBand]) write the same 14-byte FIT header, CRC trailer and
 * little-endian definition/data messages. Keeping those primitives here means
 * a header or CRC change only has to be made once.
 */
abstract class FitEncoder(
    private val profileVersion: Int,
) {
    protected data class FieldDef(val number: Int, val size: Int, val baseType: Int)

    protected fun file(payload: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = HEADER_SIZE.toByte()
        header[1] = PROTOCOL_VERSION.toByte()
        header[2] = (profileVersion and 0xFF).toByte()
        header[3] = ((profileVersion ushr 8) and 0xFF).toByte()
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

    protected fun ByteArrayOutputStream.definition(local: Int, global: Int, fields: List<FieldDef>) {
        u8(0x40 or (local and 0x0F))
        u8(0)
        u8(0) // little endian
        u16(global)
        u8(fields.size)
        fields.forEach { field ->
            u8(field.number)
            u8(field.size)
            u8(field.baseType)
        }
    }

    protected fun ByteArrayOutputStream.record(local: Int, block: ByteArrayOutputStream.() -> Unit) {
        u8(local and 0x0F)
        block()
    }

    protected fun ByteArrayOutputStream.u8(value: Int) {
        write(value and 0xFF)
    }

    protected fun ByteArrayOutputStream.u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    protected fun ByteArrayOutputStream.u32(value: Long) {
        repeat(4) { u8((value ushr (8 * it)).toInt()) }
    }

    protected fun ByteArrayOutputStream.s32(value: Int) {
        u32(value.toLong() and 0xFFFFFFFFL)
    }

    protected fun ByteArrayOutputStream.f32(value: Double) {
        u32(value.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
    }

    protected fun ByteArrayOutputStream.utf8(value: String, size: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val count = minOf(bytes.size, size - 1)
        write(bytes, 0, count)
        repeat(size - count) { write(0) }
    }

    private companion object {
        const val HEADER_SIZE = 14
        const val PROTOCOL_VERSION = 0x20
    }
}
