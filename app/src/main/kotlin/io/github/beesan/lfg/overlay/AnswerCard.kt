// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** A choice in a row of the card, such as which recap: [chosen] is filled, [marked] outlined for the controller. */
internal class CardChoice(val label: String, val chosen: Boolean, val marked: Boolean, val enabled: Boolean, val onPress: () -> Unit)

/** A button in the card's bar. */
internal class CardPill(val label: String, val enabled: Boolean = true, val onPress: () -> Unit)

/** A line in the card's list of lines. */
internal class CardRow(val time: String, val text: String, val translation: String?, val current: Boolean)

/** A question asked about the card's line, and its answer; [answer] is null while it is being written. */
internal class CardTurn(val question: String, val answer: String?, val answerColor: Int = Ink.TEXT)

/** What the card shows: its title, the line it is about, and what came back. Every part but the title may be left out. */
internal class CardContent(
    val title: String,
    val line: String? = null,
    val choices: List<CardChoice> = emptyList(),
    /** The field to write a question in. */
    val question: Boolean = false,
    val questionHint: String = "Your question",
    val body: String? = null,
    val bodyColor: Int = Ink.TEXT,
    val rows: List<CardRow> = emptyList(),
    /** What was asked about the line after [body], oldest first. */
    val turns: List<CardTurn> = emptyList(),
    /** Questions to ask next, one under the other at the foot of the card; [CardChoice.marked] is the controller's. */
    val asks: List<CardChoice> = emptyList(),
    /** Why the pills are what they are, above [asks]: "Start over?". */
    val prompt: String? = null,
    val pills: List<CardPill> = emptyList(),
    val hints: String = "",
    /** Changes when the card is about something else: its turns then start from the top. */
    val key: String = "",
)

/**
 * What a slice of the wheel opens, in a card over the game: a translation or
 * an explanation under the line it is about, a recap and its three choices, a
 * question and its answer, the game's last lines, or a note being written.
 * It only shows what [OverlayHost] gives it ([show], [showNote]).
 */
