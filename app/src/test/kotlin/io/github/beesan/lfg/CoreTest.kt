package io.github.beesan.lfg

import io.github.beesan.lfg.capture.PixelProbe
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.Sentence
import io.github.beesan.lfg.core.SettleDetector
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.dict.EntryHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun theThemeFollowsTheSystemUnlessOneIsPicked() {
        assertTrue(Palette.of(Palette.PINK, Palette.THEME_SYSTEM, systemDark = true).dark)
        assertFalse(Palette.of(Palette.PINK, Palette.THEME_SYSTEM, systemDark = false).dark)
        assertFalse(Palette.of(Palette.PINK, Palette.THEME_LIGHT, systemDark = true).dark)
        assertTrue(Palette.of(Palette.PINK, Palette.THEME_DARK, systemDark = false).dark)
        // Dark is true black.
        assertEquals(0xFF000000.toInt(), Palette.of(Palette.MONO, Palette.THEME_DARK, systemDark = false).bg)
        // The entry page reads the palette as CSS variables.
        val page = EntryHtml.page(null, emptyList(), "No entry", palette = Palette.of(Palette.PINK, Palette.THEME_LIGHT, systemDark = true))
        assertTrue(page.contains(":root{color-scheme:light;--bg:#f8f2f5;"))
        assertFalse(EntryHtml.BASE_CSS.contains("#22232c"))
        // The popup over a game is see-through: its page is clear and its cards let some of the ground through.
        assertTrue(page.contains(";--page:#f8f2f5;--card:#ffffff}"))
        val glass = EntryHtml.colours(Palette.of(Palette.PINK, Palette.THEME_LIGHT, systemDark = true), glass = true)
        assertTrue(glass.contains(";--page:transparent;--card:rgba(255,255,255,.62)}"))
    }

    /** Four 20 px characters with 4 px gaps, starting at x=100. */
    private fun horizontal(text: String = "森の奥で") = TextLine(
        text = text,
        box = Box(100, 50, 100 + text.length * 24 - 4, 70),
        chars = text.indices.map { CharBox(it, it + 1, Box(100 + it * 24, 50, 120 + it * 24, 70)) },
    )

    @Test
    fun tapLandsOnTheCharacterUnderIt() {
        val line = horizontal()
        assertEquals(0, line.charIndexAt(100, 60))
        assertEquals(2, line.charIndexAt(150, 55))
        assertEquals(3, line.charIndexAt(191, 69))
    }

    @Test
    fun tapInAGapTakesTheNearestCharacter() {
        val line = horizontal()
        // The gap after character 0 is x in 120..123.
        assertEquals(0, line.charIndexAt(120, 60))
        assertEquals(1, line.charIndexAt(123, 60))
    }

    @Test
    fun tapOutsideTheLineGoesToTheGame() {
        val line = horizontal()
        assertNull(line.charIndexAt(99, 60))
        assertNull(line.charIndexAt(150, 71))
        assertEquals(0, line.charIndexAt(96, 60, slop = 4))
    }

    @Test
    fun verticalLinesAreHitAlongY() {
        val line = TextLine(
            text = "夜の森",
            box = Box(10, 0, 30, 72),
            chars = (0..2).map { CharBox(it, it + 1, Box(10, it * 24, 30, it * 24 + 20)) },
            vertical = true,
        )
        assertEquals(1, line.charIndexAt(20, 30))
        assertEquals(2, line.charIndexAt(29, 71))
    }

    @Test
    fun spanBoxCoversTheMatchedWord() {
        val line = horizontal()
        assertEquals(Box(124, 50, 168, 70), line.spanBox(1, 3))
        assertNull(line.spanBox(4, 6))
    }

    @Test
    fun surrogatePairsKeepTheirUtf16Range() {
        val text = "𠮟る"
        val line = TextLine(text, Box(0, 0, 44, 20), listOf(CharBox(0, 2, Box(0, 0, 20, 20)), CharBox(2, 3, Box(24, 0, 44, 20))))
        assertEquals(2, line.charIndexAt(30, 10))
        assertEquals("る", text.substring(line.charIndexAt(30, 10)!!))
    }

    @Test
    fun boxRoundTripsThroughItsColumn() {
        val box = Box(1, 2, 30, 40)
        assertEquals(box, Box.decode(box.encode()))
        assertNull(Box.decode(""))
        assertNull(Box.decode("1,2,3"))
    }

    @Test
    fun textIsReadOnceItHoldsStill() {
        val d = SettleDetector(stillMs = 300)
        assertFalse(d.offer("こん", 0))
        assertFalse(d.offer("こんに", 350))     // still typing out
        assertFalse(d.offer("こんにちは", 700))
        assertFalse(d.offer("こんにちは", 900))  // unchanged for 200 ms
        assertTrue(d.offer("こんにちは", 1050))  // unchanged for 350 ms
        assertFalse(d.offer("こんにちは", 1400)) // already read
    }

    @Test
    fun textAskedForIsReadAtOnceThenAsUsual() {
        val d = SettleDetector(stillMs = 300)
        d.settleNext()
        assertFalse(d.offer("", 0))            // nothing yet: the wish waits for text
        assertTrue(d.offer("こんにちは", 350))   // read on the first picture of it
        assertFalse(d.offer("こんにちは", 700))  // already read
        assertFalse(d.offer("さようなら", 1_050)) // the next line waits to hold still, as ever
        assertTrue(d.offer("さようなら", 1_400))
    }

    @Test
    fun aLineThatAppearsWholeAndEndsASentenceIsReadAtOnce() {
        val d = SettleDetector(stillMs = 300)
        assertTrue(d.offer("こんにちは。", 0))
        assertFalse(d.offer("こんにちは。", 350)) // already read
        assertTrue(d.offer("「そうだね」", 700))   // speech opened and closed, with no full stop
        assertTrue(d.offer("ユイ\n「本当に？」", 1_050))
        // No end to it: it may be half typed, or cut by the box's edge.
        assertFalse(d.offer("それは、つまり", 1_400))
        assertTrue(d.offer("それは、つまり", 1_750))
        // Dots are typed one at a time.
        assertFalse(d.offer("「……", 2_100))
        assertFalse(d.offer("そうか……", 2_450))
    }

    @Test
    fun aLineBeingTypedWaitsEvenAtAFullStop() {
        val d = SettleDetector(stillMs = 300)
        assertFalse(d.offer("でも、", 0))
        // Caught at the rest after the first sentence: more is coming.
        assertFalse(d.offer("でも、近づいたら消えてしまった。", 350))
        assertFalse(d.offer("でも、近づいたら消えてしまった。\nあれは一体何だったんだろう。", 700))
        assertTrue(d.offer("でも、近づいたら消えてしまった。\nあれは一体何だったんだろう。", 1_050))
    }

    @Test
    fun aHelpLineThatStaysDoesNotEndTheStorysLine() {
        val d = SettleDetector(stillMs = 300)
        assertTrue(d.offer("前の行だ。\nAボタンで決定します。", 0))
        assertFalse(d.offer("次の行は\nAボタンで決定します。", 350))
        assertTrue(d.offer("次の行は\nAボタンで決定します。", 700))
        assertFalse(SettleDetector.ended("彼は「無理だ」", ""))
        assertFalse(SettleDetector.ended("所持金 100G", ""))
    }

    @Test
    fun aBlankScreenNeverSettlesAndTheSameLineIsNotReadTwice() {
        val d = SettleDetector(stillMs = 300)
        d.offer("a", 0)
        assertTrue(d.offer("a", 400))
        assertFalse(d.offer("", 800))
        assertFalse(d.offer("", 1600))
        // A menu closed and the same line is back.
        assertFalse(d.offer("a", 2000))
        assertFalse(d.offer("a", 2400))
        assertFalse(d.offer("b", 2800))
        assertTrue(d.offer("b", 3200))
    }

    @Test
    fun aLinesStartIsWhenItBeganToAppearNotWhenItFinishedTyping() {
        val d = SettleDetector(stillMs = 300)
        assertTrue(d.offer("前の行。", 0))
        // The next line types itself out from 5 000 ms and holds still from 5 700.
        assertFalse(d.offer("こ", 5_000))
        assertFalse(d.offer("こん", 5_350))
        assertFalse(d.offer("こんにちは。", 5_700))
        assertTrue(d.offer("こんにちは。", 6_050))
        assertEquals(5_000L, d.changeStartedMs)
        // A blank between two lines: the next one starts when its own text does.
        assertFalse(d.offer("", 9_000))
        assertFalse(d.offer("次", 9_350))
        assertFalse(d.offer("次の行。", 9_700))
        assertTrue(d.offer("次の行。", 10_050))
        assertEquals(9_350L, d.changeStartedMs)
    }

    @Test
    fun aMovingBackgroundDoesNotKeepTextFromSettling() {
        // Sixty characters of menu; the first pass reads a stray symbol differently on every frame.
        val menu = "グラフィックス\nコントロール\nオーディオ\nネットワーク\nツール\nシステム\n検索\nナビゲーションバーを隠す\n通知の表示位置\n"
        val d = SettleDetector(stillMs = 300)
        assertFalse(d.offer(menu + "央", 0))
        assertFalse(d.offer(menu + "肉", 150))
        assertTrue(d.offer(menu + "図", 350))
        // The same screen, still flickering, is not read again.
        assertFalse(d.offer(menu + "メ", 500))
        assertFalse(d.offer(menu + "央Ｘ", 900))
        // A real change is.
        assertFalse(d.offer(menu + "メモリースティックの設定", 1_000))
        assertTrue(d.offer(menu + "メモリースティックの設定", 1_400))
    }

    @Test
    fun shortTextMustMatchExactlyAndTypingIsNotNoise() {
        assertFalse(SettleDetector.same("はい", "いいえ"))
        assertFalse(SettleDetector.same("所持金 100G", "所持金 200G"))
        val line = "そのとき彼女は静かに頷いて、窓の外の景色をしばらく眺めていた。"
        assertFalse(SettleDetector.same(line.take(20), line.take(24)))
    }

    @Test
    fun textThatNeverHoldsStillIsReadAnyway() {
        val d = SettleDetector(stillMs = 300, restlessMs = 2_000)
        var read = 0
        for (i in 0..30) if (d.offer("ゆれる文字$i", i * 100L)) read++
        assertEquals(1, read)
    }

    @Test
    fun resetForgetsTheLastLine() {
        val d = SettleDetector(stillMs = 300)
        d.offer("a", 0)
        assertTrue(d.offer("a", 400))
        d.reset()
        d.offer("a", 800)
        assertTrue(d.offer("a", 1200))
    }

    @Test
    fun sentenceMarksTheWordAndEscapesTheRest() {
        assertEquals("森の奥で<b>不思議</b>な光", Sentence.mark("森の奥で不思議な光", 4, 7))
        assertEquals("a &lt; <b>b</b> &amp; c", Sentence.mark("a < b & c", 4, 5))
        assertEquals("abc", Sentence.mark("abc", 5, 9))
        assertEquals("ab<b>c</b>", Sentence.mark("abc", 2, 9))
    }

    @Test
    fun furiganaPlain() {
        assertEquals("不思議[ふしぎ]", Sentence.furiganaPlain("不思議", "ふしぎ"))
        assertEquals("ふしぎ", Sentence.furiganaPlain("ふしぎ", "ふしぎ"))
        assertEquals("ＣＤ", Sentence.furiganaPlain("ＣＤ", ""))
        assertEquals("食[た]べる", Sentence.furiganaPlain("食べる", "たべる"))
        assertEquals("取[と]り 引[ひ]き", Sentence.furiganaPlain("取り引き", "とりひき"))
        assertEquals("お 茶[ちゃ]", Sentence.furiganaPlain("お茶", "おちゃ"))
    }

    @Test
    fun fingerprintIgnoresFaintChangeAndSeesRealChange() {
        val dark = IntArray(8) { 0xFF101010.toInt() }
        val nearlyDark = IntArray(8) { 0xFF131313.toInt() }
        val withText = dark.copyOf().also { it[3] = 0xFFF0F0F0.toInt() }
        assertEquals(PixelProbe.fingerprint(dark), PixelProbe.fingerprint(nearlyDark))
        assertNotEquals(PixelProbe.fingerprint(dark), PixelProbe.fingerprint(withText))
    }

    @Test
    fun textTakenOnItsFirstPictureIsReadAgainWhenItLooksDifferentOnceStill() {
        val clear = "くさむらの なかから やせいの ポケモンが とびだしてきた！\nさあ どうする？ たたかうか にげるか きめよう！"
        val faded = clear.replaceFirst('ポ', 'ボ')
        val d = SettleDetector(stillMs = 300)
        assertTrue(d.offer(faded, 0))
        assertFalse(d.confirming)
        assertFalse(d.offer(clear, 333))
        assertTrue(d.offer(clear, 666))
        assertTrue(d.confirming)
        assertFalse(d.offer(clear, 1_000))
        // Read the same on the next look: nothing to read again.
        val e = SettleDetector(stillMs = 300)
        assertTrue(e.offer(clear, 0))
        assertFalse(e.offer(clear, 333))
        assertFalse(e.offer(clear, 666))
    }

    @Test
    fun typedTextThatEndedASentenceAndWentBeforeItWasReadIsMissed() {
        val d = SettleDetector(stillMs = 300)
        assertFalse(d.offer("こうかは", 0))
        assertFalse(d.offer("こうかは ばつぐんだ！", 200))
        assertFalse(d.missed)
        assertFalse(d.offer("", 400))
        assertTrue(d.missed)
        // It is not read again when it comes back.
        assertFalse(d.offer("こうかは ばつぐんだ！", 800))
        assertFalse(d.offer("こうかは ばつぐんだ！", 1_200))
        // Text that ended no sentence is still being typed out, and is not missed.
        val e = SettleDetector(stillMs = 300)
        e.offer("こう", 0)
        e.offer("こうかは", 200)
        e.offer("", 400)
        assertFalse(e.missed)
    }

    @Test
    fun theSameLettersInAnotherOrderOrAnotherNumberAreAChange() {
        val line = "ポッポは たいあたりを つかった！\nあいての コラッタに ダメージを あたえた！"
        val swapped = line.replaceFirst("ポッポ", "ポポッ")
        assertFalse(SettleDetector.same(line, swapped))
        val money = "おこづかいを 10えん もらった！\nさっそく フレンドリィショップで かいものを しよう！"
        assertFalse(SettleDetector.same(money, money.replace("10", "20")))
        // Noise is still noise.
        assertTrue(SettleDetector.same(money, money.replace("さっそく", "さっそく・")))
    }
}
