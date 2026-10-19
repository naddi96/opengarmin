package dev.opengarmin.protocol

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ZlibStoredTest {
    @Test
    fun `stored zlib stream inflates for every window size`() {
        val payload = ByteArray(1500) { (it % 251).toByte() }
        for (bits in 9..15) {
            val compressed = TransportPipe.zlibStored(payload, bits)
            val header = ((compressed[0].toInt() and 0xFF) shl 8) or (compressed[1].toInt() and 0xFF)
            assertEquals(0, header % 31, "zlib header must be a multiple of 31")
            assertEquals(bits - 8, (compressed[0].toInt() and 0xF0) ushr 4, "CINFO should match the window")

            val inflated = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            assertContentEquals(payload, inflated)
        }
    }

    @Test
    fun `empty payload still produces a valid zlib stream`() {
        val compressed = TransportPipe.zlibStored(ByteArray(0), 11)
        val inflated = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        assertEquals(0, inflated.size)
    }
}
