// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.ui.EntryLink
import io.github.beesan.lfg.ui.EntryWebView
import io.github.beesan.lfg.ui.htmlState
import io.github.beesan.lfg.ui.palette
import io.github.beesan.lfg.lookup.LookupController
import io.github.beesan.lfg.lookup.LookupLine
import io.github.beesan.lfg.lookup.LookupState

/**
 * Everything the app draws over a game, all as accessibility overlay windows:
 * they need no "display over other apps" permission, and none of them except
 * the lookup-mode window can take focus, so the controller stays with the game.
 *
 * - an invisible strip over each line, where a tap lands on a character;
 * - a mark around the looked-up word;
 * - the entry: a panel on the other screen of a two-screen handheld, or a
 *   popup beside the word on a one-screen one;
 * - in lookup mode, a window that holds the controller.
 *
 * Windows are made once and then moved or parked, never added and removed in
 * quick succession: PlayTranslate's code reports AYN firmware crashing on that.
 *
 * Main thread only.
 */
class OverlayHost(
    private val service: AccessibilityService,
    private val controller: LookupController,
    private val prefs: () -> Prefs,
    private val chordActive: () -> Boolean,
    private val onToggleStrips: () -> Unit,
    private val onHistoryLine: (LookupLine) -> Unit,
    private val styles: () -> List<EntryHtml.Style>,
    private val media: (dictionary: String, path: String) -> ByteArray?,
    /** A note written on a word over the game: the line, the entry, the text, and whether to keep the picture. */
    private val onSaveNote: (LookupLine, Entry, String, Boolean) -> Unit,
) {
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val managers = HashMap<Int, Pair<Context, WindowManager>>()

    private var gameDisplay = Display.INVALID_DISPLAY
    private val strips = ArrayList<Win>()
    private val marks = ArrayList<Win>()
    private var popup: Win? = null
    private var popupView: PopupView? = null
    private var focus: Win? = null
    private var panel: Win? = null
    private var panelView: PanelView? = null
    private var panelDisplay = Display.INVALID_DISPLAY

    private var state = LookupState()
    private var stripsOn = true
    private var gameInFront = false
    private var history: List<LookupLine> = emptyList()
    private var session: String? = null

    /** The line and entry a note is being written on, while the note editor shows. */
    private var noting: Pair<LookupLine, Entry>? = null

    val input = ControllerInput(
        controller,
        swapConfirm = { prefs().swapConfirm },
        onClose = { controller.exitControllerMode() },
        onScroll = { dy -> panelView?.entry?.scrollBy(0, dy); popupView?.entry?.scrollBy(0, dy) },
        chordActive = chordActive,
    )

    /** True while something of ours is drawn on the game's screen, so a picture of that screen would include it. */
    val drawingOnGame: Boolean get() = marks.any { it.shown } || popup?.shown == true

    // ── windows ──────────────────────────────────────────────────────────────

    private fun manager(displayId: Int): Pair<Context, WindowManager>? = managers[displayId] ?: run {
        val display = displays.getDisplay(displayId) ?: return null
        val context = service.createDisplayContext(display).createWindowContext(LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        (context to context.getSystemService(WindowManager::class.java)).also { managers[displayId] = it }
    }

    private inner class Win(val view: View, private val wm: WindowManager, private val baseFlags: Int) {
        private var flags = baseFlags
        private val lp = LayoutParams(1, 1, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Positions are display pixels, as the picture's are: no inset may shift them.
            fitInsetsTypes = 0
        }
        private var added = false
        var shown = false
            private set
        val x get() = lp.x
        val y get() = lp.y

        fun place(x: Int, y: Int, width: Int, height: Int) {
            if (shown && lp.x == x && lp.y == y && lp.width == width && lp.height == height) return
            lp.x = x
            lp.y = y
            lp.width = width
            lp.height = height
            lp.flags = flags
            view.visibility = View.VISIBLE
            shown = true
            apply()
        }

        /** Parked: one transparent, untouchable pixel. The window itself stays. */
        fun hide() {
            if (!shown) return
            shown = false
            if (!added) return
            lp.x = 0
            lp.y = 0
            lp.width = 1
            lp.height = 1
            lp.flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE
            view.visibility = View.INVISIBLE
            apply()
        }

        /** Focusable while a note is written in it, so the keyboard can come up; never otherwise. */
        fun setFocusable(on: Boolean) {
            val next = if (on) baseFlags and LayoutParams.FLAG_NOT_FOCUSABLE.inv() else baseFlags
            if (next == flags) return
            flags = next
            lp.softInputMode = if (on) LayoutParams.SOFT_INPUT_STATE_VISIBLE or LayoutParams.SOFT_INPUT_ADJUST_PAN else LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            if (!shown) return
            lp.flags = flags
            apply()
        }

        private fun apply() {
            try {
                if (added) wm.updateViewLayout(view, lp) else {
                    wm.addView(view, lp)
                    added = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "overlay window failed", e)
            }
        }

        fun remove() {
            if (!added) return
            try {
                wm.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.w(TAG, "removing an overlay window failed", e)
            }
            added = false
            shown = false
        }
    }

    private fun bounds(displayId: Int): Box? = manager(displayId)?.second?.maximumWindowMetrics?.bounds?.let { Box(0, 0, it.width(), it.height()) }

    // ── state in ─────────────────────────────────────────────────────────────

    /** The text on screen changed, or the app in front did. */
    fun onScreen(displayId: Int, tapStrips: Boolean, inFront: Boolean) {
        if (displayId != gameDisplay && displayId != Display.INVALID_DISPLAY) moveTo(displayId)
        stripsOn = tapStrips
        gameInFront = inFront
        render()
    }

    fun onState(next: LookupState) {
        val wasController = state.controllerMode
        // Another word, or none: a note half written on the last one is let go.
        noting?.let { (line, _) -> if (!next.selected || next.selectedLine?.lineId != line.lineId) endNote() }
        state = next
        if (wasController && !next.controllerMode) input.release()
        render()
    }

    fun onHistory(lines: List<LookupLine>) {
        history = lines
        panelView?.showHistory(lines)
    }

    /** The session still going, as one line for the top of the panel; null when there is none. */
    fun onSession(text: String?) {
        session = text
        panelView?.showSession(text)
    }

    private fun moveTo(displayId: Int) {
        (strips + marks + listOfNotNull(popup, focus)).forEach { it.remove() }
        strips.clear()
        marks.clear()
        popup = null
        popupView = null
        focus = null
        gameDisplay = displayId
    }

    /** The other screen of a two-screen handheld, when the panel is wanted there. */
    private fun otherDisplay(): Int {
        if (!prefs().secondScreenPanel) return Display.INVALID_DISPLAY
        return displays.displays.firstOrNull {
            it.displayId != gameDisplay && it.state != Display.STATE_OFF && it.flags and Display.FLAG_PRIVATE == 0
        }?.displayId ?: Display.INVALID_DISPLAY
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    private fun render() {
        if (gameDisplay == Display.INVALID_DISPLAY) return
        // A new scheme, or light and dark swapped: the views take their colours when made, so they are made again.
        val palette = service.palette(prefs())
        if (palette != Ink.palette) {
            Ink.palette = palette
            noting = null
            (marks + listOfNotNull(popup, panel)).forEach { it.remove() }
            marks.clear()
            popup = null
            popupView = null
            panel = null
            panelView = null
        }
        val host = manager(gameDisplay) ?: return
        val lines = if (gameInFront) state.lines else emptyList()

        // Tap strips: one per row of text with a place on screen.
        var used = 0
        if (stripsOn) {
            lines.forEachIndexed { index, line ->
                for (box in line.geometry?.rows ?: emptyList()) {
                    if (box.width <= 0 || box.height <= 0) continue
                    val strip = strips.getOrNull(used) ?: Win(StripView(host.first), host.second, STRIP_FLAGS).also { strips += it }
                    (strip.view as StripView).bind(index, box.left, box.top - STRIP_SLOP)
                    strip.place(box.left, box.top - STRIP_SLOP, box.width, box.height + 2 * STRIP_SLOP)
                    used++
                }
            }
        }
        for (i in used until strips.size) strips[i].hide()

        // The mark around the looked-up word: one piece for each row it is on.
        val spans = if (gameInFront && state.selected) state.selectedLine?.geometry?.spanBoxes(state.spanStart, state.spanEnd) ?: emptyList() else emptyList()
        spans.forEachIndexed { i, piece ->
            val win = marks.getOrNull(i) ?: Win(
                View(host.first).apply { background = rounded(Ink.WASH, Ink.ACCENT, host.first.dp(3f).toFloat(), host.first.dp(1.5f)) },
                host.second, PASSIVE_FLAGS,
            ).also { marks += it }
            win.place(piece.left - MARK_PAD, piece.top - MARK_PAD, piece.width + 2 * MARK_PAD, piece.height + 2 * MARK_PAD)
        }
        for (i in spans.size until marks.size) marks[i].hide()
        val span = spans.firstOrNull()

        // The entry: on the other screen if there is one, else beside the word.
        val other = otherDisplay()
        if (other != Display.INVALID_DISPLAY) {
            popup?.hide()
            renderPanel(other)
        } else {
            panel?.hide()
            renderPopup(host, span)
        }

        // Lookup mode: a window that can take focus, and so the controller.
        if (state.controllerMode && gameInFront && noting == null) {
            val win = focus ?: Win(FocusView(host.first), host.second, FOCUS_FLAGS).also { focus = it }
            win.place(0, 0, 1, 1)
            win.view.requestFocus()
        } else {
            focus?.hide()
        }
    }

    private fun renderPanel(displayId: Int) {
        if (displayId != panelDisplay) {
            panel?.remove()
            panel = null
            panelDisplay = displayId
        }
        if (!gameInFront) {
            panel?.hide()
            return
        }
        val host = manager(displayId) ?: return
        val size = bounds(displayId) ?: return
        val view = panelView?.takeIf { panel != null } ?: PanelView(host.first).also { panelView = it; it.showHistory(history); it.showSession(session) }
        val win = panel ?: Win(view, host.second, PANEL_FLAGS).also { panel = it }
        view.show(state, stripsOn)
        win.place(0, 0, size.width, size.height)
    }

    private fun renderPopup(host: Pair<Context, WindowManager>, span: Box?) {
        if (!state.selected || span == null || (state.entry == null && state.message == null)) {
            popup?.hide()
            return
        }
        val screen = bounds(gameDisplay) ?: return
        val context = host.first
        val view = popupView ?: PopupView(context).also { popupView = it }
        val win = popup ?: Win(view, host.second, POPUP_FLAGS).also { popup = it }
        view.show(state)
        // Portrait: near the full width, as Hachidori's popup is. Landscape: Yomitan's
        // popup beside the word, which is what GameSentenceMiner's overlay shows.
        val width = if (screen.height > screen.width) screen.width - 2 * context.dp(12f) else minOf(context.dp(360f), screen.width * 45 / 100)
        // A fixed height, as Yomitan's popup has: the entries scroll inside it. "No entry" needs one line.
        val height = if (state.entries.isEmpty()) context.dp(56f) else screen.height * 55 / 100
        val gap = context.dp(10f)
        // Clear of the line: above it when there is room, else below, else wherever fits.
        // Clear of the row the word is on; in a tall block the other rows may be covered.
        val line = state.selectedLine?.geometry?.rows?.firstOrNull { it.intersects(span) } ?: span
        val y = when {
            line.top - gap - height >= 0 -> line.top - gap - height
            line.bottom + gap + height <= screen.height -> line.bottom + gap
            else -> 0
        }
        val x = (span.left + span.width / 2 - width / 2).coerceIn(0, (screen.width - width).coerceAtLeast(0))
        win.place(x, y, width, height)
    }

    fun destroy() {
        input.release()
        (strips + marks + listOfNotNull(popup, focus, panel)).forEach { it.remove() }
        strips.clear()
        marks.clear()
    }

    // ── views ────────────────────────────────────────────────────────────────

    /** Invisible. A tap inside lands on a character; taps anywhere else never reach it. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class StripView(context: Context) : View(context) {
        private var line = 0
        private var originX = 0
        private var originY = 0

        fun bind(line: Int, x: Int, y: Int) {
            this.line = line
            originX = x
            originY = y
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val geometry = state.lines.getOrNull(line)?.geometry ?: return true
                geometry.charIndexAt(originX + event.x.toInt(), originY + event.y.toInt(), STRIP_SLOP)?.let { controller.select(line, it) }
            }
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class FocusView(context: Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // The window with focus decides whether the system bars show. A game hides them; taking
        // the controller from it must not bring them back and shift the picture under the mark.
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            hideBars()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) hideBars()
        }

        private fun hideBars() {
            windowInsetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = input.onKey(event) || super.dispatchKeyEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = input.onMotion(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun hints(context: Context): TextView {
        val swap = prefs().swapConfirm
        return context.keyHints(if (swap) "B" else "A", if (swap) "A" else "B")
    }

    /**
     * The popup beside the word: every entry of the lookup in one scrolling
     * list, each with its own card and audio buttons, drawn with the same HTML
     * as the app's entry page.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class PopupView(context: Context) : LinearLayout(context) {
        val entry = EntryWebView(context, media) { link, i -> onEntryLink(link, i) }
        private val message = context.label(12.5f, Ink.DIM)
        private val hints = hints(context)
        private val note = NoteEditor(context, { text, keep -> saveNote(text, keep) }, { endNote(); render() })

        init {
            orientation = VERTICAL
            background = rounded(Ink.BG, Ink.BORDER_STRONG, context.dp(10f).toFloat())
            val pad = context.dp(1f)
            setPadding(pad, pad, pad, context.dp(4f))
            clipToOutline = true
            addView(entry, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            note.visibility = View.GONE
            addView(note, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            val text = context.dp(14f)
            message.setPadding(text, context.dp(4f), text, 0)
            addView(message)
            hints.setPadding(text, context.dp(4f), text, context.dp(2f))
            addView(hints)
            // A tap outside closes it.
            setOnTouchListener { _, event ->
                // Not while a note is written: the keyboard is outside the popup.
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && !state.controllerMode && noting == null) controller.clear()
                true
            }
        }

        fun showNote(word: String) {
            entry.visibility = View.GONE
            message.visibility = View.GONE
            hints.visibility = View.GONE
            note.visibility = View.VISIBLE
            note.open(word)
        }

        fun hideNote() {
            note.visibility = View.GONE
            entry.visibility = View.VISIBLE
        }

        fun show(state: LookupState) {
            if (note.visibility == View.VISIBLE) return
            showEntries(entry, state)
            message.text = state.message.takeIf { state.entries.isNotEmpty() } ?: ""
            message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            hints.visibility = if (state.controllerMode) View.VISIBLE else View.GONE
        }
    }

    /** A button in the entry list, by the entry's place in the list. */
    private fun onEntryLink(link: EntryLink, index: Int) {
        when (link) {
            EntryLink.CARD -> controller.addCard(index)
            EntryLink.AUDIO -> controller.playAudio(index)
            EntryLink.NOTE -> startNote(index)
        }
    }

    /** The note button of an entry: the popup, or the panel, becomes a note on its word. */
    private fun startNote(index: Int) {
        val entry = state.entries.getOrNull(index) ?: return
        val line = state.selectedLine ?: return
        noting = line to entry
        if (panel?.shown == true) {
            panelView?.showNote(entry.expression)
            panel?.setFocusable(true)
        } else {
            popupView?.showNote(entry.expression)
            popup?.setFocusable(true)
        }
        render()
    }

    private fun saveNote(text: String, keepPicture: Boolean) {
        val (line, entry) = noting ?: return
        if (text.isNotBlank()) onSaveNote(line, entry, text.trim(), keepPicture)
        endNote()
        render()
    }

    private fun endNote() {
        if (noting == null) return
        noting = null
        popupView?.hideNote()
        panelView?.hideNote()
        popup?.setFocusable(false)
        panel?.setFocusable(false)
    }

    private fun showEntries(view: EntryWebView, state: LookupState) =
        view.showList(state.entries, styles(), state.buttons.map { it.htmlState() }, state.entryIndex, state.message, Ink.palette)

    /** The bottom screen: the line to tap, then every entry of the lookup in one scrolling list, as in the popup. */
    @SuppressLint("ViewConstructor")
    private inner class PanelView(context: Context) : LinearLayout(context) {
        private val lines = LinesView(context) { line, index -> controller.select(line, index) }

        /** The entry in full: pictures, tables and each dictionary's own styling. */
        val entry = EntryWebView(context, media) { link, i -> onEntryLink(link, i) }
        private val note = NoteEditor(context, { text, keep -> saveNote(text, keep) }, { endNote(); render() })
        private val historyList = LinearLayout(context)
        private val historyScroll = ScrollView(context)
        private val message = context.label(12.5f, Ink.DIM)
        private val stripsToggle = context.label(12.5f, Ink.DIM)
        private val hints = hints(context)
        private val empty = context.label(15f, Ink.DIM)
        private val sessionLine = context.label(13f, Ink.DIM)

        init {
            orientation = VERTICAL
            setBackgroundColor(Ink.BG)
            val pad = context.dp(16f)

            // The text on screen never takes more than two fifths of the panel: the entry needs the rest.
            val top = object : ScrollView(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val most = (this@PanelView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 2 / 5
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST))
                }
            }.apply {
                isFocusable = false
                addView(lines)
                setPadding(pad, context.dp(12f), pad, context.dp(10f))
            }
            sessionLine.setBackgroundColor(Ink.SURFACE)
            sessionLine.setPadding(pad, context.dp(8f), pad, context.dp(8f))
            sessionLine.maxLines = 1
            sessionLine.ellipsize = TextUtils.TruncateAt.END
            sessionLine.visibility = View.GONE
            addView(sessionLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            empty.text = "Waiting for text in the game…"
            empty.setPadding(pad, 0, pad, context.dp(10f))
            addView(empty)
            addView(divider(context))

            addView(entry, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            note.visibility = View.GONE
            addView(note, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            historyList.orientation = VERTICAL
            historyScroll.isFocusable = false
            historyScroll.addView(historyList)
            historyScroll.setPadding(pad, context.dp(8f), pad, 0)
            addView(historyScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            message.setPadding(pad, 0, pad, context.dp(6f))
            addView(message)
            addView(divider(context))
            val actions = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, context.dp(10f), pad, context.dp(12f))
                addView(hints, wrap())
                addView(View(context), LayoutParams(0, 1, 1f))
                addView(stripsToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(14f) })
            }
            stripsToggle.minHeight = context.dp(40f)
            stripsToggle.gravity = Gravity.CENTER_VERTICAL
            stripsToggle.setOnClickListener { onToggleStrips() }
            addView(actions)
        }

        private fun divider(context: Context) = View(context).apply {
            setBackgroundColor(Ink.BORDER)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        }

        fun show(state: LookupState, stripsOn: Boolean) {
            lines.show(state)
            lines.visibility = if (state.lines.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (state.lines.isEmpty()) View.VISIBLE else View.GONE
            val showEntry = state.selected && (state.entry != null || state.message != null)
            val writing = note.visibility == View.VISIBLE
            entry.visibility = if (showEntry && !writing) View.VISIBLE else View.GONE
            historyScroll.visibility = if (showEntry || writing) View.GONE else View.VISIBLE
            if (showEntry) showEntries(entry, state)
            hints.visibility = if (state.controllerMode) View.VISIBLE else View.GONE
            message.text = state.message.takeIf { state.entry != null } ?: ""
            message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            stripsToggle.text = if (stripsOn) "Tap on game: on" else "Tap on game: off"
        }

        fun showNote(word: String) {
            entry.visibility = View.GONE
            historyScroll.visibility = View.GONE
            note.visibility = View.VISIBLE
            note.open(word)
        }

        fun hideNote() {
            note.visibility = View.GONE
        }

        fun showSession(text: String?) {
            sessionLine.text = text.orEmpty()
            sessionLine.visibility = if (text == null) View.GONE else View.VISIBLE
        }

        /** While nothing is selected, the lines read before: tap one to look a word up in it. */
        fun showHistory(history: List<LookupLine>) {
            historyList.removeAllViews()
            for (line in history) {
                historyList.addView(
                    context.label(16f, Ink.DIM).apply {
                        text = line.text
                        setPadding(0, context.dp(8f), 0, context.dp(8f))
                        setOnClickListener { onHistoryLine(line) }
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "LfgOverlay"
        const val STRIP_SLOP = 6
        const val MARK_PAD = 3
        const val BASE = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS
        const val STRIP_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL
        const val PASSIVE_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCHABLE
        const val POPUP_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        const val PANEL_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL

        // Focusable, so it holds the controller; untouchable, so taps still reach the strips and the game.
        const val FOCUS_FLAGS = LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}
