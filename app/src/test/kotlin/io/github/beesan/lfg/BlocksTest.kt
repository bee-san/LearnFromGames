package io.github.beesan.lfg

import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aNameAsLargeAsTheDialogueAndAtItsEdgeIsStillApart() {
        // Same size, same left edge, the usual gap: it would join as the first row.
        val bracketed = Blocks.group(listOf(row("【七瀬】", 100, 700), row("今夜も放送、聴いてくれるかな。", 100, 786)))
        assertEquals(listOf("【七瀬】", "今夜も放送、聴いてくれるかな。"), bracketed.map { it.text })
        val quoted = Blocks.group(listOf(row("七瀬", 100, 700), row("「今夜も放送、聴いてくれる？」", 100, 786)))
        assertEquals(listOf("七瀬", "「今夜も放送、聴いてくれる？」"), quoted.map { it.text })
        // Neither, but set apart by more than its own height's worth of the gap.
        val spaced = Blocks.group(listOf(row("七瀬", 100, 700), row("今夜も放送、聴いてくれるかな。", 100, 800)))
        assertEquals(listOf("七瀬", "今夜も放送、聴いてくれるかな。"), spaced.map { it.text })
    }

    @Test
    fun aShortFirstRowAtTheUsualGapIsNotTakenForAName() {
        val rows = listOf(row("あいう", 100, 100), row("かきくけこさしすせそ", 100, 186), row("たちつてとなにぬねの", 100, 272))
        assertEquals(listOf("あいうかきくけこさしすせそたちつてとなにぬねの"), Blocks.group(rows).map { it.text })
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
        // Furigana half as tall as its row, as Clannad's is over a choice set large (an AYN Thor's own screens).
        assertEquals(listOf("立ち尽くす"), Blocks.group(listOf(TextLine("たちつ", Box(700, 149, 872, 185), emptyList()), TextLine("立ち尽くす", Box(686, 182, 1034, 255), emptyList()))).map { it.text })
        assertEquals(listOf("大喧嘩"), Blocks.group(listOf(TextLine("おおげんか", Box(874, 146, 1076, 180), emptyList()), TextLine("大喧嘩", Box(869, 180, 1077, 253), emptyList()))).map { it.text })
        // A name plate's second word, that tall and further off, is the name's.
        assertEquals(2, Blocks.group(listOf(TextLine("ユエ", Box(654, 743, 743, 776), emptyList()), TextLine("彼らを輝かせるために", Box(472, 795, 946, 843), emptyList()))).size)
        // A row of kana in a paragraph set tight is a row of it.
        assertEquals("いつくるかわからない", Blocks.group(listOf(row("いつくるかわからない", 140, 800, size = 46), base))[0].text)
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

    @Test
    fun rightToLeftRowsJoinAtTheirRightEdge() {
        // A right-aligned paragraph: the second, shorter row ends where the first does.
        val first = row("مرحبا بالعالم", 100, 100, 40)
        val second = row("كيف حالك", 100 + 13 * 40 - 8 * 40, 150, 40)
        val pieces = Blocks.group(listOf(first, second))
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].text.startsWith("مرحبا"))
    }
}
