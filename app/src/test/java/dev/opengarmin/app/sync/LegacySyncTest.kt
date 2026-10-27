package dev.opengarmin.app.sync

import dev.opengarmin.protocol.Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySyncTest {
    private fun entry(
        fileIndex: Int,
        dataType: Int,
        subType: Int,
        fileNumber: Int = 1,
        specificFlags: Int = 0,
        fileFlags: Int = 0x80,
        size: Long = 1000,
        timestamp: Long = 100_000_000,
    ): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(fileIndex.toShort())
        .put(dataType.toByte())
        .put(subType.toByte())
        .putShort(fileNumber.toShort())
        .put(specificFlags.toByte())
        .put(fileFlags.toByte())
        .putInt(size.toInt())
        .putInt(timestamp.toInt())
        .array()

    @Test
    fun `parses activity and wellness directory entries`() {
        val activity = entry(
            fileIndex = 0x0102,
            dataType = 128,
            subType = 4,
            fileNumber = 7,
            size = 4660,
            timestamp = 100_000_001,
        )
        val sleep = entry(
            fileIndex = 0x0103,
            dataType = 128,
            subType = 49,
            fileNumber = 8,
            size = 900,
            timestamp = 100_000_002,
        )
        val sports = entry(fileIndex = 0x0104, dataType = 128, subType = 3)
        val other = entry(fileIndex = 0x0105, dataType = 255, subType = 4)
        val empty = ByteArray(16)

        val parsed = LegacySync.parseEntries(activity + sleep + sports + other + empty)
        assertEquals(2, parsed.size)

        val activityEntry = parsed[0]
        assertTrue(activityEntry.isActivity)
        assertFalse(activityEntry.isWellness)
        assertEquals("FIT_TYPE_4", activityEntry.typeName)
        assertEquals(0x0102, activityEntry.fileIndex)
        assertEquals(7, activityEntry.fileNumber)
        assertEquals(4660L, activityEntry.size)
        assertEquals(
            "legacy:128:4:7:4660:100000001",
            activityEntry.uid,
        )

        val sleepEntry = parsed[1]
        assertFalse(sleepEntry.isActivity)
        assertTrue(sleepEntry.isWellness)
        assertEquals("FIT_TYPE_49", sleepEntry.typeName)
    }

    @Test
    fun `skips the directory header before parsing entries`() {
        val header = ByteArray(16)
        val activity = entry(fileIndex = 0x0102, dataType = 128, subType = 4)
        val bytes = header + activity
        assertEquals(16, LegacySync.directoryStart(bytes))
        val parsed = LegacySync.parseEntries(bytes, LegacySync.directoryStart(bytes))
        assertEquals(1, parsed.size)
        assertEquals(0x0102, parsed[0].fileIndex)
    }

    @Test
    fun `accepts a headerless directory`() {
        val activity = entry(fileIndex = 0x0102, dataType = 128, subType = 4)
        val sleep = entry(fileIndex = 0x0103, dataType = 128, subType = 49)
        assertEquals(0, LegacySync.directoryStart(activity + sleep))
        assertEquals(2, LegacySync.parseEntries(activity + sleep).size)
    }

    @Test
    fun `hides archived and unreadable entries like Garmin`() {
        val pending = entry(fileIndex = 1, dataType = 128, subType = 4, fileFlags = 0xA0)
        val archived = entry(fileIndex = 2, dataType = 128, subType = 4, fileFlags = 0xB0)
        val noRead = entry(fileIndex = 3, dataType = 128, subType = 4, fileFlags = 0x20)
        val bytes = pending + archived + noRead

        assertEquals(listOf(1), LegacySync.parseEntries(bytes).map { it.fileIndex })
        assertEquals(
            listOf(1, 2, 3),
            LegacySync.parseEntries(bytes, includeArchived = true).map { it.fileIndex },
        )
    }

    @Test
    fun `full scan can log FIT subtypes the client does not import`() {
        val unknown = entry(fileIndex = 4, dataType = 128, subType = 41, fileFlags = 0xB0)
        assertTrue(LegacySync.parseEntries(unknown).isEmpty())
        assertEquals(
            listOf(4),
            LegacySync.parseEntries(
                unknown,
                includeArchived = true,
                includeAllTypes = true,
            ).map { it.fileIndex },
        )
    }

    @Test
    fun `download request payload matches the legacy wire layout`() {
        assertEquals(
            "2301000000000100000000000000",
            Hex.encode(LegacySync.downloadRequestPayload(0x0123)),
        )
    }

    @Test
    fun `parses a file transfer chunk`() {
        val chunk = requireNotNull(
            LegacySync.fileData(Hex.decode("00341200010000deadbeef")),
        )
        assertEquals(0x1234, chunk.crc)
        assertEquals(0x0100L, chunk.offset)
        assertEquals("deadbeef", Hex.encode(chunk.data))
    }

    @Test
    fun `parses the download status size`() {
        val status = requireNotNull(
            LegacySync.parseDownloadStatus(Hex.decode("0010270000")),
        )
        assertEquals(0, status.first)
        assertEquals(10_000L, status.second)
    }

    @Test
    fun `data status carries the next offset`() {
        assertEquals(
            "0004030201",
            Hex.encode(LegacySync.dataStatusPayload(0x01020304, LegacySync.TRANSFER_OK)),
        )
    }
}
