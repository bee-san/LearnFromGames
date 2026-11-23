// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.GlyphBoxes
import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphBoxesTest {
    private fun chars(vararg boxes: Box) = boxes.mapIndexed { i, b -> CharBox(i, i + 1, b) }

    @Test
    fun smallKanaTakesTheLinesHeight() {
        // 待っ: 待 fills its cell, small っ sits low and short.
        val out = GlyphBoxes.even("待っ", chars(Box(0, 10, 40, 50), Box(46, 30, 70, 50)), vertical = false)
        assertEquals(10, out[1].box.top)
        assertEquals(50, out[1].box.bottom)
    }

    @Test
    fun aNarrowLetterGrowsTowardsTheNextButNotIntoIt() {
        // し is 16 wide in a 40-tall line, with a 20 gap before the next letter.
        val out = GlyphBoxes.even("しか", chars(Box(0, 0, 16, 40), Box(36, 0, 76, 40)), vertical = false)
        assertEquals(16 + 16, out[0].box.right) // grows 4/5 of the gap
        assertEquals(76, out[1].box.right) // the last letter has no next one
    }

    @Test
    fun aWideGapIsNotBridged() {
        val out = GlyphBoxes.even("しか", chars(Box(0, 0, 16, 40), Box(60, 0, 100, 40)), vertical = false)
        // It grows to its own cell's width, 12 a side, and no further.
        assertEquals(28, out[0].box.right)
    }

    @Test
    fun theCellReachesTheLowestLetter() {
        // The reader set あ a few pixels above い: the cell is both of them, not the higher one's height from the top.
        val out = GlyphBoxes.even("あい", chars(Box(0, 0, 40, 40), Box(44, 6, 84, 46)), vertical = false)
        assertEquals(listOf(0, 0), out.map { it.box.top })
        assertEquals(listOf(46, 46), out.map { it.box.bottom })
    }

    @Test
    fun theLastLetterGrowsToItsCellButNotIntoPunctuation() {
        // る read 8 short of its 50 cell: 4 a side at the line's end; before 。 only as far as the 。.
        val alone = GlyphBoxes.even("なる", chars(Box(0, 0, 50, 50), Box(54, 0, 96, 50)), vertical = false)
        assertEquals(100, alone[1].box.right)
        val stopped = GlyphBoxes.even("なる。", chars(Box(0, 0, 50, 50), Box(54, 0, 96, 50), Box(98, 40, 106, 50)), vertical = false)
        assertEquals(98, stopped[1].box.right)
    }

    @Test
    fun slopedTextKeepsEachLettersOwnPlace() {
        val out = GlyphBoxes.even("あいう", chars(Box(0, 0, 40, 40), Box(44, 40, 84, 80), Box(88, 80, 128, 120)), vertical = false)
        assertEquals(listOf(0, 40, 80), out.map { it.box.top })
    }

    @Test
    fun punctuationKeepsItsOwnBox() {
        val out = GlyphBoxes.even("あ、", chars(Box(0, 0, 40, 40), Box(44, 30, 52, 40)), vertical = false)
        assertEquals(Box(44, 30, 52, 40), out[1].box)
        assertEquals(40, out[0].box.right) // nothing full-width follows it
    }

    @Test
    fun verticalTextEvensItsWidth() {
        // ー is a thin vertical bar in a column 40 wide.
        val out = GlyphBoxes.even("ーカ", chars(Box(16, 0, 24, 40), Box(0, 44, 40, 84)), vertical = true)
        assertEquals(0, out[0].box.left)
        assertEquals(40, out[0].box.right)
    }
}
