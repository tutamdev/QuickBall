/*
 * Adapted from QuickBall (FloatTouchView) by Chayan Mistry — GPL-3.0
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
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.tutamdev.miniball.R
import kotlin.math.hypot

/** Circular floating ball: dark translucent circle, icon rotates into "×" when the fan menu is open. */
@SuppressLint("ViewConstructor")
class BallView(context: Context, sizePx: Int, bgColor: Int) : FrameLayout(context) {

    interface Listener {
        fun onTouchDown() {}
        fun onDragMove(dx: Float, dy: Float) {}
        fun onDragEnd() {}
        fun onTap() {}
    }

    var listener: Listener? = null
    var isExpanded = false
        private set

    private val imageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_menu_open)
        setColorFilter(Color.WHITE)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }
        background = bg
        foreground = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, bg)
        val icon = (sizePx - context.dp(16f)).coerceAtLeast(context.dp(12f))
        addView(imageView, LayoutParams(icon, icon, Gravity.CENTER))
        isClickable = true
    }

    fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        isExpanded = expanded
        val rotation = if (expanded) 180f else 0f
        if (!animate) {
            imageView.rotation = rotation
            imageView.setImageResource(if (expanded) R.drawable.ic_menu_close else R.drawable.ic_menu_open)
            return
        }
        if (expanded) imageView.setImageResource(R.drawable.ic_menu_close)
        imageView.animate().rotation(rotation).setDuration(300L).withEndAction {
            if (!isExpanded) imageView.setImageResource(R.drawable.ic_menu_open)
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY; dragging = false
                listener?.onTouchDown()
                drawableHotspotChanged(event.x, event.y)
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (!dragging && !isExpanded && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    isPressed = false
                }
                if (dragging) listener?.onDragMove(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) listener?.onDragEnd() else listener?.onTap()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (dragging) listener?.onDragEnd()
                dragging = false
            }
        }
        return true
    }
}
