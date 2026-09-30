package io.github.tutamdev.miniball

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import kotlin.math.roundToInt

/**
 * Shows which permissions/settings are in place and opens the matching system
 * page. Nothing is changed automatically — the user grants everything by hand.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() {
        super.onResume()
        render()
        OverlayService.sync(this)
    }

    private fun render() {
        list.removeAllViews()

        row(
            title = getString(R.string.perm_overlay_title),
            detail = getString(R.string.perm_overlay_detail),
            ok = OverlayService.canDraw(this),
            required = true,
            actionText = getString(R.string.action_grant)
        ) {
            open(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
                fallbackToAppDetails = true
            )
        }

        val adminOn = Actions.isAdminActive(this)
        row(
            title = getString(R.string.perm_admin_title),
            detail = getString(R.string.perm_admin_detail),
            ok = adminOn,
            required = Prefs(this).lockMode == Prefs.LOCK_MODE_ADMIN,
            actionText = getString(if (adminOn) R.string.action_disable else R.string.action_grant)
        ) {
            if (adminOn) {
                getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Actions.adminComponent(this))
                list.postDelayed({ render() }, 500)
            } else {
                open(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Actions.adminComponent(this))
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation)),
                    fallbackToAppDetails = false
                )
            }
        }

        val writeOk = Actions.canWriteSettings(this)
        row(
            title = getString(R.string.perm_write_title),
            detail = getString(R.string.perm_write_detail),
            ok = writeOk,
            required = false,
            actionText = getString(R.string.action_open)
        ) {
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName".toUri()), fallbackToAppDetails = true)
        }

        val battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        row(
            title = getString(R.string.perm_battery_title),
            detail = getString(R.string.perm_battery_detail),
            ok = battery,
            required = false,
            actionText = getString(R.string.action_open)
        ) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), fallbackToAppDetails = true)
        }

        row(
            title = getString(R.string.perm_notif_title),
            detail = getString(R.string.perm_notif_detail),
            ok = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            required = false,
            actionText = getString(R.string.action_open)
        ) {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                fallbackToAppDetails = true
            )
        }

        row(
            title = getString(R.string.perm_launch_title),
            detail = getString(R.string.perm_launch_detail),
            ok = null,
            required = false,
            actionText = getString(R.string.action_open)
        ) { openAppLaunch() }

        list.addView(TextView(this).apply {
            text = getString(R.string.huawei_guide)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16), 0, 0)
            setLineSpacing(0f, 1.2f)
        })
    }

    private fun row(
        title: String,
        detail: String,
        ok: Boolean?,
        required: Boolean,
        actionText: String,
        onClick: () -> Unit,
    ) {
        val status = when (ok) {
            true -> "✅"
            false -> if (required) "❌" else "⚠️"
            null -> "ℹ️"
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "$status  $title" + if (required) " *" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.75f
            })
        }
        val button = Button(this).apply {
            text = actionText
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button)
        })
    }

    /** Huawei "App launch" page if present, otherwise the standard App info page. */
    private fun openAppLaunch() {
        val huawei = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        for ((pkg, cls) in huawei) {
            val i = Intent().setClassName(pkg, cls)
            if (i.resolveActivity(packageManager) != null && tryStart(i)) return
        }
        openAppDetails()
    }

    private fun open(intent: Intent, fallbackToAppDetails: Boolean) {
        if (!tryStart(intent) && fallbackToAppDetails) openAppDetails()
    }

    private fun openAppDetails() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent); true
    } catch (_: Exception) {
        false
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).roundToInt()

}
