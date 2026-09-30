package io.github.tutamdev.miniball

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * All actions use public APIs only — no Accessibility, no root, no key injection.
 */
object Actions {
    private const val TAG = "MiniBall"

    fun run(context: Context, action: String) {
        when (action) {
            Prefs.ACTION_VOL_UP -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            Prefs.ACTION_VOL_DOWN -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            Prefs.ACTION_VOL_PANEL -> adjustVolume(context, AudioManager.ADJUST_SAME)
            Prefs.ACTION_LOCK -> lock(context)
            Prefs.ACTION_PLAY_PAUSE -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Prefs.ACTION_NEXT -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            Prefs.ACTION_PREV -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Prefs.ACTION_TORCH -> Torch.toggle(context)
            Prefs.ACTION_RINGER -> toggleRinger(context)
            Prefs.ACTION_BRIGHT_UP -> changeBrightness(context, +1)
            Prefs.ACTION_BRIGHT_DOWN -> changeBrightness(context, -1)
            Prefs.ACTION_ROTATE -> toggleRotate(context)
            Prefs.ACTION_HOME -> start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            Prefs.ACTION_CAMERA -> start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            Prefs.ACTION_WIFI -> start(
                context,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
                else Intent(Settings.ACTION_WIFI_SETTINGS)
            )
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "start failed: $intent", e)
            toast(context, context.getString(R.string.lock_failed))
        }
    }

    /* ---------------- Sound ---------------- */

    private fun adjustVolume(context: Context, direction: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val stream = if (Prefs(context).stream == "ring") AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
        try {
            // FLAG_SHOW_UI shows the normal system volume slider as feedback.
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            // Changing the ringer out of Do Not Disturb needs notification-policy access; we don't ask for it.
            Log.w(TAG, "Volume change refused", e)
            toast(context, context.getString(R.string.volume_blocked_dnd))
        }
    }

    private fun mediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun toggleRinger(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val next = if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE
        else AudioManager.RINGER_MODE_NORMAL
        try {
            am.ringerMode = next
            toast(context, context.getString(if (next == AudioManager.RINGER_MODE_NORMAL) R.string.ringer_sound else R.string.ringer_vibrate))
        } catch (e: SecurityException) {
            toast(context, context.getString(R.string.volume_blocked_dnd))
        }
    }

    /* ---------------- Display (needs "Modify system settings") ---------------- */

    fun canWriteSettings(context: Context) = Settings.System.canWrite(context)

    private fun requestWriteSettings(context: Context) {
        toast(context, context.getString(R.string.need_write_settings))
        start(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()))
    }

    private fun changeBrightness(context: Context, direction: Int) {
        if (!canWriteSettings(context)) return requestWriteSettings(context)
        val cr = context.contentResolver
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
            // Perceptual steps: 10% on a square-root curve feels even to the eye.
            val percent = (sqrt(current / 255f) * 100).roundToInt()
            val nextPercent = ((percent / 10 + direction) * 10).coerceIn(0, 100)
            val value = ((nextPercent / 100f) * (nextPercent / 100f) * 255).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
            toast(context, context.getString(R.string.brightness_value, nextPercent))
        } catch (e: Exception) {
            Log.w(TAG, "brightness", e)
        }
    }

    private fun toggleRotate(context: Context) {
        if (!canWriteSettings(context)) return requestWriteSettings(context)
        val cr = context.contentResolver
        val on = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
        toast(context, context.getString(if (on) R.string.rotate_off else R.string.rotate_on))
    }

    /* ---------------- Torch ---------------- */

    private object Torch {
        private var registered = false
        private var enabled = false
        private var cameraId: String? = null

        fun toggle(context: Context) {
            val cm = context.getSystemService(CameraManager::class.java) ?: return
            try {
                if (cameraId == null) {
                    cameraId = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                }
                val id = cameraId ?: return toast(context, context.getString(R.string.torch_unavailable))
                if (!registered) {
                    cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(camId: String, on: Boolean) {
                            if (camId == cameraId) enabled = on
                        }
                    }, Handler(Looper.getMainLooper()))
                    registered = true
                }
                cm.setTorchMode(id, !enabled)
            } catch (e: Exception) {
                Log.w(TAG, "torch", e)
                toast(context, context.getString(R.string.torch_unavailable))
            }
        }
    }

    /* ---------------- Lock ---------------- */

    fun adminComponent(context: Context) = ComponentName(context, LockAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(context)) == true

    private fun lock(context: Context) {
        val prefs = Prefs(context)
        val done = when (prefs.lockMode) {
            Prefs.LOCK_MODE_WIDGET -> LockMethods.clickWidget(context)
            Prefs.LOCK_MODE_SHORTCUT -> LockMethods.launchShortcut(context)
            Prefs.LOCK_MODE_ACTIVITY -> LockMethods.launchActivity(context) == null
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
        toast(context, context.getString(R.string.lock_not_configured))
        start(context, Intent(context, LockSetupActivity::class.java))
    }
}
