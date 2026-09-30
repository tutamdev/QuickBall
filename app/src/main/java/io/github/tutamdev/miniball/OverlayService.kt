package io.github.tutamdev.miniball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tutamdev.miniball.ui.BallView
import io.github.tutamdev.miniball.ui.FanMenuView
import io.github.tutamdev.miniball.ui.PillView
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
        private const val STASH_DELAY_MS = 2500L
        private const val STASH_ALPHA = 0.4f

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

    // Windows
    private var ball: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var pill: PillView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var menu: FanMenuView? = null
    private var menuParams: WindowManager.LayoutParams? = null

    // State
    private var ballX = 0
    private var ballY = 0
    private var onRight = true
    private var isExpanded = false
    private var isStashed = false
    private var isStashing = false
    private var isDragging = false
    private var animator: ValueAnimator? = null

    private val stashRunnable = Runnable { if (!isDragging && !isExpanded) stash() }

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
        showBall()
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
        animator?.cancel()
        removeMenu()
        removePill()
        removeBall()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeMenu()
        reposition()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.SIZE_DP, Prefs.OPACITY -> rebuild()
            // Our own snapToEdge() also writes SIDE; only react to changes made in Settings.
            Prefs.SIDE -> if (prefs.onRight != onRight) { onRight = prefs.onRight; removeMenu(); reposition() }
            Prefs.STICK_TO_EDGE -> if (prefs.stickToEdge) resetTimer() else { stopTimer(); unstash(animated = false) }
        }
    }

    private fun rebuild() {
        removeMenu(); removePill(); removeBall()
        isStashed = false; isStashing = false
        showBall()
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

    /* ---------------- Geometry ---------------- */

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).roundToInt()

    private val ballSize get() = dp(prefs.sizeDp)
    private val edgePad get() = dp(6)
    private val verticalBound get() = dp(100)

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

    private fun edgeX(sw: Int = screenSize().first) = if (onRight) sw - ballSize - edgePad else edgePad

    private fun clampY(y: Int, sh: Int = screenSize().second): Int {
        val min = verticalBound
        val max = (sh - ballSize - verticalBound).coerceAtLeast(min)
        return y.coerceIn(min, max)
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun update(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null) return
        runCatching { wm.updateViewLayout(view, params) }
    }

    /* ---------------- Ball ---------------- */

    private fun showBall() {
        if (ball != null) return
        onRight = prefs.onRight
        val (sw, sh) = screenSize()
        ballX = edgeX(sw)
        ballY = clampY((prefs.yFraction * sh).roundToInt(), sh)

        val alphaByte = (prefs.opacity * 255).roundToInt().coerceIn(40, 255)
        val view = BallView(this, ballSize, Color.argb(alphaByte, 0x2C, 0x2C, 0x2C))
        val params = overlayParams(ballSize, ballSize).apply { x = ballX; y = ballY }

        var startX = 0
        var startY = 0
        var screenW = sw
        var screenH = sh
        view.listener = object : BallView.Listener {
            override fun onTouchDown() {
                if (isStashing) return
                stopTimer()
                animator?.cancel()
                if (!isStashed) { view.animate().cancel(); view.alpha = 1f }
                startX = ballX; startY = ballY
                val (w, h) = screenSize(); screenW = w; screenH = h
            }

            override fun onDragMove(dx: Float, dy: Float) {
                if (isStashing) return
                isDragging = true
                ballX = (startX + dx).roundToInt().coerceIn(0, screenW - ballSize)
                ballY = clampY((startY + dy).roundToInt(), screenH)
                params.x = ballX; params.y = ballY
                update(view, params)
            }

            override fun onDragEnd() {
                isDragging = false
                snapToEdge()
            }

            override fun onTap() {
                isDragging = false
                if (isStashed || isStashing) unstash()
                expandMenu()
            }
        }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "addView failed (overlay permission revoked?)", e)
            stopSelf()
            return
        }
        ball = view
        ballParams = params
        isStashed = false
        resetTimer()
    }

    private fun removeBall() {
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        ballParams = null
    }

    private fun snapToEdge() {
        val (sw, sh) = screenSize()
        onRight = ballX + ballSize / 2 > sw / 2
        prefs.savePosition(onRight, if (sh > 0) ballY.toFloat() / sh else 0.4f)
        val target = edgeX(sw)
        val duration = (abs(target - ballX).toFloat() / sw * 350f).coerceIn(180f, 320f).toLong()
        animateBallX(target, duration) { resetTimer() }
    }

    private fun reposition() {
        val (sw, sh) = screenSize()
        ballX = edgeX(sw)
        ballY = clampY((prefs.yFraction * sh).roundToInt(), sh)
        ballParams?.let { it.x = ballX; it.y = ballY; update(ball, it) }
        if (isStashed) { removePill(); showPill() }
    }

    private fun animateBallX(target: Int, duration: Long, alpha: Float? = null, onEnd: (() -> Unit)? = null) {
        val view = ball ?: return
        val params = ballParams ?: return
        val startAlpha = view.alpha
        animator?.cancel()
        var cancelled = false
        animator = ValueAnimator.ofInt(ballX, target).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                ballX = it.animatedValue as Int
                params.x = ballX
                update(view, params)
                if (alpha != null) view.alpha = startAlpha + (alpha - startAlpha) * it.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd?.invoke() }
            })
            start()
        }
    }

    /* ---------------- Stash to edge (Quick Ball style) ---------------- */

    private fun resetTimer() {
        handler.removeCallbacks(stashRunnable)
        if (!isExpanded && !isStashed) handler.postDelayed(stashRunnable, STASH_DELAY_MS)
    }

    private fun stopTimer() = handler.removeCallbacks(stashRunnable)

    private fun stash() {
        if (isExpanded || isStashed) return
        val view = ball ?: return
        if (!prefs.stickToEdge) {
            // Just fade out a little, like Quick Ball without "stick to edge".
            view.animate().alpha(STASH_ALPHA + 0.2f).setDuration(250).start()
            return
        }
        val (sw, _) = screenSize()
        val target = if (onRight) sw else -ballSize
        isStashing = true
        animateBallX(target, 250L, alpha = STASH_ALPHA) {
            isStashing = false
            isStashed = true
            // Hide the off-screen ball completely so it can never block touches at the edge.
            view.visibility = View.INVISIBLE
            ballParams?.let {
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                update(view, it)
            }
            showPill()
        }
    }

    private fun unstash(animated: Boolean = true) {
        val view = ball ?: return
        val params = ballParams ?: return
        view.animate().cancel()
        if (!isStashed && !isStashing) {
            view.alpha = 1f
            return
        }
        removePill()
        isStashing = false
        isStashed = false
        view.visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val (sw, _) = screenSize()
        val target = edgeX(sw)
        if (animated) {
            ballX = if (onRight) sw - ballSize else 0
            params.x = ballX
            update(view, params)
            animateBallX(target, 250L, alpha = 1f) { resetTimer() }
        } else {
            animator?.cancel()
            ballX = target; params.x = target; view.alpha = 1f
            update(view, params)
            resetTimer()
        }
    }

    private fun showPill() {
        if (pill != null) return
        val (sw, _) = screenSize()
        val w = dp(25)
        val h = dp(48)
        val view = PillView(this, onRight, 0xCC777777.toInt()).apply {
            onTap = {
                removePill()
                unstash()
                expandMenu()
            }
        }
        val params = overlayParams(w, h).apply {
            x = if (onRight) sw - w else 0
            y = ballY + (ballSize - h) / 2
        }
        try {
            wm.addView(view, params)
            pill = view
            pillParams = params
        } catch (e: Exception) {
            Log.w(TAG, "pill addView failed", e)
        }
    }

    private fun removePill() {
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null
        pillParams = null
    }

    /* ---------------- Fan menu ---------------- */

    private fun menuItems(): List<FanMenuView.Item> = prefs.actions.map {
        when (it) {
            Prefs.ACTION_VOL_UP -> FanMenuView.Item(it, R.drawable.ic_volume_up, getString(R.string.action_vol_up))
            Prefs.ACTION_VOL_DOWN -> FanMenuView.Item(it, R.drawable.ic_volume_down, getString(R.string.action_vol_down))
            else -> FanMenuView.Item(it, R.drawable.ic_lock, getString(R.string.action_lock))
        }
    }

    private fun expandMenu() {
        if (menu != null) return
        val items = menuItems()
        if (items.isEmpty()) return
        isExpanded = true
        stopTimer()
        ball?.setExpanded(true)

        val (sw, _) = screenSize()
        val view = FanMenuView(
            context = this,
            ballX = edgeX(sw),
            ballY = ballY,
            ballSize = ballSize,
            onRight = onRight,
            items = items,
            onDismiss = { collapseMenu() },
            onDismissFinished = {
                removeMenu()
                resetTimer()
            },
            onItemClick = { item -> onMenuItem(item.key) }
        )
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        try {
            wm.addView(view, params)
            menu = view
            menuParams = params
            view.animateExpand()
        } catch (e: Exception) {
            Log.w(TAG, "menu addView failed", e)
            isExpanded = false
            ball?.setExpanded(false)
        }
    }

    private fun collapseMenu() {
        isExpanded = false
        ball?.setExpanded(false)
        val view = menu ?: return
        val params = menuParams ?: return
        // Let touches reach the app below while the collapse animation runs.
        // Android 12+ only passes touches through overlays with alpha <= 0.8.
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.alpha = 0.8f
        update(view, params)
        view.animateCollapse()
    }

    private fun removeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        menuParams = null
        if (isExpanded) {
            isExpanded = false
            ball?.setExpanded(false, animate = false)
        }
    }

    private fun onMenuItem(key: String) {
        if (key == Prefs.ACTION_LOCK) {
            collapseMenu()
            // Let the menu disappear before the screen turns off.
            handler.postDelayed({ Actions.run(this, key) }, 280)
        } else {
            Actions.run(this, key) // menu stays open for repeated volume taps
        }
    }
}
