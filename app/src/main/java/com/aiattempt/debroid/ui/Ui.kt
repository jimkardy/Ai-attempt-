package com.aiattempt.debroid.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aiattempt.debroid.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Shared design primitives so every screen looks like the same app:
 * black/red Material 3, 16 dp cards with a hairline outline, 48 dp touch
 * targets, one typography scale.
 */

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.hScroll(content: View): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    addView(content)
}

fun Context.spacer(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(heightDp))
}

/** Monospace log/console text. Not selectable: copying is the Copy button's job. */
fun Context.monoText(value: String = "", sizeSp: Float = 11.5f): TextView = TextView(this).apply {
    typeface = Typeface.MONOSPACE
    textSize = sizeSp
    text = value
    setTextColor(color(R.color.on_surface))
    setLineSpacing(0f, 1.15f)
    setTextIsSelectable(false)
    setPadding(dpi(12), dpi(8), dpi(12), dpi(10))
}

fun Context.titleText(value: String): TextView = TextView(this).apply {
    text = value
    textSize = 20f
    setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
    setTextColor(color(R.color.on_surface))
    setPadding(dpi(16), dpi(12), dpi(16), dpi(2))
}

fun Context.sectionText(value: String): TextView = TextView(this).apply {
    text = value.uppercase()
    textSize = 11f
    letterSpacing = 0.08f
    setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
    setTextColor(color(R.color.on_surface_2))
    setPadding(dpi(16), dpi(14), dpi(16), dpi(6))
}

fun Context.bodyText(value: String = "", sizeSp: Float = 13f): TextView = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color(R.color.on_surface_2))
    setPadding(dpi(16), dpi(2), dpi(16), dpi(8))
}

fun Context.captionText(value: String = ""): TextView = TextView(this).apply {
    text = value
    textSize = 11f
    setTextColor(color(R.color.on_surface_3))
    setPadding(dpi(16), 0, dpi(16), dpi(6))
}

private fun Context.buttonBase(text: String): MaterialButton = MaterialButton(this).apply {
    setText(text)
    isAllCaps = false
    textSize = 13f
    minWidth = 0
    minimumWidth = 0
    minHeight = dpi(48)
    cornerRadius = dpi(14)
    insetTop = 0
    insetBottom = 0
    setPadding(dpi(16), 0, dpi(16), 0)
    stateListAnimator = null
}

fun Context.primaryButton(text: String): MaterialButton = buttonBase(text).apply {
    backgroundTintList = ColorStateList.valueOf(color(R.color.red))
    setTextColor(color(R.color.on_red))
}

fun Context.tonalButton(text: String): MaterialButton = buttonBase(text).apply {
    backgroundTintList = ColorStateList.valueOf(color(R.color.surface_2))
    setTextColor(color(R.color.on_surface))
}

fun Context.outlinedButton(text: String): MaterialButton = buttonBase(text).apply {
    backgroundTintList = ColorStateList.valueOf(color(R.color.surface))
    setTextColor(color(R.color.on_surface))
    strokeWidth = dpi(1)
    setStrokeColor(ColorStateList.valueOf(color(R.color.outline)))
}

fun Context.dangerButton(text: String): MaterialButton = buttonBase(text).apply {
    backgroundTintList = ColorStateList.valueOf(color(R.color.surface))
    setTextColor(color(R.color.err))
    strokeWidth = dpi(1)
    setStrokeColor(ColorStateList.valueOf(color(R.color.err)))
}

/** Small status pill: `2 of 2`, `RUNNING`, `512 MB`. */
fun Context.pill(text: String, accent: Int = color(R.color.on_surface_2), filled: Boolean = false): TextView =
    TextView(this).apply {
        setText(text)
        textSize = 11f
        setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        letterSpacing = 0.04f
        setTextColor(if (filled) color(R.color.on_red) else accent)
        setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
        background = GradientDrawable().apply {
            cornerRadius = dpi(999).toFloat()
            setColor(if (filled) accent else color(R.color.surface_2))
            if (!filled) {
                setStroke(dpi(1), accent)
            }
        }
    }

fun Context.card(build: LinearLayout.() -> Unit): MaterialCardView {
    val cardView = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dpi(12), dpi(6), dpi(12), dpi(6)) }
        radius = dpi(16).toFloat()
        cardElevation = 0f
        strokeWidth = dpi(1)
        strokeColor = color(R.color.outline)
        setCardBackgroundColor(color(R.color.surface))
    }
    val inner = column().apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        build()
    }
    cardView.addView(inner)
    return cardView
}

fun Context.divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)).apply {
        setMargins(dpi(16), dpi(8), dpi(16), dpi(8))
    }
    setBackgroundColor(color(R.color.outline))
}

/** Row of action buttons with even spacing, used under cards. */
fun Context.actionRow(vararg buttons: View): LinearLayout = row().apply {
    setPadding(dpi(12), dpi(6), dpi(12), dpi(10))
    buttons.forEach { b ->
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dpi(3), 0, dpi(3), 0)
        }
        addView(b)
    }
}
