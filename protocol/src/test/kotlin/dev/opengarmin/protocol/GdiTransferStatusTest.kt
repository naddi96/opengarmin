package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GdiTransferStatusTest {
    @Test
    fun `parses the captured failure after the pipe closed`() {
        // health_ui.log, 2026-10-17 19:22:02: FIT_TYPE_41, handle 37.
        val service = Hex.decode("2a1e0a1209020101088e623645111f0101010101050110251809200c28053003")
        val status = assertNotNull(Gdi.parseTransferStatusRequest(service))

        assertEquals(37, status.transferHandle)
        assertEquals(9, status.failureReason)
        assertEquals(12, status.transportStatus)
        assertEquals(5, status.transportProviderId)
        assertEquals(3, status.transportErrorCode)
        assertTrue(status.failed)
        assertEquals("4536628e08010102010501010101011f", status.uid?.hex())
    }

    @Test
    fun `parses an explicit transport failure`() {
        val service = Hex.decode("2a1e0a1209000100008e623645111f0100000000000110251800200c28053003")
        val status = assertNotNull(Gdi.parseTransferStatusRequest(service))

        assertEquals(37, status.transferHandle)
        assertEquals(0, status.failureReason) // TRANSPORT_FAILED is still a failure
        assertTrue(status.failed)
    }

    @Test
    fun `a status without a failure reason means success`() {
        val service = Hex.decode("2a160a1209000100008e623645111f010000000000011025")
        val status = assertNotNull(Gdi.parseTransferStatusRequest(service))

        assertEquals(37, status.transferHandle)
        assertNull(status.failureReason)
        assertFalse(status.failed)
    }

    @Test
    fun `item-added notifications are not mistaken for transfer status`() {
        val service = ProtoWriter().bytes(12, ProtoWriter().bytes(1, ByteArray(4)).toByteArray()).toByteArray()
        assertNull(Gdi.parseTransferStatusRequest(service))
    }

    @Test
    fun `transfer status response carries field 21`() {
        val smart = Gdi.transferStatusResponse()
        val service = assertNotNull(Gdi.smartFields(smart)[Gdi.SMART_FILE_ACCESS])
        assertNotNull(Gdi.field(service, 21))
    }
}
