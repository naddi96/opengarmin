package dev.opengarmin.protocol

/**
 * Garmin's COBS (Consistent Overhead Byte Stuffing) variant.
 *
 * The encoded form is wrapped in delimiters: `00 <cobs-body> 00`. Runs of
 * non-zero bytes are prefixed with `runLength + 1`; runs of >= 0xFE bytes are
 * emitted as 0xFF chunks of 254 bytes; a trailing zero produces a final 0x01.
 */
object Cobs {

    fun encode(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + data.size / 254 + 3)
        out.add(0x00) // Garmin leading delimiter
        var i = 0
        val n = data.size

        while (i < n) {
            val start = i
            while (i < n && data[i] != 0x00.toByte()) i++

            var payloadSize = i - start
            var cursor = start
            val lastWasZero = i < n && data[i] == 0x00.toByte()

            while (payloadSize >= 0xFE) {
                out.add(0xFF.toByte())
                for (k in 0 until 0xFE) out.add(data[cursor + k])
                cursor += 0xFE
                payloadSize -= 0xFE
            }
            out.add((payloadSize + 1).toByte())
            for (k in 0 until payloadSize) out.add(data[cursor + k])

            if (lastWasZero) i++
        }

        if (data.isNotEmpty() && data[data.size - 1] == 0x00.toByte()) {
            out.add(0x01)
        }
        out.add(0x00) // Garmin trailing delimiter
        return out.toByteArray()
    }

    fun decode(data: ByteArray): ByteArray {
        if (data.size < 4) return ByteArray(0)
        if (data[0] != 0x00.toByte()) return ByteArray(0)
        if (data[data.size - 1] != 0x00.toByte()) return ByteArray(0)

        val buf = data.copyOfRange(1, data.size - 1)
        val out = ArrayList<Byte>(buf.size)
        var i = 0

        while (i < buf.size) {
            val code = buf[i].toInt() and 0xFF
            i++
            if (code == 0) break

            val payloadSize = code - 1
            for (k in 0 until payloadSize) {
                if (i >= buf.size) break
                out.add(buf[i])
                i++
            }
            if (code != 0xFF && i < buf.size) out.add(0x00)
        }
        return out.toByteArray()
    }
}
