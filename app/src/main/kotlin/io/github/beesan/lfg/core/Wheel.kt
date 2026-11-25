// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import kotlin.math.atan2

/** What a slice of the wheel does. */
enum class WheelAction(val label: String, val about: String) {
    AI("AI", "Translate, explain, recap or ask"),
    NOTE("Entry", "A diary entry on this moment"),
    READ_ONCE("Read once", "Two corners, read at once"),
    BOXES("Boxes", "The game’s own boxes"),
    LINES("Lines", "What was read lately"),
    TRANSLATE("Translate", "This line in your language"),
    EXPLAIN("Explain", "The line, part by part"),
    RECAP("Recap", "The scene, or the story so far"),
    ASK("Ask", "A question about the game"),
    BOX("Box", "Read this box"),
    NEW_BOX("New box", "Draw another box"),
}

/**
 * One slice of the wheel: what it does and, for [WheelAction.BOX], which of
 * the game's boxes it reads, counted from 1, the dialogue box.
 */
data class WheelSlice(val action: WheelAction, val box: Int = 0) {
    val label: String get() = if (action == WheelAction.BOX) "Box $box" else action.label
    val about: String get() = if (action == WheelAction.BOX && box == 1) "Read the dialogue box" else action.about
}

/**
 * The wheel's levels, clockwise from the top. The top level opens AI's and
 * the boxes' own; B goes back to it.
 */
object WheelLevels {
    val TOP = listOf(WheelAction.AI, WheelAction.NOTE, WheelAction.READ_ONCE, WheelAction.BOXES, WheelAction.LINES).map { WheelSlice(it) }
    val AI = listOf(WheelAction.TRANSLATE, WheelAction.EXPLAIN, WheelAction.RECAP, WheelAction.ASK).map { WheelSlice(it) }

    /** A game's [count] boxes, box 1 always among them, drawn or not, and one more to draw. */
    fun boxes(count: Int): List<WheelSlice> = (1..maxOf(count, 1)).map { WheelSlice(WheelAction.BOX, it) } + WheelSlice(WheelAction.NEW_BOX)
}

/** Which slice of the wheel a stick, the D-pad or a finger points at. */
object WheelAim {
    /**
     * How far from the middle a stick must be pushed to point: near its rim, so a
     * stick springing back to the middle, or let go one way at a time, points at
     * nothing on the way and leaves the slice it was pushed to.
     */
    const val DEAD = 0.75f

    /**
     * The slice of [count] that a push to ([x], [y]) points at, y growing
     * downwards, counted clockwise from straight up; null while it is nearer
     * the middle than [dead].
     */
    fun slice(x: Float, y: Float, count: Int, dead: Float = DEAD): Int? {
        if (x * x + y * y < dead * dead) return null
        val degrees = Math.toDegrees(atan2(x.toDouble(), -y.toDouble()))
        // Each slice is centred on its way, so a push half a slice either side of it is still that slice.
        val turned = (degrees + 360.0 + 180.0 / count) % 360.0
        return (turned / (360.0 / count)).toInt() % count
    }

    /** The slice the D-pad's held directions point at: one alone, or two together for a slant. */
    fun pad(up: Boolean, down: Boolean, left: Boolean, right: Boolean, count: Int): Int? {
        val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
        val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        return slice(x, y, count)
    }
}

/**
 * The lookup shortcut, pressed and let go. Let go soon, it is a tap: lookup
 * on or off, as always. Held past [holdMs], it opens the wheel, and letting
 * go picks the slice pointed at.
 */
class ShortcutHold(private val holdMs: Long = HOLD_MS) {
    enum class Release { TAP, HOLD, NONE }

    private var downAt = -1L
    private var held = false

    /** Whether the shortcut is down and has not been let go. */
    val pressed: Boolean get() = downAt >= 0

    fun press(now: Long) {
        downAt = now
        held = false
    }

    /** True once, the first time it is asked after the shortcut has been down for [holdMs]. */
    fun holdReached(now: Long): Boolean {
        if (downAt < 0 || held || now - downAt < holdMs) return false
        held = true
        return true
    }

    /** The hold came to nothing (there was no wheel to open): letting go is then a tap after all. */
    fun cancelHold() {
        held = false
    }

    fun release(): Release {
        if (downAt < 0) return Release.NONE
        val result = if (held) Release.HOLD else Release.TAP
        downAt = -1
        held = false
        return result
    }

    companion object {
        const val HOLD_MS = 350L
    }
}
