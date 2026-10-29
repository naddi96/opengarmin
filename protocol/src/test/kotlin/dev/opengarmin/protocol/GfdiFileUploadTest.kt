package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class GfdiFileUploadTest {
    @Test
    fun `create file requests a FIT workout slot`() {
        val frame = Gfdi.parse(GfdiFileUpload.createFile(367, fileId = 0x0102030405060708L))
        val parsed = frame!!
        assertEquals(Gfdi.MessageType.CREATE_FILE.id, parsed.type)
        val p = parsed.payload
        assertEquals(22, p.size)
        assertEquals(367, GfdiFileUpload.le32(p, 0))
        assertEquals(GfdiFileUpload.FIT_DATA_TYPE, p[4].toInt() and 0xFF)
        assertEquals(GfdiFileUpload.WORKOUTS_SUBTYPE, p[5].toInt() and 0xFF)
        assertEquals(0, Gfdi.le16(p, 6))
        assertEquals(0xFFFF, Gfdi.le16(p, 10))
        assertEquals(0x0102030405060708L, java.nio.ByteBuffer.wrap(p, 14, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
    }

    @Test
    fun `upload request carries index size offset and seed`() {
        val parsed = Gfdi.parse(GfdiFileUpload.uploadRequest(3, 367, 0, 0))!!
        assertEquals(Gfdi.MessageType.UPLOAD_REQUEST.id, parsed.type)
        val p = parsed.payload
        assertEquals(3, Gfdi.le16(p, 0))
        assertEquals(367, GfdiFileUpload.le32(p, 2))
        assertEquals(0, GfdiFileUpload.le32(p, 6))
        assertEquals(0, Gfdi.le16(p, 10))
    }

    @Test
    fun `sync complete carries the garmin two-byte event`() {
        val parsed = Gfdi.parse(GfdiFileUpload.syncComplete())!!
        assertEquals(Gfdi.MessageType.SYSTEM_EVENT.id, parsed.type)
        assertEquals(byteArrayOf(0, 0).toList(), parsed.payload.toList())
    }

    @Test
    fun `file data chunk layout`() {
        val chunk = byteArrayOf(1, 2, 3, 4, 5)
        val parsed = Gfdi.parse(GfdiFileUpload.fileTransferData(chunk, 340, 0xC1E2))!!
        assertEquals(Gfdi.MessageType.FILE_TRANSFER_DATA.id, parsed.type)
        val p = parsed.payload
        assertEquals(0, p[0].toInt())
        assertEquals(0xC1E2, Gfdi.le16(p, 1))
        assertEquals(340, GfdiFileUpload.le32(p, 3))
        assertEquals(chunk.toList(), p.copyOfRange(7, p.size).toList())
    }
}
