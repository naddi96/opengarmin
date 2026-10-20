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

/**
 * Observable state shared by [SyncSessionController], [ActivityImporter] and
 * Compose. Writes happen on the main thread; [log] posts there when callers
 * arrive on a BLE/protocol thread.
 */
class SyncUiState(
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
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

    fun log(message: String) {
        Log.d("OpenGarmin", message)
        main.post {
            logs.add(0, message)
            if (logs.size > 300) logs.removeAt(logs.lastIndex)
        }
    }
}
