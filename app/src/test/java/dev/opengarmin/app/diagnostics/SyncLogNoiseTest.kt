package dev.opengarmin.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogNoiseTest {
    @Test
    fun `recognizes the keepalive trio from a real sync log`() {
        // Lines from a Pixel 10a / FR255 session where the log contained
        // nothing but NOTIFICATION_SUBSCRIPTION traffic for several minutes.
        assertTrue(SyncLogNoise.isNoise("GFDI type=5036 seq=7 len=2 0000"))
        assertTrue(
            SyncLogNoise.isNoise("TX(0) 4300020c010486ac1301010103ba5800"),
        )
        assertTrue(SyncLogNoise.isNoise("RX 430002080324870103bb9100"))
    }

    @Test
    fun `keeps useful protocol and lifecycle lines`() {
        assertFalse(SyncLogNoise.isNoise("TX(0) 00000100000000000000182002"))
        assertFalse(SyncLogNoise.isNoise("TX(0) b0000000ac020000"))
        assertFalse(SyncLogNoise.isNoise("GFDI type=5043 seq=12 len=14 0102"))
        assertFalse(SyncLogNoise.isNoise("GDI RX(id=0x0006) type=5044 offset=0 total=49 chunk=49"))
        assertFalse(SyncLogNoise.isNoise("DOWNLOAD raw=99357 decoded=198627"))
        assertFalse(SyncLogNoise.isNoise("APP onStop syncInProgress=true"))
        assertFalse(SyncLogNoise.isNoise("TX(-1) 4300020c010486ac1301010103ba5800"))
    }
}
