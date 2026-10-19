package dev.opengarmin.app.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGateTest {
    @After
    fun tearDown() {
        SyncGate.resetForTest()
    }

    @Test
    fun `only one owner can acquire the transfer slot`() {
        assertTrue(SyncGate.tryAcquire(SyncGate.OWNER_UI))
        assertTrue(SyncGate.isHeld())
        assertFalse(SyncGate.tryAcquire(SyncGate.OWNER_BACKGROUND))
        assertEquals(SyncGate.OWNER_UI, SyncGate.owner())
    }

    @Test
    fun `the same owner is re-entrant so manual sync can reuse its lease`() {
        assertTrue(SyncGate.tryAcquire(SyncGate.OWNER_UI))
        assertTrue(SyncGate.tryAcquire(SyncGate.OWNER_UI))
        SyncGate.release(SyncGate.OWNER_UI)
        assertNull(SyncGate.owner())
    }

    @Test
    fun `a different owner cannot release the active lease`() {
        assertTrue(SyncGate.tryAcquire(SyncGate.OWNER_UI))
        SyncGate.release(SyncGate.OWNER_BACKGROUND)
        assertEquals(SyncGate.OWNER_UI, SyncGate.owner())
        SyncGate.release(SyncGate.OWNER_UI)
        assertNull(SyncGate.owner())
    }

    @Test
    fun `the slot is reusable after the owner releases`() {
        assertTrue(SyncGate.tryAcquire(SyncGate.OWNER_UI))
        SyncGate.release(SyncGate.OWNER_UI)
        assertTrue(SyncGate.tryAcquire(SyncGate.OWNER_BACKGROUND))
        assertEquals(SyncGate.OWNER_BACKGROUND, SyncGate.owner())
        SyncGate.release(SyncGate.OWNER_BACKGROUND)
        assertFalse(SyncGate.isHeld())
    }
}
