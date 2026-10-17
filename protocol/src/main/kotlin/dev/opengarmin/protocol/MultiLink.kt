package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Multi-Link handle-management frames (see PROTOCOL.md §3). */
object MlControl {
    const val CONTROL_HANDLE = 0x00
    const val REGISTER_REQ = 0x00
    const val REGISTER_RESP = 0x01
    const val CLOSE_HANDLE_REQ = 0x02
    const val CLOSE_HANDLE_RESP = 0x03
    const val UNKNOWN_HANDLE = 0x04
    const val CLOSE_ALL_REQ = 0x05
    const val CLOSE_ALL_RESP = 0x06
    const val UNKNOWN_REQ = 0x07
    const val UNKNOWN_RESP = 0x08
    const val PROTOCOL_ERROR = 0xFF

    fun closeAllRequest(clientId: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(CONTROL_HANDLE.toByte())
            .put(CLOSE_ALL_REQ.toByte())
            .putLong(clientId)
            .putShort(0)
            .array()

    fun registerRequest(clientId: Long, service: Int, reliable: Boolean): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put(CONTROL_HANDLE.toByte())
            .put(REGISTER_REQ.toByte())
            .putLong(clientId)
            .putShort(service.toShort())
            .put((if (reliable) 0x02 else 0x00).toByte())
            .array()

    fun closeHandleRequest(clientId: Long, service: Int, handle: Int): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put(CONTROL_HANDLE.toByte())
            .put(CLOSE_HANDLE_REQ.toByte())
            .putLong(clientId)
            .putShort(service.toShort())
            .put(handle.toByte())
            .array()

    data class RegisterResponse(val clientId: Long, val service: Int, val status: Int, val handle: Int)

    /** `data` is the control payload (frame bytes after the leading control-handle byte). */
    fun parseRegisterResponse(data: ByteArray): RegisterResponse? {
        if (data.size < 13) return null
        val clientId = ByteBuffer.wrap(data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val service = Gfdi.le16(data, 9)
        val status = data[11].toInt() and 0xFF
        val handle = data[12].toInt() and 0xFF
        return RegisterResponse(clientId, service, status, handle)
    }
}

/**
 * Drives a full Garmin companion session over a byte sink.
 *
 * Usage from the Android BLE layer:
 *   val session = GarminSession(clientId) { bytes -> gatt.write(bytes) }
 *   session.begin()
 *   // on every characteristic notification:
 *   session.onNotification(frame)
 *
 * All outgoing bytes are handed to [sink]; nothing here touches Android APIs, so
 * the whole flow is unit-testable.
 */
class GarminSession(
    val clientId: Long = 1L,
    private val sink: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
) {
    var maxWriteSize: Int = 20
    var gfdiHandle: Int = -1
        private set
    var registrationHandle: Int = -1
        private set

    var productNumber: Int = 0
        private set
    var firmwareVersion: Int = 0
        private set
    var unitId: Long = 0
        private set
    var supportedServicesMask: Int = 0
        private set

    var autoHandshake: Boolean = true
    var onGfdiMessage: ((Gfdi.Frame) -> Unit)? = null
    var onProtobuf: ((Gfdi.Frame) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    private val cobsBuffer = ArrayList<Byte>(1024)
    private var seqCounter = 0
    private var recovering = false

    fun begin() {
        log("ML: close-all")
        sink(MlControl.closeAllRequest(clientId))
    }

    fun nextSeq(): Int {
        val s = seqCounter
        seqCounter = (seqCounter + 1) and 0x1F
        return s
    }

    fun onNotification(frame: ByteArray) {
        if (frame.isEmpty()) return
        val handle = frame[0].toInt() and 0xFF
        val payload = frame.copyOfRange(1, frame.size)
        if (handle == MlControl.CONTROL_HANDLE) {
            handleControl(payload)
            return
        }
        if (handle == gfdiHandle) {
            for (b in payload) cobsBuffer.add(b)
            drainCobs()
            return
        }
        if (handle == registrationHandle) {
            handleRegistrationReply(payload)
        }
    }

    // REGISTRATION replies are raw (not COBS): one code byte followed by data. See PROTOCOL.md 3.2.
    private fun handleRegistrationReply(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            0x00 -> { // supported protocols bitmask
                var mask = 0
                for (i in 1 until payload.size) mask = mask or ((payload[i].toInt() and 0xFF) shl (8 * (i - 1)))
                supportedServicesMask = mask
                log("REG: supported services mask=0x%06x".format(mask))
            }
            0x03 -> { // product number + firmware version + unit id
                if (payload.size >= 9) {
                    productNumber = Gfdi.le16(payload, 1)
                    firmwareVersion = Gfdi.le16(payload, 3)
                    unitId = ByteBuffer.wrap(payload, 5, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    log("REG: product=$productNumber firmware=$firmwareVersion unitId=$unitId")
                }
            }
        }
    }

    private fun handleControl(data: ByteArray) {
        if (data.size < 10) return
        val type = data[0].toInt() and 0xFF
        val cid = ByteBuffer.wrap(data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (cid != clientId) return

        when (type) {
            MlControl.REGISTER_RESP -> {
                val resp = MlControl.parseRegisterResponse(data) ?: return
                if (resp.status != 0x00) {
                    log("ML: register service=0x%04x failed status=%d".format(resp.service, resp.status))
                    if (resp.status == 0x03 && !recovering) { // ALREADY_IN_USE
                        recovering = true
                        sink(MlControl.closeAllRequest(clientId))
                    }
                    return
                }
                if (resp.service == ServiceId.GFDI) recovering = false
                when (resp.service) {
                    ServiceId.GFDI -> {
                        gfdiHandle = resp.handle
                        log("ML: GFDI on handle ${resp.handle}")
                        onReady?.invoke()
                    }
                    ServiceId.REGISTRATION -> {
                        registrationHandle = resp.handle
                        log("ML: REGISTRATION on handle ${resp.handle}")
                        queryRegistration()
                    }
                }
            }
            MlControl.CLOSE_ALL_RESP -> {
                log("ML: close-all done, registering GFDI + REGISTRATION")
                recovering = false
                gfdiHandle = -1
                registrationHandle = -1
                sink(MlControl.registerRequest(clientId, ServiceId.GFDI, reliable = false))
                sink(MlControl.registerRequest(clientId, ServiceId.REGISTRATION, reliable = false))
            }
            MlControl.CLOSE_HANDLE_RESP -> {
                if (data.size >= 13) {
                    val service = Gfdi.le16(data, 9)
                    val handle = data[11].toInt() and 0xFF
                    log("ML: handle closed service=0x%04x handle=%d".format(service, handle))
                    if (service == ServiceId.GFDI && handle == gfdiHandle && !recovering) {
                        recovering = true
                        log("ML: GFDI closed by device; cycling close-all")
                        gfdiHandle = -1
                        sink(MlControl.closeAllRequest(clientId))
                    }
                } else {
                    log("ML: handle closed")
                }
            }
        }
    }

    private fun queryRegistration() {
        if (registrationHandle < 0) return
        sendOnHandle(registrationHandle, Handshake.registrationQuery(0x00)) // supported protocols
        sendOnHandle(registrationHandle, Handshake.registrationQuery(0x02)) // multilink version
        sendOnHandle(registrationHandle, Handshake.registrationQuery(0x03)) // product/firmware/unit id
    }

    /** Encodes and chunks a GFDI message onto the GFDI handle. */
    fun sendGfdi(message: ByteArray) {
        if (gfdiHandle < 0) {
            log("GFDI send dropped: not registered")
            return
        }
        val encoded = Cobs.encode(message)
        val perChunk = (maxWriteSize - 1).coerceAtLeast(19)
        var pos = 0
        while (pos < encoded.size) {
            val end = minOf(pos + perChunk, encoded.size)
            val packet = ByteArray(1 + (end - pos))
            packet[0] = gfdiHandle.toByte()
            System.arraycopy(encoded, pos, packet, 1, end - pos)
            sink(packet)
            pos = end
        }
    }

    /** Sends a GDI protobuf payload. The GDI data channel is GFDI message type 5044. */
    fun sendProtobuf(payload: ByteArray, seq: Int? = nextSeq()) {
        sendGfdi(Gfdi.build(Gfdi.MessageType.PROTOBUF_RESPONSE.id, payload, seq))
    }

    /** Sends a raw payload on an arbitrary registered handle (no COBS). */
    fun sendOnHandle(handle: Int, payload: ByteArray) {
        val packet = ByteArray(1 + payload.size)
        packet[0] = handle.toByte()
        System.arraycopy(payload, 0, packet, 1, payload.size)
        sink(packet)
    }

    private fun drainCobs() {
        while (true) {
            val end = indexOfZeroFrom(1)
            if (end < 0) return
            val frame = ByteArray(end + 1) { cobsBuffer[it] }
            repeat(end + 1) { cobsBuffer.removeAt(0) }
            val decoded = Cobs.decode(frame)
            val parsed = Gfdi.parse(decoded) ?: continue
            dispatch(parsed)
        }
    }

    private fun indexOfZeroFrom(start: Int): Int {
        for (i in start until cobsBuffer.size) if (cobsBuffer[i] == 0x00.toByte()) return i
        return -1
    }

    private fun dispatch(frame: Gfdi.Frame) {
        onGfdiMessage?.invoke(frame)
        if (frame.type == Gfdi.MessageType.PROTOBUF_REQUEST.id ||
            frame.type == Gfdi.MessageType.PROTOBUF_RESPONSE.id
        ) {
            onProtobuf?.invoke(frame)
        }
        if (autoHandshake) respondToHandshake(frame)
    }

    private fun respondToHandshake(frame: Gfdi.Frame) {
        val reply: ByteArray? = when (frame.type) {
            Gfdi.MessageType.DEVICE_INFORMATION.id -> Handshake.deviceInformation(frame.payload, frame.seq)
            Gfdi.MessageType.CONFIGURATION.id -> Handshake.configuration(frame.payload, frame.seq)
            Gfdi.MessageType.AUTH_NEGOTIATION.id -> Handshake.authNegotiation(frame.payload, frame.seq)
            Gfdi.MessageType.CURRENT_TIME_REQUEST.id ->
                Handshake.currentTimeRequest(frame.payload, System.currentTimeMillis() / 1000, 0, frame.seq)
            Gfdi.MessageType.NOTIFICATION_SUBSCRIPTION.id -> Handshake.notificationSubscription(frame.payload, frame.seq)
            // 5043 is a protobuf cancellation/unsupported notice from the device; it needs no reply.
            else -> null
        }
        if (reply != null) sendGfdi(reply)
    }
}
