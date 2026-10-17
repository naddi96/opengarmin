package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GDI protobuf transport.
 *
 * Wire layout of a GDI frame (little-endian):
 *   [id: u16][offset: u32][totalLength: u32][chunkLength: u32][chunk bytes]
 *
 * Frames are carried inside GFDI PROTOBUF_REQUEST (5043) / PROTOBUF_RESPONSE
 * (5044) messages. The reassembled payload is a `GDI.Proto.Smart.Smart`
 * message, whose extension fields select a service (FileAccess = 43,
 * DataTransfer = 7, Core = 13, ...).
 */
object GdiFrame {
    const val HEADER_SIZE = 14

    data class Frame(val id: Int, val offset: Int, val totalLength: Int, val payload: ByteArray)

    fun build(id: Int, offset: Int, totalLength: Int, chunk: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + chunk.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(id.toShort())
        buf.putInt(offset)
        buf.putInt(totalLength)
        buf.putInt(chunk.size)
        buf.put(chunk)
        return buf.array()
    }

    fun parse(data: ByteArray): Frame? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val id = buf.short.toInt() and 0xFFFF
        val offset = buf.int
        val total = buf.int
        val chunkLen = buf.int
        if (chunkLen < 0 || HEADER_SIZE + chunkLen > data.size) return null
        if (total < 0 || offset < 0) return null
        return Frame(id, offset, total, data.copyOfRange(HEADER_SIZE, HEADER_SIZE + chunkLen))
    }

    fun chunks(id: Int, payload: ByteArray, chunkSize: Int = 487): List<ByteArray> {
        if (payload.isEmpty()) return listOf(build(id, 0, 0, ByteArray(0)))
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            out.add(build(id, offset, payload.size, payload.copyOfRange(offset, end)))
            offset = end
        }
        return out
    }
}

/** `GDI.Proto.Smart.Smart` helpers and the FileAccess service messages. */
object Gdi {
    const val SMART_FILE_ACCESS = 43
    const val SMART_DATA_TRANSFER = 7
    const val SMART_CORE = 13

    /** Wraps a service payload into a Smart message extension field. */
    fun wrapSmart(serviceField: Int, servicePayload: ByteArray): ByteArray =
        ProtoWriter().bytes(serviceField, servicePayload).toByteArray()

