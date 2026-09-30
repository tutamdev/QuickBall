package io.github.chayanforyou.quickball.core.persistence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.github.chayanforyou.quickball.R

object NotificationChannels {
    const val KEEP_ALIVE = "keep_alive"
    const val SERVICE_STATUS = "service_status"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                KEEP_ALIVE,
                context.getString(R.string.channel_keep_alive),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE_STATUS,
                context.getString(R.string.channel_service_status),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(false) }
        )
    }
}
