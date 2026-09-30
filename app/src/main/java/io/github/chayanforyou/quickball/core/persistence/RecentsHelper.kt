package io.github.chayanforyou.quickball.core.persistence

import android.app.Activity
import android.app.ActivityManager
import androidx.core.content.getSystemService

/**
 * Uses the official ActivityManager.AppTask API to keep Quick Ball's settings
 * screen out of the Recents list. The floating ball does not need a task in
 * Recents; without a card there, "clear all" / swipe-away cannot force-stop it
 * by accident. It does not stop the user from closing the app in Settings.
 */
object RecentsHelper {
    fun apply(activity: Activity, exclude: Boolean) {
        runCatching {
            activity.getSystemService<ActivityManager>()?.appTasks?.forEach {
                it.setExcludeFromRecents(exclude)
            }
        }
    }
}
