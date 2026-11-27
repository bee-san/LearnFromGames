// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.BoxDrawing
import io.github.beesan.lfg.core.KeyRepeat
import kotlin.math.abs

/**
 * The game a box is drawn for: its row, its name, which of its boxes ([slot]:
 * 1, the dialogue box it is read in, or 2 onwards, read when picked on the
 * wheel or [always] beside the dialogue box), and that box as it is now, if it has one.
 */
class PickFor(val gameId: String, val title: String, val saved: Box?, val slot: Int = 1, val always: Boolean = false)

/** What was chosen for a box drawn on the screen. Boxes are in the display's pixels. */
sealed interface Picked {
    /** Read it once. */
    data class Once(val box: Box) : Picked

    /** Read it on every change until told to stop: an ordinary app's subtitles, a stream. */
    data class Keep(val box: Box) : Picked

    /**
     * It is this game's box [PickFor.slot]: for box 1, the game is read there only from now on; box 2
     * onwards is read when picked on the wheel, or with [always] all the time beside box 1.
     */
    data class ForGame(val game: PickFor, val box: Box, val always: Boolean = false) : Picked

    /** This game's box [PickFor.slot] is taken away: for box 1, the game is read on its whole screen again. */
    data class WholeScreen(val game: PickFor) : Picked

    data object Cancelled : Picked
}

