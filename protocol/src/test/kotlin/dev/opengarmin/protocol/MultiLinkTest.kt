package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiLinkTest {
    private val clientId = 1L

    @Test
    fun `close-all request layout`() {
        assertEquals(
            "0005" + "0100000000000000" + "0000",
            Hex.encode(MlControl.closeAllRequest(clientId)),
        )
    }

    @Test
    fun `register request layout`() {
        assertEquals(
            "0000" + "0100000000000000" + "0100" + "00",
            Hex.encode(MlControl.registerRequest(clientId, ServiceId.GFDI, reliable = false)),
        )
    }

    @Test
    fun `parses a register response`() {
        val wire = Hex.decode("00" + "01" + "0100000000000000" + "0100" + "00" + "2e")
        val resp = assertNotNull(MlControl.parseRegisterResponse(wire.copyOfRange(1, wire.size)))
        assertEquals(1L, resp.clientId)
        assertEquals(ServiceId.GFDI, resp.service)
        assertEquals(0, resp.status)
        assertEquals(0x2e, resp.handle)
    }

    @Test
    fun `full control flow registers GFDI and answers handshake`() {
        val writes = mutableListOf<ByteArray>()
        var ready = false
        val session = GarminSession(clientId, sink = { writes.add(it) })
        session.gfdiReliable = false
        session.registrationDelayMs = 0
        session.autoHandshake = true
        session.onReady = { ready = true }

        session.begin()
        assertEquals("0005" + "0100000000000000" + "0000", Hex.encode(writes.last()))

        // Watch acknowledges close-all.
        session.onNotification(Hex.decode("0006" + "0100000000000000" + "0000" + "01"))
        // We should now have emitted two register requests (GFDI + REGISTRATION).
        assertTrue(
            writes.any { it.contentEquals(MlControl.registerRequest(clientId, ServiceId.GFDI, false)) },
            "GFDI register request missing",
        )
        assertTrue(
            writes.any { it.contentEquals(MlControl.registerRequest(clientId, ServiceId.REGISTRATION, false)) },
            "REGISTRATION register request missing",
        )

        // Watch assigns GFDI handle 0x2e.
        session.onNotification(Hex.decode("00" + "01" + "0100000000000000" + "0100" + "00" + "2e"))
        assertEquals(0x2e, session.gfdiHandle)
        assertTrue(ready, "onReady should have fired")

        // Watch pushes DEVICE_INFORMATION; we must answer on the GFDI handle.
        writes.clear()
        val deviceInfo = Gfdi.build(Gfdi.MessageType.DEVICE_INFORMATION.id, Hex.decode("9600"), seq = 5)
        val encoded = Cobs.encode(deviceInfo)
        val notify = ByteArray(1 + encoded.size)
        notify[0] = 0x2e
        System.arraycopy(encoded, 0, notify, 1, encoded.size)

        session.onNotification(notify)
        assertTrue(writes.isNotEmpty(), "expected a handshake reply")
        assertEquals(0x2e, writes.first()[0].toInt() and 0xFF)
    }

    @Test
    fun `registers GFDI as reliable when requested`() {
        val writes = mutableListOf<ByteArray>()
        val session = GarminSession(clientId, sink = { writes.add(it) })
        session.gfdiReliable = true
        session.registrationDelayMs = 0
        session.begin()

        session.onNotification(Hex.decode("0006" + "0100000000000000" + "0000" + "01"))
        assertTrue(
            writes.any { it.contentEquals(MlControl.registerRequest(clientId, ServiceId.GFDI, reliable = true)) },
            "reliable GFDI register request missing",
        )

        // Watch assigns MLR handle 4 (reliable, ML service).
        session.onNotification(Hex.decode("00" + "01" + "0100000000000000" + "0100" + "00" + "04" + "01" + "01"))
        assertEquals(4, session.gfdiHandle)
    }

    @Test
    fun `phone GDI requests use PROTOBUF_REQUEST`() {
        val writes = mutableListOf<ByteArray>()
        val session = GarminSession(clientId, sink = { writes.add(it) })
        session.onNotification(Hex.decode("00" + "01" + "0100000000000000" + "0100" + "00" + "2e"))

        session.sendProtobuf(byteArrayOf(1, 2, 3))
        val packet = writes.last()
        val decoded = Cobs.decode(packet.copyOfRange(1, packet.size))
        val frame = assertNotNull(Gfdi.parse(decoded))
        assertEquals(Gfdi.MessageType.PROTOBUF_REQUEST.id, frame.type)
    }

    @Test
    fun `chunks large GFDI messages across writes`() {        val writes = mutableListOf<ByteArray>()
        val session = GarminSession(clientId, sink = { writes.add(it) })
        session.maxWriteSize = 20
        session.onNotification(Hex.decode("00" + "01" + "0100000000000000" + "0100" + "00" + "2e"))

        writes.clear()
        val big = Gfdi.build(Gfdi.MessageType.FIT_DATA.id, ByteArray(400) { 0x41 })
        session.sendGfdi(big)

        assertTrue(writes.size > 1, "expected multiple chunks, got ${writes.size}")
        assertTrue(writes.all { it.size <= 20 })
    }
}
