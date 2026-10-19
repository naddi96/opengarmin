package dev.opengarmin.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun `intervals are clamped to the documented 15 minute to 6 hour range`() {
        assertEquals(15, SyncPolicy.normalizeInterval(1))
        assertEquals(15, SyncPolicy.normalizeInterval(15))
        assertEquals(60, SyncPolicy.normalizeInterval(60))
        assertEquals(360, SyncPolicy.normalizeInterval(6 * 60))
        assertEquals(360, SyncPolicy.normalizeInterval(999))
    }

    @Test
    fun `interval options map to labels and nearest index`() {
        assertEquals("15m", SyncPolicy.intervalLabel(15))
        assertEquals("1h", SyncPolicy.intervalLabel(60))
        assertEquals("6h", SyncPolicy.intervalLabel(360))
        assertEquals(0, SyncPolicy.optionIndex(15))
        assertEquals(2, SyncPolicy.optionIndex(60))
        assertEquals(4, SyncPolicy.optionIndex(360))
        assertEquals(2, SyncPolicy.optionIndex(70))
    }

    @Test
    fun `setup problems are permanent and do not loop with backoff`() {
        assertEquals(
            SyncPolicy.FailureKind.PERMANENT,
            SyncPolicy.classifyFailure("No bonded Garmin watch found. Pair the watch first."),
        )
        assertEquals(
            SyncPolicy.FailureKind.PERMANENT,
            SyncPolicy.classifyFailure("missing BLUETOOTH_CONNECT"),
        )
        assertEquals(
            SyncPolicy.FailureKind.PERMANENT,
            SyncPolicy.classifyFailure("This device is not supported."),
        )
        assertFalse(SyncPolicy.shouldRetry(SyncPolicy.FailureKind.PERMANENT))
    }

    @Test
    fun `busy watch slots and a held Garmin Connect session are retryable`() {
        assertEquals(
            SyncPolicy.FailureKind.BUSY,
            SyncPolicy.classifyFailure("The watch accepted the connection but did not open its GDI session. Garmin Connect can hold that single session."),
        )
        assertEquals(
            SyncPolicy.FailureKind.BUSY,
            SyncPolicy.classifyFailure("A background sync is running. Try again."),
        )
        assertEquals(
            SyncPolicy.FailureKind.BUSY,
            SyncPolicy.classifyFailure("", stalledBeforeCapabilities = true),
        )
        assertTrue(SyncPolicy.shouldRetry(SyncPolicy.FailureKind.BUSY))
    }

    @Test
    fun `disconnects and transfer problems are transient`() {
        assertEquals(
            SyncPolicy.FailureKind.TRANSIENT,
            SyncPolicy.classifyFailure("The watch disconnected before the sync finished."),
        )
        assertEquals(
            SyncPolicy.FailureKind.TRANSIENT,
            SyncPolicy.classifyFailure("NO_AVAILABLE_RESOURCES"),
        )
        assertEquals(
            SyncPolicy.FailureKind.TRANSIENT,
            SyncPolicy.classifyFailure("Could not finish syncing. Keep the watch nearby and try again."),
        )
        assertEquals(
            SyncPolicy.FailureKind.TRANSIENT,
            SyncPolicy.classifyFailure(null),
        )
        assertTrue(SyncPolicy.shouldRetry(SyncPolicy.FailureKind.TRANSIENT))
    }

    @Test
    fun `duration and trigger labels are human readable`() {
        assertEquals("—", SyncPolicy.formatDuration(null))
        assertEquals("250 ms", SyncPolicy.formatDuration(250))
        assertEquals("1.5 s", SyncPolicy.formatDuration(1_500))
        assertEquals("2 min", SyncPolicy.formatDuration(120_000))
        assertEquals("Manual", SyncPolicy.triggerLabel("manual"))
        assertEquals("Background", SyncPolicy.triggerLabel("background"))
        assertEquals("Watch reconnect", SyncPolicy.triggerLabel("reconnect"))
        assertEquals("Not recorded", SyncPolicy.triggerLabel(""))
    }

    @Test
    fun `defaults and constraints are documented`() {
        assertFalse(SyncPolicy.DEFAULT_ENABLED)
        assertFalse(SyncPolicy.DEFAULT_CHARGING_ONLY)
        assertEquals(60, SyncPolicy.DEFAULT_INTERVAL_MINUTES)
        assertTrue(SyncPolicy.REQUIRES_BATTERY_NOT_LOW)
        assertEquals(10L, SyncPolicy.BACKOFF_MINUTES)
    }
}
