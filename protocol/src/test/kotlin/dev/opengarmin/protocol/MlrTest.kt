package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MlrTest {
    @Test
    fun `header round-trips`() {
        for (handle in 0..7) {
            for (req in 0..0x3F step 7) {
                for (seq in 0..0x3F step 5) {
                    val h = Mlr.encodeHeader(handle, req, seq)
                    val d = Mlr.decodeHeader(h[0].toInt() and 0xFF, h[1].toInt() and 0xFF)
                    assertEquals(Mlr.Header(handle, req, seq), d)
                }
            }
        }
    }

    @Test
    fun `known bit layout`() {
        // handle=4, reqNum=0x2A (101010), seqNum=0x15 (010101)
        // b0 = 1 100 1010 = 0xCA, b1 = 10 010101 = 0x95
        val h = Mlr.encodeHeader(4, 0x2A, 0x15)
        assertEquals("ca95", Hex.encode(h))
        assertEquals(Mlr.Header(4, 0x2A, 0x15), Mlr.decodeHeader(0xCA, 0x95))
    }

    @Test
    fun `mlr marker detection`() {
        assertTrue(Mlr.isMlr(0x80))
        assertTrue(Mlr.isMlr(0xCA))
        assertFalse(Mlr.isMlr(0x04))
        assertFalse(Mlr.isMlr(0x7F))
    }

    @Test
    fun `fragments carry incrementing sequence numbers`() {
        val payload = ByteArray(300) { it.toByte() }
        val frags = Mlr.fragment(handle = 4, startSeq = 0, reqNumForAck = 3, payload = payload, maxPacketSize = 20)
        assertEquals(17, frags.size) // 300 / 18 = 16.67 -> 17

        var seq = 0
        val rebuilt = ByteArray(300)
        var offset = 0
        for ((s, frame) in frags) {
            assertEquals(seq, s)
            val d = Mlr.decodeHeader(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)
            assertEquals(4, d.handle)
            assertEquals(3, d.reqNum)
            assertEquals(seq, d.seqNum)
            val chunk = frame.copyOfRange(2, frame.size)
            System.arraycopy(chunk, 0, rebuilt, offset, chunk.size)
            offset += chunk.size
            seq = (seq + 1) and 0x3F
        }
        assertTrue(rebuilt.contentEquals(payload))
    }

    @Test
    fun `receiver delivers in order and acks`() {
        val received = ArrayList<ByteArray>()
        val rx = MlrReceiver { _, data -> received.add(data) }

        // seq 0 delivered
        assertTrue(rx.onFrame(Mlr.encode(4, 0, 0, byteArrayOf(1))))
        // seq 2 arrives early -> dropped
        assertFalse(rx.onFrame(Mlr.encode(4, 1, 2, byteArrayOf(3))))
        // seq 1 delivered
        assertTrue(rx.onFrame(Mlr.encode(4, 1, 1, byteArrayOf(2))))
        assertEquals(2, received.size)
        assertTrue(received[0].contentEquals(byteArrayOf(1)))
        assertTrue(received[1].contentEquals(byteArrayOf(2)))

        // ack frame acknowledges up to seq 2
        val ack = rx.ackFrame(4)
        val h = Mlr.decodeHeader(ack[0].toInt() and 0xFF, ack[1].toInt() and 0xFF)
        assertEquals(2, h.reqNum)
    }

    @Test
    fun `sequence numbers wrap at 0x40`() {
        val received = ArrayList<Int>()
        val rx = MlrReceiver { h, _ -> received.add(h.seqNum) }
        for (seq in 0 until 0x3E) rx.onFrame(Mlr.encode(1, seq, seq, byteArrayOf(0)))
        assertEquals(0x3E, received.size)
        assertEquals(0x3E, rx.nextRcvSeq)
        assertTrue(rx.onFrame(Mlr.encode(1, 0x3E, 0x3E, byteArrayOf(0))))
        assertTrue(rx.onFrame(Mlr.encode(1, 0x3F, 0x3F, byteArrayOf(0))))
        assertEquals(0, rx.nextRcvSeq)
    }
}
