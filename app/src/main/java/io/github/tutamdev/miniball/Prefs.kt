package io.github.tutamdev.miniball

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** All configuration lives in the default SharedPreferences (shared with the Settings screen). */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    companion object {
        const val ENABLED = "enabled"
        const val AUTO_START = "auto_start"
        const val SIZE_DP = "size_dp"
        const val OPACITY = "opacity"
        const val SIDE = "side"
        const val Y_FRACTION = "y_fraction"
        const val ACTIONS = "actions"
        const val LOCK_MODE = "lock_mode"
        const val LOCK_APP = "lock_app"
        const val STREAM = "stream"
        const val STICK_TO_EDGE = "stick_to_edge"
        const val STASH_DELAY = "stash_delay"

        const val ACTION_VOL_UP = "vol_up"
        const val ACTION_VOL_DOWN = "vol_down"
        const val ACTION_LOCK = "lock"
        const val ACTION_VOL_PANEL = "vol_panel"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_TORCH = "torch"
        const val ACTION_RINGER = "ringer"
        const val ACTION_BRIGHT_UP = "bright_up"
        const val ACTION_BRIGHT_DOWN = "bright_down"
        const val ACTION_ROTATE = "rotate"
        const val ACTION_HOME = "home"
        const val ACTION_CAMERA = "camera"
        const val ACTION_WIFI = "wifi"
        val DEFAULT_ACTIONS = setOf(ACTION_VOL_UP, ACTION_VOL_DOWN, ACTION_LOCK)
        /** Menu order is fixed so the selection only controls visibility. */
        val ACTION_ORDER = listOf(
            ACTION_VOL_UP, ACTION_VOL_DOWN, ACTION_LOCK, ACTION_VOL_PANEL,
            ACTION_PLAY_PAUSE, ACTION_PREV, ACTION_NEXT, ACTION_TORCH, ACTION_RINGER,
            ACTION_BRIGHT_UP, ACTION_BRIGHT_DOWN, ACTION_ROTATE, ACTION_HOME, ACTION_CAMERA, ACTION_WIFI,
        )
        /** Actions that keep the menu open so they can be tapped repeatedly. */
        val REPEATABLE = setOf(ACTION_VOL_UP, ACTION_VOL_DOWN, ACTION_BRIGHT_UP, ACTION_BRIGHT_DOWN)

        const val LOCK_MODE_ADMIN = "admin"
        const val LOCK_MODE_APP = "app"
        const val LOCK_MODE_WIDGET = "widget"
        const val LOCK_MODE_SHORTCUT = "shortcut"
        const val LOCK_MODE_ACTIVITY = "activity"
    }

    val enabled get() = sp.getBoolean(ENABLED, true)
    val autoStart get() = sp.getBoolean(AUTO_START, true)
    val sizeDp get() = sp.getInt(SIZE_DP, 48).coerceIn(32, 80)
    val opacity get() = sp.getInt(OPACITY, 80).coerceIn(20, 100) / 100f
    val onRight get() = sp.getString(SIDE, "right") != "left"
    val yFraction get() = sp.getFloat(Y_FRACTION, 0.4f).coerceIn(0f, 1f)
    val actions: List<String>
        get() {
            val selected = sp.getStringSet(ACTIONS, DEFAULT_ACTIONS) ?: DEFAULT_ACTIONS
            return ACTION_ORDER.filter { it in selected }
        }
    val lockMode get() = sp.getString(LOCK_MODE, LOCK_MODE_ADMIN) ?: LOCK_MODE_ADMIN
    val lockApp get() = sp.getString(LOCK_APP, "").orEmpty()
    val stream get() = sp.getString(STREAM, "music") ?: "music"
    val stickToEdge get() = sp.getBoolean(STICK_TO_EDGE, true)
    val stashDelayMs get() = sp.getInt(STASH_DELAY, 3).coerceIn(1, 30) * 1000L
    val lockLabel get() = sp.getString(LockMethods.KEY_LABEL, "").orEmpty()

    fun savePosition(onRight: Boolean, yFraction: Float) = sp.edit {
        putString(SIDE, if (onRight) "right" else "left")
        putFloat(Y_FRACTION, yFraction)
    }
}
