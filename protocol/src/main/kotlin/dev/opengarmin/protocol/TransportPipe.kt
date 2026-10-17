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
) {
    private var handle: Int = -1
    private var sendSeq = 0
    private var configDone = false
    private var received = 0L
    private var finished = false

    private val receiver = MlrReceiver { _, data -> onFragment(data) }

    fun start() {
        log("PIPE: registering service=0x%04x".format(serviceId))
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
        payload[1] = 0x00 // direction: read
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
            log("PIPE: configured, receiving data")
            ack()
            return
        }

        if (data.isNotEmpty()) {
            received += data.size
            onData(data)
        }
        ack()
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
}
