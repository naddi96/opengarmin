package dev.opengarmin.protocol

/**
 * CRC-16 for Garmin GFDI messages.
 *
 * Polynomial 0x8005 (reflected), init 0x0000, no final XOR, computed with the
 * nibble lookup table used by Garmin/Gadgetbridge ("Dallas/Maxim" CRC-16, a.k.a.
 * crcmod `crc-16`). Verified against documented wire vectors — see Crc16Test.
 */
object Crc16 {
    private val TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int =
        continueFrom(0, data, offset, length)

    /** Continues a running CRC (used by the legacy GFDI chunked file upload). */
    fun continueFrom(initial: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = initial
        val end = offset + length
        for (i in offset until end) {
            val b = data[i].toInt() and 0xFF
            crc = (crc ushr 4 and 0x0FFF) xor TABLE[crc and 0x0F]
            crc = crc xor TABLE[b and 0x0F]
            crc = (crc ushr 4 and 0x0FFF) xor TABLE[crc and 0x0F]
            crc = crc xor TABLE[b ushr 4 and 0x0F]
        }
        return crc and 0xFFFF
    }
}
