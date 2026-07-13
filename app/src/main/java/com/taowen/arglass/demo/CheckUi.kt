package com.taowen.arglass.demo

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun Activity.checkContent(): LinearLayout {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(32))
    }
    setContentView(ScrollView(this).apply { addView(layout) })
    return layout
}

internal fun Activity.label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(0xff182033.toInt())
    if (bold) setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.START
}

internal fun Activity.margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
).apply {
    topMargin = dp(top)
    bottomMargin = dp(bottom)
}

internal fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
