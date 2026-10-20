package dev.opengarmin.app.settings

import android.content.Context
import android.content.SharedPreferences
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
    var heightCm by mutableStateOf(
        prefs.getInt(KEY_HEIGHT_CM, 0).takeIf { it in 100..250 },
    )
        private set
    var weightKg by mutableStateOf(
        prefs.getFloat(KEY_WEIGHT_KG, 0f).takeIf { it in 30f..300f }?.toDouble(),
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
    var periodicSyncEnabled by mutableStateOf(
        prefs.getBoolean(KEY_PERIODIC_ENABLED, dev.opengarmin.app.sync.SyncPolicy.DEFAULT_ENABLED),
    )
        private set
    var periodicSyncIntervalMinutes by mutableIntStateOf(
        dev.opengarmin.app.sync.SyncPolicy.normalizeInterval(
            prefs.getInt(KEY_PERIODIC_INTERVAL, dev.opengarmin.app.sync.SyncPolicy.DEFAULT_INTERVAL_MINUTES),
        ),
    )
        private set
    var periodicSyncChargingOnly by mutableStateOf(
        prefs.getBoolean(KEY_PERIODIC_CHARGING_ONLY, dev.opengarmin.app.sync.SyncPolicy.DEFAULT_CHARGING_ONLY),
    )
        private set
    var lastSyncAttemptAt by mutableStateOf(
        prefs.getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0L },
    )
        private set
    var lastSyncDurationMs by mutableStateOf(
        prefs.getLong(KEY_LAST_DURATION, 0L).takeIf { it > 0L },
    )
        private set
    var lastSyncTrigger by mutableStateOf(prefs.getString(KEY_LAST_TRIGGER, "") ?: "")
        private set
    var lastSyncKind by mutableStateOf(prefs.getString(KEY_LAST_KIND, "") ?: "")
        private set

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        applyPersistedValue(key)
    }

    init {
        // A WorkManager worker owns its own PulseSettings instance in the same
        // process. Listening to prefs keeps the visible activity in sync with
        // background attempts, results, duration and schedule changes.
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun applyPersistedValue(key: String?) {
        when (key) {
            KEY_HEIGHT_CM -> heightCm = prefs.getInt(KEY_HEIGHT_CM, 0).takeIf { it in 100..250 }
            KEY_WEIGHT_KG -> weightKg = prefs.getFloat(KEY_WEIGHT_KG, 0f)
                .takeIf { it in 30f..300f }?.toDouble()
            KEY_LAST_SYNC -> lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }
            KEY_LAST_STATUS -> lastSyncStatus = prefs.getString(KEY_LAST_STATUS, "") ?: ""
            KEY_LAST_ERROR -> lastSyncError = prefs.getString(KEY_LAST_ERROR, "") ?: ""
            KEY_LAST_ATTEMPT -> lastSyncAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0L }
            KEY_LAST_DURATION -> lastSyncDurationMs = prefs.getLong(KEY_LAST_DURATION, 0L).takeIf { it > 0L }
            KEY_LAST_TRIGGER -> lastSyncTrigger = prefs.getString(KEY_LAST_TRIGGER, "") ?: ""
            KEY_LAST_KIND -> lastSyncKind = prefs.getString(KEY_LAST_KIND, "") ?: ""
            KEY_PERIODIC_ENABLED -> periodicSyncEnabled = prefs.getBoolean(KEY_PERIODIC_ENABLED, false)
            KEY_PERIODIC_INTERVAL -> periodicSyncIntervalMinutes = dev.opengarmin.app.sync.SyncPolicy.normalizeInterval(
                prefs.getInt(KEY_PERIODIC_INTERVAL, dev.opengarmin.app.sync.SyncPolicy.DEFAULT_INTERVAL_MINUTES),
            )
            KEY_PERIODIC_CHARGING_ONLY -> periodicSyncChargingOnly = prefs.getBoolean(KEY_PERIODIC_CHARGING_ONLY, false)
        }
    }

    fun updatePeriodicSyncEnabled(value: Boolean) {
        periodicSyncEnabled = value
        prefs.edit().putBoolean(KEY_PERIODIC_ENABLED, value).apply()
    }

    fun updatePeriodicSyncInterval(minutes: Int) {
        val safe = dev.opengarmin.app.sync.SyncPolicy.normalizeInterval(minutes)
        periodicSyncIntervalMinutes = safe
        prefs.edit().putInt(KEY_PERIODIC_INTERVAL, safe).apply()
    }

    fun updatePeriodicSyncChargingOnly(value: Boolean) {
        periodicSyncChargingOnly = value
        prefs.edit().putBoolean(KEY_PERIODIC_CHARGING_ONLY, value).apply()
    }

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

    fun updateHeightCm(value: Int?) {
        if (value == null) {
            heightCm = null
            prefs.edit().remove(KEY_HEIGHT_CM).apply()
        } else {
            val safe = value.coerceIn(100, 250)
            heightCm = safe
            prefs.edit().putInt(KEY_HEIGHT_CM, safe).apply()
        }
    }

    fun updateWeightKg(value: Double?) {
        if (value == null) {
            weightKg = null
            prefs.edit().remove(KEY_WEIGHT_KG).apply()
        } else {
            val safe = value.coerceIn(30.0, 300.0)
            weightKg = safe
            prefs.edit().putFloat(KEY_WEIGHT_KG, safe.toFloat()).apply()
        }
    }

    fun updateWatchName(name: String) {
        if (name.isBlank()) return
        watchName = name
        prefs.edit().putString(KEY_WATCH_NAME, name).apply()
    }

    fun recordSyncSuccess(durationMs: Long? = null, trigger: String = lastSyncTrigger) {
        val now = System.currentTimeMillis()
        lastSyncAt = now
        lastSyncStatus = "synced"
        lastSyncError = ""
        lastSyncDurationMs = durationMs
        lastSyncTrigger = trigger
        lastSyncKind = "success"
        prefs.edit()
            .putLong(KEY_LAST_SYNC, now)
            .putString(KEY_LAST_STATUS, "synced")
            .remove(KEY_LAST_ERROR)
            .putString(KEY_LAST_TRIGGER, trigger)
            .putString(KEY_LAST_KIND, "success")
            .apply {
                if (durationMs != null) putLong(KEY_LAST_DURATION, durationMs)
            }
            .apply()
    }

    fun recordSyncStarted(trigger: String = lastSyncTrigger) {
        val now = System.currentTimeMillis()
        lastSyncStatus = "syncing"
        lastSyncError = ""
        lastSyncAttemptAt = now
        lastSyncTrigger = trigger
        prefs.edit()
            .putString(KEY_LAST_STATUS, "syncing")
            .remove(KEY_LAST_ERROR)
            .putLong(KEY_LAST_ATTEMPT, now)
            .putString(KEY_LAST_TRIGGER, trigger)
            .apply()
    }

    fun recordSyncError(
        message: String,
        durationMs: Long? = null,
        trigger: String = lastSyncTrigger,
        kind: String = "transient",
    ) {
        lastSyncStatus = "failed"
        lastSyncError = message
        lastSyncDurationMs = durationMs
        lastSyncTrigger = trigger
        lastSyncKind = kind
        prefs.edit()
            .putString(KEY_LAST_STATUS, "failed")
            .putString(KEY_LAST_ERROR, message.take(300))
            .putString(KEY_LAST_TRIGGER, trigger)
            .putString(KEY_LAST_KIND, kind)
            .apply {
                if (durationMs != null) putLong(KEY_LAST_DURATION, durationMs)
            }
            .apply()
    }

    companion object {
        const val DEFAULT_STEP_GOAL = 10_000
        private const val KEY_STEP_GOAL = "step_goal"
        private const val KEY_MAX_HR = "max_heart_rate"
        private const val KEY_HEIGHT_CM = "height_cm"
        private const val KEY_WEIGHT_KG = "weight_kg"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_STATUS = "last_sync_status"
        private const val KEY_LAST_ERROR = "last_sync_error"
        private const val KEY_WATCH_NAME = "watch_name"
        private const val KEY_PERIODIC_ENABLED = "periodic_sync_enabled"
        private const val KEY_PERIODIC_INTERVAL = "periodic_sync_interval"
        private const val KEY_PERIODIC_CHARGING_ONLY = "periodic_sync_charging_only"
        private const val KEY_LAST_ATTEMPT = "last_sync_attempt_at"
        private const val KEY_LAST_DURATION = "last_sync_duration_ms"
        private const val KEY_LAST_TRIGGER = "last_sync_trigger"
        private const val KEY_LAST_KIND = "last_sync_kind"
    }
}
