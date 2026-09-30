package io.github.tutamdev.miniball

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
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

            findPreference<Preference>("lock_setup")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LockSetupActivity::class.java))
                true
            }
        }

        override fun onResume() {
            super.onResume()
            val ctx = requireContext()
            val prefs = Prefs(ctx)

            findPreference<Preference>("lock_setup")?.summary = when (prefs.lockMode) {
                Prefs.LOCK_MODE_WIDGET -> getString(R.string.lock_current_widget, prefs.lockLabel)
                Prefs.LOCK_MODE_SHORTCUT, Prefs.LOCK_MODE_ACTIVITY -> getString(R.string.lock_current_shortcut, prefs.lockLabel)
                else -> getString(if (Actions.isAdminActive(ctx)) R.string.lock_current_admin else R.string.lock_current_none)
            }

            findPreference<Preference>("permissions")?.summary = getString(
                if (OverlayService.canDraw(ctx)) R.string.permissions_summary_ok
                else R.string.permissions_summary_missing
            )
        }
    }
}
