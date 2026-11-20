// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.beesan.lfg.core.Box
import kotlin.math.abs

/**
 * The whole screen dimmed, to drag a box on. Once one is drawn, three choices
 * show under it: read it once, keep reading it (for subtitles and streams), or
 * cancel. The box is in display pixels, as the window fills the display.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class RegionPicker(context: Context, private val onDone: (Box?, Boolean) -> Unit) : FrameLayout(context) {
    private var startX = 0f
    private var startY = 0f
    private var box: Box? = null

    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val clear = Paint().apply { color = Color.argb(30, 255, 255, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f).toFloat()
        color = Ink.ACCENT
        pathEffect = DashPathEffect(floatArrayOf(context.dp(8f).toFloat(), context.dp(5f).toFloat()), 0f)
    }

    private val hint = TextView(context).apply {
        text = "Drag a box around the text to read"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(context.dp(16f), context.dp(14f), context.dp(16f), context.dp(14f))
    }

    private val choices = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = GONE
        addView(choice("Read once", true) { finish(false) })
        addView(choice("Keep reading", false) { finish(true) })
        addView(choice("Cancel", false) { onDone(null, false) })
    }

    init {
        setWillNotDraw(false)
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(choices, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun choice(label: String, primary: Boolean, onPress: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = context.dp(44f)
        setPadding(context.dp(16f), 0, context.dp(16f), 0)
        setTextColor(if (primary) Ink.ON_FILL else Ink.TEXT)
        background = rounded(if (primary) Ink.FILL else Ink.SURFACE, if (primary) Ink.FILL else Ink.BORDER_STRONG, context.dp(5f).toFloat())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = context.dp(8f)
        }
        setOnClickListener { onPress() }
    }

    private fun finish(keep: Boolean) {
        val chosen = box ?: return
        val at = IntArray(2).also(::getLocationOnScreen)
        onDone(chosen.moved(at[0], at[1]), keep)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                box = null
                choices.visibility = GONE
                hint.visibility = GONE
            }
            MotionEvent.ACTION_MOVE -> {
                box = Box(
                    minOf(startX, event.x).toInt(), minOf(startY, event.y).toInt(),
                    maxOf(startX, event.x).toInt(), maxOf(startY, event.y).toInt(),
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val b = box
                if (b == null || abs(b.width) < context.dp(24f) || abs(b.height) < context.dp(16f)) {
                    // A tap, not a drag: the box is too small to hold text.
                    box = null
                    hint.visibility = VISIBLE
                } else {
                    showChoices(b)
                }
                invalidate()
            }
        }
        return true
    }

    private fun showChoices(b: Box) {
        choices.visibility = VISIBLE
        choices.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val gap = context.dp(10f)
        val y = if (b.bottom + gap + choices.measuredHeight <= height) b.bottom + gap else (b.top - gap - choices.measuredHeight).coerceAtLeast(0)
        val x = b.left.coerceIn(0, (width - choices.measuredWidth).coerceAtLeast(0))
        (choices.layoutParams as LayoutParams).apply {
            leftMargin = x
            topMargin = y
            gravity = Gravity.TOP or Gravity.START
        }
        choices.requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val b = box
        if (b == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            return
        }
        // Dim around the box, not over it, so the text inside reads clearly.
        canvas.drawRect(0f, 0f, width.toFloat(), b.top.toFloat(), dim)
        canvas.drawRect(0f, b.bottom.toFloat(), width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(), dim)
        canvas.drawRect(b.right.toFloat(), b.top.toFloat(), width.toFloat(), b.bottom.toFloat(), dim)
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), clear)
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), edge)
    }
}
