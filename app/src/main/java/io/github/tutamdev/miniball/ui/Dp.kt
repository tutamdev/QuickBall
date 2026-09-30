package io.github.tutamdev.miniball.ui

import android.content.Context
import android.util.TypedValue
import kotlin.math.roundToInt

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()