    /** Returns the first occurrence of each top-level extension field. */
    fun smartFields(smart: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        val r = ProtoReader(smart)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (wire == 2) out.putIfAbsent(field, r.readBytes()) else r.skip(wire)
        }
        return out
    }

    data class Uuid(val most: Long, val least: Long) {
        fun hex(): String = "%016x%016x".format(most, least)
    }

    data class DataTypeInfo(
        val format: Int,
        val gdxmlString: String?,
        val gdxmlStringKey: Int,
        val gdxmlFileIdentifier: String?,
        val softwareUpdatePath: String?,
        val specialCase: Int,
        val updatePartNumber: String?,
    )

    data class ItemReference(
        val uid: Uuid?,
        val dataType: DataTypeInfo?,
        val dataSize: Long,
        val requestedFlags: Int,
        val transactionId: Int,
        val urgency: Int,
    )

    data class ItemListResponse(
        val status: Int,
        val sessionId: Int,
        val nextTransactionId: Int,
        val items: List<ItemReference>,
        val errorCode: Int,
        val abandonedSessionId: Int,
        val malformedDataType: DataTypeInfo?,
    )

    data class PullItemResponse(
        val result: Int,
        val transport: Int,
        val transferHandle: Int,
        val filePath: String?,
        val errorCode: Int,
        val compressionWindow: Int,
    )

    /** FileAccess.Service wrapper returned to callers. */
    data class FileAccessService(
        val itemListResponse: ItemListResponse? = null,
        val pullItemResponse: PullItemResponse? = null,
    )

    /**
     * Core.FeatureCapabilitiesRequest, with the FileAccess extension (16) requested.
     * Smart.core_service = 13, CoreService.feature_capabilities_request = 8.
     */
    fun featureCapabilitiesRequest(clientVersion: Int = 0): ByteArray {
        val request = ProtoWriter()
        if (clientVersion != 0) request.uint32(2, clientVersion)
        val core = ProtoWriter().bytes(8, request.toByteArray()).toByteArray()
        return ProtoWriter().bytes(SMART_CORE, core).toByteArray()
    }

    /** Returns the CoreService.feature_capabilities_response payload if present. */
    fun coreFeatureCapabilitiesResponse(smart: ByteArray): ByteArray? {
        val core = smartFields(smart)[SMART_CORE] ?: return null
        val r = ProtoReader(core)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 9 && wire == 2) return r.readBytes()
            r.skip(wire)
        }
        return null
    }

    fun itemListRequest(
        sessionId: Int = 0,
        transactionId: Int = 0,
        maxItemCount: Int = 0,
        includedDataTypes: List<DataTypeInfo> = emptyList(),
    ): ByteArray {
        val req = ProtoWriter()
            .uint32(1, sessionId)
            .uint32(2, transactionId)
            .uint32(3, maxItemCount)
        for (dt in includedDataTypes) req.bytes(6, encodeDataType(dt))
        // FileAccess.Service.item_list_request = 9
        return ProtoWriter().bytes(9, req.toByteArray()).toByteArray()
    }

    fun encodeItemReference(item: ItemReference): ByteArray {
        val w = ProtoWriter()
        item.uid?.let { w.bytes(1, encodeUuid(it)) }
        item.dataType?.let { w.bytes(2, encodeDataType(it)) }
        w.uint64(3, item.dataSize)
        w.uint32(4, item.requestedFlags)
        w.uint32(5, item.transactionId)
        w.uint32(6, item.urgency)
        return w.toByteArray()
    }

    fun pullItemRequest(
        item: ItemReference,
        priority: Int = 30,
        offset: Long = 0,
        compressionWindow: Int = 15,
        transports: List<Int> = listOf(0), // MULTILINK_TRANSPORT_PIPE
    ): ByteArray {
        val body = ProtoWriter()
            .bytes(1, encodeItemReference(item))
            .uint32(2, priority)
        for (t in transports) body.enumAlways(3, t)
        body.uint64(4, offset)
        body.uint32(5, compressionWindow)
        // FileAccess.Service.pull_item_request = 1
        return ProtoWriter().bytes(1, body.toByteArray()).toByteArray()
    }

    fun modifyFlagsRequest(uid: Uuid, setFlags: List<Uuid> = emptyList(), clearFlags: List<Uuid> = emptyList()): ByteArray {
        val body = ProtoWriter().bytes(1, encodeUuid(uid))
        for (f in setFlags) body.bytes(2, encodeUuid(f))
        for (f in clearFlags) body.bytes(3, encodeUuid(f))
        // Service.modify_flags_request = 15
        return ProtoWriter().bytes(15, body.toByteArray()).toByteArray()
    }

    fun encodeUuid(uuid: Uuid): ByteArray =
        ProtoWriter().fixed64(1, uuid.most).fixed64(2, uuid.least).toByteArray()

    fun encodeDataType(dt: DataTypeInfo): ByteArray {
        val w = ProtoWriter().uint32(1, dt.format)
        dt.gdxmlString?.let { w.string(2, it) }
        w.uint32(3, dt.gdxmlStringKey)
        dt.gdxmlFileIdentifier?.let { w.string(4, it) }
        dt.softwareUpdatePath?.let { w.string(5, it) }
        w.uint32(6, dt.specialCase)
        dt.updatePartNumber?.let { w.string(7, it) }
        return w.toByteArray()
    }

    fun parseFileAccessService(data: ByteArray): FileAccessService {
        var list: ItemListResponse? = null
        var pull: PullItemResponse? = null
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (wire != 2) {
                r.skip(wire)
                continue
            }
            when (field) {
                2 -> pull = parsePullItemResponse(r.readBytes())
                10 -> list = parseItemListResponse(r.readBytes())
                else -> r.skip(2)
            }
        }
        return FileAccessService(list, pull)
    }

    private fun parseUuid(data: ByteArray): Uuid {
        var most = 0L
        var least = 0L
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 1 -> most = r.readFixed64()
                field == 2 && wire == 1 -> least = r.readFixed64()
                else -> r.skip(wire)
            }
        }
        return Uuid(most, least)
    }

    private fun parseDataType(data: ByteArray): DataTypeInfo {
        var format = 0
        var gdxmlString: String? = null
        var key = 0
        var identifier: String? = null
        var softwarePath: String? = null
        var special = 0
        var partNumber: String? = null
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> format = r.readVarint().toInt()
                2 -> gdxmlString = String(r.readBytes(), Charsets.UTF_8)
                3 -> key = r.readVarint().toInt()
                4 -> identifier = String(r.readBytes(), Charsets.UTF_8)
                5 -> softwarePath = String(r.readBytes(), Charsets.UTF_8)
                6 -> special = r.readVarint().toInt()
                7 -> partNumber = String(r.readBytes(), Charsets.UTF_8)
                else -> r.skip(wire)
            }
        }
        return DataTypeInfo(format, gdxmlString, key, identifier, softwarePath, special, partNumber)
    }

    private fun parseItemReference(data: ByteArray): ItemReference {
        var uid: Uuid? = null
        var dataType: DataTypeInfo? = null
        var size = 0L
        var flags = 0
        var tx = 0
        var urgency = 0
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> uid = parseUuid(r.readBytes())
                2 -> dataType = parseDataType(r.readBytes())
                3 -> size = r.readVarint()
                4 -> flags = r.readVarint().toInt()
                5 -> tx = r.readVarint().toInt()
                6 -> urgency = r.readVarint().toInt()
                else -> r.skip(wire)
            }
        }
        return ItemReference(uid, dataType, size, flags, tx, urgency)
    }

    private fun parseItemListResponse(data: ByteArray): ItemListResponse {
        var status = 0
        var sessionId = 0
        var nextTx = 0
        val items = ArrayList<ItemReference>()
        var errorCode = 0
        var abandoned = 0
        var malformed: DataTypeInfo? = null
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> status = r.readVarint().toInt()
                2 -> sessionId = r.readVarint().toInt()
                3 -> nextTx = r.readVarint().toInt()
                4 -> items.add(parseItemReference(r.readBytes()))
                5 -> errorCode = r.readVarint().toInt()
                6 -> abandoned = r.readVarint().toInt()
                7 -> malformed = parseDataType(r.readBytes())
                else -> r.skip(wire)
            }
        }
        return ItemListResponse(status, sessionId, nextTx, items, errorCode, abandoned, malformed)
    }

    private fun parsePullItemResponse(data: ByteArray): PullItemResponse {
        var result = 0
        var transport = 0
        var handle = 0
        var path: String? = null
        var errorCode = 0
        var window = 0
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> result = r.readVarint().toInt()
                2 -> transport = r.readVarint().toInt()
                3 -> handle = r.readVarint().toInt()
                4 -> path = String(r.readBytes(), Charsets.UTF_8)
                5 -> errorCode = r.readVarint().toInt()
                7 -> window = r.readVarint().toInt()
                else -> r.skip(wire)
            }
        }
        return PullItemResponse(result, transport, handle, path, errorCode, window)
    }
}

