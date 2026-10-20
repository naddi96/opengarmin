package dev.opengarmin.app.util

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

fun formatDate(unixSeconds: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

fun formatWellnessDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}.getOrDefault(date)

fun formatDistanceKm(meters: Double): String = "%.2f km".format(meters / 1000.0)

/** Elapsed clock: `m:ss`, or `h:mm:ss` once the duration reaches an hour. */
fun formatElapsedTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

/** Pace with its unit, or an em dash when the activity has no distance. */
fun formatPaceWithUnit(durationSeconds: Double, distanceMeters: Double): String {
    if (distanceMeters <= 0.0) return "—"
    val secondsPerKm = durationSeconds / (distanceMeters / 1000.0)
    val total = secondsPerKm.toLong()
    return "%d:%02d /km".format(total / 60, total % 60)
}

/** Pace without a unit, used in compact labels and chart axes. */
fun formatPacePerKm(secondsPerKm: Int): String {
    val safe = secondsPerKm.coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, safe / 60, safe % 60)
}

/** Hours and whole minutes, used for sleep and active-time summaries. */
fun formatHoursMinutes(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
