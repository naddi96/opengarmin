package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Legacy GFDI chunked file upload, still supported by GFDI v2 watches.
 *
 * This is the path Garmin's own watch firmware uses for FIT workout files
 * pushed over BLE (the newer GDI `FileAccess` push rejects `FIT_TYPE_5` with
 * `INVALID_PARAMS` on a Forerunner 255):
 *
 *  1. `CREATE_FILE (5005)` with size + FIT type/subtype; the watch allocates a
 *     file index.
 *  2. `UPLOAD_REQUEST (5003)` for that index.
 *  3. `FILE_TRANSFER_DATA (5004)` chunks with a running CRC-16; each response
 *     carries the next accepted offset.
 *  4. `SYSTEM_EVENT (5030)` `SYNC_COMPLETE`.
 */
object GfdiFileUpload {
    const val FIT_DATA_TYPE = 128
    const val WORKOUTS_SUBTYPE = 5

    /** Recommended payload size for FILE_TRANSFER_DATA (GFDI packet minus 13). */
    const val DEFAULT_CHUNK_SIZE = 340

    fun createFile(
        fileSize: Int,
        dataType: Int = FIT_DATA_TYPE,
        subType: Int = WORKOUTS_SUBTYPE,
        fileId: Long = System.nanoTime(),
    ): ByteArray {
        val payload = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(fileSize)
            .put(dataType.toByte())
            .put(subType.toByte())
            .putShort(0) // fileIndex: let the watch choose
            .put(0) // reserved
            .put(0) // subtype mask
            .putShort(0xFFFF.toShort()) // number mask
            .putShort(0) // path length
            .putLong(fileId)
            .array()
        return Gfdi.build(Gfdi.MessageType.CREATE_FILE.id, payload)
    }

    fun uploadRequest(
        fileIndex: Int,
        fileSize: Int,
        offset: Int = 0,
        crcSeed: Int = 0,
    ): ByteArray {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(fileIndex.toShort())
            .putInt(fileSize)
            .putInt(offset)
            .putShort(crcSeed.toShort())
            .array()
        return Gfdi.build(Gfdi.MessageType.UPLOAD_REQUEST.id, payload)
    }

    fun fileTransferData(
        chunk: ByteArray,
        offset: Int,
        crc: Int,
    ): ByteArray {
        val payload = ByteBuffer.allocate(7 + chunk.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0) // flags
            .putShort((crc and 0xFFFF).toShort())
            .putInt(offset)
            .put(chunk)
            .array()
        return Gfdi.build(Gfdi.MessageType.FILE_TRANSFER_DATA.id, payload)
    }

    fun syncComplete(): ByteArray =
        Gfdi.build(Gfdi.MessageType.SYSTEM_EVENT.id, byteArrayOf(0))

    fun le32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
