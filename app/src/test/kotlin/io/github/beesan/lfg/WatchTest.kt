package io.github.beesan.lfg

import io.github.beesan.lfg.capture.Look
import io.github.beesan.lfg.capture.Seen
import io.github.beesan.lfg.capture.telling
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.SettleDetector
import io.github.beesan.lfg.core.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is watched for a change while a word is looked up over the game ([telling]). */
class WatchTest {
    private fun look(vararg rows: Seen) = Look(rows.joinToString("\n") { it.text }, rows.map { it.box }, rows.toList())

    /** Pokémon Let's Go's gym on an AYN Thor, as the probe saw it: the two rows of the box, and scraps of the floor. */
    private val story = listOf(
        TextLine(
            "ニビジムは　いつでもおまえを　待って　いる!", Box(398, 885, 996, 1018), emptyList(),
            rows = listOf(Box(398, 885, 917, 932), Box(398, 967, 996, 1018)),
        ),
    )
    private val first = Seen("ニビジムはいつでも", Box(390, 874, 930, 946))
    private val second = Seen("おまえを待っている!", Box(374, 956, 1010, 1034))

    @Test
    fun whatThePictureIsReadAsIsNotTheStoryChanging() {
        val clean = telling(look(Seen("PT、", Box(280, 70, 380, 130)), Seen("町内の", Box(1654, 62, 1800, 132)), first, second), story, 0, 0)
        val later = telling(look(Seen("ーいにい", Box(280, 74, 400, 130)), Seen("町内術", Box(1656, 66, 1800, 130)), Seen("じT", Box(1576, 248, 1640, 306)), first, second), story, 0, 0)
        assertEquals("ニビジムはいつでも\nおまえを待っている!", clean)
        assertEquals(clean, later)
    }

    @Test
    fun theNextLineInTheStorysPlaceIsAChange() {
        val clean = telling(look(Seen("PT、", Box(280, 70, 380, 130)), first, second), story, 0, 0)
        val next = telling(look(Seen("ーい", Box(280, 74, 360, 130)), Seen("また出なおすがいい!", Box(374, 874, 1020, 946))), story, 0, 0)
        assertFalse(SettleDetector.same(next, clean))
        // The line gone, with only the scraps left, is a change too.
        assertEquals("", telling(look(Seen("ーい", Box(280, 74, 360, 130))), story, 0, 0))
        // A row typed under a line that was read with one row.
        val one = listOf(TextLine("ニビジムは　いつでも", Box(398, 885, 917, 932), emptyList()))
        assertEquals("ニビジムはいつでも\nおまえを", telling(look(first, Seen("おまえを", Box(374, 956, 600, 1034))), one, 0, 0))
    }

    @Test
    fun aLineSomewhereElseIsAChangeAndALabelIsNot() {
        val line = Seen("そのとき、遠くで鐘が鳴った。", Box(300, 120, 1500, 190))
        assertTrue(telling(look(line, first, second), story, 0, 0).startsWith(line.text))
        assertEquals("ニビジムはいつでも\nおまえを待っている!", telling(look(Seen("メニュー", Box(1700, 30, 1880, 80)), first, second), story, 0, 0))
    }

    @Test
    fun rowsArePlacedOnTheDisplayAndEverythingCountsWithNothingToGoBy() {
        // A look at the part of the picture a dialogue box is in: its rows are counted from that part's corner.
        val cut = look(Seen("ニビジムはいつでも", Box(40, 24, 580, 96)), Seen("かべ", Box(900, 300, 960, 340)))
        assertEquals("ニビジムはいつでも", telling(cut, story, 350, 850))
        assertEquals(cut.signature, telling(cut, emptyList(), 350, 850))
        assertEquals("ab", telling(Look("ab", null), story, 0, 0))
    }
}
