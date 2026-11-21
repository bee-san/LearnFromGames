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
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.Note
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
 * - the entry, a see-through popup beside the word, with its shadow;
 * - on the other screen of a two-screen handheld, the game's lines as they are
 *   read, as a texthooker page lists them;
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
    /** A tap landed on the app, away from the strips and the entry, while a reading asked for is up. */
    private val onTapElsewhere: () -> Unit,
    /** The read button over an ordinary app: a tap, and a long press. */
    private val onReadButton: () -> Unit,
    private val onReadButtonHeld: () -> Unit,
    /** A region dragged out: where, and whether to keep reading it. */
    private val onRegion: (Box, Boolean) -> Unit,
    /** Translate or Explain pressed on the lines' screen for this saved line; the answer comes back through [onAnswer]. */
    private val onAsk: (lineId: String, what: Ask) -> Unit,
    private val styles: () -> List<EntryHtml.Style>,
    private val media: (dictionary: String, path: String) -> ByteArray?,
    /**
     * A note written over the game: on a word (its line and entry), on a line
     * alone, or on the game as it is now (neither). Then the text, and whether
     * to keep the picture.
     */
    private val onSaveNote: (LookupLine?, Entry?, String, Boolean) -> Unit,
) {
    /** What the lines' screen can ask the model about a line. */
    enum class Ask { TRANSLATE, EXPLAIN }

    private val displays = service.getSystemService(DisplayManager::class.java)
    private val managers = HashMap<Int, Pair<Context, WindowManager>>()

    private var gameDisplay = Display.INVALID_DISPLAY
    private val strips = ArrayList<Win>()
    private val marks = ArrayList<Win>()
    private var popup: Win? = null
    private var popupView: PopupView? = null

    /** The popup's shadow: a window of its own under it, which no touch lands on. */
    private var shade: Win? = null

    /** How see-through the popup was made, to make it again when Settings changes it. */
    private var popupGlass = -1

    /** The word was picked on the lines' screen, so its entry shows there and not over the game. */
    private var pickedOnPanel = false
    private var focus: Win? = null
    private var panel: Win? = null
    private var panelView: PanelView? = null
    private var panelDisplay = Display.INVALID_DISPLAY

    private var state = LookupState()
    private var stripsOn = true
    private var gameInFront = false

    /** The lines on screen were read because the user asked, over an app that is not a game. */
    private var asking = false

    /** When a finger last went down on something of ours, to tell a tap on the app from one on a strip or the entry. */
    private var lastOwnTouch = 0L
    private var elsewhereCheck: Runnable? = null
    private var readButton: Win? = null
    private var readButtonDisplay = Display.INVALID_DISPLAY
    private var buttonX = -1
    private var buttonY = -1
    private var picker: Win? = null
    private val handler = android.os.Handler(service.mainLooper)
    private var history: List<GameLine> = emptyList()

    /** The game of the session still going and its stats, as the top of the panel shows them. */
    private var session: Pair<String, String>? = null

    private var ownApp: Set<Int> = emptySet()

    /** When Open LFG was pressed: the lines' screen is out of the way at once, before the app's page is seen in front. */
    private var appOpenedAt = 0L

    /** This game's notes show among its lines. */
    private var notes: List<Note> = emptyList()

    /** What a note is being written on, while the note editor shows: a word of a line, a line alone, or the game as it is now. */
    private class Noting(val line: LookupLine?, val entry: Entry?)

    private var noting: Noting? = null

    val input = ControllerInput(
        controller,
        swapConfirm = { prefs().swapConfirm },
        onClose = { controller.exitControllerMode() },
        onScroll = { dy -> panelView?.entry?.scrollBy(0, dy); popupView?.entry?.scrollBy(0, dy) },
        chordActive = chordActive,
    )

    /** True while something of ours is drawn on the game's screen, so a picture of that screen would include it. */
    val drawingOnGame: Boolean get() = marks.any { it.shown } || popup?.shown == true || notice?.shown == true

    private var notice: Win? = null
    private var noticeDisplay = Display.INVALID_DISPLAY
    private val hideNotice = Runnable { notice?.hide() }

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
    fun onScreen(displayId: Int, tapStrips: Boolean, inFront: Boolean, asking: Boolean = false) {
        if (displayId != gameDisplay && displayId != Display.INVALID_DISPLAY) moveTo(displayId)
        stripsOn = tapStrips
        gameInFront = inFront
        this.asking = asking && inFront
        render()
    }

    /** Something of ours was touched: a strip, the popup, the panel or the button. */
    private fun ownTouch() {
        lastOwnTouch = android.os.SystemClock.uptimeMillis()
    }

    /**
     * A strip heard a touch outside itself. Every strip hears it, including
     * for a touch on another strip, so it counts as a tap on the game only
     * when nothing of ours was touched at the same moment. Such a tap ends a
     * reading asked for, and lets go of a word picked by touch: its mark is on
     * the game's screen, and nothing is read from that screen while it is.
     */
    private fun outsideTouch() {
        if (elsewhereCheck != null || !(asking || pickedByTouch())) return
        val heardAt = android.os.SystemClock.uptimeMillis()
        val check = Runnable {
            elsewhereCheck = null
            if (lastOwnTouch < heardAt - OWN_TOUCH_SLACK_MS) {
                if (asking) onTapElsewhere() else if (pickedByTouch()) controller.clear()
            }
        }
        elsewhereCheck = check
        handler.postDelayed(check, OUTSIDE_WAIT_MS)
    }

    private fun pickedByTouch() = state.selected && !state.controllerMode && noting == null

    fun onState(next: LookupState) {
        val wasController = state.controllerMode
        // Another word, or none: a note half written on the last word is let go. A note on a line or the game stays.
        noting?.let { written -> if (written.entry != null && (!next.selected || next.selectedLine?.lineId != written.line?.lineId)) endNote() }
        // The cursor is on the game's own text, so its entry is over the game.
        if (next.controllerMode && !wasController) pickedOnPanel = false
        state = next
        if (wasController && !next.controllerMode) input.release()
        render()
    }

    /** The newest lines read, of every game; the lines' screen shows the current game's. */
    fun onHistory(lines: List<GameLine>) {
        history = lines
        panelView?.refresh()
    }

    /** The screens this app's own pages are in front on. The lines' screen is not drawn over them. */
    fun onOwnApp(displays: Set<Int>) {
        if (displays == ownApp) return
        ownApp = displays
        render()
    }

    /** Every note, newest first; the lines' screen shows the current game's among its lines. */
    fun onNotes(all: List<Note>) {
        notes = all
        panelView?.refresh()
    }

    /** The session still going: its game and its stats; null when there is none. */
    fun onSession(game: String?, stats: String?) {
        session = if (game != null && stats != null) game to stats else null
        panelView?.showSession(game, stats)
    }

    private fun moveTo(displayId: Int) {
        (strips + marks + listOfNotNull(popup, shade, focus)).forEach { it.remove() }
        strips.clear()
        marks.clear()
        popup = null
        popupView = null
        shade = null
        focus = null
        gameDisplay = displayId
    }

    /** The other screen of a two-screen handheld, when the lines are wanted there. */
    private fun otherDisplay(): Int {
        if (!prefs().secondScreenPanel) return Display.INVALID_DISPLAY
        return displays.displays.firstOrNull {
            it.displayId != gameDisplay && it.state != Display.STATE_OFF && it.flags and Display.FLAG_PRIVATE == 0
        }?.displayId ?: Display.INVALID_DISPLAY
    }

    /** The entry is a popup over the game: always on one screen, and on two unless Settings puts it with the lines. */
    private fun entryOverGame(other: Int) = other == Display.INVALID_DISPLAY || prefs().entryPlace == Prefs.ENTRY_OVER_GAME

    /** The entry shows on the lines' screen: for a word picked there, for a line no longer on screen, or when Settings says so. */
    private fun entryOnPanel(): Boolean {
        if (!state.selected || (state.entry == null && state.message == null)) return false
        return pickedOnPanel || state.selectedLine?.geometry == null || prefs().entryPlace != Prefs.ENTRY_OVER_GAME
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    private fun render() {
        if (gameDisplay == Display.INVALID_DISPLAY) return
        // A new scheme, or light and dark swapped: the views take their colours when made, so they are made again.
        val palette = service.palette(prefs())
        val glass = prefs().popupSeeThrough
        if (palette != Ink.palette) {
            Ink.palette = palette
            noting = null
            (marks + listOfNotNull(popup, shade, panel)).forEach { it.remove() }
            marks.clear()
            popup = null
            popupView = null
            shade = null
            panel = null
            panelView = null
        } else if (glass != popupGlass && popupView != null) {
            // The popup takes its ground when made.
            listOfNotNull(popup, shade).forEach { it.remove() }
            popup = null
            popupView = null
            shade = null
        }
        popupGlass = glass
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
        // Text to tap: the popup is made now, after this drawing, so the first tap does not wait for its page.
        if (lines.isNotEmpty() && popupView == null && entryOverGame(otherDisplay())) {
            val display = gameDisplay
            handler.post {
                if (popupView == null && gameDisplay == display) {
                    popupView = PopupView(host.first).also { it.entry.prewarm(styles(), Ink.palette) }
                }
            }
        }

        // The mark around the looked-up word: one piece for each row it is on.
        val spans = if (gameInFront && state.selected) state.selectedLine?.geometry?.spanBoxes(state.spanStart, state.markEnd) ?: emptyList() else emptyList()
        spans.forEachIndexed { i, piece ->
            val win = marks.getOrNull(i) ?: Win(
                View(host.first).apply { background = rounded(Ink.WASH, Ink.ACCENT, host.first.dp(3f).toFloat(), host.first.dp(1.5f)) },
                host.second, PASSIVE_FLAGS,
            ).also { marks += it }
            win.place(piece.left - MARK_PAD, piece.top - MARK_PAD, piece.width + 2 * MARK_PAD, piece.height + 2 * MARK_PAD)
        }
        for (i in spans.size until marks.size) marks[i].hide()
        val span = spans.firstOrNull()

        // The lines, on the other screen if there is one. The entry: beside the word over the game,
        // unless the word was picked on the lines' screen or Settings puts every entry there.
        val other = otherDisplay()
        if (other != Display.INVALID_DISPLAY) renderPanel(other) else panel?.hide()
        if (entryOverGame(other) && !(pickedOnPanel && panel?.shown == true)) renderPopup(host, span) else hidePopup()

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
        // Not over the app's own pages, opened from here: they are on this screen to be used.
        val opening = android.os.SystemClock.uptimeMillis() - appOpenedAt < APP_OPENING_MS
        if (!gameInFront || displayId in ownApp || opening) {
            panel?.hide()
            return
        }
        val host = manager(displayId) ?: return
        val size = bounds(displayId) ?: return
        val view = panelView?.takeIf { panel != null } ?: PanelView(host.first).also { panelView = it }
        val win = panel ?: Win(view, host.second, PANEL_FLAGS).also { panel = it }
        view.show(stripsOn)
        win.place(0, 0, size.width, size.height)
    }

    private fun hidePopup() {
        popup?.hide()
        shade?.hide()
    }

    private fun renderPopup(host: Pair<Context, WindowManager>, span: Box?) {
        if (!state.selected || span == null || (state.entry == null && state.message == null)) {
            hidePopup()
            return
        }
        val screen = bounds(gameDisplay) ?: return
        val context = host.first
        val margin = context.dp(SHADE_DP)
        val under = shade ?: Win(
            View(context).apply { background = ShadeDrawable(context.dp(POPUP_RADIUS_DP).toFloat(), margin.toFloat(), Ink.palette.dark) },
            host.second, PASSIVE_FLAGS,
        ).also { shade = it }
        val view = popupView ?: PopupView(context).also { popupView = it }
        val win = popup ?: Win(view, host.second, POPUP_FLAGS).also { popup = it }
        view.show(state)
        val edge = context.dp(6f)
        val gap = context.dp(6f)
        // Portrait: near the full width. Landscape: Hachidori's popup beside the word, which is what
        // GameSentenceMiner's overlay shows. A fixed height: the entries scroll inside it. "No entry" needs one line.
        var width = if (screen.height > screen.width) screen.width - 2 * context.dp(10f) else minOf(context.dp(400f), screen.width * 46 / 100)
        val want = if (state.entries.isEmpty()) context.dp(60f) else minOf(screen.height * 56 / 100, context.dp(460f))
        val geometry = state.selectedLine?.geometry
        // Clear of the row the word is on, as Hachidori places its popup; in a tall block the other rows may be covered.
        val row = geometry?.rows?.firstOrNull { it.intersects(span) } ?: span
        val x: Int
        val y: Int
        val height: Int
        if (geometry?.vertical == true) {
            // Beside the column: to its right when there is room, else on the roomier side, no wider than the room there.
            val right = screen.width - row.right - gap - edge
            val left = row.left - gap - edge
            val onRight = right >= width || right >= left
            width = minOf(width, maxOf(left, right)).coerceAtLeast(context.dp(160f))
            height = want
            x = (if (onRight) row.right + gap else row.left - gap - width).coerceIn(edge, (screen.width - width - edge).coerceAtLeast(edge))
            y = span.top.coerceIn(edge, (screen.height - height - edge).coerceAtLeast(edge))
        } else {
            // Above the row when it fits, else below, else on the roomier side and no taller than the room there,
            // so the word is never covered. Its left edge is on the word's.
            val above = row.top - gap - edge
            val below = screen.height - row.bottom - gap - edge
            val onTop = above >= want || (below < want && above >= below)
            height = minOf(want, maxOf(above, below)).coerceAtLeast(context.dp(96f))
            y = (if (onTop) row.top - gap - height else row.bottom + gap).coerceIn(edge, (screen.height - height - edge).coerceAtLeast(edge))
            x = span.left.coerceIn(edge, (screen.width - width - edge).coerceAtLeast(edge))
        }
        under.place(x - margin, y - margin, width + 2 * margin, height + 2 * margin)
        val back = !win.shown
        win.place(x, y, width, height)
        if (back) {
            // Parked, its page was a pixel wide and may have scrolled; once it has its size again it starts from the top.
            view.entry.toTop()
            handler.postDelayed({ if (popup?.shown == true) view.entry.toTop() }, 80)
        }
    }

    // ── the read button and the region picker ────────────────────────────────

    /** The read button on [displayId], or nowhere for null. */
    fun showReadButton(displayId: Int?) {
        if (displayId == null || displayId == Display.INVALID_DISPLAY) {
            readButton?.hide()
            return
        }
        if (displayId != readButtonDisplay) {
            readButton?.remove()
            readButton = null
            readButtonDisplay = displayId
        }
        val host = manager(displayId) ?: return
        val screen = bounds(displayId) ?: return
        val size = host.first.dp(48f)
        if (buttonX < 0) {
            buttonX = screen.width - size - host.first.dp(8f)
            buttonY = screen.height * 2 / 5
        }
        buttonX = buttonX.coerceIn(0, (screen.width - size).coerceAtLeast(0))
        buttonY = buttonY.coerceIn(0, (screen.height - size).coerceAtLeast(0))
        val win = readButton ?: Win(ReadButtonView(host.first), host.second, BUTTON_FLAGS).also { readButton = it }
        (win.view as ReadButtonView).active = asking || state.lines.isNotEmpty() && !gameInFront
        win.place(buttonX, buttonY, size, size)
    }

    /**
     * A short message at the foot of [displayId] for a few seconds: what a
     * reading asked for found, or why it found nothing. Drawn by us, so it
     * shows whether or not the app may post notifications.
     */
    fun notice(displayId: Int, text: String) {
        if (displayId != noticeDisplay) {
            notice?.remove()
            notice = null
            noticeDisplay = displayId
        }
        val host = manager(displayId) ?: return
        val screen = bounds(displayId) ?: return
        val context = host.first
        val win = notice ?: Win(
            context.label(14f, Ink.TEXT).apply {
                background = rounded(Ink.SURFACE, Ink.BORDER_STRONG, context.dp(8f).toFloat())
                setPadding(context.dp(16f), context.dp(12f), context.dp(16f), context.dp(12f))
                gravity = Gravity.CENTER
            },
            host.second, PASSIVE_FLAGS,
        ).also { notice = it }
        val view = win.view as TextView
        view.text = text
        val width = minOf(screen.width - context.dp(32f), context.dp(520f))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.AT_MOST))
        win.place((screen.width - width) / 2, screen.height - view.measuredHeight - context.dp(56f), width, view.measuredHeight)
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    /** A screen-sized window to drag a box on; it reports the box and goes. */
    fun pickRegion(displayId: Int) {
        val host = manager(displayId) ?: return
        val screen = bounds(displayId) ?: return
        picker?.remove()
        val view = RegionPicker(host.first) { box, keep ->
            picker?.remove()
            picker = null
            if (box != null) onRegion(box, keep)
        }
        val win = Win(view, host.second, PICKER_FLAGS)
        picker = win
        win.place(0, 0, screen.width, screen.height)
    }

    fun destroy() {
        input.release()
        // Everything posted, the popup's prewarm and the checks among it, would run on windows that are gone.
        handler.removeCallbacksAndMessages(null)
        notice?.remove()
        picker?.remove()
        readButton?.remove()
        (strips + marks + listOfNotNull(popup, shade, focus, panel)).forEach { it.remove() }
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
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                outsideTouch()
                return false
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ownTouch()
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val geometry = state.lines.getOrNull(line)?.geometry ?: return true
                geometry.charIndexAt(originX + event.x.toInt(), originY + event.y.toInt(), STRIP_SLOP)?.let {
                    // Picked on the game, so its entry is over the game.
                    pickedOnPanel = false
                    controller.select(line, it)
                }
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
            // An ordinary app shows its bars; hiding them would move its text out from under the strips.
            if (asking) return
            windowInsetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = input.onKey(event) || super.dispatchKeyEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = input.onMotion(event) || super.dispatchGenericMotionEvent(event)
    }

    /**
     * The round LFG button over an ordinary app. A tap reads the screen (or,
     * while a reading is up, takes it away); a long press drags out a region;
     * a drag moves the button.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class ReadButtonView(context: Context) : TextView(context) {
        var active = false
            set(value) {
                if (field == value) return
                field = value
                paint()
            }
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val held = Runnable { if (!dragging) onReadButtonHeld() }

        init {
            text = "LFG"
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = "Read this screen with LFG. Hold to read a region."
            elevation = context.dp(4f).toFloat()
            paint()
        }

        private fun paint() {
            setTextColor(if (active) Ink.FILL else Ink.ON_FILL)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (active) Ink.BG else Ink.FILL)
                setStroke(context.dp(2f), Ink.FILL)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ownTouch()
                    downX = event.rawX
                    downY = event.rawY
                    startX = buttonX
                    startY = buttonY
                    dragging = false
                    this@OverlayHost.handler.postDelayed(held, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > context.dp(10f).let { it * it }) {
                        dragging = true
                        this@OverlayHost.handler.removeCallbacks(held)
                    }
                    if (dragging) {
                        buttonX = startX + dx.toInt()
                        buttonY = startY + dy.toInt()
                        showReadButton(readButtonDisplay)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasHeld = !this@OverlayHost.handler.hasCallbacks(held) && !dragging
                    this@OverlayHost.handler.removeCallbacks(held)
                    if (!dragging && !wasHeld) onReadButton()
                }
                MotionEvent.ACTION_CANCEL -> this@OverlayHost.handler.removeCallbacks(held)
            }
            return true
        }
    }

    private fun hints(context: Context): TextView {
        val swap = prefs().swapConfirm
        return context.keyHints(
            if (swap) "B" else "A", if (swap) "A" else "B",
            listen = (context.applicationContext as? io.github.beesan.lfg.App)?.wordAudio?.info?.value != null,
            wholeWord = prefs().picking.wholeWord,
        )
    }

    /**
     * The popup beside the word, over the game: every entry of the lookup in
     * one scrolling list, each with its own card and audio buttons, drawn with
     * the same HTML as the app's entry page. Its ground lets a little of the
     * game through, as Hachidori's popup does, and its shadow is the window
     * under it ([shade]).
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class PopupView(context: Context) : LinearLayout(context) {
        val entry = EntryWebView(context, media) { link, i -> onEntryLink(link, i) }.apply { glass = true }
        private val message = context.label(12.5f, Ink.DIM)
        private val hints = hints(context)
        private val note = NoteEditor(context, { text, keep -> saveNote(text, keep) }, { endNote(); render() })

        init {
            orientation = VERTICAL
            background = rounded(seeThrough(Ink.BG, prefs().popupSeeThrough), Ink.BORDER_STRONG, context.dp(POPUP_RADIUS_DP).toFloat())
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
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) outsideTouch()
                // Not while a note is written: the keyboard is outside the popup.
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && !state.controllerMode && noting == null) controller.clear()
                true
            }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ownTouch()
            return super.dispatchTouchEvent(event)
        }

        fun showNote(heading: String) {
            entry.visibility = View.GONE
            message.visibility = View.GONE
            hints.visibility = View.GONE
            note.visibility = View.VISIBLE
            note.open(heading)
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

    /** The note button of an entry: the lines' screen, or the popup, becomes a note on its word. */
    private fun startNote(index: Int) {
        val entry = state.entries.getOrNull(index) ?: return
        val line = state.selectedLine ?: return
        beginNote(Noting(line, entry), "Note on ${entry.expression}")
    }

    private fun beginNote(next: Noting, heading: String) {
        noting = next
        if (panel?.shown == true) {
            panelView?.showNote(heading)
            panel?.setFocusable(true)
        } else {
            popupView?.showNote(heading)
            popup?.setFocusable(true)
        }
        render()
    }

    private fun saveNote(text: String, keepPicture: Boolean) {
        val written = noting ?: return
        if (text.isNotBlank()) onSaveNote(written.line, written.entry, text.trim(), keepPicture)
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

    /** A row of the lines' screen: a kept line, text on screen that is not kept, or a note. */
    private class Row(
        val key: String,
        val time: Long,
        val text: String,
        val saved: GameLine? = null,
        /** Its place among the state's lines while it is on screen, else -1. */
        val onScreen: Int = -1,
        val note: Note? = null,
    )

    /**
     * The lines' screen of a two-screen handheld, laid out as a texthooker
     * page: the game and its session at the top, then this game's lines as
     * they are read, oldest first, the one on screen last and largest. Every
     * character can be tapped. The line last touched (the newest, until
     * another is) carries its buttons: Translate and Explain once there is an
     * OpenRouter key, and Note. Notes written while playing sit among the
     * lines, at their time. A word picked here has its entry here, under the
     * lines; a word picked on the game has it over the game.
     */
    @SuppressLint("ViewConstructor")
    private inner class PanelView(context: Context) : LinearLayout(context) {
        private val pad = context.dp(16f)

        /** The entry in full: pictures, tables and each dictionary's own styling. */
        val entry = EntryWebView(context, media) { link, i -> onEntryLink(link, i) }
        private val note = NoteEditor(context, { text, keep -> saveNote(text, keep) }, { endNote(); render() })
        private val title = context.label(19f, Ink.TEXT, bold = true)
        private val stats = context.label(13f, Ink.DIM)
        private val list = LinearLayout(context)
        private val scroll = ScrollView(context)
        private val empty = context.label(15f, Ink.DIM)
        private val sheet = LinearLayout(context)
        private val sheetBar = LinearLayout(context)
        private val sheetTitle = context.label(12f, Ink.DIM, bold = true)
        private val message = context.label(12.5f, Ink.DIM)
        private val stripsToggle = context.pill("", primary = false, sizeSp = 12.5f) { onToggleStrips() }
        private val hints = hints(context)
        private val tip = context.label(12.5f, Ink.DIM)
        private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        private var rows: List<Row> = emptyList()
        private var views: List<View> = emptyList()

        /** The row carrying the buttons: the one last touched, until a new line is on screen. */
        private var touched: String? = null
        private var newest: String? = null

        init {
            orientation = VERTICAL
            setBackgroundColor(Ink.BG)

            // The game and its session, as the app's page header, with what the page can do beside it.
            val header = LinearLayout(context).apply {
                orientation = VERTICAL
                setBackgroundColor(Ink.SURFACE)
                setPadding(pad, context.dp(10f), context.dp(12f), context.dp(10f))
            }
            val top = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            stats.maxLines = 1
            stats.ellipsize = TextUtils.TruncateAt.END
            top.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            top.addView(context.pill("Note", primary = false, sizeSp = 13f) { beginNote(Noting(newestOnScreen(), null), "Note on " + title.text) }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(10f) })
            top.addView(context.pill("Open LFG", primary = false, sizeSp = 13f) { openApp() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(8f) })
            header.addView(top)
            header.addView(stats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(5f) })
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(divider(context))

            empty.setLineSpacing(context.dp(3f).toFloat(), 1f)
            empty.setPadding(pad, context.dp(18f), pad, context.dp(10f))
            addView(empty)

            list.orientation = VERTICAL
            list.setPadding(0, context.dp(4f), 0, context.dp(10f))
            scroll.isFocusable = false
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(list)
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            // The entry of a word picked here, or a note being written, under the lines.
            sheet.orientation = VERTICAL
            sheet.setBackgroundColor(Ink.BG)
            sheet.addView(divider(context))
            sheetBar.orientation = HORIZONTAL
            sheetBar.gravity = Gravity.CENTER_VERTICAL
            sheetBar.setBackgroundColor(Ink.SURFACE)
            sheetBar.setPadding(pad, context.dp(6f), context.dp(12f), context.dp(6f))
            sheetTitle.letterSpacing = 0.08f
            sheetBar.addView(sheetTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            sheetBar.addView(context.pill("Close", primary = false, sizeSp = 12.5f) { controller.clear() })
            sheet.addView(sheetBar)
            sheet.addView(divider(context))
            sheet.addView(entry, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            note.visibility = View.GONE
            sheet.addView(note, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            message.setPadding(pad, context.dp(4f), pad, context.dp(6f))
            sheet.addView(message)
            sheet.visibility = View.GONE
            addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, 0, SHEET_WEIGHT))

            addView(divider(context))
            val foot = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, context.dp(6f), context.dp(12f), context.dp(6f))
            }
            val words = android.widget.FrameLayout(context)
            hints.setPadding(0, 0, 0, 0)
            words.addView(hints)
            words.addView(tip)
            foot.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(stripsToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(10f) })
            addView(foot)
        }

        private fun divider(context: Context) = View(context).apply {
            setBackgroundColor(Ink.BORDER)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ownTouch()
            return super.dispatchTouchEvent(event)
        }

        fun show(stripsOn: Boolean) {
            showSession(session?.first, session?.second)
            stripsToggle.text = if (stripsOn) "Tap strips on" else "Tap strips off"
            hints.visibility = if (state.controllerMode) View.VISIBLE else View.INVISIBLE
            tip.visibility = if (state.controllerMode) View.GONE else View.VISIBLE
            tip.text = "Tap a word, here or on the game."
            refresh()
        }

        fun showSession(game: String?, text: String?) {
            title.text = game ?: state.lines.firstOrNull()?.gameName ?: "LearnFromGames"
            stats.text = text ?: "No reading yet this session"
        }

        fun showNote(heading: String) {
            note.visibility = View.VISIBLE
            note.open(heading)
        }

        fun hideNote() {
            note.visibility = View.GONE
        }

        /** The line on screen now that a note on the game is hung on: the newest kept one, for its picture. */
        private fun newestOnScreen(): LookupLine? = state.lines.lastOrNull { it.geometry != null && it.lineId != null }

        /** The lines, the notes among them, and the entry or note under them, as they are now. */
        fun refresh() {
            val next = rowsNow()
            val current = next.lastOrNull { it.onScreen >= 0 }?.key
            val arrived = current != newest
            if (arrived) {
                newest = current
                touched = null
            }
            val wasAtEnd = scroll.scrollY + scroll.height >= list.height - context.dp(48f)
            val same = next.size == rows.size && next.indices.all { next[it].key == rows[it].key }
            rows = next
            val active = touched?.takeIf { key -> rows.any { it.key == key } }
                ?: rows.lastOrNull { it.saved != null && it.onScreen >= 0 }?.key ?: rows.lastOrNull { it.saved != null }?.key
            if (same) {
                rows.forEachIndexed { i, row -> (views[i] as? LineRow)?.bind(row, row.key == active) }
            } else {
                list.removeAllViews()
                views = rows.map { row -> if (row.note != null) noteRow(row.note) else LineRow(context).also { it.bind(row, row.key == active) } }
                views.forEach(list::addView)
            }
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            scroll.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            if (rows.isEmpty()) {
                empty.text = "Waiting for text in the game. Each line it shows is listed here as it is read." +
                    if (prefs().aiReady) "" else "\n\nAdd an OpenRouter key in Settings › Translation and each line gets Translate and Explain."
            }

            val writing = note.visibility == View.VISIBLE
            val showEntry = entryOnPanel() && !writing
            val sheetWas = sheet.visibility == View.VISIBLE
            sheet.visibility = if (showEntry || writing) View.VISIBLE else View.GONE
            sheetBar.visibility = if (writing) View.GONE else View.VISIBLE
            entry.visibility = if (showEntry) View.VISIBLE else View.GONE
            if (showEntry) {
                showEntries(entry, state)
                sheetTitle.text = if (state.selectedLine?.geometry == null) "FROM AN EARLIER LINE" else "ON SCREEN NOW"
            }
            message.text = state.message.takeIf { showEntry && state.entry != null } ?: ""
            message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE

            // New text, or the list was at its end: stay at the end. The sheet just opened: keep the line picked in sight.
            val opened = sheet.visibility == View.VISIBLE && !sheetWas
            if (!same && (arrived || wasAtEnd)) scroll.post { scroll.scrollTo(0, list.height) }
            else if (opened) scroll.post { marked()?.let { v -> if (v.bottom > scroll.scrollY + scroll.height) scroll.scrollTo(0, v.bottom - scroll.height) } }
        }

        /** Brings a line's answer into sight: the whole row if it fits, else from its top. */
        fun reveal(lineId: String) = scroll.post {
            val row = rows.indexOfFirst { it.key == lineId }.takeIf { it >= 0 }?.let(views::getOrNull) ?: return@post
            if (row.bottom <= scroll.scrollY + scroll.height && row.top >= scroll.scrollY) return@post
            scroll.smoothScrollTo(0, if (row.height <= scroll.height) row.bottom - scroll.height else row.top)
        }

        /** The row the picked word is in, if it is listed. */
        private fun marked(): View? = rows.indexOfFirst(::picked).takeIf { it >= 0 }?.let(views::getOrNull)

        private fun picked(row: Row): Boolean {
            val line = state.selectedLine?.takeIf { state.selected } ?: return false
            return if (row.onScreen >= 0) state.line == row.onScreen else row.saved != null && line.lineId == row.key
        }

        /** This game's newest lines, oldest first, with its notes among them at their time. */
        private fun rowsNow(): List<Row> {
            val screen = state.lines.withIndex().filter { it.value.geometry != null }
            val game = screen.firstOrNull()?.value?.gameName ?: state.lines.firstOrNull()?.gameName ?: session?.first
            val kept = history.filter { it.gameName == game }.take(HISTORY_SHOWN)
            val out = ArrayList<Row>()
            for (line in kept) {
                val at = screen.firstOrNull { it.value.lineId == line.id }
                // On screen: its text as the screen has it, which the picked word's place is counted in.
                out += Row(line.id, line.createdAt, at?.value?.text ?: line.lineText, saved = line, onScreen = at?.index ?: -1)
            }
            val since = kept.lastOrNull()?.createdAt
            if (since != null) for (n in notes) if (n.gameName == game && n.createdAt >= since) out += Row("n:${n.id}", n.createdAt, n.text, note = n)
            out.sortBy { it.time }
            // Text on screen that is not a kept line (a menu, an ordinary app) comes last, while none of it is.
            if (out.none { it.onScreen >= 0 }) {
                val now = System.currentTimeMillis()
                screen.take(UNKEPT_SHOWN).forEach { out += Row("s:${it.index}:${it.value.text}", now, it.value.text, onScreen = it.index) }
            }
            return out
        }

        private fun tapped(row: Row, index: Int) {
            touched = row.key
            pickedOnPanel = true
            if (row.onScreen >= 0) controller.select(row.onScreen, index)
            else row.saved?.let { controller.lookIn(LookupLine(it.lineText, it.id, picture(it), it.gameName), index) }
            refresh()
        }

        /** A line of the list: its time, its text to tap, what was asked about it, and its buttons while it is the one touched. */
        private inner class LineRow(context: Context) : LinearLayout(context) {
            private var row: Row? = null
            private val bar = View(context)
            private val time = context.label(12f, Ink.DIM)
            private val line = LineText(context, 17f) { index -> row?.let { tapped(it, index) } }
            private val translation = context.label(14.5f, Ink.DIM)
            private val explanation = context.label(14.5f, Ink.TEXT)
            private val tags = context.label(11f, Ink.ACCENT, bold = true)
            private val actions = LinearLayout(context)
            private val translate = context.pill("Translate", primary = false, sizeSp = 12.5f) { row?.saved?.let { ask(it.id, Ask.TRANSLATE) } }
            private val explain = context.pill("Explain", primary = false, sizeSp = 12.5f) { row?.saved?.let { ask(it.id, Ask.EXPLAIN) } }
            private val write = context.pill("Note", primary = false, sizeSp = 12.5f) {
                row?.saved?.let { beginNote(Noting(lookupLine(it), null), "Note on this line") }
            }

            init {
                orientation = HORIZONTAL
                addView(bar, LayoutParams(context.dp(3f), LayoutParams.MATCH_PARENT))
                time.setPadding(context.dp(11f), context.dp(4f), 0, 0)
                addView(time, LayoutParams(context.dp(58f), LayoutParams.WRAP_CONTENT))
                val column = LinearLayout(context).apply { orientation = VERTICAL }
                column.addView(line)
                translation.setLineSpacing(context.dp(3f).toFloat(), 1f)
                column.addView(translation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(5f) })
                explanation.setLineSpacing(context.dp(4f).toFloat(), 1f)
                explanation.background = rounded(Ink.SURFACE, Ink.BORDER, context.dp(10f).toFloat())
                explanation.setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
                column.addView(explanation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f) })
                tags.letterSpacing = 0.08f
                column.addView(tags, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6f) })
                actions.orientation = HORIZONTAL
                for (button in listOf(translate, explain, write)) {
                    actions.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = context.dp(8f) })
                }
                column.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(10f) })
                addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                setPadding(0, context.dp(9f), pad, context.dp(9f))
                // A touch beside the text makes this the line with the buttons.
                setOnClickListener {
                    val key = row?.key ?: return@setOnClickListener
                    touched = key
                    refresh()
                }
                // Nothing here takes focus: a window that is never touched would show the first row as focused.
                isFocusable = false
            }

            fun bind(next: Row, active: Boolean) {
                row = next
                val current = next.onScreen >= 0
                bar.setBackgroundColor(if (current) Ink.ACCENT else TRANSPARENT)
                setBackgroundColor(if (current) Ink.SURFACE else TRANSPARENT)
                time.text = clock.format(java.util.Date(next.time))
                line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (current) 21f else 17f)
                val mark = picked(next)
                line.show(next.text, if (mark) state.spanStart else 0, if (mark) state.markEnd else 0)

                val id = next.saved?.id
                val translated = id?.let { translations[it] }
                val explained = id?.let { explanations[it] }
                val meaning = translated?.text ?: next.saved?.translation
                translation.text = translated?.error ?: if (translated?.busy == true && meaning == null) "Translating…" else meaning.orEmpty()
                translation.setTextColor(if (translated?.error != null) Ink.ACCENT else Ink.DIM)
                translation.visibility = if (translation.text.isEmpty()) View.GONE else View.VISIBLE
                // An explanation is long: it shows on the line with the buttons, and is a tag on the others.
                explanation.text = if (!active) "" else explained?.error ?: explained?.text ?: if (explained?.busy == true) "Explaining…" else ""
                explanation.setTextColor(if (explained?.error != null) Ink.ACCENT else if (explained?.text == null) Ink.DIM else Ink.TEXT)
                explanation.visibility = if (explanation.text.isEmpty()) View.GONE else View.VISIBLE
                tags.text = listOfNotNull(
                    "CARD".takeIf { next.saved?.noteIds?.isNotEmpty() == true },
                    "EXPLAINED".takeIf { !active && explained?.text != null },
                ).joinToString("   ")
                tags.visibility = if (tags.text.isEmpty()) View.GONE else View.VISIBLE

                actions.visibility = if (active && id != null) View.VISIBLE else View.GONE
                val ai = prefs().aiReady
                for ((button, answer, name) in listOf(Triple(translate, translated, "Translate"), Triple(explain, explained, "Explain"))) {
                    button.visibility = if (ai) View.VISIBLE else View.GONE
                    val had = if (button === translate) meaning != null else answer?.text != null
                    button.text = if (answer?.busy == true) "$name…" else if (had) "$name again" else name
                    button.isEnabled = answer?.busy != true
                    button.alpha = if (answer?.busy == true) 0.5f else 1f
                }
            }
        }

        /** A note written while playing, among the lines at its time. */
        private fun noteRow(note: Note): View = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(context.dp(3f), context.dp(6f), pad, context.dp(6f))
            addView(context.label(12f, Ink.DIM).apply {
                text = clock.format(java.util.Date(note.createdAt))
                setPadding(context.dp(11f), context.dp(11f), 0, 0)
            }, LayoutParams(context.dp(58f), LayoutParams.WRAP_CONTENT))
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                background = rounded(Ink.SURFACE, Ink.BORDER, context.dp(10f).toFloat())
                setPadding(context.dp(12f), context.dp(8f), context.dp(12f), context.dp(10f))
            }
            card.addView(context.label(11f, Ink.ACCENT, bold = true).apply {
                text = if (note.term.isEmpty()) "NOTE" else "NOTE  ·  ${note.term}"
                letterSpacing = 0.08f
            })
            card.addView(context.label(15f, Ink.TEXT).apply {
                text = note.text
                setLineSpacing(context.dp(3f).toFloat(), 1f)
                setPadding(0, context.dp(4f), 0, 0)
            })
            addView(card, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun lookupLine(line: GameLine) = LookupLine(line.lineText, line.id, picture(line), line.gameName)

    /** The screen's picture is cleared after a while; the line's own picture stays and stands in for it. */
    private fun picture(line: GameLine): String? =
        line.screenshotPath?.takeIf { java.io.File(it).isFile } ?: line.cropPath?.takeIf { java.io.File(it).isFile }

    /** The app's own window, on the screen the lines are on. */
    private fun openApp() {
        val intent = android.content.Intent(service, io.github.beesan.lfg.ui.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(panelDisplay.takeIf { it != Display.INVALID_DISPLAY } ?: Display.DEFAULT_DISPLAY)
        try {
            service.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "open app", e)
            return
        }
        // The lines give way now; if the app's page never comes to the front, they are back when the wait is over.
        appOpenedAt = android.os.SystemClock.uptimeMillis()
        render()
        handler.postDelayed({ render() }, APP_OPENING_MS + 50)
    }

    /** What was asked about a line on the lines' screen: the answer, that it is being asked for, or what went wrong. */
    private class Answer(val text: String? = null, val busy: Boolean = false, val error: String? = null)

    private val translations = HashMap<String, Answer>()
    private val explanations = HashMap<String, Answer>()

    private fun answers(what: Ask) = if (what == Ask.TRANSLATE) translations else explanations

    private fun ask(lineId: String, what: Ask) {
        val asked = answers(what)
        if (asked[lineId]?.busy == true) return
        asked[lineId] = Answer(asked[lineId]?.text, busy = true)
        panelView?.refresh()
        onAsk(lineId, what)
    }

    /** The answer to Translate or Explain, from the service. */
    fun onAnswer(lineId: String, what: Ask, text: String?, error: String?) {
        val asked = answers(what)
        asked[lineId] = Answer(text ?: asked[lineId]?.text, error = error)
        panelView?.refresh()
        panelView?.reveal(lineId)
    }

    private companion object {
        const val TAG = "LfgOverlay"
        const val STRIP_SLOP = 6
        const val MARK_PAD = 3
        const val BASE = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS
        const val STRIP_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        const val BUTTON_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL
        const val PICKER_FLAGS = BASE

        const val NOTICE_MS = 3500L

        /** How many of the game's lines the lines' screen lists, and how much unkept text on screen. */
        const val HISTORY_SHOWN = 40
        const val UNKEPT_SHOWN = 12

        /** The entry or note under the lines takes this much to the lines' 1. */
        const val SHEET_WEIGHT = 1.35f

        /** How long after Open LFG the lines' screen stays away, for the app's page to come to the front. */
        const val APP_OPENING_MS = 2000L

        /** The popup's corners, as Hachidori's, and the room round it for its shadow. */
        const val POPUP_RADIUS_DP = 14f
        const val SHADE_DP = 18f

        /** How long after a touch outside the strips to wait for the strip or entry it may have been on. */
        const val OUTSIDE_WAIT_MS = 120L
        const val OWN_TOUCH_SLACK_MS = 60L
        const val PASSIVE_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCHABLE
        const val POPUP_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        const val PANEL_FLAGS = BASE or LayoutParams.FLAG_NOT_TOUCH_MODAL

        // Focusable, so it holds the controller; untouchable, so taps still reach the strips and the game.
        const val FOCUS_FLAGS = LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}
