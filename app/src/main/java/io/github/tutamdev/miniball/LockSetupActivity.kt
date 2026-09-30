package io.github.tutamdev.miniball

import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import io.github.tutamdev.miniball.ui.dp

/**
 * Lets the user choose how the Lock button works. Everything is done with
 * public APIs and explicit user consent (widget bind dialog, device admin dialog).
 */
class LockSetupActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val awm by lazy { AppWidgetManager.getInstance(this) }
    private val host by lazy { LockMethods.host(this) }

    private var pendingWidgetId = -1
    private var pendingLabel = ""

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) afterWidgetBound() else cancelPendingWidget()
    }

    private val configureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) finishWidget() else cancelPendingWidget()
    }

    private val shortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION")
        val intent = r.data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        @Suppress("DEPRECATION")
        val name = r.data?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: pendingLabel
        if (r.resultCode == RESULT_OK && intent != null) {
            LockMethods.saveShortcut(this, intent, name)
            toast(getString(R.string.lock_saved, name))
        } else if (r.resultCode == RESULT_OK) {
            toast(getString(R.string.lock_shortcut_unsupported))
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.lock_setup_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(24f))
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() {
        super.onResume()
        render()
    }

    /* ---------------- UI ---------------- */

    private fun render() {
        list.removeAllViews()
        val prefs = Prefs(this)

        val current = when (prefs.lockMode) {
            Prefs.LOCK_MODE_WIDGET -> if (LockMethods.isWidgetReady(this))
                getString(R.string.lock_current_widget, prefs.lockLabel) else getString(R.string.lock_current_widget_missing)
            Prefs.LOCK_MODE_SHORTCUT -> getString(R.string.lock_current_shortcut, prefs.lockLabel)
            else -> if (Actions.isAdminActive(this)) getString(R.string.lock_current_admin)
            else getString(R.string.lock_current_none)
        }
        text(current, 16f, bold = true)
        button(getString(R.string.lock_test)) {
            toast(getString(R.string.lock_test_hint))
            Handler(Looper.getMainLooper()).postDelayed({ Actions.run(applicationContext, Prefs.ACTION_LOCK) }, 2000)
        }

        // 1. Widgets
        header(getString(R.string.lock_widget_header))
        text(getString(R.string.lock_widget_detail), 13f, dim = true)
        val widgets = LockMethods.widgetProviders(this)
        if (widgets.isEmpty()) text(getString(R.string.lock_widget_none), 14f)
        val likely = widgets.filter { it.likely }
        val others = widgets.filterNot { it.likely }
        likely.forEach { choiceRow("🔒 ${it.label}", it.appLabel) { pickWidget(it.info, it.label) } }
        if (others.isNotEmpty()) {
            var expanded = likely.isEmpty()
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun fill() {
                container.removeAllViews()
                if (expanded) others.forEach { w ->
                    container.addView(makeChoiceRow(w.label, w.appLabel) { pickWidget(w.info, w.label) })
                }
            }
            button(getString(R.string.lock_widget_show_all, others.size)) { expanded = !expanded; fill() }
            list.addView(container)
            fill()
        }

        // 2. Shortcuts
        val shortcuts = LockMethods.shortcutProviders(this)
        if (shortcuts.isNotEmpty()) {
            header(getString(R.string.lock_shortcut_header))
            text(getString(R.string.lock_shortcut_detail), 13f, dim = true)
            shortcuts.forEach { ri ->
                val label = ri.loadLabel(packageManager).toString()
                val app = ri.activityInfo.applicationInfo.loadLabel(packageManager).toString()
                val mark = if (LockMethods.looksLikeLock(label)) "🔒 " else ""
                choiceRow(mark + label, app) {
                    pendingLabel = label
                    try {
                        shortcutLauncher.launch(
                            Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(LockMethods.shortcutComponent(ri))
                        )
                    } catch (e: Exception) {
                        toast(getString(R.string.lock_failed))
                    }
                }
            }
        }

        // 3. Device admin
        header(getString(R.string.lock_admin_header))
        text(getString(R.string.lock_admin_detail), 13f, dim = true)
        val adminOn = Actions.isAdminActive(this)
        button(getString(if (adminOn) R.string.lock_admin_use else R.string.lock_admin_enable)) {
            if (adminOn) {
                prefs.sp.edit { putString(Prefs.LOCK_MODE, Prefs.LOCK_MODE_ADMIN) }
                render()
            } else {
                prefs.sp.edit { putString(Prefs.LOCK_MODE, Prefs.LOCK_MODE_ADMIN) }
                try {
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Actions.adminComponent(this))
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
                    )
                } catch (_: Exception) {
                    toast(getString(R.string.lock_failed))
                }
            }
        }
    }

    /* ---------------- Widget binding ---------------- */

    private fun pickWidget(info: AppWidgetProviderInfo, label: String) {
        cancelPendingWidget()
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        pendingLabel = label
        val allowed = try {
            awm.bindAppWidgetIdIfAllowed(id, info.provider)
        } catch (e: Exception) {
            Log.w("MiniBall", "bindAppWidgetIdIfAllowed", e); false
        }
        if (allowed) {
            afterWidgetBound()
        } else {
            // System consent dialog: "Allow MiniBall to create widgets and access their data?"
            try {
                bindLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                )
            } catch (e: Exception) {
                cancelPendingWidget()
                toast(getString(R.string.lock_widget_bind_failed))
            }
        }
    }

    private fun afterWidgetBound() {
        val info = awm.getAppWidgetInfo(pendingWidgetId)
        if (info == null) { cancelPendingWidget(); toast(getString(R.string.lock_widget_bind_failed)); return }
        if (info.configure != null) {
            try {
                configureLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                )
                return
            } catch (_: Exception) {
                // Not launchable by third parties; the widget may still work without configuration.
            }
        }
        finishWidget()
    }

    private fun finishWidget() {
        LockMethods.saveWidget(this, pendingWidgetId, pendingLabel)
        host.startListening() // let the provider push its first RemoteViews
        toast(getString(R.string.lock_saved, pendingLabel))
        pendingWidgetId = -1
        render()
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) runCatching { host.deleteAppWidgetId(pendingWidgetId) }
        pendingWidgetId = -1
    }

    /* ---------------- Small view helpers ---------------- */

    private fun header(t: String) = text(t, 17f, bold = true, top = 20f)

    private fun text(t: String, size: Float, bold: Boolean = false, dim: Boolean = false, top: Float = 6f) {
        list.addView(TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (dim) alpha = 0.75f
            setPadding(0, dp(top), 0, dp(4f))
        })
    }

    private fun button(t: String, onClick: () -> Unit) {
        list.addView(Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun choiceRow(title: String, subtitle: String, onClick: () -> Unit) =
        list.addView(makeChoiceRow(title, subtitle, onClick))

    private fun makeChoiceRow(title: String, subtitle: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            isClickable = true
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
            addView(TextView(context).apply { text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) })
            addView(TextView(context).apply { text = subtitle; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.6f })
        }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
