package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GFDI message framing.
 *
 * Layout (all little-endian):
 *   [size:2][type:2][payload:N][crc:2]
 *
 * `size` counts the whole message (itself + type + payload + crc).
 * When the high bit of `type` is set, the low byte is `type - 5000` and bits
 * 8..12 carry a 5-bit sequence number used to correlate requests/responses.
 */
object Gfdi {
    const val TYPE_BASE = 5000
    const val SEQ_FLAG = 0x8000
    const val GARMIN_EPOCH_UNIX = 631065600L // 1989-12-31T00:00:00Z

    enum class MessageType(val id: Int) {
        RESPONSE(5000),
        UPLOAD_REQUEST(5003),
        FILE_TRANSFER_DATA(5004),
        CREATE_FILE(5005),
        DIRECTORY_FILTER(5007),
        SET_FILE_FLAGS(5008),
        FIT_DEFINITION(5011),
        FIT_DATA(5012),
        WEATHER_REQUEST(5014),
        DEVICE_INFORMATION(5024),
        DEVICE_SETTINGS(5026),
        SYSTEM_EVENT(5030),
        SUPPORTED_FILE_TYPES_REQUEST(5031),
        NOTIFICATION_UPDATE(5033),
        NOTIFICATION_CONTROL(5034),
        NOTIFICATION_DATA(5035),
        NOTIFICATION_SUBSCRIPTION(5036),
        PROTOBUF_REQUEST(5043),
        PROTOBUF_RESPONSE(5044),
        PROTOBUF_NOTIFICATION(5057),
        CONFIGURATION(5050),
        CURRENT_TIME_REQUEST(5052),
        AUTH_NEGOTIATION(5101),
        ;

        companion object {
            fun of(id: Int): MessageType? = entries.firstOrNull { it.id == id }
        }
    }

    enum class Status(val code: Int) {
        ACK(0), NAK(1), UNSUPPORTED(2), DECODE_ERROR(3), CRC_ERROR(4), LENGTH_ERROR(5)
    }

    data class Frame(val type: Int, val seq: Int?, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && seq == other.seq && payload.contentEquals(other.payload)

        override fun hashCode(): Int = (type * 31 + (seq ?: -1)) * 31 + payload.contentHashCode()
    }

    data class Response(val originalType: Int, val status: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Response && originalType == other.originalType &&
                status == other.status && data.contentEquals(other.data)

        override fun hashCode(): Int = (originalType * 31 + status) * 31 + data.contentHashCode()
    }

    fun build(type: Int, payload: ByteArray = ByteArray(0), seq: Int? = null): ByteArray {
        val size = 2 + 2 + payload.size + 2
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(size.toShort())
        val typeWord = if (seq != null) {
            SEQ_FLAG or ((seq and 0x1F) shl 8) or ((type - TYPE_BASE) and 0xFF)
        } else {
            type
        }
        buf.putShort(typeWord.toShort())
        buf.put(payload)
        val crc = Crc16.compute(buf.array(), 0, size - 2)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    fun buildResponse(originalType: Int, status: Int, payload: ByteArray = ByteArray(0), seq: Int? = null): ByteArray {
        val body = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(originalType.toShort())
            .put(status.toByte())
            .put(payload)
            .array()
        return build(MessageType.RESPONSE.id, body, seq)
    }

    /** Parses a decoded GFDI message, validating length and CRC. Returns null on error. */
    fun parse(data: ByteArray): Frame? {
        if (data.size < 6) return null
        val size = le16(data, 0)
        if (size != data.size) return null
        if (Crc16.compute(data, 0, size - 2) != le16(data, size - 2)) return null

        val rawType = le16(data, 2)
        var type = rawType
        var seq: Int? = null
        if (rawType and SEQ_FLAG != 0) {
            type = (rawType and 0xFF) + TYPE_BASE
            seq = (rawType ushr 8) and 0x1F
        }
        val payload = data.copyOfRange(4, size - 2)
        return Frame(type, seq, payload)
    }

    fun parseResponse(frame: Frame): Response? {
        if (frame.type != MessageType.RESPONSE.id || frame.payload.size < 3) return null
        val originalType = le16(frame.payload, 0)
        val status = frame.payload[2].toInt() and 0xFF
        val data = frame.payload.copyOfRange(3, frame.payload.size)
        return Response(originalType, status, data)
    }

    fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun garminTimestamp(unixSeconds: Long): Long = unixSeconds - GARMIN_EPOCH_UNIX
}
