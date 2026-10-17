package dev.opengarmin.protocol

/**
 * Multi-Link Reliable (MLR) framing.
 *
 * Header layout (2 bytes):
 *
 *   1 H H H R R R R   R R S S S S S S
 *   bit7 = 1 (marks MLR)
 *   H    = handle (0..7)
 *   R    = request/ack number (6 bits, wraps at 0x40)
 *   S    = sequence number (6 bits, wraps at 0x40)
 *
 * The request number acknowledges every received sequence `< reqNum`; the
 * sequence number identifies this fragment. Data follows the header.
 *
 * A complete message is split into fragments of at most `maxPacketSize - 2`
 * bytes and reassembled by the receiving layer.
 */
object Mlr {
    const val MOD = 0x40

    data class Header(val handle: Int, val reqNum: Int, val seqNum: Int)

    fun isMlr(firstByte: Int): Boolean = (firstByte and 0x80) != 0

    fun decodeHeader(b0: Int, b1: Int): Header {
        val handle = (b0 ushr 4) and 0x07
        val reqNum = ((b0 and 0x0F) shl 2) or ((b1 ushr 6) and 0x03)
        val seqNum = b1 and 0x3F
        return Header(handle, reqNum and 0x3F, seqNum)
    }

    fun encodeHeader(handle: Int, reqNum: Int, seqNum: Int): ByteArray {
        require(handle in 0..7) { "handle out of range" }
        require(reqNum in 0..0x3F) { "reqNum out of range" }
        require(seqNum in 0..0x3F) { "seqNum out of range" }
        val b0 = 0x80 or (handle shl 4) or ((reqNum ushr 2) and 0x0F)
        val b1 = ((reqNum and 0x03) shl 6) or (seqNum and 0x3F)
        return byteArrayOf(b0.toByte(), b1.toByte())
    }

    fun encode(handle: Int, reqNum: Int, seqNum: Int, payload: ByteArray): ByteArray =
        encodeHeader(handle, reqNum, seqNum) + payload

    /** Splits a payload into MLR fragments (header + at most maxPacketSize-2 bytes). */
    fun fragment(
        handle: Int,
        startSeq: Int,
        reqNumForAck: Int,
        payload: ByteArray,
        maxPacketSize: Int,
    ): List<Pair<Int, ByteArray>> {
        val fragmentSize = (maxPacketSize - 2).coerceAtLeast(1)
        val out = ArrayList<Pair<Int, ByteArray>>()
        var offset = 0
        var seq = startSeq and 0x3F
        if (payload.isEmpty()) {
            out.add(seq to encode(handle, reqNumForAck, seq, ByteArray(0)))
            return out
        }
        while (offset < payload.size) {
            val end = minOf(offset + fragmentSize, payload.size)
            out.add(seq to encode(handle, reqNumForAck, seq, payload.copyOfRange(offset, end)))
            seq = (seq + 1) and 0x3F
            offset = end
        }
        return out
    }

    fun distance(from: Int, to: Int): Int = (to - from + MOD) % MOD
}

/**
 * Minimal in-order MLR receive state: delivers fragments in sequence, drops
 * out-of-order ones (the sender retransmits), and tracks the next ack number.
 */
class MlrReceiver(private val onFragment: (Header: Mlr.Header, data: ByteArray) -> Unit) {
    var nextRcvSeq: Int = 0
        private set
    var lastAckedSeq: Int = 0
        private set

    fun onFrame(frame: ByteArray): Boolean {
        if (frame.size < 2) return false
        val header = Mlr.decodeHeader(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)

        // Any received reqNum acknowledges our outstanding send sequences.
        lastAckedSeq = header.reqNum

        if (header.seqNum != nextRcvSeq) return false
        onFragment(header, frame.copyOfRange(2, frame.size))
        nextRcvSeq = (nextRcvSeq + 1) and 0x3F
        return true
    }

    /** A zero-length frame that acknowledges everything received so far. */
    fun ackFrame(handle: Int): ByteArray =
        Mlr.encode(handle, nextRcvSeq, 0, ByteArray(0))
}
