package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.KanaRepair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KanaRepairTest {
    /** A dictionary of [words]: the longest of them the text starts with. */
    private fun dictionary(vararg words: String): suspend (String) -> Int =
        { text -> words.filter { text.startsWith(it) }.maxOfOrNull { it.length } ?: 0 }

    private val words = dictionary(
        "でんし", "でんしゃ", "に", "の", "のって", "が", "がっこう", "へ", "ハン", "ハンバーガー", "を",
        "ソフト", "と", "ツール", "シール", "はかり", "ばかり", "や",
    )

    private fun repair(text: String) = runBlocking { KanaRepair.line(TextLine(text, Box(0, 0, 0, 0), emptyList()), words).text }

    @Test
    fun aLookalikeThatMakesALongerWordIsPutRight() {
        assertEquals("でんしゃに　のって", repair("でんしやに　のって"))
        assertEquals("がっこうへ", repair("がつこうへ"))
        assertEquals("ハンバーガーを", repair("ハンハーガーを"))
        assertEquals("ソフトと", repair("ンフトと"))
    }

    @Test
    fun whatAlreadyReadsAsWordsStays() {
        assertEquals("はかり", repair("はかり"))
        assertEquals("ばかり", repair("ばかり"))
        assertEquals("シールと", repair("シールと"))
        assertEquals("でんしゃ", repair("でんしゃ"))
    }

    @Test
    fun aLineWithKanjiIsLeftAsRead() {
        assertEquals("電車でんしや", repair("電車でんしや"))
    }

    @Test
    fun theBoxesStay() {
        val chars = listOf(CharBox(0, 1, Box(0, 0, 40, 40)), CharBox(3, 4, Box(120, 0, 160, 40)))
        val line = runBlocking { KanaRepair.line(TextLine("でんしや", Box(0, 0, 160, 40), chars), words) }
        assertEquals("でんしゃ", line.text)
        assertEquals(chars, line.chars)
    }
}
