package com.aiattempt.debroid.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = android.view.Gravity.CENTER_VERTICAL
}

fun Context.monoText(value: String = "", sizeSp: Float = 11f): TextView = TextView(this).apply {
    typeface = Typeface.MONOSPACE
    textSize = sizeSp
    text = value
    setTextIsSelectable(true)
    setPadding(dpi(12), dpi(6), dpi(12), dpi(10))
}

fun Context.titleText(value: String): TextView = TextView(this).apply {
    text = value
    textSize = 14f
    setTypeface(Typeface.DEFAULT_BOLD)
    setPadding(dpi(14), dpi(12), dpi(14), dpi(4))
}

fun Context.actionButton(value: String): MaterialButton = MaterialButton(this).apply {
    text = value
    textSize = 11f
    isAllCaps = false
    minWidth = 0
    minimumWidth = 0
    setPadding(dpi(12), 0, dpi(12), 0)
    insetTop = 0
    insetBottom = 0
}

fun Context.card(build: LinearLayout.() -> Unit): MaterialCardView {
    val card = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dpi(10), dpi(5), dpi(10), dpi(5)) }
        radius = dpi(14).toFloat()
        cardElevation = dpi(1).toFloat()
    }
    val inner = column().apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        build()
    }
    card.addView(inner)
    return card
}

fun Context.spacer(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(heightDp))
}
