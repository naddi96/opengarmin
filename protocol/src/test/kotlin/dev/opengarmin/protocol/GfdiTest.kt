package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GfdiTest {
    @Test
    fun `builds the documented SET_FILE_FLAGS request byte-for-byte`() {
        val request = Gfdi.build(5008, Hex.decode("280110"), seq = 0x18)
        assertEquals("09000898280110d7f5", Hex.encode(request))
    }

    @Test
    fun `parses the documented request`() {
        val frame = assertNotNull(Gfdi.parse(Hex.decode("09000898280110d7f5")))
        assertEquals(5008, frame.type)
        assertEquals(0x18, frame.seq)
        assertEquals("280110", Hex.encode(frame.payload))
    }

    @Test
    fun `parses the documented response and extracts original type and status`() {
        val frame = assertNotNull(Gfdi.parse(Hex.decode("0d00009690130000c5001031b1")))
        assertEquals(5000, frame.type)
        assertEquals(0x16, frame.seq)

        val response = assertNotNull(Gfdi.parseResponse(frame))
        assertEquals(5008, response.originalType)
        assertEquals(0, response.status)
        assertEquals("00c50010", Hex.encode(response.data))
    }

    @Test
    fun `round-trips a device information request with sequence`() {
        val built = Gfdi.build(Gfdi.MessageType.DEVICE_INFORMATION.id, Hex.decode("9600"), seq = 3)
        val frame = assertNotNull(Gfdi.parse(built))
        assertEquals(Gfdi.MessageType.DEVICE_INFORMATION.id, frame.type)
        assertEquals(3, frame.seq)
    }

    @Test
    fun `rejects corrupted CRC`() {
        val bad = Hex.decode("09000898280110d7ff")
        assertNull(Gfdi.parse(bad))
    }

    @Test
    fun `rejects wrong length field`() {
        val bad = Hex.decode("0a000898280110d7f5")
        assertNull(Gfdi.parse(bad))
    }

    @Test
    fun `builds responses with the request sequence number`() {
        val reply = Gfdi.buildResponse(5008, Gfdi.Status.ACK.code, Hex.decode("00"), seq = 0x16)
        val frame = assertNotNull(Gfdi.parse(reply))
        assertEquals(5000, frame.type)
        assertEquals(0x16, frame.seq)
        assertTrue(frame.payload.contentEquals(Hex.decode("90130000")))
    }

    @Test
    fun `set file flags helper encodes ARCHIVE`() {
        val msg = Handshake.setFileFlags(fileIndex = 0x0128, flags = 0x10, seq = 0x18)
        assertEquals("09000898280110d7f5", Hex.encode(msg))
    }
}
