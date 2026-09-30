/*
 * Adapted from QuickBall (FloatPanelView) by Chayan Mistry — GPL-3.0
 * https://github.com/chayanforyou/QuickBall
 */
package io.github.tutamdev.miniball.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen transparent layer that fans the action buttons out of the ball
 * (overshoot on expand, decelerate on collapse, small stagger) — the Quick Ball
 * look. Tapping anywhere else closes it.
 */
@SuppressLint("ViewConstructor")
class FanMenuView(
    context: Context,
    ballX: Int,
    ballY: Int,
    ballSize: Int,
    private val onRight: Boolean,
    private val items: List<Item>,
    private val onDismiss: () -> Unit,
    private val onDismissFinished: () -> Unit,
    private val onItemClick: (Item) -> Unit,
) : ViewGroup(context) {

    data class Item(val key: String, val iconRes: Int, val description: String)

    companion object {
        private const val START_ANGLE = 100f
        private const val SPAN_ANGLE = 160f
        private const val DURATION = 240L
        private const val STAGGER = 15L
        private val OVERSHOOT = OvershootInterpolator(0.9f)
        private val DECELERATE = DecelerateInterpolator(1.0f)
    }

    private val buttonSize = context.dp(53f)
    private val iconSize = context.dp(24f)

    // Up to 5 buttons on the inner ring, the rest on an outer ring.
    private val innerCount = if (items.size <= 5) items.size else 5
    private val outerCount = items.size - innerCount
    private val innerRadius = context.dp(if (items.size <= 3) 88f else 96f)
    private val outerRadius = context.dp(158f)

    // Items fly out of the ball centre; the fan itself is kept on screen.
    private val ballCenterX = ballX + ballSize / 2
    private val ballCenterY = ballY + ballSize / 2
    private val centerX = ballCenterX
    private val centerY: Int = run {
        val h = context.resources.displayMetrics.heightPixels
        val reach = (if (outerCount > 0) outerRadius else innerRadius) + buttonSize / 2 + context.dp(8f)
        if (h > reach * 2) ballCenterY.coerceIn(reach, h - reach) else ballCenterY
    }
    private val itemViews = ArrayList<FrameLayout>(items.size)
    private var collapsing = false
    private var dismissed = false

    init {
        clipChildren = false
        clipToPadding = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> { v.performClick(); onDismiss(); true }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> { onDismiss(); true }
                else -> false
            }
        }
        items.forEachIndexed { index, item ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xBF2C2C2C.toInt())
            }
            val view = FrameLayout(context).apply {
                background = bg
                foreground = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, bg)
                contentDescription = item.description
                addView(ImageView(context).apply {
                    setImageResource(item.iconRes)
                    setColorFilter(Color.WHITE)
                }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
                setOnClickListener { if (!collapsing) onItemClick(item) }
            }
            val (sx, sy) = startTranslation(index)
            view.translationX = sx
            view.translationY = sy
            view.scaleX = 0f; view.scaleY = 0f; view.alpha = 0f
            addView(view, LayoutParams(buttonSize, buttonSize))
            itemViews.add(view)
        }
    }

    /** Offset of item [index] from the fan centre, fanning away from the screen edge. */
    private fun offset(index: Int): Pair<Float, Float> {
        val inner = index < innerCount
        val count = if (inner) innerCount else outerCount
        val pos = if (inner) index else index - innerCount
        val r = if (inner) innerRadius else outerRadius
        val angle = if (count == 1) START_ANGLE + SPAN_ANGLE / 2
        else START_ANGLE + SPAN_ANGLE / (count - 1) * pos
        val rad = Math.toRadians(angle.toDouble())
        val x = (cos(rad) * r).toFloat()
        val y = (-sin(rad) * r).toFloat()
        return (if (onRight) x else -x) to y
    }

    /** Translation that places item [index] exactly on the ball (collapsed state). */
    private fun startTranslation(index: Int): Pair<Float, Float> {
        val (ox, oy) = offset(index)
        return (ballCenterX - (centerX + ox)) to (ballCenterY - (centerY + oy))
    }

    fun animateExpand() {
        collapsing = false; dismissed = false
        val last = itemViews.size - 1
        itemViews.forEachIndexed { i, v ->
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(DURATION).setStartDelay((last - i) * STAGGER)
                .setInterpolator(OVERSHOOT).start()
        }
    }

    fun animateCollapse() {
        if (collapsing) return
        collapsing = true
        val last = itemViews.size - 1
        itemViews.forEachIndexed { i, v ->
            val (sx, sy) = startTranslation(i)
            val a = v.animate().translationX(sx).translationY(sy).scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(DURATION).setStartDelay((last - i) * STAGGER).setInterpolator(DECELERATE)
            if (i == 0) a.withEndAction { if (!dismissed) { dismissed = true; onDismissFinished() } }
            a.start()
        }
        if (itemViews.isEmpty() && !dismissed) { dismissed = true; onDismissFinished() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildren(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        itemViews.forEachIndexed { i, child ->
            val (ox, oy) = offset(i)
            val cx = (centerX + ox).toInt()
            val cy = (centerY + oy).toInt()
            val hw = child.measuredWidth / 2
            val hh = child.measuredHeight / 2
            child.layout(cx - hw, cy - hh, cx + hw, cy + hh)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        itemViews.forEach { it.animate().cancel() }
    }
}
