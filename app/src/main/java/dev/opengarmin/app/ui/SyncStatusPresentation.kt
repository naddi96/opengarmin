package dev.opengarmin.app.ui

import androidx.compose.ui.graphics.Color
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.sync.SyncPhase
import dev.opengarmin.app.ui.theme.PulseColors

/** Label for the persistent sync chip, combining the live phase with stored history. */
fun syncStatusLabel(
    phase: SyncPhase,
    progress: ActivitySyncProgress?,
    settings: PulseSettings,
): String {
    val stored = SyncPhase.fromStored(settings.lastSyncStatus)
    val progressNumber = progress?.let { (it.done + 1).coerceAtMost(it.total) }
    return when {
        progress != null && progressNumber != null ->
            settings.localized("Syncing %d/%d", progressNumber, progress.total)
        phase == SyncPhase.Importing || phase == SyncPhase.Syncing ||
            stored == SyncPhase.Syncing -> settings.localized("Syncing…")
        phase == SyncPhase.Failed || stored == SyncPhase.Failed -> settings.localized("Sync failed")
        settings.lastSyncAt != null -> settings.localized("Synced %s", relativeSync(settings.lastSyncAt!!, settings))
        else -> settings.localized("Not synced")
    }
}

/** Colour for the persistent sync chip. */
fun syncStatusColor(
    phase: SyncPhase,
    progress: ActivitySyncProgress?,
    settings: PulseSettings,
): Color {
    val stored = SyncPhase.fromStored(settings.lastSyncStatus)
    return when {
        progress != null -> PulseColors.Orange
        phase == SyncPhase.Failed || stored == SyncPhase.Failed -> PulseColors.Coral
        phase == SyncPhase.Importing || phase == SyncPhase.Syncing ||
            stored == SyncPhase.Syncing -> PulseColors.Orange
        stored == SyncPhase.UpToDate -> PulseColors.Teal
        else -> PulseColors.TextMuted
    }
}

private fun relativeSync(timestamp: Long, settings: PulseSettings): String {
    val deltaSeconds = ((System.currentTimeMillis() - timestamp) / 1000).coerceAtLeast(0)
    return when {
        deltaSeconds < 60 -> settings.localized("just now")
        deltaSeconds < 3600 -> settings.localized("%d min ago", deltaSeconds / 60)
        deltaSeconds < 86_400 -> settings.localized("%d h ago", deltaSeconds / 3600)
        else -> settings.localized("%d d ago", deltaSeconds / 86_400)
    }
}
