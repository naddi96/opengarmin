package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GdiTest {
    @Test
    fun `frame round-trips`() {
        val frame = GdiFrame.build(id = 0x08bc, offset = 0, totalLength = 5, chunk = Hex.decode("aa01023a00"))
        val parsed = assertNotNull(GdiFrame.parse(frame))
        assertEquals(0x08bc, parsed.id)
        assertEquals(0, parsed.offset)
        assertEquals(5, parsed.totalLength)
        assertTrue(parsed.payload.contentEquals(Hex.decode("aa01023a00")))
    }

    @Test
    fun `chunks and reassembles a large payload`() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val chunks = GdiFrame.chunks(id = 7, payload = payload, chunkSize = 487)
        assertEquals(3, chunks.size)

        val buf = ByteArray(payload.size)
        var received = 0
        for (c in chunks) {
            val f = assertNotNull(GdiFrame.parse(c))
            assertEquals(payload.size, f.totalLength)
            System.arraycopy(f.payload, 0, buf, f.offset, f.payload.size)
            received += f.payload.size
        }
        assertEquals(payload.size, received)
        assertTrue(buf.contentEquals(payload))
    }

    @Test
    fun `item list request has the expected wire bytes`() {
        val req = Gdi.itemListRequest(maxItemCount = 100)
        val smart = Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, req)
        assertEquals("da02044a021864", Hex.encode(smart))
    }

    @Test
    fun `parses an item list response`() {
        val uid = Gdi.Uuid(1L, 2L)
        val dt = Gdi.DataTypeInfo(2, null, 0, null, null, 0, null)
        val item = ProtoWriter()
            .bytes(1, Gdi.encodeUuid(uid))
            .bytes(2, Gdi.encodeDataType(dt))
            .uint64(3, 1234L)
            .uint32(4, 0x10)
            .uint32(5, 7)
            .uint32(6, 1)
            .toByteArray()
        val list = ProtoWriter()
            .uint32(2, 5)
            .uint32(3, 9)
            .bytes(4, item)
            .toByteArray()
        val service = ProtoWriter().bytes(10, list).toByteArray()

        val parsed = assertNotNull(Gdi.parseFileAccessService(service).itemListResponse)
        assertEquals(0, parsed.status)
        assertEquals(5, parsed.sessionId)
        assertEquals(9, parsed.nextTransactionId)
        assertEquals(1, parsed.items.size)
        val first = parsed.items.first()
        assertEquals("00000000000000010000000000000002", first.uid?.hex())
        assertEquals(2, first.dataType?.format)
        assertEquals(1234L, first.dataSize)
        assertEquals(0x10, first.requestedFlags)
        assertEquals(7, first.transactionId)
    }

    @Test
    fun `pull item request encodes the full item reference`() {
        val item = Gdi.ItemReference(
            uid = Gdi.Uuid(1L, 2L),
            dataType = null,
            dataSize = 110,
            requestedFlags = 0,
            transactionId = 5,
            urgency = 0,
        )
        val req = Gdi.pullItemRequest(item, priority = 30, offset = 0, compressionWindow = 15)
        assertEquals(
            "0a200a180a12090100000000000000110200000000000000186e2805101e1800280f",
            Hex.encode(req),
        )
    }

    @Test
    fun `smart wrapper round-trips multiple services`() {
        val smart = ProtoWriter()
            .bytes(Gdi.SMART_CORE, Hex.decode("aabb"))
            .bytes(Gdi.SMART_FILE_ACCESS, Hex.decode("ccdd"))
            .toByteArray()
        val fields = Gdi.smartFields(smart)
        assertTrue(fields[Gdi.SMART_CORE]!!.contentEquals(Hex.decode("aabb")))
        assertTrue(fields[Gdi.SMART_FILE_ACCESS]!!.contentEquals(Hex.decode("ccdd")))
    }
}
