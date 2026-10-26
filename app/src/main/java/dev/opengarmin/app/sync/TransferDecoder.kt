package dev.opengarmin.app.sync

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes a completed transport-pipe transfer.
 *
 * The device sends a zlib stream and closes the pipe afterwards. A transfer can
 * end early (watch out of range, connection loss, watchdog) and the old code
 * silently fell back to the raw compressed bytes, which the FIT parser then
 * rejected with a nonsensical header size. Requiring a finished stream and,
 * when known, the exact item size keeps partial files out of the importer.
 */
internal object TransferDecoder {
    /** FIT files are at least a 12-byte header. */
    private const val MIN_FIT_BYTES = 12

    /**
     * Returns the decoded bytes, or null when [data] is not a complete stream
     * or its size does not match [expectedSize] (0 = unknown).
     */
    fun decode(data: ByteArray, expectedSize: Long): ByteArray? {
        val decoded = inflateFinished(data) ?: return null
        if (decoded.size < MIN_FIT_BYTES) return null
        if (expectedSize > 0 && decoded.size.toLong() != expectedSize) return null
        return decoded
    }

    /**
     * Inflates [data] only when the stream reaches its final block. Tries the
     * zlib wrapper first, then a raw deflate stream, so the watch's negotiated
     * compression window (often 11 bits) does not matter to the caller.
     */
    fun inflateFinished(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        for (nowrap in booleanArrayOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                val out = ByteArrayOutputStream(maxOf(data.size * 2, 256))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                inflater.setInput(data)
                while (!inflater.finished()) {
                    val read = try {
                        inflater.inflate(buffer)
                    } catch (_: DataFormatException) {
                        -1
                    }
                    if (read < 0) break
                    if (read == 0) {
                        // No progress: either the input is truncated or the
                        // stream needs a preset dictionary we do not have.
                        break
                    }
                    out.write(buffer, 0, read)
                }
                if (inflater.finished()) return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
        return null
    }
}
