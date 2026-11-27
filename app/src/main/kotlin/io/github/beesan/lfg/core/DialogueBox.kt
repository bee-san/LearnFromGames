// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * The part of a game's screen its dialogue is in, drawn once by the player, as
 * Rubi for Vita's dialogue region is. The game is then read there only: its
 * menus, its buttons and the words in its pictures are never read.
 *
 * It is kept in the pixels of the screen it was drawn on, with that screen's
 * size, so that it still fits when the screen's size changes.
 */
data class DialogueBox(
    val box: Box,
    val screenWidth: Int,
    val screenHeight: Int,
    /** For box 2 onwards: read all the time, beside the dialogue box, and not only when picked on the wheel. */
    val always: Boolean = false,
) {
    /** `left,top,right,bottom,screenWidth,screenHeight`, and `,1` for a box read all the time: the form the settings keep. */
    fun encode() = "${box.encode()},$screenWidth,$screenHeight" + if (always) ",1" else ""

    /** The box on a screen of this size: as drawn, or stretched with the screen when its size has changed. */
    fun on(width: Int, height: Int): Box {
        if (width == screenWidth && height == screenHeight || screenWidth <= 0 || screenHeight <= 0) return box
        return Box(
            (box.left.toLong() * width / screenWidth).toInt(), (box.top.toLong() * height / screenHeight).toInt(),
            (box.right.toLong() * width / screenWidth).toInt(), (box.bottom.toLong() * height / screenHeight).toInt(),
        )
    }

    companion object {
        fun decode(text: String): DialogueBox? {
            val n = text.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (n.size !in 6..7 || n[2] - n[0] < MIN || n[3] - n[1] < MIN || n[4] <= 0 || n[5] <= 0) return null
            return DialogueBox(Box(n[0], n[1], n[2], n[3]), n[4], n[5], always = n.getOrNull(6) == 1)
        }

        /** The smallest box worth keeping, in pixels each way, as Rubi's: a line of text does not fit in less. */
        const val MIN = 32

        /**
         * What is read of a picture [width] by [height] to read [box] of it: the
         * box with room around it, a little more than a line's height. Text cut
         * close is read worse than text with the screen around it (86.6% of lines
         * right against 87.2% with the room, issue #23). Null when too little of
         * the box is in the picture to read.
         */
        fun reading(box: Box, width: Int, height: Int): Box? {
            val room = height / ROOM
            val left = (box.left - room).coerceIn(0, width)
            val top = (box.top - room).coerceIn(0, height)
            val right = (box.right + room).coerceIn(0, width)
            val bottom = (box.bottom + room).coerceIn(0, height)
            if (minOf(box.right, width) - maxOf(box.left, 0) < MIN || minOf(box.bottom, height) - maxOf(box.top, 0) < MIN) return null
            return Box(left, top, right, bottom)
        }

        /**
         * What is read of a picture to read all of [boxes] in it: the [reading] of each, and the
         * smallest box round them all. Null when none of them can be read.
         */
        fun reading(boxes: List<Box>, width: Int, height: Int): Pair<Box, List<Box>>? {
            val each = boxes.mapNotNull { reading(it, width, height) }
            if (each.isEmpty()) return null
            return each.reduce(Box::union) to each
        }

        /** The room read around a box is the picture's height over this: 60 pixels of a 1080p screen. */
        private const val ROOM = 18
    }
}

/**
 * A box being drawn on a screen with the controller or a finger, as Rubi for
 * Vita draws its dialogue region: a cursor is moved to one corner and fixed
 * there, then moved to the opposite corner with the box following it, and
 * fixing that one finishes the box. A finger does the same in one drag, or
 * with a tap on each corner.
 */
class BoxDrawing(private val width: Int, private val height: Int, private val min: Int = DialogueBox.MIN, start: Box? = null) {
    enum class Stage {
        /** No corner yet: the cursor is being moved to the first. */
        AIMING,

        /** One corner is fixed and the box follows the cursor. */
        STRETCHING,

