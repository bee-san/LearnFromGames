// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.ask.LineAsker
import io.github.beesan.lfg.core.Furigana
import io.github.beesan.lfg.core.Ruby
import io.github.beesan.lfg.core.WheelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMarksTest {
    @Test
    fun `a reading divides at the kana in its word`() {
        assertEquals(listOf(Ruby(0, 1, "と"), Ruby(2, 3, "ひ")), Furigana.divide("取り引き", "とりひき"))
        assertEquals(listOf(Ruby(0, 2, "がっこう")), Furigana.divide("学校", "がっこう"))
        assertEquals(listOf(Ruby(1, 2, "ちゃ")), Furigana.divide("お茶", "おちゃ"))
        assertEquals(emptyList<Ruby>(), Furigana.divide("ひらがな", "ひらがな"))
    }

    @Test
    fun `an inflected word keeps the reading of the kanji its inflection leaves alone`() {
        assertEquals(listOf(Ruby(0, 1, "み")), Furigana.of("見た", "見る", "みる"))
        assertEquals(listOf(Ruby(0, 1, "たべ").copy(reading = "た")), Furigana.of("食べました", "食べる", "たべる"))
        assertEquals(listOf(Ruby(0, 2, "べんきょう")), Furigana.of("勉強し", "勉強する", "べんきょうする"))
    }

    @Test
    fun `a katakana word in the dictionary form is read as its kana`() {
        assertEquals(listOf(Ruby(3, 4, "か")), Furigana.divide("サボり家", "さぼりか"))
    }

    @Test
    fun `kuru is read by what follows it`() {
        assertEquals(listOf(Ruby(0, 1, "こ")), Furigana.of("来ない", "来る", "くる"))
        assertEquals(listOf(Ruby(0, 1, "き")), Furigana.of("来た", "来る", "くる"))
        assertEquals(listOf(Ruby(0, 1, "く")), Furigana.of("来る", "来る", "くる"))
    }

    @Test
    fun `particles are not worth marking by their card, words are`() {
        assertFalse(Furigana.worthKnowing("は"))
        assertFalse(Furigana.worthKnowing("から"))
        assertTrue(Furigana.worthKnowing("猫"))
        assertTrue(Furigana.worthKnowing("ゲーム"))
        assertTrue(Furigana.worthKnowing("パン"))
    }

    @Test
    fun `each tile that asks about a line has its own task, and a Prompt tile its own question`() {
        val asking = WheelAction.entries.filter { it.asksOfLine }
        assertEquals(8, asking.size)
        val tasks = asking.map { LineAsker.task(it, "English", "Is this rude?") }
        tasks.forEach(::assertNotNull)
        assertEquals(tasks.size, tasks.toSet().size)
        assertTrue(LineAsker.task(WheelAction.PROMPT, "English", "Is this rude?")!!.contains("Is this rude?"))
        assertNull(LineAsker.task(WheelAction.PROMPT, "English", "  "))
        assertNull(LineAsker.task(WheelAction.COPY, "English"))
        assertTrue(LineAsker.prompt("Game", listOf("前の行"), "この行").contains("<line>\nこの行\n</line>"))
    }
}
