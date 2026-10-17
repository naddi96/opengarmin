package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal FIT activity parser.
 *
 * Only the messages needed by the dashboard are retained: file_id,
 * activity, session, lap and record. Definition messages are honoured,
 * including big-endian files, developer fields and compressed timestamps.
 */
object Fit {
    const val FILE_ID = 0
    const val SESSION = 18
    const val LAP = 19
    const val RECORD = 20
    const val ACTIVITY = 34

    data class FileId(
        val type: Int?,
        val manufacturer: Int?,
        val product: Int?,
        val serialNumber: Long?,
        val timeCreated: Long?,
        val productName: String?,
    )

    data class Session(
        val timestamp: Long?,
        val startTime: Long?,
        val sport: Int?,
        val subSport: Int?,
        val totalElapsedSeconds: Double?,
        val totalTimerSeconds: Double?,
        val distanceMeters: Double?,
        val calories: Int?,
        val avgSpeedMps: Double?,
        val maxSpeedMps: Double?,
        val avgHeartRate: Int?,
        val maxHeartRate: Int?,
        val ascentMeters: Int?,
        val descentMeters: Int?,
    )

    data class Lap(
        val timestamp: Long?,
        val startTime: Long?,
        val totalElapsedSeconds: Double?,
        val totalTimerSeconds: Double?,
        val distanceMeters: Double?,
        val calories: Int?,
        val avgSpeedMps: Double?,
        val maxSpeedMps: Double?,
        val avgHeartRate: Int?,
        val maxHeartRate: Int?,
        val ascentMeters: Int?,
        val descentMeters: Int?,
    )

    data class Record(
        val timestamp: Long,
        val distanceMeters: Double?,
        val speedMps: Double?,
        val heartRate: Int?,
        val cadence: Int?,
        val altitudeMeters: Double?,
        val temperatureC: Double?,
        val latitude: Double?,
        val longitude: Double?,
    )

    data class Activity(
        val timestamp: Long?,
        val totalTimerSeconds: Double?,
        val numSessions: Int?,
        val type: Int?,
        val event: Int?,
        val eventType: Int?,
        val localTimestamp: Long?,
    )

    data class Parsed(
        val fileId: FileId?,
        val activity: Activity?,
        val session: Session?,
        val laps: List<Lap>,
        val records: List<Record>,
        val messageCount: Int,
    )

    private data class FieldDef(val number: Int, val size: Int, val baseType: Int)

    private data class Definition(
        val globalMessage: Int,
        val bigEndian: Boolean,
        val fields: List<FieldDef>,
        val developerFields: List<FieldDef>,
    )

    private class Reader(private val data: ByteArray) {
        var pos = 0

        fun remaining() = data.size - pos
        fun u8(): Int = data[pos++].toInt() and 0xFF
        fun s8(): Int = data[pos++].toInt()
        fun u16(big: Boolean): Int {
            val v = if (big) {
                ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            } else {
                (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            }
            pos += 2
            return v
        }
        fun bytes(n: Int): ByteArray {
            val end = minOf(pos + n, data.size)
            val out = data.copyOfRange(pos, end)
            pos = end
            return out
        }
        fun skip(n: Int) {
            pos = minOf(pos + n, data.size)
        }
    }