        /** Both corners are fixed. */
        DONE,
    }

    var stage = if (start != null) Stage.DONE else Stage.AIMING
        private set

    /** The cursor: the corner being moved. */
    var x = start?.right ?: (width / 2)
        private set
    var y = start?.bottom ?: (height / 2)
        private set
    private var cornerX = start?.left ?: 0
    private var cornerY = start?.top ?: 0

    /** Whether the box is no longer the one drawing began with. */
    var changed = start == null
        private set

    /** The box as it stands; none while the first corner is still being chosen. */
    val box: Box?
        get() = if (stage == Stage.AIMING) null else Box(minOf(cornerX, x), minOf(cornerY, y), maxOf(cornerX, x), maxOf(cornerY, y))

    private fun bigEnough() = box?.let { it.width >= min && it.height >= min } == true

    /** The stick or the D-pad: the cursor goes that far, and no farther than the screen's edge. */
    fun move(dx: Int, dy: Int) {
        if (stage == Stage.DONE || dx == 0 && dy == 0) return
        x = (x + dx).coerceIn(0, width)
        y = (y + dy).coerceIn(0, height)
        if (stage == Stage.STRETCHING) changed = true
    }

    /** The confirm button: fixes the corner under the cursor. True when that finished the box. */
    fun confirm(): Boolean = when (stage) {
        Stage.AIMING -> {
            cornerX = x
            cornerY = y
            stage = Stage.STRETCHING
            false
        }
        // A box too small to hold a line is not a box yet: the corner stays loose.
        Stage.STRETCHING -> bigEnough().also { if (it) stage = Stage.DONE }
        Stage.DONE -> false
    }

    /** The back button: takes the last corner back. False when there was none, which is leaving. */
    fun back(): Boolean = when (stage) {
        Stage.DONE -> {
            stage = Stage.STRETCHING
            true
        }
        Stage.STRETCHING -> {
            stage = Stage.AIMING
            tapped = false
            true
        }
        Stage.AIMING -> false
    }

    /** The first corner was placed by a tap: the next touch places the second. */
    private var tapped = false

    /** A finger lands: that is the first corner, whatever was drawn before; after a tap, the second. */
    fun touchDown(atX: Int, atY: Int) {
        if (stage == Stage.STRETCHING && tapped) {
            touchMove(atX, atY)
            return
        }
        tapped = false
        cornerX = atX.coerceIn(0, width)
        cornerY = atY.coerceIn(0, height)
        x = cornerX
        y = cornerY
        stage = Stage.STRETCHING
        changed = true
    }

    fun touchMove(atX: Int, atY: Int) {
        if (stage != Stage.STRETCHING) return
        x = atX.coerceIn(0, width)
        y = atY.coerceIn(0, height)
    }

    /**
     * The finger lifts: the box is finished when it is big enough. A first tap
     * leaves its corner placed, for a second tap or a drag to the opposite one.
     * True when finished.
     */
    fun touchUp(): Boolean {
        if (stage != Stage.STRETCHING) return false
        if (bigEnough()) {
            stage = Stage.DONE
            tapped = false
            return true
        }
        if (!tapped) {
            tapped = true
            x = cornerX
            y = cornerY
        }
        return false
    }

    companion object {
        /**
         * How far the cursor goes in one [tickMs] of the stick held [deflection]
         * (-1 to 1) for [heldMs], on a screen [screenHeight] tall: slowly at
         * first, to place a corner on a line's edge, and up to a screen's height
         * in under a second once held, as Rubi's cursor speeds up.
         */
        fun stickStep(deflection: Float, heldMs: Long, tickMs: Long, screenHeight: Int): Float {
            val slow = screenHeight * 0.18f
            val fast = screenHeight * 1.4f
            val speed = slow + (fast - slow) * (heldMs.coerceIn(0, RAMP_MS).toFloat() / RAMP_MS)
            return deflection * speed * tickMs / 1000f
        }

        private const val RAMP_MS = 900L
    }
}