/** Sends GDI requests over a [GarminSession] and reassembles responses. */
class GdiClient(
    private val session: GarminSession,
    private val log: (String) -> Unit = {},
) {
    private var nextId = 0x0800
    private val pending = HashMap<Int, (ByteArray) -> Unit>()
    private val reassembly = HashMap<Int, Reassembly>()
    var onSmart: ((ByteArray) -> Unit)? = null

    private class Reassembly(val total: Int) {
        val buf = ByteArray(total)
        var received = 0
        fun add(offset: Int, chunk: ByteArray): Boolean {
            if (offset < 0 || offset + chunk.size > buf.size) return false
            System.arraycopy(chunk, 0, buf, offset, chunk.size)
            received += chunk.size
            return received >= total
        }
    }

    init {
        session.onProtobuf = { frame -> onProtobuf(frame) }
    }

    /** Sends a Smart message and invokes [onComplete] with the reassembled Smart response. */
    fun sendSmart(smart: ByteArray, onComplete: (ByteArray) -> Unit): Int {
        val id = nextId
        nextId = if (nextId >= 0xFFFF) 0x0800 else nextId + 1
        pending[id] = onComplete
        val chunks = GdiFrame.chunks(id, smart)
        log("GDI TX id=0x%04x %d chunk(s), %d bytes".format(id, chunks.size, smart.size))
        for (chunk in chunks) {
            session.sendProtobuf(chunk)
        }
        return id
    }

    private fun onProtobuf(frame: Gfdi.Frame) {
        if (frame.type == Gfdi.MessageType.PROTOBUF_REQUEST.id) {
            // Cancellation / unsupported notice: [id: u16] ...
            if (frame.payload.size >= 2) {
                val id = (frame.payload[0].toInt() and 0xFF) or ((frame.payload[1].toInt() and 0xFF) shl 8)
                if (pending.remove(id) != null) log("GDI: device cancelled id=0x%04x".format(id))
            }
            return
        }
        if (frame.type != Gfdi.MessageType.PROTOBUF_RESPONSE.id) return
        val parsed = GdiFrame.parse(frame.payload) ?: run {
            log("GDI: malformed frame")
            return
        }
        val state = reassembly.getOrPut(parsed.id) { Reassembly(parsed.totalLength) }
        log("GDI RX id=0x%04x offset=%d total=%d chunk=%d".format(parsed.id, parsed.offset, parsed.totalLength, parsed.payload.size))
        val done = state.add(parsed.offset, parsed.payload)
        if (!done) return
        reassembly.remove(parsed.id)
        onSmart?.invoke(state.buf)
        val callback = pending.remove(parsed.id) ?: return
        callback(state.buf)
    }
}