    fun parse(data: ByteArray): Parsed {
        require(data.size >= 12) { "FIT file too short" }
        val headerSize = data[0].toInt() and 0xFF
        require(headerSize == 12 || headerSize == 14) { "unsupported FIT header size $headerSize" }
        require(data.size >= headerSize) { "truncated FIT header" }
        val magic = String(data, 8, 4, Charsets.US_ASCII)
        require(magic == ".FIT") { "missing .FIT magic" }
        val dataSize = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(dataSize >= 0) { "negative FIT data size" }
        val end = minOf(headerSize + dataSize, data.size)

        val reader = Reader(data)
        reader.pos = headerSize
        val definitions = HashMap<Int, Definition>()
        var lastTimestamp: Long? = null
        var messageCount = 0

        var fileId: FileId? = null
        var activity: Activity? = null
        var session: Session? = null
        val laps = ArrayList<Lap>()
        val records = ArrayList<Record>()

        while (reader.pos < end) {
            val header = reader.u8()
            val isCompressed = header and 0x80 != 0
            val isDefinition = !isCompressed && header and 0x40 != 0
            val hasDeveloperData = isDefinition && header and 0x20 != 0
            val localType = if (isCompressed) (header ushr 5) and 0x03 else header and 0x0F

            if (isDefinition) {
                reader.u8() // reserved
                val architecture = reader.u8()
                val big = architecture == 1
                val global = reader.u16(big)
                val fieldCount = reader.u8()
                val fields = ArrayList<FieldDef>(fieldCount)
                repeat(fieldCount) {
                    fields.add(FieldDef(reader.u8(), reader.u8(), reader.u8()))
                }
                val devFields = ArrayList<FieldDef>()
                if (hasDeveloperData) {
                    val devCount = reader.u8()
                    repeat(devCount) {
                        val number = reader.u8()
                        val size = reader.u8()
                        reader.u8() // developer data index
                        devFields.add(FieldDef(number, size, 0))
                    }
                }
                definitions[localType] = Definition(global, big, fields, devFields)
                continue
            }

            val def = definitions[localType] ?: error("data message without definition (local $localType)")
            val values = HashMap<Int, Any?>(def.fields.size)
            for (field in def.fields) {
                val raw = reader.bytes(field.size)
                values[field.number] = decode(field.baseType, raw, def.bigEndian)
            }
            for (field in def.developerFields) reader.skip(field.size)
            messageCount++

            if (isCompressed) {
                val offset = header and 0x1F
                val previous = lastTimestamp ?: 0L
                var timestamp = (previous and 0x1F.inv().toLong()) + offset
                if (offset < (previous and 0x1F)) timestamp += 0x20
                values[253] = timestamp
                lastTimestamp = timestamp
            } else {
                (values[253] as? Number)?.let { lastTimestamp = it.toLong() }
            }

            when (def.globalMessage) {
                FILE_ID -> if (fileId == null) {
                    fileId = FileId(
                        type = int(values, 0),
                        manufacturer = int(values, 1),
                        product = int(values, 2),
                        serialNumber = long(values, 3),
                        timeCreated = long(values, 4),
                        productName = string(values, 8),
                    )
                }
                ACTIVITY -> if (activity == null) {
                    activity = Activity(
                        timestamp = long(values, 253),
                        totalTimerSeconds = scaled(values, 0, 1000.0),
                        numSessions = int(values, 1),
                        type = int(values, 2),
                        event = int(values, 3),
                        eventType = int(values, 4),
                        localTimestamp = long(values, 5),
                    )
                }
                SESSION -> if (session == null) {
                    session = Session(
                        timestamp = long(values, 253),
                        startTime = long(values, 2),
                        sport = int(values, 5),
                        subSport = int(values, 6),
                        totalElapsedSeconds = scaled(values, 7, 1000.0),
                        totalTimerSeconds = scaled(values, 8, 1000.0),
                        distanceMeters = scaled(values, 9, 100.0),
                        calories = int(values, 11),
                        avgSpeedMps = scaled(values, 124, 1000.0) ?: scaled(values, 14, 1000.0),
                        maxSpeedMps = scaled(values, 125, 1000.0) ?: scaled(values, 15, 1000.0),
                        avgHeartRate = int(values, 16),
                        maxHeartRate = int(values, 17),
                        ascentMeters = int(values, 22),
                        descentMeters = int(values, 23),
                    )
                }
                LAP -> laps.add(
                    Lap(
                        timestamp = long(values, 253),
                        startTime = long(values, 2),
                        totalElapsedSeconds = scaled(values, 7, 1000.0),
                        totalTimerSeconds = scaled(values, 8, 1000.0),
                        distanceMeters = scaled(values, 9, 100.0),
                        calories = int(values, 11),
                        avgSpeedMps = scaled(values, 110, 1000.0) ?: scaled(values, 13, 1000.0),
                        maxSpeedMps = scaled(values, 111, 1000.0) ?: scaled(values, 14, 1000.0),
                        avgHeartRate = int(values, 15),
                        maxHeartRate = int(values, 16),
                        ascentMeters = int(values, 21),
                        descentMeters = int(values, 22),
                    ),
                )
                RECORD -> {
                    val timestamp = long(values, 253) ?: lastTimestamp
                    if (timestamp != null) {
                        records.add(
                            Record(
                                timestamp = timestamp,
                                distanceMeters = scaled(values, 5, 100.0),
                                speedMps = scaled(values, 73, 1000.0) ?: scaled(values, 6, 1000.0),
                                heartRate = int(values, 3),
                                cadence = int(values, 4),
                                altitudeMeters = scaled(values, 78, 5.0, offset = -500.0)
                                    ?: scaled(values, 2, 5.0, offset = -500.0),
                                temperatureC = int(values, 13)?.toDouble(),
                                latitude = scaled(values, 0, 1.0)?.let { it * 180.0 / 2147483648.0 },
                                longitude = scaled(values, 1, 1.0)?.let { it * 180.0 / 2147483648.0 },
                            ),
                        )
                    }
                }
            }
        }

        return Parsed(fileId, activity, session, laps, records, messageCount)
    }

