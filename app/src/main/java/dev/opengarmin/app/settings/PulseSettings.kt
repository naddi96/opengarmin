package dev.opengarmin.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Local-only user preferences. Health data lives in Room; this holds the small
 * profile values the mockups need (goal, zone reference) and sync metadata.
 */
class PulseSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulse_settings", Context.MODE_PRIVATE)

    var stepGoal by mutableIntStateOf(prefs.getInt(KEY_STEP_GOAL, DEFAULT_STEP_GOAL))
        private set
    var maxHeartRate by mutableStateOf(
        prefs.getInt(KEY_MAX_HR, 0).takeIf { it in 100..250 },
    )
        private set
    var lastSyncAt by mutableStateOf(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L })
        private set
    var lastSyncStatus by mutableStateOf(prefs.getString(KEY_LAST_STATUS, "") ?: "")
        private set
    var lastSyncError by mutableStateOf(prefs.getString(KEY_LAST_ERROR, "") ?: "")
        private set
    var watchName by mutableStateOf(prefs.getString(KEY_WATCH_NAME, "") ?: "")
        private set

    fun updateStepGoal(value: Int) {
        val safe = value.coerceIn(1_000, 100_000)
        stepGoal = safe
        prefs.edit().putInt(KEY_STEP_GOAL, safe).apply()
    }

    fun updateMaxHeartRate(value: Int?) {
        if (value == null) {
            maxHeartRate = null
            prefs.edit().remove(KEY_MAX_HR).apply()
        } else {
            val safe = value.coerceIn(100, 250)
            maxHeartRate = safe
            prefs.edit().putInt(KEY_MAX_HR, safe).apply()
        }
    }

    fun updateWatchName(name: String) {
        if (name.isBlank()) return
        watchName = name
        prefs.edit().putString(KEY_WATCH_NAME, name).apply()
    }

    fun recordSyncSuccess() {
        val now = System.currentTimeMillis()
        lastSyncAt = now
        lastSyncStatus = "synced"
        lastSyncError = ""
        prefs.edit()
            .putLong(KEY_LAST_SYNC, now)
            .putString(KEY_LAST_STATUS, "synced")
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordSyncStarted() {
        lastSyncStatus = "syncing"
        lastSyncError = ""
        prefs.edit().putString(KEY_LAST_STATUS, "syncing").remove(KEY_LAST_ERROR).apply()
    }

    fun recordSyncError(message: String) {
        lastSyncStatus = "failed"
        lastSyncError = message
        prefs.edit()
            .putString(KEY_LAST_STATUS, "failed")
            .putString(KEY_LAST_ERROR, message.take(300))
            .apply()
    }

    companion object {
        const val DEFAULT_STEP_GOAL = 10_000
        private const val KEY_STEP_GOAL = "step_goal"
        private const val KEY_MAX_HR = "max_heart_rate"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_STATUS = "last_sync_status"
        private const val KEY_LAST_ERROR = "last_sync_error"
        private const val KEY_WATCH_NAME = "watch_name"
    }
}
