package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.TextClean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun aBracketReadAsALetterWithItsPairOnAnotherRow() {
        // The Greek letter, as ScreenAI gives it on the OCR bench.
        assertEquals("「…………」", clean("Γ…………]"))
        assertEquals("「………………」", clean("Γ………………|"))
        // The line's other end is on the next row, or was not read.
        assertEquals("「……!", clean("Γ……!"))
        assertEquals("「そうだね、それは", clean("[そうだね、それは"))
        assertEquals("……」", clean("……]"))
        // A square bracket with its own pair is one.
        assertEquals("[1]はい", clean("[1]はい"))
        assertEquals("はい[A]", clean("はい[A]"))
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

    @Test
    fun aSymbolReadAsACharacterGoesAndWordlessLinesStay() {
        // Senren Banka's quick-save prompt on the AYN Thor: its checkbox was kept as a line.
        assertNull(clean("□"))
        assertNull(clean("▼"))
        assertNull(clean("→ ■"))
        for (line in listOf("……。", "――", "「……」", "♪", "!?", "・・・", "～♡", "〇〇")) assertNotNull(line, clean(line))
        assertEquals("□を押す", clean("□を押す"))
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
    fun aKanjiDrawnLikeAKatakanaAmongKatakana() {
        assertEquals("ロケット", clean("口ケット"))
        assertEquals("カード", clean("力一ド"))
        assertEquals("エニグマ", clean("工二グマ"))
        assertEquals("ハンバーガー", clean("八ンバーガー"))
        assertEquals("アジトへ", clean("アジトへ"))
        // A kanji beside a kanji, or with no katakana beside it, is one.
        for (line in listOf("人口", "口を", "工場", "二人", "八月", "力を", "一二三", "夕方")) assertEquals(line, clean(line))
    }

    @Test
    fun aKanaTakesTheScriptOfTheWordItStarts() {
        assertEquals("へや", clean("ヘや"))
        assertEquals("りんご", clean("リんご"))
        assertEquals("ヘルメット", clean("へルメット"))
        assertEquals("ドリル", clean("ドりル"))
        assertEquals("メモリ", clean("メモリ"))
    }

    /** A line of 40 px cells as a pixel font sets it: [layout] marks each cell, `.` an empty one; [read] is ScreenAI's text, its spaces unboxed. */
    private fun grid(read: String, layout: String): TextLine {
        val cells = layout.indices.filter { layout[it] != '.' }
        var n = 0
        val chars = read.indices.filter { !read[it].isWhitespace() }.map { i ->
            val x = cells[n++] * 40
            CharBox(i, i + 1, Box(x + 4, 0, x + 36, 40))
        }
        return TextLine(read, Box(0, 0, layout.length * 40, 40), chars)
    }

    @Test
    fun spacesOnAPixelFontComeFromTheCells() {
        val layout = "xxxxxxx.xxxx"
        val want = "ロケットだんの　アジトに"
        assertEquals(want, TextClean.line(grid("ロケットだんの　アジトに", layout))!!.text)
        assertEquals(want, TextClean.line(grid("ロケットだんの アジトに", layout))!!.text)
        assertEquals(want, TextClean.line(grid("ロケットだんのアジトに", layout))!!.text)
        // A space where no cell is empty goes.
        assertEquals("でんしゃにのって", TextClean.line(grid("でんしゃに のって", "xxxxxxxx"))!!.text)
        // Two empty cells are still one space.
        assertEquals("はい　いいえ", TextClean.line(grid("はい いいえ", "xx..xxx"))!!.text)
    }

    @Test
    fun spacesStayOffAGrid() {
        // Letters and a space a cell and a half wide: not a pixel font's grid.
        val chars = listOf(CharBox(0, 1, Box(0, 0, 40, 40)), CharBox(1, 2, Box(40, 0, 80, 40)), CharBox(3, 4, Box(140, 0, 180, 40)), CharBox(4, 5, Box(180, 0, 220, 40)))
        assertEquals("ああ いい", TextClean.line(TextLine("ああ いい", Box(0, 0, 220, 40), chars))!!.text)
        assertEquals("OK だよ", TextClean.line(grid("OK だよ", "xx.xx"))!!.text)
    }

    @Test
    fun aLineWithoutCharacterBoxesIsCleanedToo() {
        assertEquals("コーヒー…", TextClean.line(TextLine("コ一ヒ一...", Box(0, 0, 100, 40), emptyList()))!!.text)
    }

    @Test
    fun theBoxsCursorIsNoPartOfTheLine() {
        assertEquals("それじゃ いってきます", clean("それじゃ いってきます ▼"))
        assertEquals("はい", clean("▶はい"))
        assertNull(clean("▼"))
    }

    @Test
    fun aTiledPictureReadAsKanjiIsNoLine() {
        assertNull(clean("田田回"))
        assertNull(clean("口 目"))
        assertEquals("田口さん", clean("田口さん"))
    }

    @Test
    fun aKanjiOrKanaThatIsRightBesideKatakanaStays() {
        assertEquals("ジャンプ力が上がった", clean("ジャンプ力が上がった"))
        assertEquals("パン二つ", clean("パン二つ"))
        assertEquals("残りターン", clean("残りターン"))
        assertEquals("ケーキ八つ", clean("ケーキ八つ"))
    }

    @Test
    fun aLookalikeInsideRunningTextIsStillPutRight() {
        assertEquals("このカードを", clean("この力一ドを"))
        assertEquals("ヒントを", clean("ヒン卜を"))
        assertEquals("データが", clean("デー夕が"))
        assertEquals("ゼロに", clean("ゼ口に"))
    }

    @Test
    fun aKanjiStartingAWordBeforeKatakanaStays() {
        assertEquals("あと二ターン", clean("あと二ターン"))
        assertEquals("あと八ポイント", clean("あと八ポイント"))
        assertEquals("その口コミ", clean("その口コミ"))
        assertEquals("町へバスで", clean("町へバスで"))
        assertEquals("城へワープ", clean("城へワープ"))
    }
}
