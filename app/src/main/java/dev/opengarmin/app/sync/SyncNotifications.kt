package dev.opengarmin.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.opengarmin.app.MainActivity
import dev.opengarmin.app.R

/** One updated-in-place notification for the watch sync and activity import. */
object SyncNotifications {
    const val CHANNEL_ID = "watch_sync"
    const val NOTIFICATION_ID = 1001
    private const val FINAL_NOTIFICATION_ID = 1002
    private const val CHANNEL_NAME = "Watch sync"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Watch sync and activity import progress"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** Starts (or reuses) the foreground service that hosts the notification. */
    fun start(context: Context) {
        ensureChannel(context)
        NotificationManagerCompat.from(context).cancel(FINAL_NOTIFICATION_ID)
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncForegroundService::class.java),
            )
        } catch (_: Exception) {
            // The notification can still be posted without a foreground service.
        }
    }

    fun show(
        context: Context,
        title: String,
        status: String,
        detail: String?,
        progress: ActivitySyncProgress?,
        ongoing: Boolean,
    ) {
        ensureChannel(context)
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            build(context, title, status, detail, progress, ongoing),
        )
    }

    /** Leaves a dismissible completion notification and stops the foreground service. */
    fun finish(context: Context, title: String, status: String, detail: String?) {
        ensureChannel(context)
        // Use a separate id so stopping the foreground service cannot remove the
        // final summary together with its progress notification.
        NotificationManagerCompat.from(context).notify(
            FINAL_NOTIFICATION_ID,
            build(context, title, status, detail, progress = null, ongoing = false),
        )
        context.stopService(Intent(context, SyncForegroundService::class.java))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun cancel(context: Context) {
        context.stopService(Intent(context, SyncForegroundService::class.java))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(FINAL_NOTIFICATION_ID)
    }

    fun build(
        context: Context,
        title: String,
        status: String,
        detail: String?,
        progress: ActivitySyncProgress?,
        ongoing: Boolean,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (detail != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        }
        if (progress != null && progress.total > 0) {
            builder.setProgress(progress.total, progress.done, false)
            builder.setSubText("${progress.done} of ${progress.total}")
        } else if (ongoing) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
