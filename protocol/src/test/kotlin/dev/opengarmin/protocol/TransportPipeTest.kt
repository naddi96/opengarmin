package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransportPipeTest {
    @Test
    fun `acks the last fragment and re-acks duplicates`() {
        val sent = ArrayList<ByteArray>()
        val session = GarminSession(clientId = 1L, sink = { sent.add(it.copyOf()) }).apply {
            maxWriteSize = 64
        }
        var error: String? = null
        var received = 0
        val pipe = TransportPipe(
            session = session,
            serviceId = 0x2018,
            transferHandle = 7,
            expectedSize = 0,
            onData = { received += it.size },
            onComplete = {},
            onError = { error = it },
        )
        pipe.onRegistered(handle = 4)
        sent.clear()

        // Configure response occupies sequence 0.
        pipe.onFrame(Mlr.encode(4, 0, 0, byteArrayOf(0, 0, 0)))
        // Two data fragments.
        pipe.onFrame(Mlr.encode(4, 0, 1, ByteArray(8)))
        pipe.onFrame(Mlr.encode(4, 0, 2, ByteArray(8)))

        assertEquals(16, received)
        assertNull(error)
        // The ack after seq 2 must acknowledge 2 as well (reqNum 3), otherwise
        // the watch resends the final fragment forever.
        assertEquals(3, ackReqNum(sent.last()))

        // A retransmitted fragment is dropped but still acked at the same
        // position, so a lost ack cannot deadlock the sender.
        sent.clear()
        pipe.onFrame(Mlr.encode(4, 0, 2, ByteArray(8)))
        assertEquals(16, received)
        assertEquals(3, ackReqNum(sent.last()))
    }

    private fun ackReqNum(frame: ByteArray): Int {
        val header = Mlr.decodeHeader(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)
        assertEquals(4, header.handle)
        return header.reqNum
    }
}
