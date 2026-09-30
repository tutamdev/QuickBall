package io.github.tutamdev.miniball

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit

/**
 * Ways to lock the screen without Accessibility:
 *
 *  1. WIDGET  – host the phone's own "lock screen" widget (e.g. Huawei's
 *               "Khoá màn hình") with the official AppWidgetHost API, the same
 *               way a launcher does, and deliver a click to it. The system app
 *               then locks the screen its own way, so fingerprint/face unlock
 *               keeps working.
 *  2. SHORTCUT – a legacy launcher shortcut (ACTION_CREATE_SHORTCUT) chosen by the user.
 *  3. ADMIN   – DevicePolicyManager.lockNow(). Always works, but Android then
 *               requires the PIN/password for the next unlock.
 */
object LockMethods {
    private const val TAG = "MiniBall"
    const val HOST_ID = 0x4D42

    const val KEY_WIDGET_ID = "lock_widget_id"
    const val KEY_SHORTCUT_URI = "lock_shortcut_uri"
    const val KEY_LABEL = "lock_label"
    const val KEY_ACTIVITY = "lock_activity"

    private val LOCK_LABEL = Regex(
        "khóa màn hình|khoá màn hình|khoa man hinh|lock screen|screen lock|screenlock|\\block\\b|onetouchlock|一键锁屏|锁屏",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeLock(text: String) = LOCK_LABEL.containsMatchIn(text)

    fun host(context: Context) = AppWidgetHost(context.applicationContext, HOST_ID)

    /* ---------------- Widget ---------------- */

    data class WidgetChoice(val info: AppWidgetProviderInfo, val label: String, val appLabel: String, val likely: Boolean)

    fun widgetProviders(context: Context): List<WidgetChoice> {
        val pm = context.packageManager
        return AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName != context.packageName }
            .map { info ->
                val label = runCatching { info.loadLabel(pm) }.getOrNull() ?: info.provider.className
                val app = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                }.getOrDefault(info.provider.packageName)
                WidgetChoice(info, label, app, looksLikeLock(label) || looksLikeLock(info.provider.className))
            }
            .sortedWith(compareByDescending<WidgetChoice> { it.likely }.thenBy { it.label.lowercase() })
    }

    fun boundWidgetId(context: Context): Int = Prefs(context).sp.getInt(KEY_WIDGET_ID, -1)

    fun isWidgetReady(context: Context): Boolean {
        val id = boundWidgetId(context)
        return id != -1 && AppWidgetManager.getInstance(context).getAppWidgetInfo(id) != null
    }

    fun saveWidget(context: Context, id: Int, label: String) {
        val old = boundWidgetId(context)
        if (old != -1 && old != id) runCatching { host(context).deleteAppWidgetId(old) }
        Prefs(context).sp.edit {
            putInt(KEY_WIDGET_ID, id)
            putString(KEY_LABEL, label)
            putString(Prefs.LOCK_MODE, Prefs.LOCK_MODE_WIDGET)
        }
    }

    /**
     * Inflate the bound widget and click its first clickable view. The click goes
     * through the widget's own PendingIntent, exactly like tapping it on the home screen.
     */
    fun clickWidget(context: Context): Boolean {
        val id = boundWidgetId(context)
        if (id == -1) return false
        val awm = AppWidgetManager.getInstance(context)
        val info = awm.getAppWidgetInfo(id) ?: return false
        return try {
            val host = host(context)
            host.startListening()
            val view = host.createView(context.applicationContext, id, info)
            val target = findClickable(view)
            if (target == null) {
                Log.w(TAG, "Widget has no clickable view yet")
                false
            } else {
                target.performClick()
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Widget click failed", e)
            false
        }
    }

    private fun findClickable(view: View): View? {
        if (view.hasOnClickListeners()) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findClickable(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /* ---------------- Shortcut ---------------- */

    fun shortcutProviders(context: Context): List<ResolveInfo> {
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedWith(compareByDescending<ResolveInfo> {
                looksLikeLock(it.loadLabel(context.packageManager).toString())
            }.thenBy { it.loadLabel(context.packageManager).toString().lowercase() })
    }

    fun saveShortcut(context: Context, intent: Intent, label: String) {
        Prefs(context).sp.edit {
            putString(KEY_SHORTCUT_URI, intent.toUri(Intent.URI_INTENT_SCHEME))
            putString(KEY_LABEL, label)
            putString(Prefs.LOCK_MODE, Prefs.LOCK_MODE_SHORTCUT)
        }
    }

    fun launchShortcut(context: Context): Boolean {
        val uri = Prefs(context).sp.getString(KEY_SHORTCUT_URI, null) ?: return false
        return try {
            val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shortcut launch failed", e)
            false
        }
    }

    fun shortcutComponent(info: ResolveInfo) =
        ComponentName(info.activityInfo.packageName, info.activityInfo.name)

    /* ---------------- Direct activity ---------------- */

    data class ActivityChoice(val component: ComponentName, val label: String, val appLabel: String)

    private fun looksLikeLockClass(name: String): Boolean {
        val n = name.lowercase()
        if ("clock" in n || "unlock" in n || "block" in n) return false
        return listOf("lock", "keyguard", "screenoff", "sleep").any { it in n }
    }

    /**
     * A home-screen shortcut is just an *exported* activity that the launcher
     * starts. If the maker's "create shortcut" screen is reserved for its own
     * launcher, we can still start that public target activity directly.
     * Only exported activities without a required permission are listed.
     */
    fun directActivities(context: Context): List<ActivityChoice> {
        val pm = context.packageManager
        val packages = linkedSetOf<String>()
        shortcutProviders(context)
            .filter { looksLikeLock(it.loadLabel(pm).toString()) }
            .forEach { packages += it.activityInfo.packageName }
        widgetProviders(context).filter { it.likely }.forEach { packages += it.info.provider.packageName }

        val result = mutableListOf<ActivityChoice>()
        for (pkg in packages) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue
            val appLabel = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
            info.activities.orEmpty()
                .filter { it.exported && it.permission == null && it.enabled }
                .filter { looksLikeLockClass(it.name) || looksLikeLock(it.loadLabel(pm).toString()) }
                .forEach {
                    result += ActivityChoice(ComponentName(pkg, it.name), it.loadLabel(pm).toString(), appLabel)
                }
        }
        return result
    }

    fun saveActivity(context: Context, component: ComponentName, label: String) {
        Prefs(context).sp.edit {
            putString(KEY_ACTIVITY, component.flattenToString())
            putString(KEY_LABEL, label)
            putString(Prefs.LOCK_MODE, Prefs.LOCK_MODE_ACTIVITY)
        }
    }

    /** @return null on success, otherwise a short error description. */
    fun launchActivity(context: Context, component: ComponentName? = null): String? {
        val comp = component ?: Prefs(context).sp.getString(KEY_ACTIVITY, null)
            ?.let { ComponentName.unflattenFromString(it) } ?: return "not set"
        return try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(comp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            null
        } catch (e: Exception) {
            Log.w(TAG, "Direct launch failed: $comp", e)
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
