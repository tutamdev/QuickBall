/*
 * Adapted from QuickBall (SideKickView) by Chayan Mistry — GPL-3.0
 * https://github.com/chayanforyou/QuickBall
 */
package io.github.tutamdev.miniball.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Thin curved handle shown on the screen edge while the ball is stashed. Tap to bring the ball back. */
@SuppressLint("ViewConstructor")
class PillView(context: Context, private val onRight: Boolean, color: Int) : View(context) {

    var onTap: (() -> Unit)? = null

    private val strokePx = context.dp(3f).toFloat()
    private val visibleWidthPx = context.dp(10f).toFloat()
    private val arcIndent = 40f
    private val sweep = 180f - 2f * arcIndent
    private val rect = RectF()
    private var startAngle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokePx
        this.color = color
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = width.toFloat()
        val h = height.toFloat()
        val half = strokePx / 2f
        if (onRight) {
            rect.set(w - visibleWidthPx, half, w + visibleWidthPx, h - half)
            startAngle = 90f + arcIndent
        } else {
            rect.set(-visibleWidthPx, half, visibleWidthPx, h - half)
            startAngle = 270f + arcIndent
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(rect, startAngle, sweep, false, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_UP ->
                if (abs(event.x - downX) < slop * 3 && abs(event.y - downY) < slop * 3) onTap?.invoke()
        }
        return true
    }
}