/**
 * The whole screen dimmed, to draw a box on: with a finger in one drag, or,
 * while the controller is LFG's, with the stick or the D-pad from one corner
 * to the opposite one, as Rubi for Vita's dialogue region is drawn. Once it is
 * drawn the choices show under it.
 *
 * Over an ordinary app they are to read the box once, to keep reading it, or
 * to cancel. Over a game ([game]) the first is to read the game there only
 * from now on; a game that already has a box is shown it, and can go back to
 * its whole screen.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class RegionPicker(
    context: Context,
    private val game: PickFor?,
    /** Whether the controller's buttons come here: they do from lookup mode, and not when the app in front has them. */
    private val pad: Boolean,
    /** The face buttons the other way round, as Settings › Controls has them. */
    private val swapConfirm: Boolean,
    /** A finger came down here, so the touch is not one on the app behind. */
    private val onTouched: () -> Unit,
    /** The box is to be read once, and nothing else: Read once on the wheel. */
    private val once: Boolean = false,
    private val onDone: (Picked) -> Unit,
) : FrameLayout(context) {
    private class Option(val label: String, val pick: (Box) -> Picked)

    private var drawing = BoxDrawing(1, 1)
    private var options: List<Option> = emptyList()
    private var selected = 0

    private val confirmName = if (swapConfirm) "B" else "A"
    private val backName = if (swapConfirm) "A" else "B"

    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val clear = Paint().apply { color = Color.argb(30, 255, 255, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f).toFloat()
        color = Ink.ACCENT
        pathEffect = DashPathEffect(floatArrayOf(context.dp(8f).toFloat(), context.dp(5f).toFloat()), 0f)
    }
    private val cursorUnder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(5f).toFloat()
        color = Color.argb(200, 0, 0, 0)
    }
    private val cursorOver = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f).toFloat()
        color = Color.WHITE
    }

    private val hint = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setShadowLayer(context.dp(3f).toFloat(), 0f, 0f, Color.BLACK)
        setPadding(context.dp(16f), context.dp(14f), context.dp(16f), context.dp(14f))
    }

    private val choices = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = GONE
    }

    init {
        setWillNotDraw(false)
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(choices, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // The window fills the screen, so the game's box is where it was drawn.
        drawing = BoxDrawing(w, h, start = game?.saved?.takeIf { it.right <= w && it.bottom <= h })
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(padRepeat)
        removeCallbacks(stickTick)
    }

    // ── what shows ───────────────────────────────────────────────────────────

    private fun refresh() {
        val done = drawing.stage == BoxDrawing.Stage.DONE
        // As in Rubi: the second corner is the end. A box to read once is read, and a game's dialogue box is kept,
        // at once. Box 2 onwards asks when it is read.
        if (done && drawing.changed && (once || game?.slot == 1)) {
            take(if (once) Option("") { Picked.Once(it) } else Option("") { Picked.ForGame(game!!, it) })
            return
        }
        hint.text = hintNow()
        if (done) showChoices() else choices.visibility = GONE
        invalidate()
    }

    /** What the box is for, at the start of the hint. */
    private fun what(): String = when {
        once -> "Read once"
        game == null -> "Read"
        game.slot == 1 -> "Dialogue box of ${game.title}"
        else -> "Box ${game.slot} of ${game.title}"
    }

    private fun hintNow(): String {
        val size = drawing.box?.let { "${it.width} × ${it.height}" }.orEmpty()
        return when (drawing.stage) {
            BoxDrawing.Stage.AIMING ->
                if (pad) "${what()}: move to a corner and press $confirmName, or tap it. $backName leaves." else "${what()}: tap a corner, or drag across"
            BoxDrawing.Stage.STRETCHING ->
                if (pad) "$size · The opposite corner: move and press $confirmName, or tap it. $backName takes the corner back." else "$size · Tap the opposite corner"
            BoxDrawing.Stage.DONE -> when {
                game != null && game.slot == 1 && !drawing.changed -> "${game.title} is read only in this box." + if (pad) " $backName moves its corner." else " Draw a new one to change it."
                game != null && !drawing.changed -> what() + (if (game.always) ": always read." else ": read when picked.") + if (pad) " $backName moves its corner." else " Draw a new one to change it."
                pad -> "$size · $confirmName chooses. $backName moves the corner."
                else -> size
            }
        }
    }

    /** What can be done with a finished box: over an ordinary app, how to read it; for a game's own box, keep it or not. */
    private fun optionsNow(): List<Option> = when {
        game == null -> listOf(Option("Read once") { Picked.Once(it) }, Option("Keep reading") { Picked.Keep(it) }, Option("Cancel") { Picked.Cancelled })
        game.slot == 1 -> listOf(Option("Keep this box") { Picked.Cancelled }, Option("Read the whole screen") { Picked.WholeScreen(game) })
        // As it was read before first, so that a box drawn again stays as it was with one press.
        drawing.changed -> listOf(Option("Read when picked") { Picked.ForGame(game, it) }, Option("Always read") { Picked.ForGame(game, it, always = true) })
            .let { if (game.always) it.reversed() else it }
        else -> listOf(
            Option("Keep this box") { Picked.Cancelled },
            if (game.always) Option("Read when picked") { Picked.ForGame(game, it) } else Option("Always read") { Picked.ForGame(game, it, always = true) },
            Option("Remove box") { Picked.WholeScreen(game) },
        )
    }

    private fun showChoices() {
        val b = drawing.box ?: return
        val next = optionsNow()
        if (next.map { it.label } != options.map { it.label }) selected = 0
        options = next
        choices.removeAllViews()
        options.forEachIndexed { index, option -> choices.addView(choice(option, index)) }
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

    private fun choice(option: Option, index: Int) = TextView(context).apply {
        val primary = index == 0
        // With the controller, the choice a press of the confirm button takes is the one outlined.
        val marked = pad && index == selected
        text = option.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = context.dp(44f)
        setPadding(context.dp(16f), 0, context.dp(16f), 0)
        setTextColor(if (primary) Ink.ON_FILL else Ink.TEXT)
        background = rounded(
            if (primary) Ink.FILL else Ink.SURFACE, if (marked) Color.WHITE else if (primary) Ink.FILL else Ink.BORDER_STRONG,
            context.dp(5f).toFloat(), if (marked) context.dp(3f) else 1,
        )
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = context.dp(8f)
        }
        setOnClickListener { take(option) }
    }

    private fun take(option: Option) {
        val chosen = drawing.box ?: return
        val at = IntArray(2).also(::getLocationOnScreen)
        onDone(option.pick(chosen.moved(at[0], at[1])))
    }

    // ── a finger ─────────────────────────────────────────────────────────────

    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** A drag that begins on one of the choices is a new box, not a press of the choice: the choices lie where text may. */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                drawing.touchDown(downX.toInt(), downY.toInt())
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onTouched()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> drawing.touchDown(event.x.toInt(), event.y.toInt())
            MotionEvent.ACTION_MOVE -> drawing.touchMove(event.x.toInt(), event.y.toInt())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawing.touchUp()
        }
        refresh()
        return true
    }

    // ── the controller ───────────────────────────────────────────────────────

    /** A button of the controller, which the lookup-mode window passes on while this shows. Always used: none may reach lookup under it. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val way = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> null
        }
        if (way != null) {
            // The system's own repeats are ignored; ours run from the first press.
            if (down && event.repeatCount == 0) holdPad(way) else if (!down) holdPad(null)
            return true
        }
        val confirm = if (swapConfirm) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val back = if (swapConfirm) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        when (event.keyCode) {
            confirm, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) {
                if (drawing.stage == BoxDrawing.Stage.DONE) options.getOrNull(selected)?.let(::take) else drawing.confirm()
                refresh()
            }
            // On release, as lookup's own close is: the button's up must not be left for what shows next.
            back, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) {
                if (drawing.back()) refresh() else onDone(Picked.Cancelled)
            }
            // The volume keys and the like stay the system's.
            else -> return KeyEvent.isGamepadButton(event.keyCode)
        }
        return true
    }

    /** The sticks and, on pads that send it so, the D-pad. */
    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0 || event.action != MotionEvent.ACTION_MOVE) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        holdPad(
            when {
                hatX <= -0.5f -> -1 to 0
                hatX >= 0.5f -> 1 to 0
                hatY <= -0.5f -> 0 to -1
                hatY >= 0.5f -> 0 to 1
                else -> null
            },
        )
        val x = event.getAxisValue(MotionEvent.AXIS_X).takeIf { abs(it) > DEAD } ?: 0f
        val y = event.getAxisValue(MotionEvent.AXIS_Y).takeIf { abs(it) > DEAD } ?: 0f
        val wasStill = stickX == 0f && stickY == 0f
        // Pushed across to one side, once: the choice beside the one marked.
        if (drawing.stage == BoxDrawing.Stage.DONE && abs(x) >= FLICK && abs(stickX) < FLICK) choose(if (x > 0) 1 else -1)
        stickX = x
        stickY = y
        if (wasStill && (x != 0f || y != 0f)) {
            stickSince = SystemClock.uptimeMillis()
            stickAt = stickSince
            restX = 0f
            restY = 0f
            post(stickTick)
        }
        return true
    }

    private var padWay: Pair<Int, Int>? = null
    private val padTimes = KeyRepeat(firstMs = 300, nextMs = 16, fasterByMs = 40)
    private val padRepeat = object : Runnable {
        override fun run() {
            val way = padWay ?: return
            val now = SystemClock.uptimeMillis()
            if (padTimes.press(0, now)) step(way.first, way.second)
            postDelayed(this, (padTimes.nextRepeatAt - now).coerceAtLeast(1))
        }
    }

    /** A direction of the D-pad held, or let go: a pixel a press, and faster while held. */
    private fun holdPad(way: Pair<Int, Int>?) {
        if (way == padWay) return
        removeCallbacks(padRepeat)
        padWay = way
        padTimes.release()
        if (way == null) return
        if (drawing.stage == BoxDrawing.Stage.DONE) choose(way.first) else padRepeat.run()
    }

    private var stickX = 0f
    private var stickY = 0f
    private var stickSince = 0L
    private var stickAt = 0L
    private var restX = 0f
    private var restY = 0f
    private val stickTick = object : Runnable {
        override fun run() {
            if (stickX == 0f && stickY == 0f) return
            val now = SystemClock.uptimeMillis()
            val tick = (now - stickAt).coerceIn(1, 50)
            stickAt = now
            // Whole pixels are moved; what is left of each step is carried to the next.
            val dx = BoxDrawing.stickStep(stickX, now - stickSince, tick, height) + restX
            val dy = BoxDrawing.stickStep(stickY, now - stickSince, tick, height) + restY
            restX = dx - dx.toInt()
            restY = dy - dy.toInt()
            step(dx.toInt(), dy.toInt())
            postDelayed(this, TICK_MS)
        }
    }

    private fun step(dx: Int, dy: Int) {
        if (drawing.stage == BoxDrawing.Stage.DONE || dx == 0 && dy == 0) return
        drawing.move(dx, dy)
        refresh()
    }

    private fun choose(by: Int) {
        if (by == 0 || options.isEmpty()) return
        selected = (selected + by).coerceIn(0, options.lastIndex)
        showChoices()
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val b = drawing.box
        if (b == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        } else {
            // Dim around the box, not over it, so the text inside reads clearly.
            canvas.drawRect(0f, 0f, width.toFloat(), b.top.toFloat(), dim)
            canvas.drawRect(0f, b.bottom.toFloat(), width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(), dim)
            canvas.drawRect(b.right.toFloat(), b.top.toFloat(), width.toFloat(), b.bottom.toFloat(), dim)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), clear)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), edge)
        }
        if (!pad || drawing.stage == BoxDrawing.Stage.DONE) return
        // The cursor: a cross on the corner being placed, dark under light so it shows on any picture.
        val arm = context.dp(14f).toFloat()
        val x = drawing.x.toFloat()
        val y = drawing.y.toFloat()
        for (paint in arrayOf(cursorUnder, cursorOver)) {
            canvas.drawLine(x - arm, y, x + arm, y, paint)
            canvas.drawLine(x, y - arm, x, y + arm, paint)
        }
    }

    private companion object {
        /** The stick at rest is never quite at rest. */
        const val DEAD = 0.2f

        /** How far across the stick counts as pushed to that side. */
        const val FLICK = 0.6f
        const val TICK_MS = 16L
    }
}
