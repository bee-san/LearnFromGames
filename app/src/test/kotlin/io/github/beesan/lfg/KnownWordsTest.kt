// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.anki.AnkiWords
import io.github.beesan.lfg.core.KnownWordList
import io.github.beesan.lfg.ui.Page
import io.github.beesan.lfg.ui.SettingsIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownWordsTest {
    @Test
    fun `a pasted list is one word a line, or apart by spaces and commas`() {
        assertEquals(listOf("見る", "食べる", "日本"), KnownWordList.parse("見る\n食べる\r\n\n  日本  \n"))
        assertEquals(listOf("見る", "食べる", "日本", "猫", "犬"), KnownWordList.parse("見る 食べる,日本、猫　犬"))
        assertEquals(listOf("a", "b", "c"), KnownWordList.parse("a\tb; c，"))
    }

    @Test
    fun `a word given twice is kept once, the first time, and capitals do not matter`() {
        assertEquals(listOf("見る", "Haus"), KnownWordList.parse("見る\nHaus\n見る\nhaus"))
        assertEquals(emptyList<String>(), KnownWordList.parse(" \n\t、,"))
    }

    @Test
    fun `the list reads back as it was saved`() {
        val words = listOf("見る", "食べる", "日本")
        assertEquals(words, KnownWordList.parse(KnownWordList.format(words)))
    }

    @Test
    fun `a word is known by its dictionary form`() {
        val list = setOf("見る", "haus").map(KnownWordList::key).toSet()
        assertTrue(KnownWordList.knows(list, "見る", "みる", "見た"))
        assertTrue(KnownWordList.knows(list, "Haus", "", "Hauses"))
        assertFalse(KnownWordList.knows(list, "食べる", "たべる", "食べた"))
    }

    @Test
    fun `a word written in kana is known by its reading too, one written in kanji is not`() {
        val list = setOf("ありがとう", "みる")
        assertTrue(KnownWordList.knows(list, "有難う", "ありがとう", "ありがとう"))
        // 見る's reading on the list does not make 見る known where the line writes it in kanji.
        assertFalse(KnownWordList.knows(list, "見る", "みる", "見た"))
        assertTrue(KnownWordList.knows(list, "見る", "みる", "みた"))
    }

    @Test
    fun `a field's checksum is Anki's`() {
        // int(sha1(word.encode()).hexdigest()[:8], 16), as Anki's field_checksum and AnkiDroid's API make it.
        assertEquals(1155165116L, AnkiWords.checksum("日本"))
        assertEquals(3364594046L, AnkiWords.checksum("見る"))
        assertEquals(1120966288L, AnkiWords.checksum("ありがとう"))
        assertEquals(3834974802L, AnkiWords.checksum("dog"))
        assertEquals("csum in (1155165116,3364594046)", AnkiWords.byChecksum(listOf("日本", "見る", "日本")))
    }

    @Test
    fun `a field is the word it holds, without HTML or readings`() {
        assertEquals("日本", AnkiWords.plain("<b>日本</b>"))
        assertEquals("日本", AnkiWords.plain(" 日本[にほん]"))
        assertEquals("見る", AnkiWords.plain("見[み]る"))
        assertEquals("ice cream", AnkiWords.plain("ice&nbsp; cream<br>"))
        assertEquals("日本", AnkiWords.firstField("日本\u001fにほん\u001fJapan"))
        assertEquals(listOf("日本", "にほん", "Japan"), AnkiWords.fields("<div>日本</div>\u001fにほん\u001fJapan"))
    }

    /** The regular expression inside the search, as Anki is given it, with the `(?i)` Anki puts before it. */
    private fun regex(search: String): Regex {
        assertTrue(search, search.startsWith("\"*:re:") && search.endsWith("\""))
        return Regex("(?i)" + search.removePrefix("\"*:re:").removeSuffix("\"").replace("\\\"", "\""))
    }

    @Test
    fun `the search for any field finds a field that is just the word`() {
        val re = regex(AnkiWords.anyField(listOf("日本", "見る")))
        for (field in listOf("日本", "<b>日本</b>", " 日本[にほん]", "見[み]る", "見る&nbsp;", "<div>見る</div>")) {
            assertTrue(field, re.containsMatchIn(field))
        }
        // A sentence, or a longer word, that has it inside is not that word.
        for (field in listOf("日本語", "日本に行く", "見るな", "大日本")) assertFalse(field, re.containsMatchIn(field))
    }

    @Test
    fun `what a regular expression means in a word is taken as it is`() {
        val re = regex(AnkiWords.anyField(listOf("a.b", "c+", "say \"hi\"")))
        assertTrue(re.containsMatchIn("a.b"))
        assertFalse(re.containsMatchIn("axb"))
        assertTrue(re.containsMatchIn("c+"))
        assertFalse(re.containsMatchIn("cc"))
        assertTrue(re.containsMatchIn("say \"hi\""))
    }

    @Test
    fun `mature notes are those with a card of three weeks`() {
        assertEquals("nid:1,22,333 prop:ivl>=21", AnkiWords.mature(listOf(1L, 22L, 333L), 21))
    }

    @Test
    fun `Settings search finds known words on the Anki page`() {
        val found = SettingsIndex.search("known words")
        assertTrue(found.any { it.name == "Your known words" && it.page == Page.ANKI })
        assertTrue(found.any { it.name == "Look in all of Anki" && it.page == Page.ANKI })
    }
}
