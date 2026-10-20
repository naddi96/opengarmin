package dev.opengarmin.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPhaseTest {
    @Test
    fun `persisted statuses map to explicit phases`() {
        assertEquals(SyncPhase.Syncing, SyncPhase.fromStored("syncing"))
        assertEquals(SyncPhase.UpToDate, SyncPhase.fromStored("synced"))
        assertEquals(SyncPhase.Failed, SyncPhase.fromStored("failed"))
    }

    @Test
    fun `missing or unknown persisted statuses have no phase`() {
        assertNull(SyncPhase.fromStored(""))
        assertNull(SyncPhase.fromStored(null))
        assertNull(SyncPhase.fromStored("something-new"))
    }
}
