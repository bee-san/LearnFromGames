package io.github.beesan.lfg

import io.github.beesan.lfg.core.InterfaceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceTextTest {
    private val minute = 60_000L

    @Test
    fun aMenuIsACrowdOfShortLabels() {
        val menu = listOf("オーディオ", "ビデオ", "コントロール", "ネットワーク", "システム", "ゲームを終了する")
        assertEquals(menu.toSet(), InterfaceText().onReading(menu))
    }

    @Test
    fun aNovelPageIsNoMenu() {
        // Six pieces, but sentences: only the short one is taken.
        val page = List(5) { "これは長い文章の${it}行目で、まだまだ続いていきます。" } + "……"
        assertEquals(setOf("……"), InterfaceText().onReading(page))
    }

    @Test
    fun aTextBoxAndItsChoicesAreNoMenu() {
        val screen = listOf("アリス", "どうする？", "戦う", "逃げる", "話す")
        assertTrue(InterfaceText().onReading(screen).isEmpty())
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
}
