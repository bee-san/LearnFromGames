package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Bands
import io.github.beesan.lfg.ocr.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandsTest {
    /** A picture whose every pixel says which row it is in. */
    private fun rows(width: Int, height: Int) = Frame(width, height, IntArray(width * height) { it / width })

    @Test
    fun aDialogueBoxAtTheBottomIsOneBandWithRoomAroundIt() {
        // Two rows of 60 px type at the foot of a 1080p screen.
        val bands = Bands.around(listOf(Box(200, 800, 1500, 860), Box(200, 880, 900, 940)), 1920, 1080)!!
        // Two heights above and below: the probe misses a short last row now and then, or a name over the first.
        assertEquals(listOf(680..1060), bands.rows)
        assertEquals(380, bands.height)
    }

    @Test
    fun aHudAtTheTopAndDialogueAtTheBottomAreTwoBandsStacked() {
        val bands = Bands.around(listOf(Box(40, 40, 400, 80), Box(200, 880, 1500, 940)), 1920, 1080)!!
        assertEquals(listOf(0..200, 760..1060), bands.rows)
        assertEquals(200 + Bands.SEAM + 300, bands.height)
        val stacked = bands.stack(rows(1920, 1080))
        assertEquals(1920, stacked.width)
        assertEquals(bands.height, stacked.height)
        // The first band's rows, then black, then the second band's.
        assertEquals(0, stacked.argb[0])
        assertEquals(199, stacked.argb[199 * 1920 + 7])
        assertEquals(0xFF000000.toInt(), stacked.argb[200 * 1920])
        assertEquals(0xFF000000.toInt(), stacked.argb[(200 + Bands.SEAM - 1) * 1920 + 1919])
        assertEquals(760, stacked.argb[(200 + Bands.SEAM) * 1920])
        assertEquals(1059, stacked.argb[(bands.height - 1) * 1920 + 1919])
    }

    @Test
    fun whatIsReadGoesBackWhereItWasAndWhatLiesOnASeamIsDropped() {
        val bands = Bands.around(listOf(Box(40, 40, 400, 80), Box(200, 880, 1500, 940)), 1920, 1080)!!
        val second = 200 + Bands.SEAM
        fun line(text: String, top: Int) = TextLine(text, Box(100, top, 300, top + 40), listOf(CharBox(0, text.length, Box(100, top, 300, top + 40))))
        val back = bands.unstack(listOf(line("上", 40), line("縫", 195), line("下", second + 120)))
        assertEquals(listOf("上", "下"), back.map { it.text })
        assertEquals(Box(100, 40, 300, 80), back[0].box)
        assertEquals(Box(100, 880, 300, 920), back[1].box)
        assertEquals(Box(100, 880, 300, 920), back[1].chars[0].box)
    }

    @Test
    fun textAllOverThePictureOrNoneAtAllReadsTheWholeOfIt() {
        assertNull(Bands.around(emptyList(), 1920, 1080))
        assertNull(Bands.around(listOf(Box(40, 100, 400, 180), Box(40, 500, 400, 580), Box(40, 900, 400, 980)), 1920, 1080))
        // A column of vertical text is as tall as the screen: its width is the size of its type.
        assertNull(Bands.around(listOf(Box(1700, 60, 1760, 1000)), 1920, 1080))
    }
}
