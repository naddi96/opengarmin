package dev.opengarmin.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class TransferDecoderTest {
    @Test
    fun `decodes a complete zlib stream and enforces the expected size`() {
        val payload = fitPayload()
        val compressed = deflate(payload)

        assertArrayEquals(payload, TransferDecoder.decode(compressed, payload.size.toLong()))
        assertArrayEquals(payload, TransferDecoder.decode(compressed, expectedSize = 0))
        assertNull(TransferDecoder.decode(compressed, expectedSize = payload.size + 1L))
    }

    @Test
    fun `falls back to a raw deflate stream`() {
        val payload = fitPayload()
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(payload) }

        assertArrayEquals(payload, TransferDecoder.decode(out.toByteArray(), payload.size.toLong()))
    }

    @Test
    fun `decodes the watch's small-window stored stream`() {
        // The FR255 negotiates an 11-bit window, so its zlib header starts
        // with 0x38 instead of the usual 0x78. Java's Inflater accepts it.
        val payload = fitPayload()
        val compressed = storedZlib(payload, windowBits = 11)

        assertArrayEquals(payload, TransferDecoder.decode(compressed, payload.size.toLong()))
    }

    @Test
    fun `rejects a truncated stream instead of returning compressed bytes`() {
        val compressed = deflate(fitPayload())
        val truncated = compressed.copyOf(compressed.size - 6)

        assertNull(TransferDecoder.decode(truncated, expectedSize = 0))
        assertNull(TransferDecoder.decode(compressed.copyOf(4), expectedSize = 0))
    }

    @Test
    fun `rejects an empty or non-zlib transfer`() {
        assertNull(TransferDecoder.decode(ByteArray(0), expectedSize = 0))
        assertNull(TransferDecoder.decode(ByteArray(64) { 0x5A }, expectedSize = 0))
    }

    @Test
    fun `rejects decoded data shorter than a FIT header`() {
        val tiny = byteArrayOf(1, 2, 3)
        assertNull(TransferDecoder.decode(deflate(tiny), expectedSize = 0))
    }

    private fun fitPayload(): ByteArray {
        val header = byteArrayOf(
            14, 0x10, 0x34, 0x12, 0, 0, 0, 0,
            '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(),
        )
        return header + ByteArray(200) { (it % 251).toByte() }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater()).use { it.write(data) }
        return out.toByteArray()
    }

    /** Mirrors the stored-block zlib stream the watch produces for small windows. */
    private fun storedZlib(data: ByteArray, windowBits: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        val cmf = ((windowBits - 8) shl 4) or 8
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
        val adler = Adler32().apply { update(data) }.value
        out.write((adler ushr 24).toInt() and 0xFF)
        out.write((adler ushr 16).toInt() and 0xFF)
        out.write((adler ushr 8).toInt() and 0xFF)
        out.write(adler.toInt() and 0xFF)
        return out.toByteArray()
    }
}
