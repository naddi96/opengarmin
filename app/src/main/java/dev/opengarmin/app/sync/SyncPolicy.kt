package dev.opengarmin.app.sync

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure scheduling and failure-classification policy for background sync.
 * Keeping this Android-free makes the retry/interval behaviour unit-testable.
 */
object SyncPolicy {
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 6 * 60
    const val DEFAULT_INTERVAL_MINUTES = 60
    const val DEFAULT_ENABLED = false
    const val DEFAULT_CHARGING_ONLY = false

    /** WorkManager's minimum backoff is 10 minutes. */
    const val BACKOFF_MINUTES = 10L

    /**
     * A user-triggered one-time sync may retry a few times. Periodic work does
     * not use WorkManager backoff at all; its next interval is the retry.
     */
    const val MAX_ONE_TIME_RETRIES = 3

    val INTERVAL_OPTIONS = listOf(15, 30, 60, 180, 360)
    val INTERVAL_LABELS = listOf("15m", "30m", "1h", "3h", "6h")

    /** Battery-not-low is always required; charging is optional. */
    const val REQUIRES_BATTERY_NOT_LOW = true

    enum class FailureKind(val storageKey: String) {
        /** Watch out of range, disconnect, transfer timeout, slot busy. Retry with backoff. */
        TRANSIENT("transient"),

        /** Another sync owns the BLE connection/transfer slot. Retry later. */
        BUSY("busy"),

        /** Missing permission, no bonded watch, unsupported device. Show an action. */
        PERMANENT("permanent"),
    }

    fun normalizeInterval(minutes: Int): Int =
        minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun intervalLabel(minutes: Int): String {
        val safe = normalizeInterval(minutes)
        return when {
            safe % 60 == 0 -> "${safe / 60}h"
            else -> "${safe}m"
        }
    }

    fun optionIndex(minutes: Int): Int {
        val safe = normalizeInterval(minutes)
        val exact = INTERVAL_OPTIONS.indexOf(safe)
        if (exact >= 0) return exact
        // Pick the nearest option for a value written by a future version.
        return INTERVAL_OPTIONS.indices.minByOrNull { kotlin.math.abs(INTERVAL_OPTIONS[it] - safe) } ?: 0
    }

    fun shouldRetry(kind: FailureKind): Boolean = kind != FailureKind.PERMANENT

    /**
     * Whether this particular run should return [androidx.work.Result.retry].
     *
     * Periodic work must never enter WorkManager's backoff path: a transient
     * failure would delay the next 15-minute period by hours. It records the
     * failure and returns success so Android runs it again at the requested
     * interval. Only the explicit one-time request retries, with a small cap.
     */
    fun shouldRetryWork(oneTime: Boolean, kind: FailureKind, runAttemptCount: Int): Boolean =
        oneTime && shouldRetry(kind) && runAttemptCount < MAX_ONE_TIME_RETRIES

    /**
     * Classifies a failure from the existing sync messages. The messages are
     * deliberately matched by intent rather than by exact text so small wording
     * changes do not turn a permanent setup problem into an endless retry.
     */
    fun classifyFailure(
        message: String?,
        stalledBeforeCapabilities: Boolean = false,
    ): FailureKind {
        val text = message.orEmpty().lowercase(Locale.US)
        return when {
            "background sync" in text && "running" in text -> FailureKind.BUSY
            "already" in text && "sync" in text -> FailureKind.BUSY
            stalledBeforeCapabilities -> FailureKind.BUSY
            "garmin connect" in text || "single session" in text -> FailureKind.BUSY
            "no bonded" in text ||
                "not paired" in text ||
                "pair" in text && "watch" in text -> FailureKind.PERMANENT
            "permission" in text ||
                "bluetooth_connect" in text ||
                "bluetooth_scan" in text -> FailureKind.PERMANENT
            "not supported" in text || "unsupported" in text -> FailureKind.PERMANENT
            "no bluetooth" in text || "adapter" in text && "null" in text -> FailureKind.PERMANENT
            else -> FailureKind.TRANSIENT
        }
    }

    fun triggerLabel(trigger: String): String = when (trigger) {
        "launch" -> "App launch"
        "manual" -> "Manual"
        "background" -> "Background"
        "upload" -> "Watch upload"
        "reconnect" -> "Watch reconnect"
        else -> "Not recorded"
    }

    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return "—"
        val seconds = durationMs / 1000.0
        return when {
            seconds < 1.0 -> "${durationMs} ms"
            seconds < 60.0 -> String.format(Locale.US, "%.1f s", seconds)
            else -> {
                val whole = (seconds / 60.0).roundToLong()
                "$whole min"
            }
        }
    }
}