    private fun int(values: Map<Int, Any?>, field: Int): Int? = (values[field] as? Number)?.toInt()
    private fun long(values: Map<Int, Any?>, field: Int): Long? = (values[field] as? Number)?.toLong()
    private fun string(values: Map<Int, Any?>, field: Int): String? = values[field] as? String

    private fun scaled(
        values: Map<Int, Any?>,
        field: Int,
        scale: Double,
        offset: Double = 0.0,
    ): Double? {
        val raw = (values[field] as? Number)?.toDouble() ?: return null
        return raw / scale + offset
    }

    /** Decodes the first value of a field (FIT arrays are rare in our subset). */
    private fun decode(baseType: Int, raw: ByteArray, bigEndian: Boolean): Any? {
        if (raw.isEmpty()) return null
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buf = ByteBuffer.wrap(raw).order(order)
        return when (baseType) {
            0x00 -> (raw[0].toInt() and 0xFF).takeUnless { it == 0xFF }
            0x02 -> (raw[0].toInt() and 0xFF).takeUnless { it == 0xFF }
            0x0A -> (raw[0].toInt() and 0xFF).takeUnless { it == 0 }
            0x01 -> raw[0].toInt().takeUnless { it == 0x7F }
            0x83 -> if (raw.size >= 2) buf.short.toInt().takeUnless { it == 0x7FFF } else null
            0x84 -> if (raw.size >= 2) (buf.short.toInt() and 0xFFFF).takeUnless { it == 0xFFFF } else null
            0x8B -> if (raw.size >= 2) (buf.short.toInt() and 0xFFFF).takeUnless { it == 0 } else null
            0x85 -> if (raw.size >= 4) buf.int.takeUnless { it == Int.MAX_VALUE } else null
            0x86 -> if (raw.size >= 4) {
                (buf.int.toLong() and 0xFFFFFFFFL).takeUnless { it == 0xFFFFFFFFL }
            } else null
            0x8C -> if (raw.size >= 4) {
                (buf.int.toLong() and 0xFFFFFFFFL).takeUnless { it == 0L }
            } else null
            0x07 -> {
                var n = 0
                while (n < raw.size && raw[n] != 0.toByte()) n++
                String(raw, 0, n, Charsets.UTF_8)
            }
            0x88 -> if (raw.size >= 4) buf.float.takeUnless { it.isNaN() } else null
            0x89 -> if (raw.size >= 8) buf.double.takeUnless { it.isNaN() } else null
            0x0D -> raw[0].toInt() and 0xFF
            0x8E -> if (raw.size >= 8) buf.long.takeUnless { it == Long.MAX_VALUE } else null
            0x8F -> if (raw.size >= 8) buf.long.takeUnless { it == -1L } else null
            0x90 -> if (raw.size >= 8) buf.long.takeUnless { it == 0L } else null
            else -> null
        }
    }
}
