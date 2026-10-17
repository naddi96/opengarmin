package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CobsTest {
    @Test
    fun `wraps payload in leading and trailing zero delimiters`() {
        val encoded = Cobs.encode(Hex.decode("09000898280110d7f5"))
        assertEquals(0x00, encoded.first().toInt())
        assertEquals(0x00, encoded.last().toInt())
    }

    @Test
    fun `round-trips a framed GFDI message`() {
        val message = Hex.decode("09000898280110d7f5")
        val decoded = Cobs.decode(Cobs.encode(message))
        assertTrue(decoded.contentEquals(message), "got ${Hex.encode(decoded)}")
    }

    @Test
    fun `round-trips payloads containing zero bytes`() {
        val payload = Hex.decode("0000ff0011223300")
        val decoded = Cobs.decode(Cobs.encode(payload))
        assertTrue(decoded.contentEquals(payload), "got ${Hex.encode(decoded)}")
    }

    @Test
    fun `round-trips a long run over the 254-byte chunk boundary`() {
        val payload = ByteArray(600) { (it % 251 + 1).toByte() } // no zero bytes
        val decoded = Cobs.decode(Cobs.encode(payload))
        assertTrue(decoded.contentEquals(payload))
    }

    @Test
    fun `round-trips a payload ending in zero`() {
        val payload = Hex.decode("aabbcc00")
        val decoded = Cobs.decode(Cobs.encode(payload))
        assertTrue(decoded.contentEquals(payload), "got ${Hex.encode(decoded)}")
    }
}
