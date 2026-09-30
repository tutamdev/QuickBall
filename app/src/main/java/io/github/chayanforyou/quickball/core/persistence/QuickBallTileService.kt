package io.github.chayanforyou.quickball.core.persistence

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.core.QuickBallService
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.ui.MainActivity
import io.github.chayanforyou.quickball.utils.PermissionUtils

/**
 * Quick Settings tile (official API 24+).
 *  - Service running → toggles the ball on/off.
 *  - Service off     → opens Quick Ball, which links to the Accessibility
 *                      settings page. The tile never changes that setting.
 */
class QuickBallTileService : TileService() {

    private val prefs by lazy { AppPreference.getInstance(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!PermissionUtils.isAccessibilityServiceEnabled(this)) {
            openApp()
        } else {
            val enable = !prefs.isQuickBallEnabled
            prefs.isQuickBallEnabled = enable
            QuickBallService.dispatch(
                this,
                if (enable) QuickBallService.ACTION_ENABLE else QuickBallService.ACTION_DISABLE
            )
        }
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val serviceOn = PermissionUtils.isAccessibilityServiceEnabled(this)
        tile.state = when {
            serviceOn && prefs.isQuickBallEnabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (serviceOn) R.string.tile_subtitle_running else R.string.tile_subtitle_service_off
            )
        }
        tile.updateTile()
    }
}
