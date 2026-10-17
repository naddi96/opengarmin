package dev.opengarmin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FIT activity parser.
 *
 * The typed subset (file_id / activity / session / lap / record) feeds the
 * dashboard. Every message and field is also retained in [Parsed.messages] so
 * the data explorer can show and cross-plot the full file. Definition messages
 * are honoured, including big-endian files, developer fields, arrays and
 * compressed timestamps.
 */
object Fit {
    const val FILE_ID = 0
    const val SESSION = 18
    const val LAP = 19
    const val RECORD = 20
    const val ACTIVITY = 34

    private val TIME_FIELDS = setOf(
        "start_time",
        "end_time",
        "begin_time",
        "local_timestamp",
        "time_created",
    )

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
        val messages: List<Message>,
    )

    /** One decoded field in a generic FIT message. Values are already scaled. */
    data class FieldValue(
        val number: Int,
        val name: String,
        val value: Any?,
        val units: String?,
    )

    /** A generic FIT message with every field (including unknown/developer). */
    data class Message(
        val globalNumber: Int,
        val name: String,
        /** Unix seconds, or null for messages without a timestamp. */
        val timestamp: Long?,
        val fields: List<FieldValue>,
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
        val messages = ArrayList<Message>()

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
            val rawFields = ArrayList<Pair<FieldDef, ByteArray>>(def.fields.size)
            for (field in def.fields) {
                val raw = reader.bytes(field.size)
                rawFields.add(field to raw)
                values[field.number] = decode(field.baseType, raw, def.bigEndian)
            }
            val rawDevFields = ArrayList<Pair<FieldDef, ByteArray>>(def.developerFields.size)
            for (field in def.developerFields) {
                rawDevFields.add(field to reader.bytes(field.size))
            }
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

            messages.add(
                buildMessage(def, rawFields, rawDevFields, values),
            )

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

        return Parsed(fileId, activity, session, laps, records, messageCount, messages)
    }

    private fun buildMessage(
        def: Definition,
        rawFields: List<Pair<FieldDef, ByteArray>>,
        rawDevFields: List<Pair<FieldDef, ByteArray>>,
        values: Map<Int, Any?>,
    ): Message {
        val tsRaw = (values[253] as? Number)?.toLong()
        val unixTs = tsRaw?.plus(Gfdi.GARMIN_EPOCH_UNIX)
        val fields = ArrayList<FieldValue>(rawFields.size + rawDevFields.size + 1)

        for ((field, raw) in rawFields) {
            val info = FitProfile.field(def.globalMessage, field.number)
            val name = info?.name ?: "field_${field.number}"
            val units = info?.units
            val scale = info?.scale ?: 1.0
            val offset = info?.offset ?: 0.0
            val decoded = decodeAll(field.baseType, raw, def.bigEndian)
            val scaled = decoded.map { scaleValue(it, scale, offset, units) }
            val adjusted = if (name in TIME_FIELDS && field.number != 253) {
                scaled.map { value ->
                    if (value is Number) value.toLong() + Gfdi.GARMIN_EPOCH_UNIX else value
                }
            } else {
                scaled
            }
            val value = if (adjusted.size == 1) adjusted[0] else adjusted.joinToString(", ")
            val finalUnits = if (units == "semicircles") "deg" else units
            fields.add(FieldValue(field.number, name, value, finalUnits))
        }
        for ((field, raw) in rawDevFields) {
            fields.add(FieldValue(field.number, "dev_${field.number}", Hex.encode(raw), null))
        }
        if (unixTs != null) {
            val index = fields.indexOfFirst { it.number == 253 }
            if (index >= 0) {
                fields[index] = fields[index].copy(value = unixTs, units = "s")
            } else {
                fields.add(FieldValue(253, "timestamp", unixTs, "s"))
            }
        }

        val name = FitProfile.message(def.globalMessage)?.name ?: "msg_${def.globalMessage}"
        return Message(def.globalMessage, name, unixTs, fields)
    }

    private fun scaleValue(value: Any?, scale: Double, offset: Double, units: String?): Any? {
        if (value !is Number) return value
        if (units == "semicircles") return value.toDouble() * 180.0 / 2147483648.0
        if (scale == 1.0 && offset == 0.0) return value
        return value.toDouble() / scale - offset
    }

    private fun baseSize(baseType: Int): Int = when (baseType) {
        0x00, 0x01, 0x02, 0x07, 0x0A, 0x0D -> 1
        0x83, 0x84, 0x8B -> 2
        0x85, 0x86, 0x88, 0x8C -> 4
        0x89, 0x8E, 0x8F, 0x90 -> 8
        else -> 0
    }

    private fun decodeAll(baseType: Int, raw: ByteArray, bigEndian: Boolean): List<Any?> {
        if (raw.isEmpty()) return emptyList()
        if (baseType == 0x07) return listOfNotNull(decode(baseType, raw, bigEndian))
        val size = baseSize(baseType)
        if (size <= 0 || raw.size <= size) return listOfNotNull(decode(baseType, raw, bigEndian))
        val out = ArrayList<Any?>()
        var pos = 0
        while (pos + size <= raw.size) {
            out.add(decode(baseType, raw.copyOfRange(pos, pos + size), bigEndian))
            pos += size
        }
        return out.ifEmpty { listOf(null) }
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
