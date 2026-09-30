package io.github.chayanforyou.quickball.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import io.github.chayanforyou.quickball.doki.AutoStartPermissionHelper
import java.util.Locale

/**
 * Detects Huawei / HarmonyOS and opens the matching system pages.
 * Nothing here changes any setting — Huawei's App launch / background rules
 * cannot be changed by third-party apps, so we only guide the user there.
 */
object HuaweiHelper {

    val isHuawei: Boolean by lazy {
        val m = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val b = Build.BRAND.lowercase(Locale.ROOT)
        m.contains("huawei") || b.contains("huawei") || m.contains("honor") || b.contains("honor")
    }

    /** HarmonyOS reports itself through a Huawei framework class; read-only reflection. */
    val isHarmonyOs: Boolean by lazy {
        runCatching {
            @SuppressLint("PrivateApi")
            val c = Class.forName("com.huawei.system.BuildEx")
            (c.getMethod("getOsBrand").invoke(c) as? String)?.contains("harmony", ignoreCase = true) == true
        }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    @SuppressLint("BatteryLife")
    fun openBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return openAppDetails(context)
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${context.packageName}".toUri())
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (!tryStart(context, direct) && !tryStart(context, list)) openAppDetails(context)
    }

    /** Huawei "App launch" (Khởi chạy ứng dụng) page, falls back to App info. */
    fun openAppLaunchSettings(context: Context) {
        val opened = runCatching {
            AutoStartPermissionHelper.getAutoStartPermission(context, open = true, newTask = true)
        }.getOrDefault(false)
        if (!opened) openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        tryStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
        )
    }

    fun openNotificationSettings(context: Context) {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else null
        if (i == null || !tryStart(context, i)) openAppDetails(context)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
