// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.core.ShortcutHold
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.WheelIcons
import io.github.beesan.lfg.core.WheelLevels
import io.github.beesan.lfg.core.WheelSet
import io.github.beesan.lfg.core.WheelSize
import io.github.beesan.lfg.core.WheelSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTest {
    private fun at(level: List<WheelSlice>, x: Float, y: Float) = WheelAim.slice(x, y, level.size)?.let { level[it].action }

    private val top = WheelSet.DEFAULT.level(WheelSet.TOP)

    @Test
    fun `the first wheel is AI, Entry, Keep, Read once, Boxes and Lines, clockwise from the top`() {
        assertEquals(WheelAction.WHEEL, at(top, 0f, -1f))
        assertEquals("AI", top[0].label)
        assertEquals(WheelAction.NOTE, at(top, 0.87f, -0.5f))
        assertEquals(WheelAction.KEEP, at(top, 0.87f, 0.5f))
        assertEquals(WheelAction.READ_ONCE, at(top, 0f, 1f))
        assertEquals(WheelAction.BOXES, at(top, -0.87f, 0.5f))
        assertEquals(WheelAction.LINES, at(top, -0.87f, -0.5f))
    }

    @Test
    fun `every top slice is one the D-pad can point at`() {
        val ways = listOf(-1f, 0f, 1f).flatMap { x -> listOf(-1f, 0f, 1f).map { y -> x to y } }.filter { it != 0f to 0f }
        val reached = ways.mapNotNull { (x, y) -> WheelAim.slice(x, y, top.size) }.toSet()
        assertEquals(top.indices.toSet(), reached)
    }

    @Test
    fun `AI's wheel has one slice for each straight way`() {
        val ai = WheelSet.DEFAULT.level(WheelSet.AI)
        assertEquals(WheelAction.TRANSLATE, at(ai, 0f, -1f))
        assertEquals(WheelAction.EXPLAIN, at(ai, 1f, 0f))
        assertEquals(WheelAction.RECAP, at(ai, 0f, 1f))
        assertEquals(WheelAction.ASK, at(ai, -1f, 0f))
    }

    @Test
    fun `every tile has an id of its own, an icon, and something to say`() {
        assertEquals(WheelAction.entries.size, WheelAction.entries.map { it.id }.toSet().size)
        for (action in WheelAction.entries) {
            assertEquals(action, WheelAction.of(action.id))
            assertTrue(action.id, WheelSlice(action, box = 1).iconPath.isNotEmpty())
            assertTrue(action.id, action.label.isNotBlank() && action.about.isNotBlank() && action.more.isNotBlank())
        }
    }

    @Test
    fun `a tile is added at the end, or after the slice it was dropped on`() {
        val (added, at) = WheelSet.DEFAULT.add(WheelSet.TOP, WheelAction.COPY)
        assertEquals(6, at)
        assertEquals(WheelAction.COPY, added.top.slices.last().action)
        val (dropped, where) = WheelSet.DEFAULT.add(WheelSet.TOP, WheelAction.COPY, 1)
        assertEquals(1, where)
        assertEquals(listOf(WheelAction.WHEEL, WheelAction.COPY, WheelAction.NOTE), dropped.top.slices.take(3).map { it.action })
    }

    @Test
    fun `New wheel makes an empty wheel that its slice opens, and removing the slice takes the wheel with it`() {
        val (set, at) = WheelSet.DEFAULT.add(WheelSet.TOP, WheelAction.WHEEL)
        val slice = set.top.slices[at]
        assertEquals(WheelAction.WHEEL, slice.action)
        assertEquals(emptyList<WheelSlice>(), set[slice.wheel]!!.slices)
        assertEquals("Wheel 2", set.level(WheelSet.TOP)[at].label)
        val inside = set.add(slice.wheel, WheelAction.HEAR).first
        assertEquals(listOf(WheelAction.HEAR), inside.level(slice.wheel).map { it.action })
        assertNull(inside.remove(WheelSet.TOP, at)[slice.wheel])
    }

    @Test
    fun `AI removed and added again is the ready-made wheel again`() {
        val without = WheelSet.DEFAULT.remove(WheelSet.TOP, 0)
        assertNull(without[WheelSet.AI])
        val (again, at) = without.add(WheelSet.TOP, WheelAction.AI)
        assertEquals(WheelSet.AI, again.top.slices[at].wheel)
        assertEquals(4, again[WheelSet.AI]!!.slices.size)
    }

    @Test
    fun `a slice moves round the wheel, past the top and back`() {
        val (on, at) = WheelSet.DEFAULT.move(WheelSet.TOP, 5, 1)
        assertEquals(0, at)
        assertEquals(WheelAction.LINES, on.top.slices[0].action)
        assertEquals(WheelAction.WHEEL, on.top.slices[5].action)
        assertEquals(WheelSet.DEFAULT, on.move(WheelSet.TOP, 0, -1).first)
    }

    @Test
    fun `a Box tile reads box 1 until it is given another, and says so`() {
        val (set, at) = WheelSet.DEFAULT.add(WheelSet.TOP, WheelAction.BOX)
        assertEquals("Box 1", set.top.slices[at].label)
        assertEquals("1", set.top.slices[at].number)
        val third = set.edit(WheelSet.TOP, at) { it.copy(box = 3) }.top.slices[at]
        assertEquals("Box 3", third.label)
        // An icon of the player's own takes the number's place.
        assertEquals("", third.copy(glyph = "あ").number)
    }

    @Test
    fun `a wheel comes back from its file as it went in`() {
        var set = WheelSet.DEFAULT.add(WheelSet.TOP, WheelAction.WHEEL).first
        val made = set.top.slices.last().wheel
        set = set.rename(made, "Mine").add(made, WheelAction.PROMPT).first
            .edit(made, 0) { it.copy(prompt = "Is this rude?", glyph = "🙂") }
            .add(made, WheelAction.BOX).first.edit(made, 1) { it.copy(box = 2, icon = "heart") }
        assertEquals(set, WheelSet.decode(set.encode()))
    }

    @Test
    fun `a file that is no wheel is refused, and a tile this version does not know is left out`() {
        assertNull(WheelSet.decode("not a wheel"))
        assertNull(WheelSet.decode("""{"v":1,"wheels":{"other":{"name":"x","slices":[]}}}"""))
        val read = WheelSet.decode("""{"v":1,"wheels":{"top":{"name":"Wheel","slices":[{"tile":"copy"},{"tile":"teleport"},{"wheel":"gone"},{"tile":"ai"}]}}}""")
        assertEquals(listOf(WheelAction.COPY), read!!.top.slices.map { it.action })
    }

    @Test
    fun `the D-pad steps round a wheel of any size`() {
        assertEquals(0, WheelAim.step(null, 1, 11))
        assertEquals(1, WheelAim.step(0, 1, 11))
        assertEquals(10, WheelAim.step(0, -1, 11))
        assertNull(WheelAim.step(null, 1, 0))
    }

    @Test
    fun `a stick points sooner when its reach is set shorter`() {
        assertNull(WheelAim.slice(0f, -0.6f, 6, dead = 0.75f))
        assertEquals(0, WheelAim.slice(0f, -0.6f, 6, dead = 0.5f))
    }

    @Test
    fun `the wheel grows with its tiles`() {
        assertEquals(182f, WheelSize.outer(6))
        assertEquals(170f, WheelSize.outer(2))
        assertTrue(WheelSize.outer(12) > WheelSize.outer(6))
        assertTrue(WheelSize.inner(12) < WheelSize.outer(12))
    }

    @Test
    fun `a slice's own glyph is one character, an emoji of several code points counting as one`() {
        assertEquals("あ", WheelIcons.lastGlyph("かあ"))
        assertEquals("", WheelIcons.lastGlyph("  "))
        assertEquals("b", WheelIcons.lastGlyph("ab "))
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
        val hold = ShortcutHold { 350 }
        hold.press(1_000)
        assertFalse(hold.holdReached(1_200))
        assertEquals(ShortcutHold.Release.TAP, hold.release())
        assertFalse(hold.pressed)
    }

    @Test
    fun `held, it opens the wheel once and letting go picks`() {
        val hold = ShortcutHold { 350 }
        hold.press(1_000)
        assertTrue(hold.holdReached(1_360))
        assertFalse(hold.holdReached(1_500))
        assertEquals(ShortcutHold.Release.HOLD, hold.release())
    }

    @Test
    fun `a hold with no wheel to open is a tap after all`() {
        val hold = ShortcutHold { 350 }
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
