package io.github.beesan.lfg

import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlocksTest {
    /** A row of square characters of the given size, as a reader returns it. */
    private fun row(text: String, left: Int, top: Int, size: Int = 60) = TextLine(
        text, Box(left, top, left + text.length * size, top + size),
        text.indices.map { CharBox(it, it + 1, Box(left + it * size, top, left + (it + 1) * size, top + size)) },
    )

    private fun column(text: String, right: Int, top: Int, size: Int = 60) = TextLine(
        text, Box(right - size, top, right, top + text.length * size),
        text.indices.map { CharBox(it, it + 1, Box(right - size, top + it * size, right, top + (it + 1) * size)) },
        vertical = true,
    )

    @Test
    fun aWrappedSentenceIsOnePieceAndTheSpeakersNameAnother() {
        val name = row("ユイ", 140, 770, size = 38)
        val a = row("近づいたら消えてしまって、結局何", 140, 820)
        val b = row("だったのか分からない。", 140, 906)
        val blocks = Blocks.group(listOf(b, name, a))
        assertEquals(listOf("ユイ", "近づいたら消えてしまって、結局何だったのか分からない。"), blocks.map { it.text })
        val sentence = blocks[1]
        assertEquals(2, sentence.rows.size)
        assertEquals(sentence.text.length, sentence.chars.size)
        assertEquals(Box(140, 820, 140 + 16 * 60, 966), sentence.box)
    }

    @Test
    fun aTapLandsInTheRowItIsOn() {
        val block = Blocks.group(listOf(row("あいうえお", 100, 100), row("かきくけこ", 100, 190))).single()
        assertEquals(1, block.charIndexAt(170, 130))
        assertEquals(6, block.charIndexAt(170, 220))
        // Between the rows is not on the text: the tap goes to the game.
        assertNull(block.charIndexAt(170, 175))
        assertNull(block.charIndexAt(500, 130))
    }

    @Test
    fun aWordCutByTheLineBreakIsMarkedInBothRows() {
        val block = Blocks.group(listOf(row("森の奥で不思", 100, 100), row("議な光を見た", 100, 190))).single()
        // 不思議 is characters 4, 5 and 6: two at the end of the first row, one at the start of the second.
        assertEquals(listOf(Box(340, 100, 460, 160), Box(100, 190, 160, 250)), block.spanBoxes(4, 7))
        assertEquals(listOf(Box(100, 100, 160, 160)), block.spanBoxes(0, 1))
    }

    @Test
    fun choicesAndSeparateParagraphsStayApart() {
        // Centred buttons: under one another, but each starts at its own edge.
        val choices = listOf(row("一緒に帰る", 810, 322), row("図書室に寄る", 780, 472), row("何も言わずに立ち去る", 660, 622))
        assertEquals(3, Blocks.group(choices).size)
        // Two paragraphs: the gap between them is more than a row.
        val paragraphs = listOf(row("あいうえお", 100, 100), row("かきくけこ", 100, 186), row("さしすせそ", 100, 330), row("たちつてと", 100, 416))
        assertEquals(listOf("あいうえおかきくけこ", "さしすせそたちつてと"), Blocks.group(paragraphs).map { it.text })
        // Status lines set well apart are one each.
        assertEquals(2, Blocks.group(listOf(row("レベル27", 200, 200, 52), row("所持金", 200, 310, 52))).size)
    }

    @Test
    fun aParagraphsFirstRowMayBeIndented() {
        val block = Blocks.group(listOf(row("雨は夜になっても", 160, 100), row("止まなかった。街灯", 100, 186), row("の光が滲んだ。", 100, 272)))
        assertEquals(listOf("雨は夜になっても止まなかった。街灯の光が滲んだ。"), block.map { it.text })
        // But a later row that starts further in is something else.
        assertEquals(2, Blocks.group(listOf(row("あいうえお", 100, 100), row("かきくけこ", 100, 186), row("さしす", 260, 272))).size)
    }

    @Test
    fun furiganaIsDropped() {
        val base = row("彼は憂鬱な表情で曖昧に頷いた。", 140, 860)
        val ruby = listOf(row("ゆううつ", 250, 832, size = 24), row("あいまい", 620, 832, size = 24))
        assertEquals(listOf(base.text), Blocks.group(ruby + base).map { it.text })
        // A speaker's name is small and above the line too, but not that small.
        assertEquals(2, Blocks.group(listOf(row("ユイ", 140, 800, size = 38), base)).size)
        // A name as close and small as furigana, but over kana, is a name.
        assertEquals(2, Blocks.group(listOf(row("ユイ", 137, 778, size = 27), row("そのとき彼女は", 138, 818, size = 71))).size)
        // Sizes the readers measured for a name plate over a wrapped sentence: 0.46 of the row's height, 0.6 of it away.
        assertEquals(2, Blocks.group(listOf(row("ユイ", 140, 772, size = 25), row("昨日の夜、森の奥で", 140, 830, size = 54))).size)
        // Small kanji above a line is a caption, not furigana.
        assertEquals(2, Blocks.group(listOf(row("第一章", 140, 832, size = 24), base)).size)
    }

    @Test
    fun verticalColumnsJoinRightToLeft() {
        val block = Blocks.group(listOf(column("月だけが見ていた", 1650, 120), column("静かな夜だった", 1760, 120))).single()
        assertEquals("静かな夜だった月だけが見ていた", block.text)
        assertEquals(2, block.rows.size)
        // A tap in the left column lands there, not in the right one.
        assertEquals(7, block.charIndexAt(1620, 140))
        assertEquals(0, block.charIndexAt(1730, 140))
        // Furigana for vertical text sits to the right of its column.
        val ruby = column("しず", 1790, 120, size = 24)
        assertEquals(1, Blocks.group(listOf(column("静かな夜だった", 1760, 120), ruby)).size)
    }

    @Test
    fun rowsOfDifferentTypeSizesAreNotOneSentence() {
        assertEquals(2, Blocks.group(listOf(row("見出し", 100, 100, 90), row("本文です", 100, 200, 50))).size)
        assertEquals(emptyList<TextLine>(), Blocks.group(listOf(row("   ", 0, 0))))
    }
}
