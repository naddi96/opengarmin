package dev.opengarmin.app.sync

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.opengarmin.app.diagnostics.Diagnostics

/**
 * Keeps the process alive while the watch sync runs and hosts
 * [SyncNotifications]. The BLE session and coordinator still live in
 * [dev.opengarmin.app.MainActivity]; this service only holds the foreground
 * notification.
 */
class SyncForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = SyncNotifications.build(
            context = this,
            title = "Syncing watch",
            status = "Waiting for the watch…",
            detail = null,
            progress = null,
            ongoing = true,
        )
        try {
            ServiceCompat.startForeground(
                this,
                SyncNotifications.NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        } catch (error: Exception) {
            Diagnostics.record(
                area = Diagnostics.AREA_SYNC,
                code = "FGS_START_FAILED",
                message = "The sync foreground service could not start.",
                throwable = error,
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The completion summary uses a separate notification id.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
