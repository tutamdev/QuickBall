package io.github.chayanforyou.quickball.core.persistence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.ui.MainActivity

/**
 * Tells the user when Quick Ball's accessibility service is off after a
 * reboot/update (typically because the OS force-stopped the app). Tapping it
 * opens Quick Ball, which shows the one-tap fix. No permission is faked.
 */
object ServiceStatusNotifier {
    private const val NOTIFICATION_ID = 1002

    fun notifyServiceOff(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        NotificationChannels.ensure(context)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.SERVICE_STATUS)
            .setSmallIcon(R.drawable.ic_menu_open)
            .setContentTitle(context.getString(R.string.service_off_notification_title))
            .setContentText(context.getString(R.string.service_off_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.service_off_notification_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
