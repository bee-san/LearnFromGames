// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.beesan.lfg.core.KeyRepeat
import io.github.beesan.lfg.lookup.LookupController
import io.github.beesan.lfg.lookup.Move

/**
 * The controller in lookup mode, after Rubi for Vita and Kimochi: the D-pad
 * steps words (or characters, in Character selection) and rows, the left stick
 * goes the same four ways a character at a time and faster while held, one
 * face button adds the card and the other gives the controller back to the
 * game, the shoulder buttons change entry and the right stick scrolls it.
 * With [stickWords] the D-pad and the left stick change places. Select draws
 * the game's dialogue box, as Rubi's Square does.
 *
 * A D-pad reaches a focused window either as keys or as the HAT axes, depending
 * on the pad; both are handled, and a held direction repeats on Rubi's timings
 * rather than the system's.
 */
class ControllerInput(
    private val controller: LookupController,
    private val swapConfirm: () -> Boolean,
    private val stickWords: () -> Boolean,
    private val onClose: () -> Unit,
    /** X: a note on the word shown. */
    private val onNote: () -> Unit,
    private val onScroll: (Int) -> Unit,
    /** Select: the game's dialogue box is to be drawn. */
    private val onBox: () -> Unit,
    /** True while the shortcut's own buttons are down, so releasing them does not also change entry. */
    private val chordActive: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pad = Repeater(characters = { stickWords() })
    private val stick = Repeater(characters = { !stickWords() }, fast = KeyRepeat.stick())
    private var scrollSpeed = 0f
    private val scroller = object : Runnable {
        override fun run() {
            if (scrollSpeed == 0f) return
            onScroll((scrollSpeed * 40).toInt())
            handler.postDelayed(this, 32)
        }
    }

    /**
     * A held direction of the D-pad or the left stick, stepping by word or, with [characters], by
     * character. A stick going by character repeats at Rubi's quicker stick pace ([fast]); a button,
     * and anything going by word, at the button's, so one press is one step.
     */
    private inner class Repeater(private val characters: () -> Boolean, private val fast: KeyRepeat? = null) : Runnable {
        private val slow = KeyRepeat()
        private var repeat = slow
        private var move: Move? = null

        fun hold(next: Move?) {
            if (next == move) return
            handler.removeCallbacks(this)
            move = next
            repeat.release()
            repeat = fast?.takeIf { characters() } ?: slow
            if (next != null) run()
        }

        override fun run() {
            val current = move ?: return
            val now = SystemClock.uptimeMillis()
            if (repeat.press(current.ordinal, now)) controller.move(current, characters())
            handler.postDelayed(this, (repeat.nextRepeatAt - now).coerceAtLeast(1))
        }
    }

    /** Returns true when the event was used; in lookup mode nothing reaches the game either way. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val move = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Move.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Move.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Move.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Move.DOWN
            else -> null
        }
        if (move != null) {
            // The system's own repeats are ignored; ours run from the first press.
            if (down && event.repeatCount == 0) pad.hold(move) else if (!down) pad.hold(null)
            return true
        }
        val confirm = if (swapConfirm()) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val close = if (swapConfirm()) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        when (event.keyCode) {
            confirm, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) controller.addCard()
            close, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) onClose()
            // On release, and not when the two were the shortcut being pressed.
            KeyEvent.KEYCODE_BUTTON_Y -> if (down && event.repeatCount == 0) controller.playAudio()
            // On release: the note's field then takes the controller's keys, and must not get this one's up.
            KeyEvent.KEYCODE_BUTTON_X -> if (!down) onNote()
            // On release, and not when it was the shortcut being pressed with Start.
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (!down && !chordActive()) onBox()
            KeyEvent.KEYCODE_BUTTON_L1 -> if (!down && !chordActive()) controller.previousEntry()
            KeyEvent.KEYCODE_BUTTON_R1 -> if (!down && !chordActive()) controller.nextEntry()
            else -> return KeyEvent.isGamepadButton(event.keyCode)
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0 || event.action != MotionEvent.ACTION_MOVE) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        pad.hold(
            when {
                hatX <= -0.5f -> Move.LEFT
                hatX >= 0.5f -> Move.RIGHT
                hatY <= -0.5f -> Move.UP
                hatY >= 0.5f -> Move.DOWN
                else -> null
            },
        )
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        stick.hold(
            when {
                // A diagonal counts as the way pushed farther, a tie as left or right.
                x <= -STICK && -x >= kotlin.math.abs(y) -> Move.LEFT
                x >= STICK && x >= kotlin.math.abs(y) -> Move.RIGHT
                y <= -STICK -> Move.UP
                y >= STICK -> Move.DOWN
                else -> null
            },
        )
        // Pads disagree on where the right stick's vertical axis is; RZ is the common one, RY the other.
        val right = event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { kotlin.math.abs(it) > 0.2f } ?: event.getAxisValue(MotionEvent.AXIS_RY)
        val speed = if (kotlin.math.abs(right) > 0.2f) right else 0f
        val wasIdle = scrollSpeed == 0f
        scrollSpeed = speed
        if (wasIdle && speed != 0f) scroller.run()
        return true
    }

    /** Lookup mode ended: nothing may keep repeating. */
    fun release() {
        pad.hold(null)
        stick.hold(null)
        scrollSpeed = 0f
        handler.removeCallbacks(scroller)
    }

    private companion object {
        const val STICK = 0.6f
    }
}
