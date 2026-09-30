package io.github.tutamdev.miniball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Why a foreground service:
 * An overlay window lives only as long as the process that added it. Since
 * Android 8, a plain background service is stopped about a minute after the
 * app leaves the foreground, and the process becomes killable. A foreground
 * service (with its required notification) is the standard, documented way
 * to keep a user-visible component running. Type "specialUse" because no
 * predefined FGS type describes a floating button.
 *
 * Restart policy: START_STICKY lets the system recreate the service after it
 * kills it for memory; the system applies its own back-off. We never schedule
 * our own restarts (no alarms/loops), and if the overlay permission is missing
 * or the button is disabled the service stops with START_NOT_STICKY.
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "MiniBall"
        private const val CHANNEL_ID = "floating_button"
        private const val NOTIFICATION_ID = 1
        private const val MENU_AUTO_CLOSE_MS = 4000L

        fun canDraw(context: Context) = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!canDraw(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** Start or stop according to the saved configuration. */
        fun sync(context: Context) {
            val prefs = Prefs(context)
            if (prefs.enabled && canDraw(context)) start(context) else stop(context)
        }
    }

    private val prefs by lazy { Prefs(this) }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null
    private var lastOutsideCloseAt = 0L
    private var bubbleDownAt = 0L

    private val closeMenuRunnable = Runnable { hideMenu() }

    /* ---------------- Lifecycle ---------------- */

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the startForegroundService() contract before anything else.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!prefs.enabled || !canDraw(this)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_STICKY
    }

    /**
     * Called when the user swipes the settings screen away from Recents. The
     * foreground service keeps running on AOSP, so nothing to do. (On HarmonyOS
     * the system may still kill the whole app unless it is locked in Recents.)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; service keeps running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        hideMenu()
        removeBubble()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideMenu()
        placeBubbleFromPrefs()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.SIZE_DP, Prefs.OPACITY -> { hideMenu(); removeBubble(); showBubble() }
            Prefs.SIDE -> { hideMenu(); placeBubbleFromPrefs() }
            Prefs.ACTIONS -> hideMenu()
        }
    }

    /* ---------------- Notification ---------------- */

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /* ---------------- Geometry helpers ---------------- */

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).roundToInt()

    private val bubbleSizePx get() = dp(prefs.sizeDp)
    private val edgeMarginPx get() = dp(4)

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /* ---------------- Bubble ---------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = bubbleSizePx
        val view = BubbleView(this).apply { alpha = prefs.opacity }
        val params = overlayParams(size, size)
        bubble = view
        bubbleParams = params

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    bubbleDownAt = System.currentTimeMillis()
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    view.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        hideMenu()
                    }
                    if (dragging) {
                        val (sw, sh) = screenSize()
                        params.x = (startX + dx).roundToInt().coerceIn(0, sw - size)
                        params.y = (startY + dy).roundToInt().coerceIn(0, sh - size)
                        updateLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.alpha = prefs.opacity
                    if (dragging) snapToEdge() else onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = prefs.opacity
                    if (dragging) snapToEdge()
                }
            }
            true
        }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            // Overlay permission revoked while running.
            Log.w(TAG, "addView failed", e)
            bubble = null
            stopSelf()
            return
        }
        placeBubbleFromPrefs()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    private fun placeBubbleFromPrefs() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val (sw, sh) = screenSize()
        val size = bubbleSizePx
        params.x = if (prefs.onRight) sw - size - edgeMarginPx else edgeMarginPx
        params.y = (prefs.yFraction * sh).roundToInt().coerceIn(0, (sh - size).coerceAtLeast(0))
        updateLayout(view, params)
    }

    private fun snapToEdge() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val (sw, sh) = screenSize()
        val size = bubbleSizePx
        val onRight = params.x + size / 2 > sw / 2
        val targetX = if (onRight) sw - size - edgeMarginPx else edgeMarginPx
        prefs.savePosition(onRight, if (sh > 0) params.y.toFloat() / sh else 0.4f)

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateLayout(view, params)
            }
            start()
        }
    }

    private fun onBubbleTap() {
        // A tap on the bubble also counts as an "outside" touch for the menu window;
        // don't immediately reopen a menu that was just closed by that same tap.
        if (lastOutsideCloseAt >= bubbleDownAt - 150) return
        if (menu != null) hideMenu() else showMenu()
    }

    /* ---------------- Menu ---------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val bParams = bubbleParams ?: return
        val actions = prefs.actions
        if (actions.isEmpty()) return

        val itemSize = dp(48)
        val pad = dp(6)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.argb(230, 32, 32, 36))
            }
        }
        for (action in actions) {
            panel.addView(ImageButton(this).apply {
                setImageResource(
                    when (action) {
                        Prefs.ACTION_VOL_UP -> R.drawable.ic_volume_up
                        Prefs.ACTION_VOL_DOWN -> R.drawable.ic_volume_down
                        else -> R.drawable.ic_lock
                    }
                )
                contentDescription = getString(
                    when (action) {
                        Prefs.ACTION_VOL_UP -> R.string.action_vol_up
                        Prefs.ACTION_VOL_DOWN -> R.string.action_vol_down
                        else -> R.string.action_lock
                    }
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
                setColorFilter(Color.WHITE)
                setOnClickListener { onMenuAction(action) }
            }, LinearLayout.LayoutParams(itemSize, itemSize))
        }

        val width = itemSize * actions.size + pad * 2
        val height = itemSize + pad * 2
        val (sw, sh) = screenSize()
        val bSize = bubbleSizePx
        val onRight = bParams.x + bSize / 2 > sw / 2
        val params = overlayParams(
            width, height,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        ).apply {
            x = if (onRight) bParams.x - width - dp(8) else bParams.x + bSize + dp(8)
            x = x.coerceIn(0, (sw - width).coerceAtLeast(0))
            y = (bParams.y + bSize / 2 - height / 2).coerceIn(0, (sh - height).coerceAtLeast(0))
        }

        panel.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                lastOutsideCloseAt = System.currentTimeMillis()
                hideMenu()
                true
            } else false
        }

        try {
            wm.addView(panel, params)
            menu = panel
            menuParams = params
            scheduleMenuClose()
        } catch (e: Exception) {
            Log.w(TAG, "Menu addView failed", e)
        }
    }

    private fun hideMenu() {
        handler.removeCallbacks(closeMenuRunnable)
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        menuParams = null
    }

    private fun scheduleMenuClose() {
        handler.removeCallbacks(closeMenuRunnable)
        handler.postDelayed(closeMenuRunnable, MENU_AUTO_CLOSE_MS)
    }

    private fun onMenuAction(action: String) {
        if (action == Prefs.ACTION_LOCK) {
            hideMenu()
            // Let the menu disappear before the screen turns off.
            handler.postDelayed({ Actions.run(this, action) }, 150)
        } else {
            Actions.run(this, action)
            scheduleMenuClose() // keep the menu open for repeated volume taps
        }
    }

    private fun updateLayout(view: View, params: WindowManager.LayoutParams) {
        runCatching { wm.updateViewLayout(view, params) }
    }

    /* ---------------- Bubble drawing ---------------- */

    private class BubbleView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 30, 34) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val r = c
            canvas.drawCircle(c, c, r, fill)
            ring.strokeWidth = r * 0.10f
            canvas.drawCircle(c, c, r * 0.62f, ring)
            canvas.drawCircle(c, c, r * 0.32f, dot)
        }
    }
}
