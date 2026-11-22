package io.github.beesan.lfg

import io.github.beesan.lfg.core.InterfaceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceTextTest {
    private val minute = 60_000L

    @Test
    fun piecesOnScreenTogetherAreNotInterfaceForThat() {
        // Which pieces came as a menu does is LineSorter's to tell: it knows what has just arrived.
        val menu = listOf("オーディオ", "ビデオ", "コントロール", "ネットワーク", "システム", "ゲームを終了する")
        assertTrue(InterfaceText().onReading(menu).isEmpty())
    }

    @Test
    fun aNamePlateThatStaysThroughThreeLinesIsInterface() {
        val tell = InterfaceText()
        assertTrue(tell.onReading(listOf("アリス", "おはよう。")).isEmpty())
        assertTrue(tell.onReading(listOf("アリス", "今日はいい天気だね。")).isEmpty())
        assertTrue(tell.onReading(listOf("アリス", "どこかへ出かけない？")).isEmpty())
        assertEquals(setOf("アリス"), tell.onReading(listOf("アリス", "公園なんてどう？")))
    }

    @Test
    fun aLongLineNeverIsWhateverStaysAroundIt() {
        val tell = InterfaceText()
        val line = "この先は危険だから、気をつけて進んでください。"
        for (label in listOf("残り三分", "残り二分", "残り一分", "時間切れ")) {
            assertTrue(tell.onReading(listOf(line, label)).isEmpty())
        }
    }

    @Test
    fun textThatLeftAndCameBackHasNotStayed() {
        val tell = InterfaceText()
        tell.onReading(listOf("アリス", "一"))
        tell.onReading(listOf("アリス", "二"))
        tell.onReading(emptyList())
        tell.onReading(listOf("アリス", "三"))
        assertTrue(tell.onReading(listOf("アリス", "四")).isEmpty())
    }

    @Test
    fun theSameScreenAgainIsNoChange() {
        val tell = InterfaceText()
        repeat(5) { assertTrue(tell.onReading(listOf("アリス", "おはよう。")).isEmpty()) }
    }

    @Test
    fun aLabelBackOnThreeOccasionsIsInterface() {
        val now = 100 * minute
        assertTrue(InterfaceText.comesBack("セーブ", listOf(now - 40 * minute, now - 20 * minute), now))
        // A menu opened every five minutes: occasions begin ten minutes apart.
        assertTrue(InterfaceText.comesBack("セーブ", List(4) { now - (it + 1) * 5 * minute }, now))
    }

    @Test
    fun aLineReadAgainAfterAMenuIsOneOccasion() {
        val now = 100 * minute
        assertFalse(InterfaceText.comesBack("はい", listOf(now - 3 * minute, now - minute), now))
        assertFalse(InterfaceText.comesBack("はい", listOf(now - 30 * minute), now))
    }

    @Test
    fun aLongLineNeverComesBackAsInterface() {
        val now = 100 * minute
        val line = "ようこそ！ここは港町のよろず屋だよ。"
        assertFalse(InterfaceText.comesBack(line, listOf(now - 60 * minute, now - 30 * minute), now))
    }

    @Test
    fun aCharacterAloneIsNoLineButAMarkAloneMayBe() {
        // An icon read as a character on the Thor: 目 in CLANNAD, シ in Senren Banka.
        assertTrue(InterfaceText.lone("目"))
        assertTrue(InterfaceText.lone("シ"))
        assertTrue(InterfaceText.lone("ー"))
        assertFalse(InterfaceText.lone("…"))
        assertFalse(InterfaceText.lone("！"))
        assertFalse(InterfaceText.lone("え？"))
    }

    @Test
    fun typeFarSmallerThanTheStorysIsScenery() {
        // CLANNAD on the Thor: the story is set at about 48 pixels, the spines of the books behind at about 22.
        assertTrue(InterfaceText.small(22, 48))
        // A name plate and a choice are nearer the story's size.
        assertFalse(InterfaceText.small(38, 48))
        assertFalse(InterfaceText.small(48, 48))
        // Nothing is known: nothing is scenery.
        assertFalse(InterfaceText.small(22, 0))
        assertFalse(InterfaceText.small(0, 48))
    }

    @Test
    fun aLinesTypeSizeIsItsLettersMiddleSize() {
        fun box(left: Int, size: Int) = io.github.beesan.lfg.core.Box(left, 0, left + size, size)
        val chars = listOf(48, 46, 50, 48, 12).mapIndexed { i, size -> io.github.beesan.lfg.core.CharBox(i, i + 1, box(i * 60, size)) }
        // The mark at the end is small and is left out.
        val line = io.github.beesan.lfg.core.TextLine("食べるの。", io.github.beesan.lfg.core.Box(0, 0, 300, 50), chars)
        assertEquals(48, line.typeSize)
        assertEquals(0, io.github.beesan.lfg.core.TextLine("……", io.github.beesan.lfg.core.Box(0, 0, 10, 10), emptyList()).typeSize)
    }
}
