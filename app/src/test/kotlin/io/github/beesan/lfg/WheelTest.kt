// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.core.ShortcutHold
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.WheelLevels
import io.github.beesan.lfg.core.WheelSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTest {
    private fun at(level: List<WheelSlice>, x: Float, y: Float) = WheelAim.slice(x, y, level.size)?.let { level[it].action }

    @Test
    fun `the top level is AI, Entry, Read once, Boxes and Lines, clockwise from the top`() {
        val top = WheelLevels.TOP
        assertEquals(WheelAction.AI, at(top, 0f, -1f))
        assertEquals(WheelAction.NOTE, at(top, 0.95f, -0.31f))
        assertEquals(WheelAction.READ_ONCE, at(top, 0.59f, 0.81f))
        assertEquals(WheelAction.BOXES, at(top, -0.59f, 0.81f))
        assertEquals(WheelAction.LINES, at(top, -0.95f, -0.31f))
    }

    @Test
    fun `AI's level has one slice for each straight way`() {
        val ai = WheelLevels.AI
        assertEquals(WheelAction.TRANSLATE, at(ai, 0f, -1f))
        assertEquals(WheelAction.EXPLAIN, at(ai, 1f, 0f))
        assertEquals(WheelAction.RECAP, at(ai, 0f, 1f))
        assertEquals(WheelAction.ASK, at(ai, -1f, 0f))
    }

    @Test
    fun `the boxes' level has every box, box 1 even when not drawn, and one more to draw`() {
        assertEquals(listOf("Box 1", "New box"), WheelLevels.boxes(0).map { it.label })
        assertEquals(listOf("Box 1", "Box 2", "Box 3", "New box"), WheelLevels.boxes(3).map { it.label })
        assertEquals(listOf(1, 2, 3), WheelLevels.boxes(3).filter { it.action == WheelAction.BOX }.map { it.box })
    }

    @Test
    fun `a push a little off its way is still that slice`() {
        // Eight slices: 20° off straight up either side is inside the 22.5° each side a slice spans; 25° is the next.
        assertEquals(0, WheelAim.slice(0.34f, -0.94f, 8))
        assertEquals(0, WheelAim.slice(-0.34f, -0.94f, 8))
        assertEquals(1, WheelAim.slice(0.42f, -0.9f, 8))
        assertEquals(7, WheelAim.slice(-0.42f, -0.9f, 8))
    }

    @Test
    fun `a stick near the middle points at nothing`() {
        assertNull(WheelAim.slice(0.1f, -0.2f, 5))
        assertNull(WheelAim.slice(0f, 0f, 5))
    }

    @Test
    fun `a slant let go one way at a time points at nothing on the way back`() {
        assertEquals(7, WheelAim.slice(-0.7f, -0.7f, 8))
        // The stick's x back to the middle first: straight up, but only part of the way out.
        assertNull(WheelAim.slice(0f, -0.7f, 8))
    }

    @Test
    fun `the d-pad points straight with one way and on a slant with two`() {
        assertEquals(0, WheelAim.pad(up = true, down = false, left = false, right = false, count = 8))
        assertEquals(1, WheelAim.pad(up = true, down = false, left = false, right = true, count = 8))
        assertEquals(6, WheelAim.pad(up = false, down = false, left = true, right = false, count = 8))
        assertNull(WheelAim.pad(up = true, down = true, left = false, right = false, count = 8))
        assertNull(WheelAim.pad(up = false, down = false, left = false, right = false, count = 8))
    }

    @Test
    fun `let go soon the shortcut is a tap`() {
        val hold = ShortcutHold(holdMs = 350)
        hold.press(1_000)
        assertFalse(hold.holdReached(1_200))
        assertEquals(ShortcutHold.Release.TAP, hold.release())
        assertFalse(hold.pressed)
    }

    @Test
    fun `held, it opens the wheel once and letting go picks`() {
        val hold = ShortcutHold(holdMs = 350)
        hold.press(1_000)
        assertTrue(hold.holdReached(1_360))
        assertFalse(hold.holdReached(1_500))
        assertEquals(ShortcutHold.Release.HOLD, hold.release())
    }

    @Test
    fun `a hold with no wheel to open is a tap after all`() {
        val hold = ShortcutHold(holdMs = 350)
        hold.press(1_000)
        assertTrue(hold.holdReached(1_400))
        hold.cancelHold()
        assertEquals(ShortcutHold.Release.TAP, hold.release())
    }

    @Test
    fun `a release with no press is nothing`() {
        assertEquals(ShortcutHold.Release.NONE, ShortcutHold().release())
    }
}