@SuppressLint("ViewConstructor")
internal class AnswerCard(
    context: Context,
    private val onClose: () -> Unit,
    private val onAsk: (String) -> Unit,
    onSaveNote: (text: String, keepPicture: Boolean) -> Unit,
    onNoteBack: () -> Unit,
) : LinearLayout(context) {
    private val pad = context.dp(16f)
    private val title = context.label(12f, Ink.DIM, bold = true)
    private val pills = LinearLayout(context)
    private val bar = LinearLayout(context)
    val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private val line = context.label(17f, Ink.TEXT)
    private val choices = LinearLayout(context)
    private val asking = LinearLayout(context)
    private val field = EditText(context)
    private val body = context.label(15.5f, Ink.TEXT)
    private val rows = LinearLayout(context)
    private val turns = LinearLayout(context)
    private val prompt = context.label(13.5f, Ink.ACCENT)
    private val asks = LinearLayout(context)
    private var shownKey = ""
    private var shownTurns = 0
    private val note = NoteEditor(context, onSaveNote, onNoteBack)
    private val hints = context.keyHints("")

    /** Whether the note editor is up in place of the rest. */
    val writing: Boolean get() = note.visibility == VISIBLE

    init {
        orientation = VERTICAL
        background = rounded(Ink.BG, Ink.BORDER_STRONG, context.dp(14f).toFloat())
        clipToOutline = true
        elevation = context.dp(6f).toFloat()

        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ink.SURFACE)
        bar.setPadding(pad, context.dp(6f), context.dp(10f), context.dp(6f))
        title.letterSpacing = 0.08f
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        pills.orientation = HORIZONTAL
        bar.addView(pills)
        bar.addView(context.pill("Close", primary = false, sizeSp = 12.5f) { onClose() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(8f) })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Ink.BORDER) }, LayoutParams(LayoutParams.MATCH_PARENT, 1))

        content.orientation = VERTICAL
        content.setPadding(pad, context.dp(12f), pad, context.dp(14f))
        line.setLineSpacing(context.dp(4f).toFloat(), 1f)
        content.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(10f) })
        choices.orientation = HORIZONTAL
        content.addView(choices, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(12f) })

        asking.orientation = HORIZONTAL
        asking.gravity = Gravity.CENTER_VERTICAL
        field.hint = "Your question"
        field.setTextColor(Ink.TEXT)
        field.setHintTextColor(Ink.DIM)
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        field.maxLines = 3
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.imeOptions = EditorInfo.IME_ACTION_SEND
        field.background = rounded(Ink.SUNKEN, Ink.ACCENT, context.dp(6f).toFloat())
        field.setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                onAsk(field.text.toString())
                true
            } else {
                false
            }
        }
        asking.addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        asking.addView(context.pill("Ask", primary = true, sizeSp = 14f) { onAsk(field.text.toString()) }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(10f) })
        content.addView(asking, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(12f) })

        body.setLineSpacing(context.dp(4f).toFloat(), 1f)
        content.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rows.orientation = VERTICAL
        content.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        turns.orientation = VERTICAL
        content.addView(turns, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scroll.isFocusable = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        note.visibility = GONE
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        prompt.setPadding(pad, context.dp(8f), pad, 0)
        addView(prompt)
        asks.orientation = VERTICAL
        asks.setPadding(context.dp(10f), context.dp(6f), context.dp(10f), context.dp(2f))
        addView(asks, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        hints.setPadding(pad, context.dp(4f), pad, context.dp(8f))
        addView(hints)
    }

    fun show(next: CardContent) {
        note.visibility = GONE
        scroll.visibility = VISIBLE
        bar.visibility = VISIBLE
        title.text = next.title.uppercase()

        pills.removeAllViews()
        for (p in next.pills) {
            pills.addView(context.pill(p.label, primary = false, sizeSp = 12.5f) { p.onPress() }.apply {
                isEnabled = p.enabled
                alpha = if (p.enabled) 1f else 0.5f
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(8f) })
        }

        line.text = next.line.orEmpty()
        line.visibility = if (next.line.isNullOrEmpty()) GONE else VISIBLE

        choices.removeAllViews()
        for (c in next.choices) {
            choices.addView(context.pill(c.label, primary = false, sizeSp = 13f) { c.onPress() }.apply {
                isEnabled = c.enabled
                alpha = if (c.enabled || c.chosen) 1f else 0.5f
                setTextColor(if (c.chosen) Ink.ON_FILL else Ink.TEXT)
                background = rounded(
                    if (c.chosen) Ink.FILL else Ink.SURFACE, if (c.marked) Color.WHITE else if (c.chosen) Ink.FILL else Ink.BORDER_STRONG,
                    context.dp(19f).toFloat(), if (c.marked) context.dp(2.5f) else 1,
                )
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = context.dp(8f) })
        }
        choices.visibility = if (next.choices.isEmpty()) GONE else VISIBLE

        val wasAsking = asking.visibility == VISIBLE
        asking.visibility = if (next.question) VISIBLE else GONE
        field.hint = next.questionHint
        if (next.question && !wasAsking) {
            field.setText("")
            keyboard()
        }

        body.text = next.body.orEmpty()
        body.setTextColor(next.bodyColor)
        body.visibility = if (next.body.isNullOrEmpty()) GONE else VISIBLE

        val hadRows = rows.childCount
        rows.removeAllViews()
        next.rows.forEach { rows.addView(row(it)) }
        rows.visibility = if (next.rows.isEmpty()) GONE else VISIBLE
        // A list of lines opens at its newest, as the lines' screen does.
        if (next.rows.isNotEmpty() && hadRows == 0) scroll.post { scroll.scrollTo(0, content.height) }

        turns.removeAllViews()
        next.turns.forEach { turn(it) }
        turns.visibility = if (next.turns.isEmpty()) GONE else VISIBLE
        // A question just asked, or its answer: brought into sight. Another line's start from the top.
        if (next.key != shownKey) {
            scroll.scrollTo(0, 0)
        } else if (next.turns.size > shownTurns) {
            val first = shownTurns * 2
            scroll.post { scroll.smoothScrollTo(0, (turns.top + (turns.getChildAt(first)?.top ?: 0) - context.dp(8f)).coerceAtLeast(0)) }
        }
        shownKey = next.key
        shownTurns = next.turns.size

        prompt.text = next.prompt.orEmpty()
        prompt.visibility = if (next.prompt.isNullOrEmpty()) GONE else VISIBLE
        asks.removeAllViews()
        for (a in next.asks) asks.addView(ask(a), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4f) })
        asks.visibility = if (next.asks.isEmpty()) GONE else VISIBLE

        hints.text = next.hints
        hints.visibility = if (next.hints.isEmpty()) GONE else VISIBLE
    }

    /** A question, to the right in its bubble, and its answer under it. */
    private fun turn(t: CardTurn) {
        turns.addView(
            context.label(14.5f, Ink.TEXT).apply {
                text = t.question
                setLineSpacing(context.dp(3f).toFloat(), 1f)
                background = rounded(Ink.WASH, Ink.BORDER, context.dp(10f).toFloat())
                setPadding(context.dp(12f), context.dp(7f), context.dp(12f), context.dp(7f))
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = context.dp(14f)
                leftMargin = context.dp(48f)
            },
        )
        turns.addView(
            context.label(15.5f, t.answerColor).apply {
                text = t.answer ?: "Asking…"
                if (t.answer == null) setTextColor(Ink.DIM)
                setLineSpacing(context.dp(4f).toFloat(), 1f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f) },
        )
    }

    /** A question to ask next: a row the whole width of the card, outlined when the controller is on it. */
    private fun ask(a: CardChoice): View = context.label(14.5f, Ink.TEXT).apply {
        text = a.label
        maxLines = 2
        gravity = Gravity.CENTER_VERTICAL
        minHeight = context.dp(40f)
        setPadding(context.dp(12f), context.dp(6f), context.dp(12f), context.dp(6f))
        background = rounded(if (a.marked) Ink.WASH else Ink.SURFACE, if (a.marked) Ink.ACCENT else Ink.BORDER, context.dp(8f).toFloat(), if (a.marked) context.dp(2f) else 1)
        isEnabled = a.enabled
        isFocusable = false
        alpha = if (a.enabled) 1f else 0.5f
        setOnClickListener { a.onPress() }
    }

    /** The note editor in place of the rest, ready to write in. */
    fun showNote(heading: String) {
        bar.visibility = GONE
        scroll.visibility = GONE
        hints.visibility = GONE
        note.visibility = VISIBLE
        note.open(heading)
    }

    private fun row(r: CardRow): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(context.dp(6f), context.dp(7f), context.dp(6f), context.dp(7f))
        if (r.current) background = rounded(Ink.SURFACE, Ink.BORDER, context.dp(8f).toFloat())
        addView(context.label(12f, Ink.DIM).apply {
            text = r.time
            setPadding(0, context.dp(3f), 0, 0)
        }, LayoutParams(context.dp(50f), LayoutParams.WRAP_CONTENT))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(context.label(16f, if (r.current) Ink.ACCENT else Ink.TEXT).apply {
            text = r.text
            setLineSpacing(context.dp(3f).toFloat(), 1f)
        })
        if (!r.translation.isNullOrEmpty()) {
            column.addView(context.label(13.5f, Ink.DIM).apply {
                text = r.translation
                setLineSpacing(context.dp(2f).toFloat(), 1f)
                setPadding(0, context.dp(3f), 0, 0)
            })
        }
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Scrolls what the card says by [dy] pixels. */
    fun scrollBy(dy: Int) = scroll.smoothScrollBy(0, dy)

    /**
     * Brings the keyboard up for the question. The card's window has only just
     * been allowed focus, and the keyboard is refused until it has it, so this is
     * asked again when focus arrives and once more a little later.
     */
    private fun keyboard() {
        val show = Runnable {
            if (asking.visibility == VISIBLE && isAttachedToWindow) {
                field.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        field.post(show)
        field.postDelayed(show, 350)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) return
        hideBars()
        if (asking.visibility == VISIBLE && field.text.isEmpty()) keyboard()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideBars()
    }

    /**
     * The window with focus decides whether the system bars show. A game hides them, and one that shows
     * them for long is taken for an ordinary app: the card, with focus while a note or a question is
     * written, would then be put away under the player's fingers.
     */
    private fun hideBars() {
        windowInsetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }

    // ── slid away to the right ───────────────────────────────────────────────

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var sliding = false
    private var velocity: VelocityTracker? = null

    /** A drag to the right, more across than down, slides the card; anything else is its buttons' and its scrolling's. */
    private fun slideStarts(event: MotionEvent): Boolean {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        // Not a note: a slip of the finger must not throw away what was written.
        return !writing && dx > slop && dx > abs(dy) * 1.5f
    }

    private fun track(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.rawX
            downY = event.rawY
            sliding = false
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        // Raw places: the card moves under the finger as it slides.
        val raw = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        velocity?.addMovement(raw)
        raw.recycle()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && slideStarts(event)) sliding = true
        return sliding
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A touch that began between the card's buttons comes here without passing the check above.
        track(event)
        if (!sliding && event.actionMasked == MotionEvent.ACTION_MOVE && slideStarts(event)) sliding = true
        if (!sliding) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> translationX = (event.rawX - downX).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocity
                tracker?.computeCurrentVelocity(1000)
                val flung = (tracker?.xVelocity ?: 0f) > context.dp(700f)
                val away = event.actionMasked == MotionEvent.ACTION_UP && (translationX > width * 0.3f || flung)
                sliding = false
                velocity?.recycle()
                velocity = null
                if (away) {
                    animate().translationX(width.toFloat()).alpha(0f).setDuration(SLIDE_MS).withEndAction {
                        onClose()
                        translationX = 0f
                        alpha = 1f
                    }
                } else {
                    animate().translationX(0f).setDuration(SLIDE_MS)
                }
            }
        }
        return true
    }

    /**
     * While the card has the focus (a question or a note is written), Back puts it away once the keyboard
     * is down. A pad's B that nothing takes arrives as Back too.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onClose()
            return true
        }
        return false
    }

    private companion object {
        /** How long the card takes to slide away, or back. */
        const val SLIDE_MS = 160L
    }
}
