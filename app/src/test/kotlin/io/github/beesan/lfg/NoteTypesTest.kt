package io.github.beesan.lfg

import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.Family
import io.github.beesan.lfg.anki.NoteTypes
import io.github.beesan.lfg.anki.NoteTypes.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTypesTest {
    private val lapis = listOf(
        "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "SelectionText", "MainDefinition",
        "DefinitionPicture", "Sentence", "SentenceFurigana", "SentenceAudio", "Picture", "Glossary", "Hint",
        "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard", "PitchPosition", "PitchCategories",
        "Frequency", "FreqSort", "MiscInfo",
    )
    private val senren = listOf(
        "word", "reading", "sentence", "sentenceFurigana", "sentenceTranslation", "sentenceCard", "audioCard", "notes",
        "hint", "picture", "wordAudio", "sentenceAudio", "selectionText", "definition", "glossary", "pitchAccents",
        "pitchPositions", "pitchCategories", "frequencies", "freqSort", "miscInfo", "dictionaryPreference",
    )
    private val content = CardContent(
        expression = "不思議", reading = "ふしぎ", sentence = "森の奥で不思議な光を見たんだ。", spanStart = 4, spanEnd = 7,
        senses = listOf("wonderful; strange", "wonder; mystery"), gameName = "PPSSPP Gold",
    )

    @Test
    fun familyNameMustLeadAndEndAtAWordBoundary() {
        assertEquals(Family.KIKU, NoteTypes.family("Kiku v2"))
        assertEquals(Family.LAPIS, NoteTypes.family("  lapis 1.4"))
        assertEquals(Family.SENREN, NoteTypes.family("Senren"))
        assertEquals(Family.LAPIS, NoteTypes.family("Lapis-JP"))
        assertNull(NoteTypes.family("Kikuchi"))
        assertNull(NoteTypes.family("My Kiku"))
        assertNull(NoteTypes.family("Lapis2"))
        assertNull(NoteTypes.family("Basic"))
    }

    @Test
    fun aNamesakeWithoutTheFieldsIsNotEligible() {
        assertTrue(NoteTypes.coreMapped(Family.LAPIS, lapis))
        assertTrue(NoteTypes.coreMapped(Family.SENREN, senren))
        assertTrue(NoteTypes.coreMapped(Family.SENREN, listOf("Word", "Reading", "Sentence", "Glossary")))
        assertFalse(NoteTypes.coreMapped(Family.LAPIS, listOf("Expression", "Meaning")))
        assertFalse(NoteTypes.coreMapped(Family.KIKU, listOf("Front", "Back")))
        assertNull(NoteTypes.choose(listOf(Candidate(1, "Kiku", listOf("Front", "Back"), 900), Candidate(2, "Basic", lapis, 50))))
    }

    @Test
    fun theNoteTypeWithMostNotesWins() {
        val choice = NoteTypes.choose(
            listOf(Candidate(10, "Lapis", lapis, 120), Candidate(11, "Senren", senren, 800), Candidate(12, "Kiku", lapis, 3)),
        )!!
        assertEquals("Senren", choice.candidate.name)
        assertEquals(Family.SENREN, choice.family)
    }

    @Test
    fun aTieGoesToTheOlderNoteTypeAndAnUnusedOneIsStillUsed() {
        assertEquals(10L, NoteTypes.choose(listOf(Candidate(11, "Kiku", lapis, 5), Candidate(10, "Lapis", lapis, 5)))!!.candidate.id)
        assertEquals("Lapis", NoteTypes.choose(listOf(Candidate(10, "Lapis", lapis, 0)))!!.candidate.name)
    }

    @Test
    fun lapisFieldsAreFilledInTheNoteTypesOwnOrder() {
        val values = NoteTypes.fill(Family.LAPIS, lapis, content, "<img src=\"lfg_1.webp\">")
        val byName = lapis.zip(values.toList()).toMap()
        assertEquals(lapis.size, values.size)
        assertEquals("不思議", byName["Expression"])
        assertEquals("ふしぎ", byName["ExpressionReading"])
        assertEquals("不思議[ふしぎ]", byName["ExpressionFurigana"])
        assertEquals("森の奥で<b>不思議</b>な光を見たんだ。", byName["Sentence"])
        assertEquals("<ol><li>wonderful; strange</li><li>wonder; mystery</li></ol>", byName["MainDefinition"])
        assertEquals("", byName["Glossary"])
        assertEquals("<img src=\"lfg_1.webp\">", byName["Picture"])
        assertEquals("PPSSPP Gold", byName["MiscInfo"])
        assertEquals("", byName["SentenceAudio"])
        assertEquals("", byName["IsSentenceCard"])
    }

    @Test
    fun senrenGetsItsOwnSentenceMarkup() {
        val byName = senren.zip(NoteTypes.fill(Family.SENREN, senren, content).toList()).toMap()
        assertEquals("不思議", byName["word"])
        assertEquals(
            "<span class=\"group\">森の奥で<span class=\"highlight\">不思議</span>な光を見たんだ。</span>",
            byName["sentence"],
        )
        assertEquals("<ol><li>wonderful; strange</li><li>wonder; mystery</li></ol>", byName["definition"])
        assertEquals("", byName["sentenceFurigana"])
    }

    @Test
    fun theAppsOwnNoteTypeUsesLapisNames() {
        assertTrue(NoteTypes.coreMapped(Family.LAPIS, NoteTypes.OWN_FIELDS))
        val byName = NoteTypes.OWN_FIELDS.zip(NoteTypes.fill(Family.OWN, NoteTypes.OWN_FIELDS, content.copy(senses = listOf("light"))).toList()).toMap()
        assertEquals("light", byName["MainDefinition"])
        assertEquals("不思議", byName["Expression"])
    }

    @Test
    fun aNoteTypeWithOnlyAGlossaryFieldStillGetsTheDefinition() {
        val fields = listOf("word", "reading", "sentence", "glossary")
        val byName = fields.zip(NoteTypes.fill(Family.SENREN, fields, content.copy(senses = listOf("light"))).toList()).toMap()
        assertEquals("light", byName["glossary"])
    }

    @Test
    fun gameTag() {
        assertEquals("Game::PPSSPP_Gold", NoteTypes.gameTag("PPSSPP Gold"))
        assertEquals("Game::a_b", NoteTypes.gameTag(" a::b "))
        assertEquals("Game", NoteTypes.gameTag("  "))
    }
}
