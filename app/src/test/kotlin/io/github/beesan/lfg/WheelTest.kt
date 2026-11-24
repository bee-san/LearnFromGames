// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.core.ShortcutHold
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTest {
    private fun at(x: Float, y: Float) = WheelAim.slice(x, y)?.let { WheelAction.entries[it] }

    @Test
    fun `the four straight ways are the four most used`() {
        assertEquals(WheelAction.TRANSLATE, at(0f, -1f))
        assertEquals(WheelAction.EXPLAIN, at(1f, 0f))
        assertEquals(WheelAction.NOTE, at(0f, 1f))
        assertEquals(WheelAction.BOX_2, at(-1f, 0f))
    }

    @Test
    fun `only the two box slices read a box, each its own`() {
        assertEquals(1, WheelAction.BOX_1.box)
        assertEquals(2, WheelAction.BOX_2.box)
        assertEquals(listOf(WheelAction.BOX_1, WheelAction.BOX_2), WheelAction.entries.filter { it.box != null })
    }

    @Test
    fun `the slants are the rest, clockwise`() {
        assertEquals(WheelAction.ASK, at(0.7f, -0.7f))
        assertEquals(WheelAction.RECAP, at(0.7f, 0.7f))
        assertEquals(WheelAction.BOX_1, at(-0.7f, 0.7f))
        assertEquals(WheelAction.LINES, at(-0.7f, -0.7f))
    }

    @Test
    fun `a push a little off its way is still that slice`() {
        // 20° off straight up either side, inside the 22.5° each side a slice spans.
        assertEquals(WheelAction.TRANSLATE, at(0.34f, -0.94f))
        assertEquals(WheelAction.TRANSLATE, at(-0.34f, -0.94f))
        // 25° off is the next slice.
        assertEquals(WheelAction.ASK, at(0.42f, -0.9f))
        assertEquals(WheelAction.LINES, at(-0.42f, -0.9f))
    }

    @Test
    fun `a stick near the middle points at nothing`() {
        assertNull(WheelAim.slice(0.1f, -0.2f))
        assertNull(WheelAim.slice(0f, 0f))
    }

    @Test
    fun `a slant let go one way at a time points at nothing on the way back`() {
        assertEquals(WheelAction.LINES, at(-0.7f, -0.7f))
        // The stick's x back to the middle first: straight up, but only part of the way out.
        assertNull(WheelAim.slice(0f, -0.7f))
    }

    @Test
    fun `the d-pad points straight with one way and on a slant with two`() {
        assertEquals(0, WheelAim.pad(up = true, down = false, left = false, right = false))
        assertEquals(1, WheelAim.pad(up = true, down = false, left = false, right = true))
        assertEquals(6, WheelAim.pad(up = false, down = false, left = true, right = false))
        assertNull(WheelAim.pad(up = true, down = true, left = false, right = false))
        assertNull(WheelAim.pad(up = false, down = false, left = false, right = false))
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
