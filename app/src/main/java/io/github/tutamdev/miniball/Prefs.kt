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

        const val ACTION_VOL_UP = "vol_up"
        const val ACTION_VOL_DOWN = "vol_down"
        const val ACTION_LOCK = "lock"
        val DEFAULT_ACTIONS = setOf(ACTION_VOL_UP, ACTION_VOL_DOWN, ACTION_LOCK)
        /** Menu order is fixed so the selection only controls visibility. */
        val ACTION_ORDER = listOf(ACTION_VOL_UP, ACTION_VOL_DOWN, ACTION_LOCK)

        const val LOCK_MODE_ADMIN = "admin"
        const val LOCK_MODE_APP = "app"
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

    fun savePosition(onRight: Boolean, yFraction: Float) = sp.edit {
        putString(SIDE, if (onRight) "right" else "left")
        putFloat(Y_FRACTION, yFraction)
    }
}
