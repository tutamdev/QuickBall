package io.github.tutamdev.miniball

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import android.widget.Toast

/**
 * The three actions, implemented with public APIs only (no Accessibility):
 *  - Volume: AudioManager.adjustStreamVolume (no permission needed).
 *  - Lock:   DevicePolicyManager.lockNow() with a device admin that only has
 *            the "force-lock" policy, OR launching a lock app the user picked
 *            (e.g. Huawei's own "Khoá màn hình" shortcut).
 */
object Actions {
    private const val TAG = "MiniBall"

    fun run(context: Context, action: String) {
        when (action) {
            Prefs.ACTION_VOL_UP -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            Prefs.ACTION_VOL_DOWN -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            Prefs.ACTION_LOCK -> lock(context)
        }
    }

    private fun adjustVolume(context: Context, direction: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val stream = if (Prefs(context).stream == "ring") AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
        try {
            // FLAG_SHOW_UI shows the normal system volume slider as feedback.
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            // Changing the ringer out of Do Not Disturb needs notification-policy access; we don't ask for it.
            Log.w(TAG, "Volume change refused", e)
            Toast.makeText(context, R.string.volume_blocked_dnd, Toast.LENGTH_SHORT).show()
        }
    }

    fun adminComponent(context: Context) = ComponentName(context, LockAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(context)) == true

    private fun lock(context: Context) {
        val prefs = Prefs(context)
        val done = when (prefs.lockMode) {
            Prefs.LOCK_MODE_WIDGET -> LockMethods.clickWidget(context)
            Prefs.LOCK_MODE_SHORTCUT -> LockMethods.launchShortcut(context)
            Prefs.LOCK_MODE_APP -> launchLockApp(context, prefs.lockApp)
            else -> false
        }
        if (done) return
        if (prefs.lockMode != Prefs.LOCK_MODE_ADMIN) {
            Log.w(TAG, "Lock method ${prefs.lockMode} failed, falling back to device admin")
        }

        if (isAdminActive(context)) {
            try {
                context.getSystemService(DevicePolicyManager::class.java)?.lockNow()
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "lockNow refused", e)
            }
        }
        Toast.makeText(context, R.string.lock_not_configured, Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(context, LockSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun launchLockApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch lock app $packageName", e)
            false
        }
    }

    data class LauncherApp(val packageName: String, val label: String, val looksLikeLock: Boolean)

    private val LOCK_LABEL = Regex(
        "khóa màn hình|khoá màn hình|khoa man hinh|lock screen|screen lock|screenlock|锁屏",
        RegexOption.IGNORE_CASE
    )

    /** Launcher apps, with likely "lock screen" apps (e.g. Huawei's) listed first. */
    fun launcherApps(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
        return list.asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .map {
                val label = it.loadLabel(pm).toString()
                LauncherApp(it.packageName, label, LOCK_LABEL.containsMatchIn(label) || LOCK_LABEL.containsMatchIn(it.packageName))
            }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<LauncherApp> { it.looksLikeLock }.thenBy { it.label.lowercase() })
            .toList()
    }
}
