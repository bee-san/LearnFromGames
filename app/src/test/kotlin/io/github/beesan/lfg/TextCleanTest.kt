package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.TextClean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCleanTest {
    /** A line with one 40 px box per character, as ScreenAI gives. */
    private fun boxed(text: String): TextLine {
        val chars = text.indices.map { CharBox(it, it + 1, Box(it * 40, 0, it * 40 + 40, 40)) }
        return TextLine(text, Box(0, 0, text.length * 40, 40), chars)
    }

    private fun clean(text: String) = TextClean.line(boxed(text))?.text

    @Test
    fun widthTildesAndDashesBecomeOneForm() {
        assertEquals("本当!?", clean("本当！？"))
        assertEquals("CDを", clean("ＣＤを"))
        assertEquals("ガンバレ", clean("ｶﾞﾝﾊﾞﾚ"))
        assertEquals("ねえ~", clean("ねえ〜"))
        assertEquals("待って――", clean("待って—─"))
    }

    @Test
    fun dotsBecomeEllipsesAndANameKeepsItsDot() {
        assertEquals("そう…", clean("そう..."))
        assertEquals("そう…", clean("そう・・・"))
        assertEquals("あ…", clean("あ・・"))
        assertEquals("ジョン・スミス", clean("ジョン・スミス"))
        // Each ellipsis covers the dots it was made of.
        val line = TextClean.line(boxed("あ......"))!!
        assertEquals("あ……", line.text)
        assertEquals(listOf(Box(40, 0, 160, 40), Box(160, 0, 280, 40)), line.chars.drop(1).map { it.box })
    }

    @Test
    fun theLongVowelAndTheKanjiOneByContext() {
        assertEquals("コーヒー", clean("コ一ヒ一"))
        assertEquals("だよーっ", clean("だよ一っ"))
        assertEquals("統一的", clean("統ー的"))
        assertEquals("コーヒー一杯", clean("コーヒー一杯"))
        assertEquals("ハネムーン一ヶ月", clean("ハネムーン一ヶ月"))
        assertEquals("もう一", clean("もう一"))
    }

    @Test
    fun aCounterReadAsSmallTsu() {
        assertEquals("九ヶ月です」", clean("九ッ月です」"))
        assertEquals("三ヶ国", clean("三ッ国"))
        assertEquals("一ッ所", clean("一ッ所"))
        assertEquals("三ッ日月", clean("三ッ日月"))
    }

    @Test
    fun anOpeningBracketReadAsALetter() {
        assertEquals("「ふーん…」", clean("[ふーん…」"))
        assertEquals("「……」", clean("J……」"))
        assertEquals("『はい』", clean("Eはい』"))
        assertEquals("Jはい", clean("Jはい"))
        assertEquals("Jun「はい」", clean("Jun「はい」"))
        assertEquals("「……」", clean("E……Г"))
        assertEquals("「はい」", clean("「はいJ"))
        assertEquals("J…", clean("J…"))
    }

    @Test
    fun whatIsNotTheGamesTextGoes() {
        assertNull(clean("ーニーーーーニーニー"))
        assertNull(clean("Canesvenatica"))
        assertNull(clean("1819"))
        assertEquals("一二三", clean("一二三"))
        assertEquals("ーーー", clean("ーーー"))
        assertEquals("ニート", clean("ニート"))
        assertEquals("!?", clean("!?"))
        assertEquals("……", clean("……"))
        assertEquals("OK~", clean("OK～"))
        assertEquals("J…………", clean("J…………"))
    }

    /** ScreenAI can give one box to several dots, or to a whole word with no symbols of its own. */
    @Test(timeout = 2000)
    fun aBoxHoldingSeveralDotsEnds() {
        fun line(vararg pieces: String): TextLine {
            var at = 0
            val chars = pieces.mapIndexed { i, p -> CharBox(at, at + p.length, Box(i * 40, 0, i * 40 + 40, 40)).also { at += p.length } }
            return TextLine(pieces.joinToString(""), Box(0, 0, pieces.size * 40, 40), chars)
        }
        assertEquals("あ..い", TextClean.line(line("あ", "..", "い"))!!.text)
        assertEquals("あ・・・", TextClean.line(line("あ", "・・", "・"))!!.text)
        assertEquals("ン・・ス", TextClean.line(line("ン", "・", "・ス"))!!.text)
        assertEquals("あ…い", TextClean.line(line("あ", "・", "・", "・", "い"))!!.text)
    }

    @Test
    fun aLineWithoutCharacterBoxesIsCleanedToo() {
        assertEquals("コーヒー…", TextClean.line(TextLine("コ一ヒ一...", Box(0, 0, 100, 40), emptyList()))!!.text)
    }
}
