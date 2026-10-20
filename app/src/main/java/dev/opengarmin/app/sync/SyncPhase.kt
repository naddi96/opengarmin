package dev.opengarmin.app.sync

/**
 * Coarse phase of the visible sync/import flow.
 *
 * Persisted history keeps the existing string values in [PulseSettings]; use
 * [fromStored] at the boundary instead of comparing strings in the UI.
 */
enum class SyncPhase {
    Idle,
    Connecting,
    Connected,
    AwaitingWatch,
    Syncing,
    Importing,
    UpToDate,
    Background,
    Reconnecting,
    Failed,
    ;

    companion object {
        fun fromStored(value: String?): SyncPhase? = when (value) {
            "syncing" -> Syncing
            "synced" -> UpToDate
            "failed" -> Failed
            else -> null
        }
    }
}
