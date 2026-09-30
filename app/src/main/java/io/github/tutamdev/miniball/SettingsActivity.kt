package io.github.tutamdev.miniball

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // Starting a foreground service is always allowed while this screen is visible.
        OverlayService.sync(this)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<SwitchPreferenceCompat>(Prefs.ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
                val ctx = requireContext()
                if (newValue == true && !OverlayService.canDraw(ctx)) {
                    startActivity(Intent(ctx, PermissionsActivity::class.java))
                    return@setOnPreferenceChangeListener false
                }
                // Apply after the value is saved.
                view?.post { OverlayService.sync(ctx) }
                true
            }

            findPreference<Preference>("permissions")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PermissionsActivity::class.java))
                true
            }

            findPreference<Preference>("reset_position")?.setOnPreferenceClickListener {
                Prefs(requireContext()).sp.edit {
                    putString(Prefs.SIDE, "right")
                    putFloat(Prefs.Y_FRACTION, 0.4f)
                }
                OverlayService.stop(requireContext())
                OverlayService.sync(requireContext())
                true
            }

            findPreference<ListPreference>(Prefs.LOCK_MODE)?.setOnPreferenceChangeListener { _, newValue ->
                findPreference<ListPreference>(Prefs.LOCK_APP)?.isVisible = newValue == Prefs.LOCK_MODE_APP
                true
            }
        }

        override fun onResume() {
            super.onResume()
            val ctx = requireContext()
            val prefs = Prefs(ctx)

            // Lock app list is built at runtime; likely "lock screen" apps come first.
            findPreference<ListPreference>(Prefs.LOCK_APP)?.apply {
                val apps = Actions.launcherApps(ctx)
                entries = apps.map { if (it.looksLikeLock) "🔒 ${it.label}" else it.label }.toTypedArray()
                entryValues = apps.map { it.packageName }.toTypedArray()
                isVisible = prefs.lockMode == Prefs.LOCK_MODE_APP
                if (value.isNullOrEmpty()) apps.firstOrNull { it.looksLikeLock }?.let { value = it.packageName }
            }

            findPreference<Preference>("permissions")?.summary = getString(
                if (OverlayService.canDraw(ctx)) R.string.permissions_summary_ok
                else R.string.permissions_summary_missing
            )
        }
    }
}
