package dev.opengarmin.protocol

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire-format writer (only what we need). */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long): ProtoWriter {
        var x = value
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return this
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun tag(field: Int, wire: Int): ProtoWriter = varint(((field shl 3) or wire).toLong())

    fun uint32(field: Int, value: Int): ProtoWriter {
        if (value != 0) {
            tag(field, 0)
            varint(value.toLong() and 0xFFFFFFFFL)
        }
        return this
    }

    fun uint64(field: Int, value: Long): ProtoWriter {
        if (value != 0L) {
            tag(field, 0)
            varint(value)
        }
        return this
    }

    fun enumField(field: Int, value: Int): ProtoWriter = uint32(field, value)

    /** Writes a varint field even when the value is zero (needed for repeated enums). */
    fun enumAlways(field: Int, value: Int): ProtoWriter {
        tag(field, 0)
        varint(value.toLong())
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun fixed64(field: Int, value: Long): ProtoWriter {
        tag(field, 1)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal protobuf wire-format reader. */
class ProtoReader(val data: ByteArray, var pos: Int = 0) {
    fun hasMore(): Boolean = pos < data.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) error("varint too long")
        }
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        val out = data.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readFixed64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun readFixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> pos += readVarint().toInt()
            5 -> pos += 4
            else -> error("unsupported wire type $wire")
        }
    }
}
