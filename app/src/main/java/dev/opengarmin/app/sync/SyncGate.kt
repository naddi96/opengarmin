package dev.opengarmin.app.sync

/**
 * Process-wide single-flight guard for watch transfers.
 *
 * The BLE watch has one useful transfer/session slot. Foreground sync holds a
 * lease while the app is visible and connected; the periodic worker can only
 * acquire it after the activity releases the connection. Acquiring is
 * re-entrant for the same owner so a manual sync can reuse an existing UI lease.
 */
object SyncGate {
    const val OWNER_UI = "ui"
    const val OWNER_BACKGROUND = "background"

    private val lock = Any()
    private var owner: String? = null

    fun tryAcquire(requester: String): Boolean = synchronized(lock) {
        when {
            owner == null -> {
                owner = requester
                true
            }
            owner == requester -> true
            else -> false
        }
    }

    fun release(requester: String) {
        synchronized(lock) {
            if (owner == requester) owner = null
        }
    }

    fun owner(): String? = synchronized(lock) { owner }

    fun isHeld(): Boolean = synchronized(lock) { owner != null }

    /** Test-only escape hatch; production callers must release their own lease. */
    internal fun resetForTest() {
        synchronized(lock) { owner = null }
    }
}
