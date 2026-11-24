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
 * The soft shadow round the popup over a game, in a window of its own under
 * the popup's: the popup is see-through, so the shadow is drawn only outside
 * its card ([inset] in from every edge), never beneath it.
 */
internal class ShadeDrawable(private val radius: Float, private val inset: Float, dark: Boolean) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(inset * 0.72f, 0f, inset * 0.22f, if (dark) 0x8C000000.toInt() else 0x47000000)
    }
    private val card = android.graphics.RectF()
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        card.set(bounds)
        card.inset(inset, inset)
        path.rewind()
        path.addRoundRect(card, radius, radius, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawRoundRect(card, radius, radius, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** [color] with [percent] of what is behind it showing through. */
internal fun seeThrough(color: Int, percent: Int): Int = (color and 0x00FFFFFF) or ((255 * (100 - percent.coerceIn(0, 90)) / 100) shl 24)

/** `A Add card · B Close · ◀ ▶ Word · ▲ ▼ Row`: what replaces the button while the controller drives. */
/** What the controller's buttons do in lookup mode, in a line. */
internal fun keyHintsText(
    confirm: String, close: String, listen: Boolean = true, wholeWord: Boolean = true, stickWords: Boolean = false, box: Boolean = false,
): String =
    // Y says the word only with word audio installed (Settings › Dictionaries); without it, it is not offered.
    "$confirm Add card    $close Close    X Note    " + (if (listen) "Y Listen    " else "") +
        (if (stickWords) "Stick" else "◀ ▶") + (if (wholeWord) " Word" else " Character") + "    ▲ ▼ Row    L1 R1 Entry" +
        // Over a game, Select draws the box its dialogue is read in.
        (if (box) "    Select Box" else "")

internal fun Context.keyHints(text: String): TextView = label(12.5f, Ink.DIM).apply {
    this.text = text
    setPadding(0, dp(6f), 0, 0)
}

/** A button as the app draws one: filled for the main action, outlined for the rest. */
internal fun Context.pill(text: String, primary: Boolean, sizeSp: Float = 14f, onPress: () -> Unit) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    minHeight = dp(if (sizeSp >= 16f) 44f else 38f)
    setPadding(dp(16f), 0, dp(16f), 0)
    includeFontPadding = false
    setTextColor(if (primary) Ink.ON_FILL else Ink.TEXT)
    background = rounded(if (primary) Ink.FILL else Ink.SURFACE, if (primary) Ink.FILL else Ink.BORDER_STRONG, dp(19f).toFloat())
    isFocusable = false
    setOnClickListener { onPress() }
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

    /** Ready for a note headed [heading] ("Note on 雪"): an empty field with the keyboard up. */
    fun open(heading: String) {
        title.text = heading
        keep.isChecked = true
        field.setText("")
        field.requestFocus()
        keyboard()
    }

    /**
     * Brings the keyboard up. Its window has only just been allowed focus, and
     * the keyboard is refused until it has it, so this is asked again when
     * focus arrives ([onWindowFocusChanged]) and once more a little later.
     */
    private fun keyboard() {
        val show = Runnable {
            if (visibility == VISIBLE && isAttachedToWindow) {
                field.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        field.post(show)
        field.postDelayed(show, 350)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && visibility == VISIBLE) keyboard()
    }

    private fun button(context: Context, text: String, primary: Boolean, onPress: () -> Unit) = context.pill(text, primary, 16f, onPress)
}
