package io.github.tutamdev.miniball

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the floating button after reboot / app update when the user enabled
 * "Auto start". BOOT_COMPLETED is an official exemption for starting a
 * foreground service from the background. Android does not deliver it to an
 * app the user force-stopped, and we do not try to work around that.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        Log.i("MiniBall", "$action enabled=${prefs.enabled} autoStart=${prefs.autoStart}")
        if (prefs.enabled && prefs.autoStart) OverlayService.start(context)
    }
}
