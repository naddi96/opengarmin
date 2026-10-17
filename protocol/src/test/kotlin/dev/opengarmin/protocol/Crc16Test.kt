package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc16Test {
    @Test
    fun `matches documented GFDI wire vectors`() {
        // From the Gadgetbridge protocol documentation (Forerunner 245).
        assertEquals(0xf5d7, Crc16.compute(Hex.decode("09000898280110")))
        assertEquals(0xb131, Crc16.compute(Hex.decode("0d00009690130000c50010")))
        assertEquals(0x9b11, Crc16.compute(Hex.decode("0d00009890130000270110")))
    }

    @Test
    fun `empty input is zero`() {
        assertEquals(0, Crc16.compute(ByteArray(0)))
    }
}
