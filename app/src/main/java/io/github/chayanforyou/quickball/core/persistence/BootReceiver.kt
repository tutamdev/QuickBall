package io.github.chayanforyou.quickball.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.utils.PermissionUtils

/**
 * After reboot / app update the system rebinds an *enabled* accessibility
 * service by itself — nothing is needed for the normal case.
 *
 * This receiver only handles the failure case: if the service ended up
 * disabled (e.g. force-stopped before the reboot), it posts a notification
 * so the user can re-enable it in one tap. It never changes the setting.
 * If enabled, it also starts the optional keep-alive notification.
 *
 * Note: Android does not deliver BOOT_COMPLETED to an app that is still in
 * the "force-stopped" state; that case is covered by the in-app status
 * screen and the Quick Settings tile.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val app = context.applicationContext
        // Give the system time to bind enabled accessibility services first.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (!AppPreference.getInstance(app).isOnboardingCompleted) return@postDelayed
                val enabled = PermissionUtils.isAccessibilityServiceEnabled(app)
                Log.i("BootReceiver", "$action → accessibility enabled=$enabled")
                if (enabled) {
                    // BOOT_COMPLETED is an allowed context for starting an FGS.
                    KeepAliveService.syncWithPreference(app)
                } else {
                    ServiceStatusNotifier.notifyServiceOff(app)
                }
            } finally {
                pending.finish()
            }
        }, 8_000L)
    }
}
