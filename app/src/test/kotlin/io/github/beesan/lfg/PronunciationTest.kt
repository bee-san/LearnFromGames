package io.github.beesan.lfg

import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.Family
import io.github.beesan.lfg.anki.NoteTypes
import io.github.beesan.lfg.dict.DictJson
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.dict.Frequency
import io.github.beesan.lfg.dict.FrequencyValue
import io.github.beesan.lfg.dict.LookupResponse
import io.github.beesan.lfg.dict.Pitch
import io.github.beesan.lfg.dict.PitchValue
import io.github.beesan.lfg.dict.Pronunciation
import io.github.beesan.lfg.dict.Pronunciation.Kind
import io.github.beesan.lfg.dict.WordAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationTest {
    private fun entry(expression: String, reading: String, vararg positions: Int, rules: String = "") =
        Entry(expression = expression, reading = reading, rules = rules, pitches = listOf(Pitch("NHK", positions.map { PitchValue(it) })))

    @Test
    fun smallKanaJoinTheMoraBeforeThem() {
        assertEquals(listOf("きょ", "う"), Pronunciation.morae("きょう"))
        assertEquals(listOf("シャ", "ツ"), Pronunciation.morae("シャツ"))
        assertEquals(listOf("が", "っ", "こ", "う"), Pronunciation.morae("がっこう"))
    }

    @Test
    fun eachAccentGetsYomitansCategory() {
        assertEquals(Kind.HEIBAN, Pronunciation.accents(entry("桜", "さくら", 0)).single().kind)
        assertEquals(Kind.ATAMADAKA, Pronunciation.accents(entry("箸", "はし", 1)).single().kind)
        assertEquals(Kind.NAKADAKA, Pronunciation.accents(entry("卵", "たまご", 2)).single().kind)
        assertEquals(Kind.ODAKA, Pronunciation.accents(entry("橋", "はし", 2)).single().kind)
        assertEquals(Kind.KIFUKU, Pronunciation.accents(entry("食べる", "たべる", 2, rules = "v1")).single().kind)
        // The same accent from two dictionaries shows once; one past the end of the word is dropped.
        assertEquals(listOf(1, 0), Pronunciation.accents(entry("今日", "きょう", 1, 0, 1, 5)).map { it.position })
    }

    @Test
    fun aPatternOfHighsAndLowsGivesItsDrop() {
        assertEquals(2, Pronunciation.position(PitchValue(pattern = "LHL")))
        assertEquals(0, Pronunciation.position(PitchValue(pattern = "LHHH")))
    }

    @Test
    fun theReadingIsDrawnWithItsPitch() {
        val accent = Pronunciation.accents(entry("卵", "たまご", 2)).single()
        assertEquals(listOf(false, true, false, false), accent.high)
        assertEquals(
            """<span class="lfg-reading lfg-pitched" lang="ja" data-pitch="nakadaka"><span class="lfg-mora">た</span>""" +
                """<span class="lfg-mora lfg-high lfg-drop">ま</span><span class="lfg-mora">ご</span></span>""",
            EntryHtml.pitched(accent),
        )
        // 尾高 drops after the last mora, onto the particle.
        assertTrue(EntryHtml.pitched(Pronunciation.accents(entry("橋", "はし", 2)).single()).endsWith("""<span class="lfg-mora lfg-high lfg-drop">し</span></span>"""))
    }

    @Test
    fun anEntryShowsItsFrequenciesAccentsAndAudioButton() {
        val e = entry("卵", "たまご", 2).copy(
            frequencies = listOf(Frequency("JPDB_v2.2_Frequency_Kana_2024-10-13", listOf(FrequencyValue(1234, "1234㋕")))),
        )
        val html = EntryHtml.body(e, audio = true)
        assertTrue(html.contains("""<span class="lfg-pitch-tag" data-pitch="nakadaka">中高 <b>2</b></span>"""))
        assertTrue(html.contains("""<span class="lfg-freq-name">JPDB</span><span class="lfg-freq-value">1234㋕</span>"""))
        assertTrue(html.contains("""href="${EntryHtml.AUDIO_URL}""""))
        assertFalse(EntryHtml.body(e).contains("lfg-audio"))
        assertEquals("中高 2 · JPDB 1234㋕", Pronunciation.summary(e))
        // Nothing extra for an entry with neither.
        assertFalse(EntryHtml.body(Entry(expression = "見る", reading = "みる")).contains("lfg-meta"))
    }

    @Test
    fun theLookupJsonCarriesFrequenciesAndPitches() {
        val json = """{"results":[{"expression":"卵","reading":"たまご","glossaries":[],
            "frequencies":[{"dictionary":"JPDB","values":[{"value":1234,"display":""}]}],
            "pitches":[{"dictionary":"NHK","values":[{"position":2,"pattern":""}]}]}]}"""
        val e = DictJson.decodeFromString(LookupResponse.serializer(), json).results.single()
        assertEquals(1234, e.frequencies.single().values.single().value)
        assertEquals(2, e.pitches.single().values.single().position)
    }

    @Test
    fun frequencyRankIsTheHarmonicMeanOfEachDictionarysFirstValue() {
        val e = Entry(frequencies = listOf(Frequency("A", listOf(FrequencyValue(100), FrequencyValue(9))), Frequency("B", listOf(FrequencyValue(300)))))
        assertEquals(150, Pronunciation.frequencyRank(e))
        assertNull(Pronunciation.frequencyRank(Entry()))
    }

    @Test
    fun frequencyDictionaryNamesLoseTheirVersions() {
        assertEquals("JPDB", Pronunciation.shortName("JPDB_v2.2_Frequency_Kana_2024-10-13"))
        assertEquals("BCCWJ", Pronunciation.shortName("BCCWJ"))
        assertEquals("Innocent Ranked", Pronunciation.shortName("Innocent Ranked"))
        assertEquals("2024", Pronunciation.shortName("2024"))
    }

    @Test
    fun kanaFormsForTheAudioLookup() {
        assertEquals("たまご", WordAudio.hiragana("タマゴ"))
        assertEquals("タマゴ", WordAudio.katakana("たまご"))
        assertEquals("ー", WordAudio.hiragana("ー"))
    }

    @Test
    fun cardsGetWordAudioPitchAndFrequency() {
        val lapis = listOf("Expression", "ExpressionReading", "ExpressionAudio", "MainDefinition", "Sentence", "PitchPosition", "FreqSort")
        val content = CardContent(
            expression = "卵", reading = "たまご", sentence = "卵を買った。", spanStart = 0, spanEnd = 1, senses = listOf("egg"),
            gameName = "G", pitchPositions = listOf(2), frequencyRank = 1234,
        )
        val fields = NoteTypes.fill(Family.LAPIS, lapis, content, wordAudio = "[sound:lfg_word_1.mp3]")
        assertEquals("[sound:lfg_word_1.mp3]", fields[2])
        assertEquals("2", fields[5])
        assertEquals("1234", fields[6])
        assertTrue(NoteTypes.hasWordAudioField(Family.LAPIS, lapis))
        assertTrue(NoteTypes.hasWordAudioField(Family.SENREN, listOf("word", "wordAudio")))
        assertFalse(NoteTypes.hasWordAudioField(Family.OWN, NoteTypes.OWN_FIELDS))
        assertEquals("<ol><li>0</li><li>2</li></ol>", NoteTypes.fill(Family.LAPIS, lapis, content.copy(pitchPositions = listOf(0, 2)))[5])
    }
}
