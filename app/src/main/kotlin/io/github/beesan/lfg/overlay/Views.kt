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
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
    val SURFACE get() = palette.surface
    val SUNKEN get() = palette.sunken
    val FILL get() = palette.fill
    val ON_FILL get() = palette.onFill
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

/**
 * A note on a word, written without leaving the game: whether to keep the
 * picture of the game as it is now, and the text. Its window takes focus
 * while it shows, so the keyboard can come up; [OverlayHost] gives it back.
 */
@SuppressLint("ViewConstructor")
internal class NoteEditor(context: Context, onSave: (text: String, keepPicture: Boolean) -> Unit, onBack: () -> Unit) : LinearLayout(context) {
    private val title = context.label(17f, Ink.TEXT, bold = true)
    private val keep = CheckBox(context)
    private val field = EditText(context)

    init {
        orientation = VERTICAL
        val pad = context.dp(14f)
        setPadding(pad, context.dp(12f), pad, context.dp(12f))
        addView(title)
        keep.text = "Keep this picture"
        keep.setTextColor(Ink.TEXT)
        keep.buttonTintList = ColorStateList.valueOf(Ink.ACCENT)
        keep.minHeight = context.dp(44f)
        addView(keep, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6f) })
        field.hint = "Note"
        field.setTextColor(Ink.TEXT)
        field.setHintTextColor(Ink.DIM)
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        field.gravity = Gravity.TOP or Gravity.START
        field.minLines = 4
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.background = rounded(Ink.SUNKEN, Ink.ACCENT, context.dp(6f).toFloat())
        field.setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(6f) })
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        buttons.addView(button(context, "Save note", primary = true) { onSave(field.text.toString(), keep.isChecked) }, wrap())
        buttons.addView(button(context, "Back", primary = false) { onBack() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(8f) })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(10f) })
    }

    /** Ready for a note on [word]: an empty field with the keyboard up. */
    fun open(word: String) {
        title.text = "Note on $word"
        keep.isChecked = true
        field.setText("")
        field.requestFocus()
        field.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0) }
    }

    private fun button(context: Context, text: String, primary: Boolean, onPress: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = context.dp(44f)
        setPadding(context.dp(18f), 0, context.dp(18f), 0)
        setTextColor(if (primary) Ink.ON_FILL else Ink.TEXT)
        background = rounded(if (primary) Ink.FILL else Ink.SURFACE, if (primary) Ink.FILL else Ink.BORDER_STRONG, context.dp(5f).toFloat())
        isFocusable = false
        setOnClickListener { onPress() }
    }
}
