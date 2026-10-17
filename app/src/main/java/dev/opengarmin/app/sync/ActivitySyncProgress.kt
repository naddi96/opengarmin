package dev.opengarmin.app.sync

/**
 * Progress through the activity portion of a sync.
 *
 * [currentSize] is the raw size the watch reported for the item currently being
 * downloaded, or null when no activity pull is in flight.
 */
data class ActivitySyncProgress(
    val done: Int,
    val total: Int,
    val currentSize: Long?,
)
