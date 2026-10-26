package dev.opengarmin.app.sync

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.data.WorkoutSendStatus
import dev.opengarmin.app.diagnostics.SyncLogNoise

/**
 * Observable state shared by [SyncSessionController], [ActivityImporter] and
 * Compose. Writes happen on the main thread; [log] posts there when callers
 * arrive on a BLE/protocol thread.
 */
class SyncUiState(
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private companion object {
        /** Enough to keep several minutes of real protocol traffic around. */
        const val MAX_LOG_LINES = 2_000
    }

    var phase by mutableStateOf(SyncPhase.Idle)
    var activityProgress by mutableStateOf<ActivitySyncProgress?>(null)
    var lastImportedLabel by mutableStateOf<String?>(null)
    var workoutSendStatus by mutableStateOf<WorkoutSendStatus?>(null)
    var pacePlanSendStatus by mutableStateOf<PacePlanSendStatus?>(null)

    /** Activities imported during the current sync attempt. */
    var importedThisSync: Int = 0

    /** First import failure during the current sync attempt, if any. */
    var importErrorThisSync: String? = null

    val logs = mutableStateListOf<String>()
    private var suppressedKeepaliveLines = 0

    fun log(message: String) {
        Log.d("OpenGarmin", message)
        main.post {
            if (SyncLogNoise.isNoise(message)) {
                suppressedKeepaliveLines++
                // Keep a marker in the log so a silent stall is still visible.
                if (suppressedKeepaliveLines % 100 == 0) {
                    addLog("… $suppressedKeepaliveLines keepalive/ack lines suppressed")
                }
                return@post
            }
            if (suppressedKeepaliveLines >= 20) {
                addLog("… $suppressedKeepaliveLines keepalive/ack lines suppressed")
                suppressedKeepaliveLines = 0
            }
            addLog(message)
        }
    }

    private fun addLog(message: String) {
        logs.add(0, message)
        if (logs.size > MAX_LOG_LINES) logs.removeAt(logs.lastIndex)
    }
}
