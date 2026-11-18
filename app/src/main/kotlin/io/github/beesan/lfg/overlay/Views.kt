// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.lookup.LookupState

/**
 * The app's colours as plain colour ints, for the Views drawn over the game.
 * [OverlayHost] sets [palette] from Settings and makes its views again when
 * it changes.
 */
internal object Ink {
    var palette: Palette = Palette.of(Palette.PINK, Palette.THEME_DARK, systemDark = true)

    val BG get() = palette.bg
    val BORDER get() = palette.border
    val BORDER_STRONG get() = palette.borderStrong
    val TEXT get() = palette.text
    val DIM get() = palette.dim
    val ACCENT get() = palette.accent
    val WASH get() = palette.wash
}

internal fun Context.dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

internal fun rounded(fill: Int, stroke: Int, radius: Float, strokeWidth: Int = 1) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, stroke)
}

internal fun Context.label(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = false
}

/**
 * The lines on screen as text, each character tappable, with the looked-up
 * word marked. This is the bottom-screen twin of the tap strips over the game.
 */
@SuppressLint("ViewConstructor")
/** More pieces of text than this on screen is a menu or a page, not a dialogue box. */
private const val MANY = 4

internal class LinesView(context: Context, private val onTap: (line: Int, index: Int) -> Unit) : TextView(context) {
    private var starts = IntArray(0)
    private var texts: List<String> = emptyList()

    /** Which of the state's lines each shown one is. */
    private var shown = IntArray(0)

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        setTextColor(Ink.TEXT)
        setLineSpacing(context.dp(8f).toFloat(), 1f)
        isFocusable = false
    }

    fun show(state: LookupState) {
        // A dialogue box is a line or two and is shown whole. A menu is dozens of pieces of text:
        // once a word is picked in it, only its own piece is shown, so the entry stays on screen.
        shown = if (state.selected && state.lines.size > MANY) intArrayOf(state.line) else IntArray(state.lines.size) { it }
        texts = shown.map { state.lines[it].text }
        starts = IntArray(texts.size)
        val joined = StringBuilder()
        texts.forEachIndexed { i, t ->
            if (i > 0) joined.append('\n')
            starts[i] = joined.length
            joined.append(t)
        }
        val span = SpannableString(joined)
        val at = shown.indexOf(state.line)
        if (state.selected && at >= 0 && state.spanEnd > state.spanStart) {
            val from = starts[at] + state.spanStart
            val to = starts[at] + state.spanEnd
            span.setSpan(BackgroundColorSpan(Ink.WASH), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(Ink.ACCENT), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = span
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val layout = layout ?: return true
        val x = event.x - totalPaddingLeft + scrollX
        val y = event.y - totalPaddingTop + scrollY
        val row = layout.getLineForVertical(y.toInt())
        if (y < 0 || y > layout.height || x < layout.getLineLeft(row) || x > layout.getLineRight(row)) return true
        // The caret nearest the touch sits on one side of the character under it.
        var offset = layout.getOffsetForHorizontal(row, x)
        if (offset > layout.getLineStart(row) && layout.getPrimaryHorizontal(offset) > x) offset--
        val line = starts.indexOfLast { it <= offset }
        if (line < 0) return true
        val index = offset - starts[line]
        if (index in texts[line].indices) onTap(shown[line], if (Character.isLowSurrogate(texts[line][index]) && index > 0) index - 1 else index)
        return true
    }
}

/** `A Add card · B Close · ◀ ▶ Word · ▲ ▼ Line`: what replaces the button while the controller drives. */
internal fun Context.keyHints(confirm: String, close: String): TextView = label(12.5f, Ink.DIM).apply {
    text = "$confirm Add card    $close Close    Y Listen    ◀ ▶ Word    ▲ ▼ Line    L1 R1 Entry"
    setPadding(0, dp(6f), 0, 0)
}

internal fun wrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

internal val TRANSPARENT = Color.TRANSPARENT
