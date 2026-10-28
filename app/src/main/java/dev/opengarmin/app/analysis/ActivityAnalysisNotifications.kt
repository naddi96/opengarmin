package dev.opengarmin.app.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.opengarmin.app.MainActivity
import dev.opengarmin.app.R
import dev.opengarmin.app.coach.sportLabel
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.i18n.localized
import dev.opengarmin.app.util.formatDate
import dev.opengarmin.app.util.formatDistanceKm

/** Dismissible "analysis ready" notification, one per activity. */
object ActivityAnalysisNotifications {
    const val CHANNEL_ID = "activity_analysis"
    const val EXTRA_ACTIVITY_ID = "open_activity_id"
    private const val CHANNEL_NAME = "Activity analysis"
    private const val NOTIFICATION_ID_BASE = 2_000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.localized(CHANNEL_NAME),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.localized("Tells you when an AI activity analysis is ready")
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyReady(context: Context, activity: ActivityEntity, content: String) {
        ensureChannel(context)
        val notificationId = notificationId(activity.id)
        val openApp = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_ACTIVITY_ID, activity.id)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = listOf(
            context.localized(sportLabel(activity.sport)),
            formatDistanceKm(activity.distanceMeters),
            formatDate(activity.startTime),
        ).joinToString(" · ")
        val plain = content
            .replace(Regex("[#*_`>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_analysis_notification)
            .setContentTitle(context.localized("AI analysis ready"))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(System.currentTimeMillis())
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    fun cancel(context: Context, activityId: Long) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(activityId))
        }
    }

    private fun notificationId(activityId: Long): Int =
        NOTIFICATION_ID_BASE + (activityId % 100_000L).toInt()
}
