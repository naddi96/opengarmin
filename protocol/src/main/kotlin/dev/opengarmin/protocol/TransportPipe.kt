package dev.opengarmin.protocol

/**
 * Garmin "MultiLink Transport Pipe" client.
 *
 * After a successful `PullItemDataResponse` the device exposes the file bytes on
 * a dedicated Multi-Link **reliable** service (ids 0x2018, 0x4018, ...).
 *
 * Wire protocol (all multi-byte values little-endian):
 *
 *   configure request   : [0x00][direction: 0=read, 1=write][transferHandle: u32]
 *   configure response  : [0x00][generalStatus][configureStatus]
 *   data                : raw file bytes, delivered as MLR fragments
 *
 * A configure response has generalStatus 0 for OK; configureStatus is
 * 0=OK, 1=unknown handle, 2=unexpected direction, 3=unexpected size.
 */
class TransportPipe(
    private val session: GarminSession,
    val serviceId: Int,
    val transferHandle: Int,
    val expectedSize: Long,
    private val onData: (ByteArray) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit,
    private val log: (String) -> Unit = {},
    /** When non-null the pipe writes this payload instead of reading. */
    private val writePayload: ByteArray? = null,
    /** Device compression window for writes (0 = send raw bytes). */
    private val writeCompressionWindow: Int = 0,
) {
    private var handle: Int = -1
    private var sendSeq = 0
    private var configDone = false
    private var received = 0L
    private var finished = false

    private val receiver = MlrReceiver { _, data -> onFragment(data) }

    fun start() {
        log(
            "PIPE: registering service=0x%04x (%s)".format(
                serviceId,
                if (writePayload != null) "write" else "read",
            ),
        )
        session.registerService(serviceId, reliable = true)
    }

    /** Called when the session reports the assigned MLR handle for [serviceId]. */
    fun onRegistered(handle: Int) {
        if (this.handle >= 0) return
        this.handle = handle
        log("PIPE: handle=$handle, configuring transfer=$transferHandle")
        configure()
    }

    private fun configure() {
        val payload = ByteArray(6)
        payload[0] = 0x00 // configure command
        payload[1] = if (writePayload != null) 0x01 else 0x00 // direction: write/read
        for (i in 0 until 4) payload[2 + i] = ((transferHandle ushr (8 * i)) and 0xFF).toByte()
        send(payload)
    }

    private fun send(payload: ByteArray) {
        if (handle < 0) return
        // MLR fragments may use the full negotiated MTU.
        for ((seq, frame) in Mlr.fragment(handle, sendSeq, receiver.nextRcvSeq, payload, session.maxWriteSize)) {
            session.sendMlrFrame(frame)
            sendSeq = (seq + 1) and 0x3F
        }
    }

    /** Feed a raw MLR frame from the BLE layer. */
    fun onFrame(frame: ByteArray) {
        if (frame.size < 2 || !Mlr.isMlr(frame[0].toInt() and 0xFF)) return
        val header = Mlr.decodeHeader(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)
        if (handle >= 0 && header.handle != handle) return
        receiver.onFrame(frame)
    }

    private fun onFragment(data: ByteArray) {
        if (!configDone) {
            configDone = true
            if (data.size < 3) {
                onError("short configure response: ${Hex.encode(data)}")
                return
            }
            val general = data[1].toInt() and 0xFF
            val config = data[2].toInt() and 0xFF
            if (general != 0 || config != 0) {
                onError("configure failed general=$general config=$config")
                return
            }
            val payload = writePayload
            if (payload != null) {
                write(payload)
            } else {
                log("PIPE: configured, receiving data")
                ack()
            }
            return
        }

        if (data.isNotEmpty()) {
            received += data.size
            onData(data)
        }
        ack()
    }

    private fun write(payload: ByteArray) {
        if (finished || handle < 0) return
        val bytes = if (writeCompressionWindow > 0) deflate(payload) else payload
        log(
            "PIPE: writing ${bytes.size} bytes (${payload.size} raw, window=$writeCompressionWindow)",
        )
        send(bytes)
        // Queue the close after the data; the BLE write queue preserves order.
        session.closeService(serviceId, handle)
        finished = true
        log("PIPE: write queued, closing service 0x%04x".format(serviceId))
        onComplete()
    }

    /** Called when the device closes the transport pipe service (end of transfer). */
    fun finish() {
        if (finished) return
        finished = true
        log("PIPE: complete ($received bytes)")
        onComplete()
    }

    private fun ack() {
        if (finished || handle < 0) return
        session.sendMlrFrame(receiver.ackFrame(handle))
    }

    private fun deflate(data: ByteArray): ByteArray = zlibStored(data, writeCompressionWindow)

    companion object {
        /**
         * Wraps [data] in a valid zlib stream made of stored (uncompressed)
         * deflate blocks. The official client uses a deflater with the window
         * bits negotiated by the watch; Java's Deflater is fixed at 15 bits and
         * a mismatched CINFO makes the watch's inflater reject the stream.
         * Stored blocks are valid for every window size and the files are tiny.
         */
        internal fun zlibStored(data: ByteArray, windowBits: Int): ByteArray {
            val wb = windowBits.coerceIn(9, 15)
            val out = java.io.ByteArrayOutputStream(data.size + 16)
            val cmf = ((wb - 8) shl 4) or 8
            val flg = (31 - ((cmf shl 8) % 31)) % 31
            out.write(cmf)
            out.write(flg)
            var pos = 0
            do {
                val length = minOf(65535, data.size - pos)
                val last = pos + length >= data.size
                out.write(if (last) 1 else 0)
                out.write(length and 0xFF)
                out.write((length ushr 8) and 0xFF)
                val nlen = length.inv() and 0xFFFF
                out.write(nlen and 0xFF)
                out.write((nlen ushr 8) and 0xFF)
                if (length > 0) out.write(data, pos, length)
                pos += length
            } while (pos < data.size)

            var a = 1
            var b = 0
            for (byte in data) {
                a = (a + (byte.toInt() and 0xFF)) % 65521
                b = (b + a) % 65521
            }
            val adler = (b shl 16) or a
            out.write((adler ushr 24) and 0xFF)
            out.write((adler ushr 16) and 0xFF)
            out.write((adler ushr 8) and 0xFF)
            out.write(adler and 0xFF)
            return out.toByteArray()
        }
    }
}
