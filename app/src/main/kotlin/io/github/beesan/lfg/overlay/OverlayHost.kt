// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.TextView
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.TapGate
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.WheelLevels
import io.github.beesan.lfg.core.WheelSlice
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.ui.EntryLink
import io.github.beesan.lfg.ui.EntryScroller
import io.github.beesan.lfg.ui.Lfg
import io.github.beesan.lfg.ui.LineRowUi
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
 * - in lookup mode, a window that holds the controller and, over a game,
 *   covers it so that no tap reaches it.
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
    /** A tap landed on the app, away from the strips and the entry, while a reading asked for is up. */
    private val onTapElsewhere: () -> Unit,
    /** The read button over an ordinary app: a tap, and a long press. */
    private val onReadButton: () -> Unit,
    private val onReadButtonHeld: () -> Unit,
    /** A box was drawn on the screen and something chosen for it ([RegionPicker]). */
    private val onPicked: (Picked) -> Unit,
    /**
     * A box is to be drawn over the game: its box [slot] (1, the dialogue box, from lookup mode, Crop or the
     * wheel; 2 onwards from the wheel), or with [once] a box read a single time. With [pad] the controller draws
     * it as well as a finger; with [fresh] it is drawn anew, not shown as it is.
     */
    private val onPickBox: (pad: Boolean, once: Boolean, slot: Int, fresh: Boolean) -> Unit,
    /** The game's box [slot] picked on the wheel: it is read once, now. */
    private val onReadBox: (slot: Int) -> Unit,
    /** The game in front's boxes on its screen, from box 1, which is null while it is not drawn. */
    private val gameBoxes: () -> List<Box?>,
    /** Translate or Explain pressed on the lines' screen for this saved line; the answer comes back through [onAnswer]. */
    private val onAsk: (lineId: String, what: Ask) -> Unit,
    /** A question about the game in front, asked from the wheel; the answer comes back through [onGameAnswer]. */
    private val onAskGame: (question: String) -> Unit,
    /** A recap chosen on the lines' screen for the game being read; it comes back through [onRecap]. */
    private val onRecapAsked: (gameId: String, gameName: String, what: RecapOf) -> Unit,
    /** Taps a point of a screen, as a finger would, once a window of ours is on top there: only ever our own focus window. */
    private val tapOn: (displayId: Int, x: Int, y: Int) -> Unit,
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

    /** The tabs of the answer sheet under the lines: two about the line picked, and the recap of the game. */
    enum class SheetTab(val label: String) { TRANSLATE("Translate"), EXPLAIN("Explain"), RECAP("Recap") }

    /** What Recap on the lines' screen can tell: the scene being read, the last session, or the whole story so far. */
    enum class RecapOf(val label: String) { SCENE("Scene"), LAST("Last time"), STORY("Story so far") }

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

    /** The box picker while it shows: in lookup mode the controller is its own. */
    private var pickerView: RegionPicker? = null
    private val handler = android.os.Handler(service.mainLooper)
    private var history: List<GameLine> = emptyList()

    /** The game of the session still going and its stats, as the top of the panel shows them. */
    private var session: Pair<String, String>? = null

    private var ownApp: Set<Int> = emptySet()

    /** When LFG was pressed: the lines' screen is out of the way at once, before the app's page is seen in front. */
    private var appOpenedAt = 0L

    /** This game's notes show among its lines. */
    private var notes: List<Note> = emptyList()

    /** What a note is being written on, while the note editor shows: a word of a line, a line alone, or the game as it is now. */
    private class Noting(val line: LookupLine?, val entry: Entry?)

    private var noting: Noting? = null

    val input = ControllerInput(
        controller,
        swapConfirm = { prefs().swapConfirm },
        stickWords = { prefs().stickWords },
        onClose = { controller.exitControllerMode() },
        onNote = { startNote(state.entryIndex) },
        onScroll = { dy -> panelView?.entries?.scrollBy(dy); popupView?.entry?.scrollBy(0, dy) },
        // An ordinary app has no dialogue box: its regions are read when asked, from the read button.
        onBox = { if (gameInFront && !asking) onPickBox(true, false, 1, false) },
        chordActive = chordActive,
    )

    /**
     * True while something of ours is drawn on the game's screen, so a picture of that screen would include it.
     * A notice is not counted: it is cut out of the picture instead ([noticeOn]), so it never holds reading up.
     */
    val drawingOnGame: Boolean get() = marks.any { it.shown } || popup?.shown == true || covering

    /** True while the game's screen is covered by a box being drawn, the wheel or its card: a picture of the whole screen is no use. */
    val covering: Boolean get() = picker != null || wheelUp

    /** Where the mark and the entry are on the game's screen, in display pixels: what a picture of it shows of ours. */
    fun drawnOn(displayId: Int): List<Box> = if (displayId != gameDisplay) emptyList() else
        (marks + listOfNotNull(shade, popup)).filter { it.shown }.map { Box(it.x, it.y, it.x + it.width, it.y + it.height) }

    /**
     * The mark and the entry are off the game's screen while this is set, and the word stays chosen:
     * the text behind them has changed, and a picture without them is wanted to read it from.
     */
    var aside = false
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    /** Where a notice is showing on [displayId], in display pixels, or null for none. */
    fun noticeOn(displayId: Int): Box? = notice?.takeIf { it.shown && noticeDisplay == displayId }?.let { Box(it.x, it.y, it.x + it.width, it.y + it.height) }

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
        val width get() = lp.width
        val height get() = lp.height

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

        /** Put on the screen parked, before it is first shown: every window made after it then lies over it. */
        fun addParked() {
            if (added) return
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
        // The game has gone: what the wheel was opened over is not there to act on.
        if (!inFront || this.asking) {
            wheelOpen = false
            wheelHeld = false
            closeCard()
            pickerPad = pickerPad && picker != null
        }
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

    /** The system went light or dark: everything on screen takes the other colours now, not at the next line. */
    fun onSystemTheme() = render()

    fun onState(next: LookupState) {
        val wasController = state.controllerMode
        // Another word, or none: a note half written on the last word is let go. A note on a line or the game stays.
        noting?.let { written -> if (written.entry != null && (!next.selected || next.selectedLine?.lineId != written.line?.lineId)) endNote() }
        // The cursor is on the game's own text, so its entry is over the game.
        if (next.controllerMode && !wasController) pickedOnPanel = false
        val entered = next.controllerMode && !wasController
        // A word picked while the answer sheet is up: its entry takes the sheet's place.
        if (next.selected && (!state.selected || next.line != state.line || next.spanStart != state.spanStart || next.selectedLine?.lineId != state.selectedLine?.lineId)) sheetTab = null
        state = next
        if (entered) bringController()
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
        // A page of the app has just left the lines' screen: the controller is the game's again.
        val left = panelDisplay in ownApp && panelDisplay !in displays
        ownApp = displays
        render()
        if (left) bringController()
    }

    /** Every note, newest first; the lines' screen shows the current game's among its lines. */
    fun onNotes(all: List<Note>) {
        notes = all
        panelView?.refresh()
    }

    /** The session still going: its game and its stats; null when there is none. */
    fun onSession(game: String?, stats: String?) {
        session = if (game != null && stats != null) game to stats else null
        panelView?.refresh()
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

    private var controllerSeen = false
    private var controllerSeenAt = 0L

    /** Whether the device has a controller, built in or connected. Asked on every drawing, so looked up at most every few seconds. */
    private fun hasController(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (controllerSeenAt == 0L || now - controllerSeenAt > 3_000) {
            controllerSeenAt = now
            controllerSeen = InputDevice.getDeviceIds().any { id ->
                val device = InputDevice.getDevice(id) ?: return@any false
                !device.isVirtual && device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            }
        }
        return controllerSeen
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    private fun render() {
        if (gameDisplay == Display.INVALID_DISPLAY) return
        // A new scheme, or light and dark swapped: the views take their colours when made, so they are made again.
        val palette = service.palette(prefs())
        val glass = prefs().popupSeeThrough
        // The lines' screen is drawn in Compose, with the app's colours.
        if (Lfg.palette != palette) Lfg.palette = palette
        if (palette != Ink.palette) {
            Ink.palette = palette
            noting = null
            if (cardFor == WheelAction.NOTE) cardFor = null
            (marks + listOfNotNull(popup, shade, panel, card)).forEach { it.remove() }
            marks.clear()
            popup = null
            popupView = null
            shade = null
            panel = null
            panelView = null
            card = null
            cardView = null
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

        // The lookup-mode window goes on the screen first, parked: the strips, the mark and the entry are
        // then all over it, so when it covers the game it does not cover them.
        val cover = focus ?: Win(FocusView(host.first), host.second, FOCUS_FLAGS).also {
            focus = it
            it.addParked()
        }

        // Tap strips: one per row of text with a place on screen. Not under the wheel or its card, which a
        // strip made after them would lie over.
        var used = 0
        if (stripsOn && !wheelUp && TapGate.open(prefs().tapLookup, hasController(), state.controllerMode, asking)) {
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
        val spans = if (gameInFront && state.selected && !aside) state.selectedLine?.geometry?.spanBoxes(state.spanStart, state.markEnd) ?: emptyList() else emptyList()
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
        if (!aside && !wheelUp && entryOverGame(other) && !(pickedOnPanel && panel?.shown == true)) renderPopup(host, span) else hidePopup()

        // Lookup mode: a window that can take focus, and so the controller. It is also up for a
        // moment whenever the controller has to be brought back to the game's screen. The wheel, its
        // card and a box drawn from the wheel hold the controller too, but not while a note or a
        // question is written: the card takes the focus then, for the keyboard.
        val padOurs = wheelOpen || (cardFor != null && !cardTyping()) || pickerPad
        val holding = (state.controllerMode || padOurs) && gameInFront && noting == null
        if (holding || bringing) {
            // Over a game it covers the whole screen, seen through: a tap that misses the words and the entry
            // lands on it and not on the game, and nothing but our own windows is left to take the buttons.
            // The game is not told of it, as it would be of a screen put over it, so nothing pauses.
            // An ordinary app that was asked to be read keeps its taps: one elsewhere ends the reading.
            val whole = if (holding && !asking) bounds(gameDisplay) else null
            if (whole != null) cover.place(0, 0, whole.width, whole.height) else cover.place(0, 0, FOCUS_PX, FOCUS_PX)
            if (holding) cover.view.requestFocus()
        } else {
            cover.hide()
        }
        // The wheel is drawn in that window, which has the controller: the corner our own tap lands on to
        // bring the controller is its own.
        (cover.view as FocusView).wheel.let { wheel ->
            wheel.visibility = if (wheelOpen) View.VISIBLE else View.GONE
            if (wheelOpen) {
                wheel.holes = wheelLine?.geometry?.rows.orEmpty()
                wheel.boxes = gameBoxes()
            }
        }
        renderCard(other)
    }

    /** True for the moment [bringController] has the focus window up to be tapped. */
    private var bringing = false

    /**
     * Brings the controller to the game's screen. On a two-screen handheld the
     * buttons go to whichever screen was last touched on something that takes
     * focus: after a page of the app, or the launcher, on the other screen,
     * that is not the game's, and neither the game nor lookup mode hears them.
     * A tap on our own focus window, in the corner of the game's screen, makes
     * that screen the one again without touching the game.
     */
    private fun bringController() {
        if (gameDisplay == Display.INVALID_DISPLAY || !gameInFront) return
        bringing = true
        render()
        handler.postDelayed({ tapOn(gameDisplay, FOCUS_PX / 2, FOCUS_PX / 2) }, 60)
        // Long enough for the tap, which waits until the window is seen to be up.
        handler.postDelayed({
            bringing = false
            render()
        }, 600)
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
        val win = panel ?: Win(view.view, host.second, PANEL_FLAGS).also { panel = it }
        view.show()
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
        var width = if (screen.height > screen.width) screen.width - 2 * context.dp(10f) else minOf(context.dp(520f), screen.width * 56 / 100)
        val want = if (state.entries.isEmpty()) context.dp(60f) else minOf(screen.height * 66 / 100, context.dp(560f))
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
    /** A message on [displayId] for a few seconds; kept off [avoid], such as a box being read. */
    fun notice(displayId: Int, text: String, avoid: Box? = null) {
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
        val height = view.measuredHeight
        val left = (screen.width - width) / 2
        val margin = context.dp(16f)
        var top = screen.height - height - context.dp(56f)
        if (avoid != null && avoid.intersects(Box(left, top, left + width, top + height))) {
            // Above the box where there is room, else below it, else at the top of the screen.
            top = when {
                avoid.top - height - margin >= margin -> avoid.top - height - margin
                avoid.bottom + margin + height <= screen.height - margin -> avoid.bottom + margin
                else -> margin
            }
        }
        win.place(left, top, width, height)
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    /** The size of a screen, as a box at its corner; null for a screen that is gone. */
    fun screen(displayId: Int): Box? = bounds(displayId)

    /**
     * A screen-sized window to draw a box on; it reports what was chosen for
     * the box and goes. Over a game, [game] is that game: the box is then its
     * dialogue box. From lookup mode ([lookup]) the controller draws it as well
     * as a finger.
     */
    fun pickRegion(displayId: Int, game: PickFor? = null, lookup: Boolean = false, once: Boolean = false) {
        val host = manager(displayId) ?: run {
            padPickFailed()
            return
        }
        val screen = bounds(displayId) ?: run {
            padPickFailed()
            return
        }
        picker?.remove()
        // The controller is ours in lookup mode, or from the wheel, and only on the game's screen.
        val pad = lookup && displayId == gameDisplay
        if (pad) input.release()
        pickerPad = pad
        val view = RegionPicker(host.first, game, pad, prefs().swapConfirm, ::ownTouch, once) { picked ->
            closePicker()
            onPicked(picked)
        }
        val win = Win(view, host.second, PICKER_FLAGS)
        picker = win
        pickerView = view
        win.place(0, 0, screen.width, screen.height)
        panelView?.refresh()
        render()
    }

    /** Whether a box is being drawn. */
    val picking: Boolean get() = picker != null

    /** The box picker goes, with nothing chosen. */
    fun closePicker() {
        picker?.remove()
        picker = null
        pickerView = null
        panelView?.refresh()
        // Drawn from the wheel, the controller goes back to the game.
        if (pickerPad) {
            pickerPad = false
            render()
        }
    }

    /** A box was to be drawn from the wheel, and there turned out to be nothing to draw it on: the controller is the game's again. */
    fun padPickFailed() {
        if (!pickerPad || picker != null) return
        pickerPad = false
        render()
    }

    fun destroy() {
        input.release()
        // Everything posted, the popup's prewarm and the checks among it, would run on windows that are gone.
        handler.removeCallbacksAndMessages(null)
        notice?.remove()
        picker?.remove()
        pickerView = null
        readButton?.remove()
        (strips + marks + listOfNotNull(popup, shade, focus, panel, card)).forEach { it.remove() }
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
    private inner class FocusView(context: Context) : android.widget.FrameLayout(context) {
        /** The wheel, drawn here while it is up: this window has the controller it is pointed with. */
        val wheel = WheelView(context, onPick = ::pickWheel, onDismiss = ::putAwayWheel).apply { visibility = View.GONE }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(wheel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ownTouch()
            return super.dispatchTouchEvent(event)
        }

        // Our own tap, bringing the controller here: it is not a tap on the game.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = true

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

        // While a box is being drawn the controller draws it, and while the wheel or its card is up the
        // controller is theirs; lookup under them stays as it is.
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            (pickerView?.onKey(event) ?: wheelKey(event) ?: input.onKey(event)) || super.dispatchKeyEvent(event)

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            (pickerView?.onMotion(event) ?: wheelMotion(event) ?: input.onMotion(event)) || super.dispatchGenericMotionEvent(event)
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

    private fun hints(context: Context): TextView = context.keyHints(hintsText(context))

    /** The hints as Settings has the buttons now: read each time they show, since Controls can change under them. */
    private fun hintsText(context: Context): String {
        val swap = prefs().swapConfirm
        return keyHintsText(
            if (swap) "B" else "A", if (swap) "A" else "B",
            listen = (context.applicationContext as? io.github.beesan.lfg.App)?.wordAudio?.info?.value != null,
            wholeWord = prefs().picking.wholeWord,
            stickWords = prefs().stickWords,
            box = gameInFront && !asking,
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
        val entry = EntryWebView(context, media) { link, i -> onEntryLink(link, i) }.apply {
            glass = true
            // Read from the game's distance, not a page's.
            settings.textZoom = 112
        }
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
            if (state.controllerMode) hints.text = hintsText(context)
            hints.visibility = if (state.controllerMode) View.VISIBLE else View.GONE
        }
    }

    /** A button in the entry list, by the entry's place in the list. */
    private fun onEntryLink(link: EntryLink, index: Int) {
        when (link) {
            EntryLink.CARD -> controller.addCard(index)
            EntryLink.AUDIO -> controller.playAudio(index)
            EntryLink.NOTE -> startNote(index)
            EntryLink.VN_YES -> controller.answerVn(true)
            EntryLink.VN_NO -> controller.answerVn(false)
            EntryLink.KANJI -> controller.lookUpKanji(index)
            EntryLink.BACK -> controller.backToWord()
        }
    }

    /** The note button of an entry: the lines' screen, or the popup, becomes a note on its word. */
    private fun startNote(index: Int) {
        val entry = state.entries.getOrNull(index) ?: return
        val line = state.selectedLine ?: return
        beginNote(Noting(line, entry), "Note on ${entry.expression}")
    }

    private fun beginNote(next: Noting, heading: String) {
        // With a screen for the lines, the note is written on the app's own page there: nothing
        // drawn over other apps can have the keyboard above it, and the page has the keyboard.
        if (panel?.shown == true) {
            openApp(
                android.content.Intent(io.github.beesan.lfg.ui.MainActivity.ACTION_NOTE)
                    .putExtra(io.github.beesan.lfg.ui.MainActivity.EXTRA_LINE, next.line?.lineId)
                    .putExtra(io.github.beesan.lfg.ui.MainActivity.EXTRA_AT, if (next.entry != null && next.line === state.selectedLine) state.spanStart else -1),
            )
            return
        }
        noting = next
        if (next.entry == null) {
            // On a line or on the game, from the wheel: written in the card over the game, which has no entry to hold it.
            wheelOpen = false
            cardFor = WheelAction.NOTE
            render()
            cardView?.showNote(heading)
            return
        }
        popupView?.showNote(heading)
        popup?.setFocusable(true)
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
        popup?.setFocusable(false)
        panel?.setFocusable(false)
        if (cardFor == WheelAction.NOTE) {
            cardFor = null
            card?.setFocusable(false)
            card?.hide()
        }
    }

    private fun showEntries(view: EntryWebView, state: LookupState) =
        view.showList(state.entries, styles(), state.buttons.map { it.htmlState() }, state.entryIndex, state.message, Ink.palette, askVn = state.vnQuestion?.title, back = state.kanjiFrom)

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
     * The lines' screen of a two-screen handheld: [LinesPanel] in a window of
     * its own. What it shows is worked out here, from the lookup, the lines
     * kept and what was asked of them, and handed to it whole as a [PanelUi].
     */
    private inner class PanelView(context: Context) {
        /** The entry under the lines, which the controller's stick scrolls. */
        val entries = EntryScroller()
        private var ui by mutableStateOf(PanelUi())
        private val actions = Actions()
        val view = OverlayComposeView(context, ::ownTouch) {
            LinesPanel(ui, actions, styles(), media, entries)
        }

        private var rows: List<Row> = emptyList()

        /** The line touched last, which the sheet's Translate and Explain are about; until it is, the newest. */
        private var touched: String? = null
        private var newest: String? = null

        /** The key of the line the sheet is about, as the last refresh found it. */
        private var active: String? = null
        private var sheetWas = false
        private var endSignal = 0
        private var keepSignal = 0

        fun show() = refresh()

        /** The game being read, as the top of the screen names it. */
        private fun title(): String = session?.first ?: state.lines.firstOrNull()?.gameName ?: "LearnFromGames"

        /** The line on screen now that a note on the game is hung on: the newest kept one, for its picture. */
        private fun newestOnScreen(): LookupLine? = state.lines.lastOrNull { it.geometry != null && it.lineId != null }

        /** The lines, the notes among them, and the sheet under them, as they are now. */
        fun refresh() {
            val next = rowsNow()
            val current = next.lastOrNull { it.onScreen >= 0 }?.key
            val arrived = current != newest
            val tab = sheetTab
            val aboutLine = tab == SheetTab.TRANSLATE || tab == SheetTab.EXPLAIN
            if (arrived) {
                newest = current
                // A line being read in the sheet stays its subject when the game moves on.
                if (!aboutLine) touched = null
            }
            val same = next.size == rows.size && next.indices.all { next[it].key == rows[it].key }
            rows = next
            active = touched?.takeIf { key -> rows.any { it.key == key } }
                ?: rows.lastOrNull { it.saved != null && it.onScreen >= 0 }?.key ?: rows.lastOrNull { it.saved != null }?.key
            val lit = active.takeIf { aboutLine }

            val showEntry = entryOnPanel() && tab == null
            val sheet = when {
                tab == SheetTab.RECAP -> recapSheet()
                tab != null -> answerSheet(tab)
                showEntry -> PanelSheet.Entry(
                    if (state.selectedLine?.geometry == null) "FROM AN EARLIER LINE" else "ON SCREEN NOW",
                    state.message.takeIf { state.entry != null },
                )
                else -> null
            }
            // New text: the list goes to its end, unless a line is being read in the sheet.
            // The sheet just opened: the line picked, or the one it is about, stays in sight just above it.
            val opened = sheet != null && !sheetWas
            sheetWas = sheet != null
            if (!same && arrived && lit == null) endSignal++
            val keep = lit ?: rows.firstOrNull(::picked)?.key
            if (opened || (lit != null && !same)) keepSignal++

            ui = PanelUi(
                title = title(),
                stats = session?.second ?: "No reading yet this session",
                rows = rows.map { row -> rowUi(row, row.key == lit) },
                empty = "Waiting for text in the game. Each line it shows is listed here as it is read." +
                    if (prefs().aiReady) "" else "\n\nAdd an OpenRouter key in Settings › Translation and each line gets Translate and Explain.",
                sheet = sheet,
                lookup = state,
                picking = picking,
                foot = if (state.controllerMode) hintsText(view.context) else "Tap a word to look it up, or beside a line to translate or explain it.",
                lit = lit,
                endSignal = endSignal,
                keep = keep,
                keepSignal = keepSignal,
            )
        }

        private fun rowUi(row: Row, lit: Boolean): LineRowUi {
            val id = row.saved?.id
            val mark = picked(row)
            return LineRowUi(
                key = row.key,
                time = row.time,
                text = row.text,
                // The translation is short: it stays under its line. What went wrong, or that it is being asked, is in the sheet.
                translation = id?.let { translations[it]?.text } ?: row.saved?.translation,
                card = row.saved?.noteIds?.isNotEmpty() == true,
                explained = id != null && explanations[id]?.text != null,
                current = row.onScreen >= 0,
                lit = lit,
                markStart = if (mark) state.spanStart else 0,
                markEnd = if (mark) state.markEnd else 0,
                note = row.note,
            )
        }

        /** The kept line the sheet's Translate and Explain are about; null when it is text on screen that is not kept. */
        private fun sheetLine(): GameLine? = rows.firstOrNull { it.key == active }?.saved

        /** The line's translation or explanation: the answer, that it is being written, or what went wrong. */
        private fun answerSheet(tab: SheetTab): PanelSheet.Answer {
            val ai = prefs().aiReady
            val line = sheetLine()
            val translating = tab == SheetTab.TRANSLATE
            val asked = line?.let { (if (translating) translations else explanations)[it.id] }
            val known = asked?.text ?: if (translating) line?.translation?.ifBlank { null } else null
            val name = tab.label
            val text = when {
                line == null -> "This text is not kept as a line, so it cannot be asked about."
                asked?.error != null -> asked.error
                asked?.busy == true && known == null -> if (translating) "Translating…" else "Explaining…"
                known != null -> known
                !ai -> "$name is written by the model in Settings › Translation, with your OpenRouter key. Press LFG to add one."
                else -> ""
            }
            return PanelSheet.Answer(
                tab, text,
                tone = if (asked?.error != null) Tone.ACCENT else if (known == null) Tone.DIM else Tone.TEXT,
                again = if (!ai || line == null) null else if (asked?.busy == true) "$name…" else if (known != null) "$name again" else name,
                againEnabled = asked?.busy != true,
                key = "$tab:${line?.id}",
            )
        }

        /** The recap's three choices, and the one chosen: its text, that it is being written, or what went wrong. */
        private fun recapSheet(): PanelSheet.Recap {
            val ai = prefs().aiReady
            val busy = recaps.values.any { it.busy }
            val answer = recapOf?.let { recaps[it] }
            val text = when {
                !ai -> "A recap is written by the model in Settings › Translation, with your OpenRouter key. Press LFG to add one."
                recapOf == null -> "Which recap? The scene you are in now, the last session, or the whole story so far. " +
                    "Each is told from only what you have read, so nothing ahead is spoiled."
                answer?.error != null -> answer.error
                answer?.text != null -> answer.text
                else -> "Writing…"
            }
            return PanelSheet.Recap(
                choices = if (!ai) emptyList() else RecapOf.entries.map { what ->
                    RecapChoice(what, if (recaps[what]?.busy == true) what.label + "…" else what.label, chosen = what == recapOf, enabled = !busy)
                },
                text = text,
                tone = if (answer?.error != null && ai) Tone.ACCENT else if (answer?.text == null || !ai) Tone.DIM else Tone.TEXT,
            )
        }

        /** A tab chosen: the sheet opens on it, and a line not yet translated or explained is asked about at once. */
        private fun pickTab(tab: SheetTab) {
            openSheet(tab)
            val line = sheetLine() ?: return
            if (!prefs().aiReady) return
            when (tab) {
                SheetTab.TRANSLATE -> if (translations[line.id]?.let { it.text ?: it.error } == null && line.translation.isNullOrBlank()) ask(line.id, Ask.TRANSLATE)
                SheetTab.EXPLAIN -> if (explanations[line.id]?.let { it.text ?: it.error } == null) ask(line.id, Ask.EXPLAIN)
                SheetTab.RECAP -> Unit
            }
        }

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

        private inner class Actions : PanelActions {
            override fun tapCharacter(key: String, index: Int) {
                val row = rows.firstOrNull { it.key == key } ?: return
                touched = row.key
                pickedOnPanel = true
                if (row.onScreen >= 0) controller.select(row.onScreen, index)
                else row.saved?.let { controller.lookIn(lookupLine(it), index) }
                refresh()
            }

            /** A touch beside a line: the sheet about it, on the tab it is on, else on what it lacks. */
            override fun touchRow(key: String) {
                val row = rows.firstOrNull { it.key == key } ?: return
                touched = row.key
                val translated = row.saved?.let { translations[it.id]?.text ?: it.translation?.ifBlank { null } } != null
                pickTab(sheetTab.takeIf { it == SheetTab.TRANSLATE || it == SheetTab.EXPLAIN } ?: if (translated) SheetTab.EXPLAIN else SheetTab.TRANSLATE)
            }

            override fun tab(tab: SheetTab) = pickTab(tab)

            /** Note in the sheet: on the line it is about, or on the game from the recap. */
            override fun noteTab() {
                val line = sheetLine()
                if (sheetTab == SheetTab.RECAP || line == null) beginNote(Noting(newestOnScreen(), null), "Note on " + title())
                else beginNote(Noting(lookupLine(line), null), "Note on this line")
            }

            override fun closeSheet() = if (sheetTab != null) openSheet(null) else controller.clear()

            override fun again() {
                val line = sheetLine() ?: return
                ask(line.id, if (sheetTab == SheetTab.EXPLAIN) Ask.EXPLAIN else Ask.TRANSLATE)
            }

            /** Asks for a recap of the game being read, which its newest kept line names. */
            override fun recap(what: RecapOf) {
                val name = title()
                askRecapOf(history.firstOrNull { it.gameName == name }, what)
            }

            override fun crop() {
                if (picking) closePicker() else if (gameInFront) onPickBox(false, false, 1, false)
            }

            override fun recapButton() = openSheet(if (sheetTab == SheetTab.RECAP) null else SheetTab.RECAP)

            override fun note() = beginNote(Noting(newestOnScreen(), null), "Note on " + title())

            override fun openApp() = this@OverlayHost.openApp()

            override fun entryLink(link: EntryLink, index: Int) = onEntryLink(link, index)
        }
    }

    private fun lookupLine(line: GameLine) = LookupLine(line.lineText, line.id, picture(line), line.gameName, gameId = line.gameId)

    /** The screen's picture is cleared after a while; the line's own picture stays and stands in for it. */
    private fun picture(line: GameLine): String? =
        line.screenshotPath?.takeIf { java.io.File(it).isFile } ?: line.cropPath?.takeIf { java.io.File(it).isFile }

    /** The app's own window, on the screen the lines are on. */
    private fun openApp(asking: android.content.Intent? = null) {
        val intent = (asking ?: android.content.Intent()).setClass(service, io.github.beesan.lfg.ui.MainActivity::class.java)
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

    /** The tab the answer sheet under the lines is open on, or null while it is down. */
    private var sheetTab: SheetTab? = null

    /** Which recap was chosen last, of which game, and what each came back as. */
    private var recapOf: RecapOf? = null
    private var recapGame: String? = null
    private val recaps = HashMap<RecapOf, Answer>()

    /** The answer sheet opens on [tab], in place of a word's entry, or goes down for null. */
    private fun openSheet(tab: SheetTab?) {
        sheetTab = tab
        if (tab != null && state.selected) controller.clear()
        render()
    }

    /**
     * Asks for a recap of the game whose newest kept line is [line], on the
     * lines' screen or in the wheel's card; null when nothing has been read in it.
     */
    private fun askRecapOf(line: GameLine?, what: RecapOf) {
        if (recaps.values.any { it.busy }) return
        recapOf = what
        if (line == null) {
            recaps[what] = Answer(error = "Nothing has been read in this game yet.")
        } else {
            // Another game's recaps are not this one's.
            if (recapGame != line.gameId) recaps.clear()
            recapGame = line.gameId
            recaps[what] = Answer(recaps[what]?.text, busy = true)
            onRecapAsked(line.gameId, line.gameName, what)
        }
        panelView?.refresh()
        if (cardFor == WheelAction.RECAP) render()
    }

    /** The recap asked for, from the service. */
    fun onRecap(what: RecapOf, text: String?, error: String?) {
        recaps[what] = Answer(text ?: recaps[what]?.text, error = error)
        panelView?.refresh()
        if (cardFor == WheelAction.RECAP) render()
    }

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
        if (cardFor == WheelAction.TRANSLATE || cardFor == WheelAction.EXPLAIN) render()
    }

    // ── the wheel ────────────────────────────────────────────────────────────

    /** The wheel is up over the game. */
    private var wheelOpen = false

    /** The shortcut that opened the wheel is still down: letting it go picks the slice pointed at. */
    private var wheelHeld = false

    /** The line the wheel, and what it opens, act on: the word's line, else the newest kept line on screen. */
    private var wheelLine: LookupLine? = null

    /** The D-pad's ways held while the wheel is up: two together point on a slant. */
    private val padHeld = HashSet<Int>()

    /** What the card over the game shows, opened from the wheel; null while there is none. */
    private var cardFor: WheelAction? = null
    private var card: Win? = null
    private var cardView: AnswerCard? = null

    /** The screen the card's window is on. */
    private var cardDisplay = Display.INVALID_DISPLAY

    /** In the recap's card, the choice the controller is on. */
    private var recapCursor = 0

    /** The question asked from the wheel, and what came back. */
    private var gameAnswer: Answer? = null

    /** A box is being drawn with the controller, from the wheel or lookup: the controller is LFG's until it is drawn. */
    private var pickerPad = false

    /** The D-pad's last way on the HAT axes in the card, to step once a push. */
    private var cardHat = 0
    private var cardScrollSpeed = 0f
    private val cardScroller = object : Runnable {
        override fun run() {
            if (cardScrollSpeed == 0f) return
            cardView?.scroll?.scrollBy(0, (cardScrollSpeed * 40).toInt())
            handler.postDelayed(this, 32)
        }
    }

    private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    /** Whether the wheel or a card opened from it is up. */
    val wheelUp: Boolean get() = wheelOpen || cardFor != null

    /** Whether the wheel itself is up, rather than a card opened from it. */
    val wheelShown: Boolean get() = wheelOpen

    /** Whether the wheel can open now: over a game, with nothing else being drawn or written on it. */
    val canOpenWheel: Boolean
        get() = gameInFront && !asking && gameDisplay != Display.INVALID_DISPLAY && picker == null && noting == null

    private fun focusView(): FocusView? = focus?.view as? FocusView

    /** A note or a question is being written in the card: it has the focus, for the keyboard. */
    private fun cardTyping() = cardFor == WheelAction.NOTE || (cardFor == WheelAction.ASK && prefs().aiReady && prefs().ask)

    /**
     * Opens the wheel over the game, as the shortcut is held; false when it
     * cannot open now. It acts on the line of the word looked up, else the
     * newest line on screen.
     */
    fun openWheel(): Boolean {
        if (!canOpenWheel) return false
        closeCard()
        wheelLine = state.selectedLine?.takeIf { state.selected }
            ?: state.lines.lastOrNull { it.geometry != null && it.lineId != null }
            ?: state.lines.lastOrNull { it.geometry != null }
        wheelOpen = true
        wheelHeld = true
        showLevel(WheelLevels.TOP)
        aimAfresh = false
        // Lookup under the wheel stops where it is: nothing may keep stepping while the wheel has the controller.
        input.release()
        render()
        bringController()
        return true
    }

    /** The shortcut that opened the wheel was let go: the slice pointed at is picked, or, with none, the wheel stays to be pointed at and chosen with A. */
    fun letGoWheel() {
        if (!wheelOpen) return
        wheelHeld = false
        val slice = hovered()
        if (slice != null) pickWheel(slice) else render()
    }

    /** The level of the wheel showing: the top one, AI's or the boxes'. */
    private var wheelLevel: List<WheelSlice> = WheelLevels.TOP

    /** A level was just opened: the stick must come back to the middle before it points, or it would point on at once. */
    private var aimAfresh = false

    private fun hovered(): WheelSlice? = focusView()?.wheel?.hover?.let { wheelLevel.getOrNull(it) }

    /** The wheel shows [level], with nothing pointed at. */
    private fun showLevel(level: List<WheelSlice>) {
        wheelLevel = level
        padHeld.clear()
        aimAfresh = true
        focusView()?.wheel?.slices = level
    }

    /** The wheel and its card go, with nothing more done. */
    fun putAwayWheel() {
        if (!wheelUp) return
        wheelOpen = false
        wheelHeld = false
        closeCard()
        render()
    }

    private fun closeCard() {
        if (cardFor == null) return
        if (cardFor == WheelAction.NOTE) {
            endNote()
            return
        }
        cardFor = null
        cardScrollSpeed = 0f
        handler.removeCallbacks(cardScroller)
        card?.setFocusable(false)
        card?.hide()
    }

    private fun pickWheel(slice: WheelSlice) {
        wheelHeld = false
        // A level opens in the wheel, which stays up.
        when (slice.action) {
            WheelAction.AI -> return showLevel(WheelLevels.AI)
            WheelAction.BOXES -> return showLevel(WheelLevels.boxes(gameBoxes().size))
            else -> Unit
        }
        wheelOpen = false
        val line = wheelLine
        when (slice.action) {
            // As in Rubi: two corners, and the box is read at once, with nothing kept.
            WheelAction.READ_ONCE -> drawBox(1, once = true)
            // A box drawn is read; one not drawn yet is drawn first.
            WheelAction.BOX -> if (gameBoxes().getOrNull(slice.box - 1) != null) {
                render()
                onReadBox(slice.box)
            } else {
                drawBox(slice.box)
            }
            WheelAction.NEW_BOX -> drawBox(gameBoxes().size + 1)
            WheelAction.NOTE -> {
                val kept = line?.takeIf { it.lineId != null }
                beginNote(Noting(kept, null), if (kept != null) "Note on this line" else "Note on " + (line?.gameName?.ifBlank { null } ?: "this game"))
            }
            else -> openCard(slice.action)
        }
    }

    /** The card for [action], asking for its answer if there is none yet. */
    private fun openCard(action: WheelAction) {
        cardFor = action
        recapCursor = recapOf?.ordinal ?: 0
        val id = wheelLine?.lineId
        val ai = prefs().aiReady
        when (action) {
            WheelAction.TRANSLATE -> if (ai && id != null && translations[id]?.text == null && savedTranslation(id) == null) ask(id, Ask.TRANSLATE)
            WheelAction.EXPLAIN -> if (ai && id != null && explanations[id]?.text == null) ask(id, Ask.EXPLAIN)
            // A fresh question each time, unless one is still being answered.
            WheelAction.ASK -> if (gameAnswer?.busy != true) gameAnswer = null
            else -> Unit
        }
        render()
    }

    private fun savedTranslation(lineId: String): String? = history.firstOrNull { it.id == lineId }?.translation?.ifBlank { null }

    /** The newest kept line of the game the wheel was opened over: which game a recap or the lines are of. */
    private fun gameLineNow(): GameLine? {
        val line = wheelLine
        return history.firstOrNull { line?.gameId != null && it.gameId == line.gameId }
            ?: (line?.gameName ?: session?.first)?.let { name -> history.firstOrNull { it.gameName == name } }
    }

    private fun askGame(question: String) {
        val asked = question.trim()
        if (asked.isEmpty() || gameAnswer?.busy == true) return
        gameAnswer = Answer(busy = true)
        render()
        onAskGame(asked)
    }

    /** The answer to a question asked from the wheel, from the service. */
    fun onGameAnswer(text: String?, error: String?) {
        gameAnswer = Answer(text ?: gameAnswer?.text, error = error)
        if (cardFor == WheelAction.ASK) render()
    }

    /**
     * The game's box [slot], or with [once] a box to read once, is drawn anew over the game from the wheel,
     * with the controller too, which stays LFG's until it is drawn.
     */
    private fun drawBox(slot: Int, once: Boolean = false) {
        wheelOpen = false
        wheelHeld = false
        pickerPad = true
        render()
        onPickBox(true, once, slot, true)
    }

    /**
     * The card opened from the wheel. On a two-screen handheld it fills the
     * screen the game is not on ([other]), where the lines are, unless the
     * app's own pages are there or something is typed in it: the keyboard
     * would be under it. Otherwise it is over the game, sized to what it says
     * and kept off the line it is about.
     */
    private fun renderCard(other: Int) {
        val what = cardFor
        if (what == null || !gameInFront) {
            card?.hide()
            return
        }
        val apart = other != Display.INVALID_DISPLAY && other !in ownApp && !cardTyping()
        val displayId = if (apart) other else gameDisplay
        // A window is on one screen: on another, the card is made again there.
        if (displayId != cardDisplay) {
            card?.remove()
            card = null
            cardView = null
            cardDisplay = displayId
        }
        val host = manager(displayId) ?: return
        val context = host.first
        val screen = bounds(displayId) ?: return
        val view = cardView ?: AnswerCard(
            context,
            onClose = { putAwayWheel() },
            onAsk = ::askGame,
            onSaveNote = { text, keep -> saveNote(text, keep) },
            onNoteBack = { endNote(); render() },
        ).also { made ->
            cardView = made
            // A tap beside it puts it away, as one beside the entry does; not while writing, as the keyboard is beside it.
            made.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && !cardTyping()) putAwayWheel()
                event.actionMasked == MotionEvent.ACTION_OUTSIDE
            }
        }
        val win = card ?: Win(view, host.second, POPUP_FLAGS).also { card = it }
        if (what != WheelAction.NOTE) view.show(cardContent(what))
        win.setFocusable(cardTyping())
        if (apart) {
            win.place(0, 0, screen.width, screen.height)
            return
        }
        val edge = context.dp(10f)
        val landscape = screen.width > screen.height
        // Landscape: a box at the right of the screen, as tall as what it says, from the top, where a
        // game's dialogue seldom is. Portrait: the width of the screen, above or below the line.
        val width = if (landscape) minOf(context.dp(480f), screen.width * 42 / 100) else screen.width - 2 * edge
        val most = if (landscape) screen.height - 2 * edge else screen.height * 62 / 100
        val height = if (what == WheelAction.NOTE) {
            minOf(most, context.dp(330f))
        } else {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.measuredHeight.coerceIn(context.dp(96f), most)
        }
        val x: Int
        val y: Int
        if (landscape) {
            x = screen.width - width - edge
            y = edge
        } else {
            // Above the line when it fits, else below, else at the top; with no line on screen, in the upper part.
            val line = wheelLine?.geometry?.rows?.reduceOrNull { a, b -> a.union(b) }
            val gap = context.dp(12f)
            x = edge
            y = when {
                line == null -> (screen.height - height) / 4
                line.top - gap - edge >= height -> line.top - gap - height
                screen.height - line.bottom - gap - edge >= height -> line.bottom + gap
                else -> edge
            }.coerceAtLeast(edge)
        }
        win.place(x, y, width, height)
    }

    /** What the card says for [what], as things stand. */
    private fun cardContent(what: WheelAction): CardContent {
        val p = prefs()
        val confirm = if (p.swapConfirm) "B" else "A"
        val close = if (p.swapConfirm) "A" else "B"
        val pad = hasController()
        fun keys(text: String) = if (pad) text else ""
        val line = wheelLine
        val id = line?.lineId
        return when (what) {
            WheelAction.TRANSLATE, WheelAction.EXPLAIN -> {
                val translate = what == WheelAction.TRANSLATE
                val kind = if (translate) Ask.TRANSLATE else Ask.EXPLAIN
                val answer = id?.let { answers(kind)[it] }
                val known = answer?.text ?: if (translate) id?.let(::savedTranslation) else null
                val doing = if (translate) "translated" else "explained"
                val (text, color) = when {
                    line == null -> "Nothing has been read on this screen yet." to Ink.DIM
                    id == null -> "This text is not kept as one of the game's lines, so it cannot be $doing." to Ink.DIM
                    !p.aiReady -> "${what.label} uses the model in Settings › Translation, with your OpenRouter key." to Ink.DIM
                    answer?.error != null -> answer.error to Ink.ACCENT
                    known != null -> known to Ink.TEXT
                    else -> (if (translate) "Translating…" else "Explaining…") to Ink.DIM
                }
                val other = if (translate) WheelAction.EXPLAIN else WheelAction.TRANSLATE
                val usable = p.aiReady && id != null
                CardContent(
                    title = if (translate) "Translation" else "Explanation",
                    line = line?.text,
                    body = text,
                    bodyColor = color,
                    pills = if (!usable) emptyList() else listOf(
                        CardPill(if (answer?.busy == true) "${what.label}…" else "Again", enabled = answer?.busy != true) { id?.let { ask(it, kind) }; render() },
                        CardPill(other.label) { openCard(other) },
                    ),
                    hints = keys(if (usable) "$confirm ${other.label}    $close Close    ▲ ▼ Scroll" else "$close Close"),
                )
            }
            WheelAction.RECAP -> {
                val game = gameLineNow()
                val mine = game != null && recapGame == game.gameId
                val busy = recaps.values.any { it.busy }
                val chosen = recapOf.takeIf { mine }
                val answer = chosen?.let { recaps[it] }
                val (text, color) = when {
                    !p.aiReady -> "A recap is written by the model in Settings › Translation, with your OpenRouter key." to Ink.DIM
                    game == null -> "Nothing has been read in this game yet." to Ink.DIM
                    chosen == null -> "Which recap? The scene you are in now, the last session, or the whole story so far. " +
                        "Each is told from only what you have read, so nothing ahead is spoiled." to Ink.DIM
                    answer?.error != null -> answer.error to Ink.ACCENT
                    answer?.text != null -> answer.text to Ink.TEXT
                    else -> "Writing…" to Ink.DIM
                }
                CardContent(
                    title = "Recap",
                    choices = if (!p.aiReady || game == null) emptyList() else RecapOf.entries.map { kind ->
                        CardChoice(
                            if (mine && recaps[kind]?.busy == true) kind.label + "…" else kind.label,
                            chosen = kind == chosen, marked = pad && kind.ordinal == recapCursor, enabled = !busy,
                        ) {
                            recapCursor = kind.ordinal
                            askRecapOf(game, kind)
                        }
                    },
                    body = text,
                    bodyColor = color,
                    hints = keys(if (p.aiReady && game != null) "◀ ▶ Choose    $confirm Tell    $close Close    ▲ ▼ Scroll" else "$close Close"),
                )
            }
            WheelAction.ASK -> {
                val answer = gameAnswer
                val (text, color) = when {
                    !p.aiReady -> "Ask uses the model in Settings › Translation, with your OpenRouter key." to Ink.DIM
                    !p.ask -> "Turn on Ask in Settings › Translation." to Ink.DIM
                    answer?.busy == true -> "Asking…" to Ink.DIM
                    answer?.error != null -> answer.error to Ink.ACCENT
                    answer?.text != null -> answer.text to Ink.TEXT
                    else -> "Answered from walkthroughs and wikis on the web, without spoiling anything past the lines you have read." to Ink.DIM
                }
                CardContent(title = "Ask about this game", question = p.aiReady && p.ask, body = text, bodyColor = color)
            }
            WheelAction.LINES -> {
                val game = gameLineNow()
                val lines = if (game == null) emptyList() else history.filter { it.gameId == game.gameId }.take(LINES_SHOWN).reversed()
                CardContent(
                    title = "Lines" + (game?.gameName?.ifBlank { null }?.let { " · $it" } ?: ""),
                    rows = lines.map {
                        CardRow(clock.format(java.util.Date(it.createdAt)), it.lineText, translations[it.id]?.text ?: it.translation?.ifBlank { null }, current = it.id == id)
                    },
                    body = if (lines.isEmpty()) "Nothing has been read in this game yet." else null,
                    bodyColor = Ink.DIM,
                    pills = listOf(CardPill("Open LFG") { putAwayWheel(); openApp() }),
                    hints = keys("$confirm Open LFG    $close Close    ▲ ▼ Scroll"),
                )
            }
            else -> CardContent(title = what.label)
        }
    }

    /** A key while the wheel or its card holds the controller; null when neither does. */
    private fun wheelKey(event: KeyEvent): Boolean? = when {
        wheelOpen -> wheelOpenKey(event)
        cardFor != null && !cardTyping() -> cardKey(event)
        else -> null
    }

    private fun wheelMotion(event: MotionEvent): Boolean? = when {
        wheelOpen -> pointWheel(event)
        cardFor != null && !cardTyping() -> cardMotion(event)
        else -> null
    }

    private fun aim(slice: Int?) {
        if (aimAfresh) return
        val wheel = focusView()?.wheel ?: return
        if (slice == null || slice == wheel.hover) return
        wheel.hover = slice
    }

    private fun wheelOpenKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val swap = prefs().swapConfirm
        val confirm = if (swap) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val close = if (swap) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) padHeld += event.keyCode else padHeld -= event.keyCode
                // Pointed when pressed; letting one of two go leaves the slant they pointed at. A press is meant, so it points at once.
                if (down) {
                    aimAfresh = false
                    aim(
                        WheelAim.pad(
                            KeyEvent.KEYCODE_DPAD_UP in padHeld, KeyEvent.KEYCODE_DPAD_DOWN in padHeld,
                            KeyEvent.KEYCODE_DPAD_LEFT in padHeld, KeyEvent.KEYCODE_DPAD_RIGHT in padHeld,
                            wheelLevel.size,
                        ),
                    )
                }
            }
            confirm, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) {
                hovered()?.let(::pickWheel)
            }
            // On release, as lookup's own close is: the button's up must not be left for what shows next.
            // In AI's or the boxes' level, back to the top one.
            close, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) {
                if (wheelLevel === WheelLevels.TOP) putAwayWheel() else showLevel(WheelLevels.TOP)
            }
            // Pointed at a box that is drawn: it is drawn again.
            KeyEvent.KEYCODE_BUTTON_X -> if (down && event.repeatCount == 0) {
                val slice = hovered()
                if (slice?.action == WheelAction.BOX && gameBoxes().getOrNull(slice.box - 1) != null) drawBox(slice.box)
            }
            else -> return KeyEvent.isGamepadButton(event.keyCode)
        }
        return true
    }

    /** The sticks and the D-pad on the HAT axes point at a slice; let go, the slice stays pointed at. */
    private fun pointWheel(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0 || event.action != MotionEvent.ACTION_MOVE) return false
        val count = wheelLevel.size
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val (rx, ry) = rightStick(event)
        // A level just opened: nothing points until the sticks are back in the middle.
        if (aimAfresh) {
            val still = 0.3f * 0.3f
            if (hx == 0f && hy == 0f && lx * lx + ly * ly < still && rx * rx + ry * ry < still) aimAfresh = false
            return true
        }
        aim(WheelAim.slice(hx, hy, count))
        // Whichever stick is pushed farther.
        aim(if (lx * lx + ly * ly >= rx * rx + ry * ry) WheelAim.slice(lx, ly, count) else WheelAim.slice(rx, ry, count))
        return true
    }

    /** The right stick: pads disagree on its axes, Z and RZ being the common ones and RX and RY the others. */
    private fun rightStick(event: MotionEvent): Pair<Float, Float> {
        val x = event.getAxisValue(MotionEvent.AXIS_Z).takeIf { kotlin.math.abs(it) > 0.2f } ?: event.getAxisValue(MotionEvent.AXIS_RX)
        val y = event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { kotlin.math.abs(it) > 0.2f } ?: event.getAxisValue(MotionEvent.AXIS_RY)
        return x to y
    }

    private fun cardKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val swap = prefs().swapConfirm
        val confirm = if (swap) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val close = if (swap) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val step = service.dp(90f)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) cardView?.scrollBy(-step)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) cardView?.scrollBy(step)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down && event.repeatCount == 0) moveRecapCursor(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && event.repeatCount == 0) moveRecapCursor(1)
            confirm, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) cardConfirm()
            close, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) putAwayWheel()
            else -> return KeyEvent.isGamepadButton(event.keyCode)
        }
        return true
    }

    private fun cardMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0 || event.action != MotionEvent.ACTION_MOVE) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hat = when {
            hatY <= -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            hatY >= 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            hatX <= -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            hatX >= 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
        // Once a push, as a key press is.
        if (hat != cardHat) {
            cardHat = hat
            if (hat != 0) cardKey(KeyEvent(KeyEvent.ACTION_DOWN, hat))
        }
        // Either stick scrolls, held.
        val left = event.getAxisValue(MotionEvent.AXIS_Y)
        val right = rightStick(event).second
        val speed = when {
            kotlin.math.abs(right) > 0.2f -> right
            kotlin.math.abs(left) > 0.4f -> left
            else -> 0f
        }
        val wasIdle = cardScrollSpeed == 0f
        cardScrollSpeed = speed
        if (wasIdle && speed != 0f) cardScroller.run()
        return true
    }

    private fun moveRecapCursor(by: Int) {
        if (cardFor != WheelAction.RECAP) return
        recapCursor = (recapCursor + by).coerceIn(0, RecapOf.entries.size - 1)
        render()
    }

    /** The card's main button, A: the other of Translate and Explain, the recap the cursor is on, or the app's lines. */
    private fun cardConfirm() {
        when (cardFor) {
            WheelAction.TRANSLATE -> if (prefs().aiReady && wheelLine?.lineId != null) openCard(WheelAction.EXPLAIN)
            WheelAction.EXPLAIN -> if (prefs().aiReady && wheelLine?.lineId != null) openCard(WheelAction.TRANSLATE)
            WheelAction.RECAP -> gameLineNow()?.takeIf { prefs().aiReady }?.let { askRecapOf(it, RecapOf.entries[recapCursor]) }
            WheelAction.LINES -> {
                putAwayWheel()
                openApp()
            }
            else -> Unit
        }
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

        /** How many of the game's lines the wheel's Lines card lists. */
        const val LINES_SHOWN = 15

        /** How long after LFG is pressed the lines' screen stays away, for the app's page to come to the front. */
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

        // Focusable, so it holds the controller. Our own tap lands in its corner to bring the controller to
        // this screen. Over a game in lookup mode it is the whole screen; otherwise a few pixels in that
        // corner, so every other tap still reaches the strips and the app.
        const val FOCUS_FLAGS = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS
        const val FOCUS_PX = 4
    }
}
