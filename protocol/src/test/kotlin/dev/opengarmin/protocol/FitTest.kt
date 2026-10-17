package dev.opengarmin.protocol

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FitTest {
    private class FitWriter {
        val data = ByteArrayOutputStream()

        fun u8(v: Int) = apply { data.write(v and 0xFF) }
        fun u16(v: Int) = apply { data.write(v and 0xFF); data.write((v ushr 8) and 0xFF) }
        fun u32(v: Long) = apply {
            repeat(4) { data.write(((v ushr (8 * it)) and 0xFF).toInt()) }
        }

        fun definition(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>) = apply {
            u8(0x40 or local)
            u8(0)
            u8(0) // little endian
            u16(global)
            u8(fields.size)
            fields.forEach { (number, size, baseType) ->
                u8(number)
                u8(size)
                u8(baseType)
            }
        }

        fun record(local: Int, block: FitWriter.() -> Unit) = apply {
            u8(local)
            block()
        }

        fun file(): ByteArray {
            val payload = data.toByteArray()
            val header = ByteArrayOutputStream()
            header.write(0x0E)
            header.write(0x10)
            header.write(0xC0)
            header.write(0x52)
            repeat(4) { header.write((payload.size ushr (8 * it)) and 0xFF) }
            header.write(".FIT".toByteArray(Charsets.US_ASCII))
            header.write(0)
            header.write(0)
            return header.toByteArray() + payload
        }
    }

    private fun sample(): ByteArray {
        val w = FitWriter()
        w.definition(0, Fit.FILE_ID, listOf(Triple(0, 1, 0x00), Triple(1, 2, 0x84), Triple(4, 4, 0x86)))
        w.record(0) {
            u8(4) // activity
            u16(1) // Garmin
            u32(123456)
        }
        w.definition(
            1,
            Fit.SESSION,
            listOf(
                Triple(2, 4, 0x86), Triple(5, 1, 0x00), Triple(7, 4, 0x86),
                Triple(8, 4, 0x86), Triple(9, 4, 0x86), Triple(16, 1, 0x02),
                Triple(17, 1, 0x02), Triple(22, 2, 0x84), Triple(23, 2, 0x84),
            ),
        )
        w.record(1) {
            u32(1000)
            u8(1) // running
            u32(3_600_000)
            u32(3_500_000)
            u32(1_000_000)
            u8(150)
            u8(175)
            u16(15)
            u16(12)
        }
        w.definition(
            2,
            Fit.RECORD,
            listOf(Triple(253, 4, 0x86), Triple(3, 1, 0x02), Triple(5, 4, 0x86), Triple(2, 2, 0x84)),
        )
        w.record(2) {
            u32(1000); u8(120); u32(0); u16(500)
        }
        w.record(2) {
            u32(1010); u8(130); u32(1000); u16(525)
        }
        w.definition(
            3,
            Fit.ACTIVITY,
            listOf(Triple(253, 4, 0x86), Triple(0, 4, 0x86), Triple(1, 2, 0x84), Triple(2, 1, 0x00)),
        )
        w.record(3) {
            u32(1005); u32(3_500_000); u16(1); u8(0)
        }
        return w.file()
    }

    @Test
    fun `parses a small activity`() {
        val parsed = Fit.parse(sample())

        val fileId = assertNotNull(parsed.fileId)
        assertEquals(4, fileId.type)
        assertEquals(1, fileId.manufacturer)
        assertEquals(123456L, fileId.timeCreated)

        val session = assertNotNull(parsed.session)
        assertEquals(1, session.sport)
        assertEquals(1000L, session.startTime)
        assertEquals(3500.0, session.totalTimerSeconds)
        assertEquals(3600.0, session.totalElapsedSeconds)
        assertEquals(10000.0, session.distanceMeters)
        assertEquals(150, session.avgHeartRate)
        assertEquals(175, session.maxHeartRate)

        val activity = assertNotNull(parsed.activity)
        assertEquals(1, activity.numSessions)
        assertEquals(3500.0, activity.totalTimerSeconds)

        assertEquals(2, parsed.records.size)
        val first = parsed.records[0]
        assertEquals(120, first.heartRate)
        assertEquals(0.0, first.distanceMeters)
        assertEquals(-400.0, first.altitudeMeters)
        val second = parsed.records[1]
        assertEquals(130, second.heartRate)
        assertEquals(10.0, second.distanceMeters)
        assertEquals(-395.0, second.altitudeMeters)

        assertEquals(5, parsed.messageCount)

        assertEquals(5, parsed.messages.size)
        val recordMessage = parsed.messages.first { it.name == "record" }
        assertEquals(20, recordMessage.globalNumber)
        val hr = recordMessage.fields.first { it.number == 3 }
        assertEquals("heart_rate", hr.name)
        assertEquals("bpm", hr.units)
        assertEquals(120, hr.value)
        val altitude = recordMessage.fields.first { it.number == 2 }
        assertEquals("altitude", altitude.name)
        assertEquals(-400.0, altitude.value)
        val sessionMessage = parsed.messages.first { it.name == "session" }
        val start = sessionMessage.fields.first { it.number == 2 }
        assertEquals(1000L + 631065600L, start.value)
    }

    @Test
    fun `rejects a file without FIT magic`() {
        val bad = sample()
        bad[8] = 'X'.code.toByte()
        assertFailsWith<IllegalArgumentException> { Fit.parse(bad) }
    }

    @Test
    fun `handles a truncated definition gracefully`() {
        val bad = byteArrayOf(0x0E, 0x10, 0, 0, 0x01, 0, 0, 0, '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 0, 0, 0x40)
        val parsed = runCatching { Fit.parse(bad) }
        assertTrue(parsed.isFailure || parsed.getOrNull()?.messageCount == 0)
    }
}
