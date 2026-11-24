// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import kotlin.math.atan2

/**
 * What the wheel offers, clockwise from the top: one slice for each way a
 * stick or the D-pad can point, the ones used most on the four straight ways.
 */
enum class WheelAction(val label: String, val about: String) {
    TRANSLATE("Translate", "This line in your language"),
    ASK("Ask", "A question about the game"),
    EXPLAIN("Explain", "The line, part by part"),
    RECAP("Recap", "The scene, or the story so far"),
    NOTE("Note", "A note on this line"),
    BOX_1("Box 1", "Read the main box"),
    BOX_2("Box 2", "Read the second box"),
    LINES("Lines", "What was read lately"),
}

/** The box a slice reads: 1 or 2, or null for a slice that is not a box. */
val WheelAction.box: Int?
    get() = when (this) {
        WheelAction.BOX_1 -> 1
        WheelAction.BOX_2 -> 2
        else -> null
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
    fun slice(x: Float, y: Float, dead: Float = DEAD, count: Int = WheelAction.entries.size): Int? {
        if (x * x + y * y < dead * dead) return null
        val degrees = Math.toDegrees(atan2(x.toDouble(), -y.toDouble()))
        // Each slice is centred on its way, so a push half a slice either side of it is still that slice.
        val turned = (degrees + 360.0 + 180.0 / count) % 360.0
        return (turned / (360.0 / count)).toInt() % count
    }

    /** The slice the D-pad's held directions point at: one alone, or two together for a slant. */
    fun pad(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Int? {
        val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
        val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        return slice(x, y)
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
