package io.github.chayanforyou.quickball.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import io.github.chayanforyou.quickball.core.QuickBallService

object PermissionUtils {

    /**
     * True if the user switched Quick Ball on in Accessibility settings.
     * Checks the real system setting first (updates immediately), then the list of
     * connected services (which lags on HarmonyOS while the service is binding).
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean =
        isEnabledInSecureSettings(context) || isAccessibilityServiceConnected(context)

    /** True only once the system has actually bound/connected the service. */
    fun isAccessibilityServiceConnected(context: Context): Boolean {
        val manager = getSystemService(context, AccessibilityManager::class.java)
        val services = runCatching {
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrNull() ?: return false
        return services.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun isEnabledInSecureSettings(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val globallyOn = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            val own = ComponentName(context, QuickBallService::class.java)
            val listed = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
                .split(':')
                .any { ComponentName.unflattenFromString(it) == own }
            globallyOn && listed
        } catch (_: Exception) {
            false
        }
    }

    fun canModifySystemSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else true
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val key = ComponentName(context.packageName, QuickBallService::class.java.name).flattenToString()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", key)
                putExtra(
                    ":settings:show_fragment_args",
                    bundleOf(":settings:fragment_args_key" to key)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    fun openSystemSettingsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
