package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GFDI session handshake.
 *
 * After GFDI is registered the watch pushes a small sequence of messages; the
 * phone must answer each one for the watch to treat it as a valid companion.
 * Responses echo the request's sequence number when present.
 */
object Handshake {

    /** Reply to DEVICE_INFORMATION (5024). */
    fun deviceInformation(
        payload: ByteArray,
        seq: Int? = null,
        hostName: String = "opengarmin",
        platform: String = "Android",
    ): ByteArray {
        val protocolVersion = if (payload.size >= 2) Gfdi.le16(payload, 0) else 150
        val protocolFlags = if (protocolVersion / 100 == 1) 1 else 0

        val body = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(protocolVersion.toShort())
        body.putShort(0xFFFF.toShort())
        body.putInt(0xFFFFFFFF.toInt())
        body.putShort(7791.toShort())
        body.putShort(0xFFFF.toShort())
        putString(body, hostName)
        putString(body, platform)
        putString(body, hostName)
        body.put(protocolFlags.toByte())

        return Gfdi.buildResponse(Gfdi.MessageType.DEVICE_INFORMATION.id, Gfdi.Status.ACK.code, body.trimmed(), seq)
    }

    /** Reply to CONFIGURATION (5050) — echo capability bytes back. */
    fun configuration(payload: ByteArray, seq: Int? = null): ByteArray? {
        if (payload.isEmpty()) return null
        val numBytes = payload[0].toInt() and 0xFF
        val caps = payload.copyOfRange(1, minOf(1 + numBytes, payload.size))
        return Gfdi.build(Gfdi.MessageType.CONFIGURATION.id, ByteArray(1) { numBytes.toByte() } + caps, seq)
    }

    /** Reply to AUTH_NEGOTIATION (5101). */
    fun authNegotiation(payload: ByteArray, seq: Int? = null): ByteArray =
        Gfdi.buildResponse(Gfdi.MessageType.AUTH_NEGOTIATION.id, Gfdi.Status.ACK.code, byteArrayOf(0, 0, 0, 0, 0), seq)

    /**
     * Reply to CURRENT_TIME_REQUEST (5052).
     *
     * The official client answers with the request reference id, the current
     * time in Garmin epoch seconds, the UTC offset in seconds, and the next two
     * daylight-saving transitions (also in Garmin epoch). The watch uses this
     * exchange to align its clock; sending zero for the DST fields can make it
     * ignore the update.
     */
    fun currentTimeRequest(payload: ByteArray, nowUnixSeconds: Long, localOffsetSeconds: Int, seq: Int? = null): ByteArray {
        val referenceId = if (payload.size >= 4) Gfdi.le16(payload, 0) or (Gfdi.le16(payload, 2) shl 16) else 0
        val garminTs = Gfdi.garminTimestamp(nowUnixSeconds)
        val (dstStart, dstEnd) = dstTransitions(nowUnixSeconds)
        val body = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(referenceId)
            .putInt(garminTs.toInt())
            .putInt(localOffsetSeconds)
            .putInt(dstStart.toInt())
            .putInt(dstEnd.toInt())
            .array()
        return Gfdi.buildResponse(Gfdi.MessageType.CURRENT_TIME_REQUEST.id, Gfdi.Status.ACK.code, body, seq)
    }

    /** Next DST start/end as Garmin epoch seconds, or (0, 0) if the zone has none. */
    private fun dstTransitions(nowUnixSeconds: Long): Pair<Long, Long> {
        val rules = java.time.ZoneId.systemDefault().rules
        val now = java.time.Instant.ofEpochSecond(nowUnixSeconds)
        val next = rules.nextTransition(now) ?: return 0L to 0L
        val after = rules.nextTransition(next.instant.plusSeconds(86_400)) ?: return 0L to 0L
        fun garmin(instant: java.time.Instant): Long =
            (instant.epochSecond - Gfdi.GARMIN_EPOCH_UNIX).coerceAtLeast(0)
        return if (rules.isDaylightSavings(next.instant)) {
            garmin(next.instant) to garmin(after.instant)
        } else {
            garmin(after.instant) to garmin(next.instant)
        }
    }

    /** Reply to NOTIFICATION_SUBSCRIPTION (5036). */
    fun notificationSubscription(payload: ByteArray, seq: Int? = null): ByteArray {
        val enable = if (payload.isNotEmpty()) payload[0] else 0
        val unknown = if (payload.size >= 2) payload[1] else 0
        return Gfdi.buildResponse(
            Gfdi.MessageType.NOTIFICATION_SUBSCRIPTION.id,
            Gfdi.Status.ACK.code,
            byteArrayOf(0, enable, unknown),
            seq,
        )
    }

    /** Reply to PROTOBUF_REQUEST (5043) until protobuf services are implemented. */
    fun protobufRequest(seq: Int? = null): ByteArray =
        Gfdi.buildResponse(Gfdi.MessageType.PROTOBUF_REQUEST.id, Gfdi.Status.UNSUPPORTED.code, seq = seq)

    /** DEVICE_SETTINGS (5026): enable auto-upload + weather. */
    fun deviceSettings(): ByteArray =
        Gfdi.build(Gfdi.MessageType.DEVICE_SETTINGS.id, byteArrayOf(3, 6, 1, 1, 7, 1, 1, 8, 1, 1))

    /**
     * SYSTEM_EVENT (5030) with the SYNC_READY event (8) and its value byte.
     * Garmin Connect's `SystemEventMessage` always writes the value byte.
     */
    fun systemEventSyncReady(): ByteArray =
        Gfdi.build(Gfdi.MessageType.SYSTEM_EVENT.id, byteArrayOf(8, 0))

    /** SET_FILE_FLAGS (5008): mark a file ARCHIVE (0x10). */
    fun setFileFlags(fileIndex: Int, flags: Int, seq: Int? = null): ByteArray =
        Gfdi.build(
            Gfdi.MessageType.SET_FILE_FLAGS.id,
            byteArrayOf((fileIndex and 0xFF).toByte(), ((fileIndex ushr 8) and 0xFF).toByte(), flags.toByte()),
            seq,
        )

    /** DIRECTORY_FILTER (5007). filter: 0 = none, 1 = default, 3 = pending only. */
    fun directoryFilter(filter: Int, seq: Int? = null): ByteArray =
        Gfdi.build(Gfdi.MessageType.DIRECTORY_FILTER.id, byteArrayOf(filter.toByte()), seq)

    /** Registration-service request byte (see PROTOCOL.md §3.2). */
    fun registrationQuery(code: Int): ByteArray = byteArrayOf(code.toByte())

    private fun putString(buf: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buf.put(bytes.size.toByte())
        buf.put(bytes)
    }

    private fun ByteBuffer.trimmed(): ByteArray {
        val out = ByteArray(position())
        val dup = duplicate()
        dup.flip()
        dup.get(out)
        return out
    }
}
