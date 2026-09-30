package io.github.chayanforyou.quickball.core.persistence

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.ui.MainActivity

/**
 * OPTIONAL, off by default.
 *
 * The overlay lives inside the AccessibilityService, which the system already
 * binds with high priority, so this service is NOT required for Quick Ball to
 * work. On some Huawei/HarmonyOS builds the power manager is more lenient with
 * apps that show an ongoing foreground notification, so the user may opt in.
 *
 * It does nothing except hold a low-importance notification. It cannot and
 * does not prevent a force-stop from Recents/Settings.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 1001

        fun syncWithPreference(context: Context) {
            if (AppPreference.getInstance(context).isKeepAliveNotificationEnabled) start(context)
            else stop(context)
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }.onFailure {
                // Android 12+ may refuse a background FGS start; the UI retries when opened.
                Log.w(TAG, "Could not start keep-alive service: ${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        // Always satisfy the startForegroundService() contract first, otherwise
        // Android 12+ throws ForegroundServiceDidNotStartInTimeException.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AppPreference.getInstance(this).isKeepAliveNotificationEnabled) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        NotificationChannels.ensure(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NotificationChannels.KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_menu_open)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
