package io.github.beesan.lfg

import io.github.beesan.lfg.dict.DictionaryRepository
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.dict.Glossary
import io.github.beesan.lfg.dict.InstalledDictionary
import io.github.beesan.lfg.dict.KanjiEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanjiTest {
    // Cut down from 生 in Bee's Ultimate Kanji Dictionary 2026.09.08.
    private val sei = """[{"content":[{"content":[{"content":[{"content":"生","data":{"beeRole":"hero-glyph"},"lang":"ja","tag":"span"},
        {"content":"life","data":{"beeRole":"hero-keyword"},"tag":"span"}],"data":{"beeRole":"hero"},"tag":"div"},
        {"content":[{"content":"せい","data":{"beeRole":"reading-chip"},"lang":"ja","tag":"span"},{"content":"お","data":{"beeRole":"reading-chip"},"lang":"ja","tag":"span"}],"data":{"beeRole":"reading-chips"},"tag":"div"},
        {"content":"life; genuine; birth","data":{"beeRole":"meaning"},"tag":"div"},
        {"content":[{"content":"On","tag":"summary"},{"content":"セイ","data":{"beeRole":"reading-chip"},"tag":"span"}],"tag":"details"},
        {"content":[{"content":{"content":[{"content":["生",{"content":"い","tag":"rt"}],"tag":"ruby"},"きる"],"href":"?query=生きる","tag":"a"},"data":{"beeRole":"vocab-word"},"tag":"span"},
        {"content":" — to live","data":{"beeRole":"vocab-gloss"},"tag":"span"}],"data":{"beeRole":"vocab-item"},"tag":"div"}],"data":{"beeRole":"detail"},"tag":"div"}],"type":"structured-content"}]"""

    private val beeTitle = "Bee's Ultimate Kanji Dictionary"

    @Test
    fun eachKanjiOfAHeadwordInAListIsALinkToItsEntry() {
        val html = EntryHtml.body(Entry(expression = "生きる", reading = "いきる"), index = 0)
        assertTrue(html, html.contains("""<a class="lfg-kanji" href="${EntryHtml.KANJI_URL}/${"生".codePointAt(0)}">生</a>"""))
        // The furigana and the kana are left alone.
        assertTrue(html, html.contains("<rt>い</rt></ruby>きる"))
        // A page of one entry has nowhere to go.
        assertFalse(EntryHtml.body(Entry(expression = "生きる", reading = "いきる")).contains("lfg-kanji"))
        assertEquals("ひらがな", EntryHtml.kanjiLinks("ひらがな"))
    }

    @Test
    fun aKanjisEntriesHaveAWayBackToTheWord() {
        val body = EntryHtml.listBody(listOf(Entry(expression = "生")), back = "生きる") { false }
        assertTrue(body, body.startsWith("""<a class="lfg-back" href="${EntryHtml.BACK_URL}/0">"""))
        assertTrue(body.contains("生きる"))
        assertFalse(EntryHtml.listBody(listOf(Entry(expression = "生"))) { false }.contains("lfg-back"))
    }

    @Test
    fun aMinedKanjiHasItsReadingsAndMeaningsWithoutTheExamples() {
        // The engine gives an entry with no reading the kanji itself as one.
        val entry = Entry(expression = "生", reading = "生", glossaries = listOf(Glossary(dictionary = beeTitle, glossary = sei)))
        assertEquals("せい、お", KanjiEntry.reading(entry))
        assertEquals(listOf("life", "life; genuine; birth"), KanjiEntry.senses(entry))
    }

    @Test
    fun anotherDictionarysKanjiGivesItsSensesAsAWordsWould() {
        val entry = Entry(expression = "生", reading = "せい", glossaries = listOf(Glossary(dictionary = "D", glossary = """["life","birth"]""")))
        assertEquals("せい", KanjiEntry.reading(entry))
        assertEquals(listOf("life; birth"), KanjiEntry.senses(entry))
        assertEquals("", KanjiEntry.reading(entry.copy(reading = "生")))
    }

    @Test
    fun theKanjiDictionaryIsTheChosenOneElseBees() {
        fun d(title: String) = InstalledDictionary(title = title, path = "/x", terms = 10, installedAt = 0)
        val all = listOf(d("Jitendex.org [2026-11-01]"), d(beeTitle), d("KANJIDIC"))
        assertEquals(beeTitle, DictionaryRepository.kanjiDictionary(all, "")?.title)
        assertEquals("KANJIDIC", DictionaryRepository.kanjiDictionary(all, "KANJIDIC")?.title)
        // A choice since removed falls back to Bee's.
        assertEquals(beeTitle, DictionaryRepository.kanjiDictionary(all, "Gone")?.title)
        assertNull(DictionaryRepository.kanjiDictionary(listOf(d("KANJIDIC")), ""))
    }

    @Test
    fun inAListADictionarysNameFoldsItAwayAndAPageOfOneEntryHasNoSuchButton() {
        val entry = Entry(expression = "生", glossaries = listOf(io.github.beesan.lfg.dict.Glossary(dictionary = "JMdict", glossary = "[\"life\"]")))
        val listed = EntryHtml.listBody(listOf(entry)) { false }
        assertTrue(listed.contains("""<div class="lfg-fold" role="button" aria-expanded="true" onclick="lfgToggle(this)"><span class="lfg-source">JMdict</span>"""))
        assertFalse(EntryHtml.page(entry, emptyList()).contains("lfgToggle(this)"))
        assertEquals("""lfgFold(["A \"b\"","JMdict"]);""", EntryHtml.foldScript(setOf("JMdict", "A \"b\"")))
        assertTrue(EntryHtml.listHead(emptyList(), io.github.beesan.lfg.core.Palette.of(io.github.beesan.lfg.core.Palette.PINK, io.github.beesan.lfg.core.Palette.THEME_DARK, systemDark = true)).contains("function lfgToggle"))
    }
}
