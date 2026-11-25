// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import io.github.beesan.lfg.core.Sentence
import kotlinx.serialization.Serializable

/** What a card is made of, before it is poured into some note type's fields. */
@Serializable
data class CardContent(
    val expression: String,
    val reading: String,
    val sentence: String,
    /** The UTF-16 range of the looked-up word in [sentence]. */
    val spanStart: Int,
    val spanEnd: Int,
    /** Each sense as plain text. */
    val senses: List<String>,
    val gameName: String,
    /** Path of the frame the line was read from, if there is one. */
    val picturePath: String? = null,
    /** When the line began to appear on screen, if it was read from a game just now: where its sound starts. */
    val lineStartMs: Long? = null,
    /** Where the word's pitch drops, one per accent the pitch dictionaries give. */
    val pitchPositions: List<Int> = emptyList(),
    /** The word's frequency rank, averaged over the frequency dictionaries, for sorting. */
    val frequencyRank: Int? = null,
    /** Read from an ordinary app, not a game: no sentence audio and no moving picture. */
    val fromApp: Boolean = false,
    /** A kanji mined from a word's headword rather than the word: tagged Kanji, and with no word audio. */
    val kanji: Boolean = false,
)

enum class Family { SENREN, LAPIS, KIKU, OWN }

/**
 * Finding where cards go without asking, after Hachidori's anki-setup.js and
 * the field tables in its anki-templates.js (reviewed there against Kiku
 * 2.1.0, Lapis 1.7.0 and Senren 5.1.0).
 */
object NoteTypes {
    // The family name must lead the model name and end at a word boundary:
    // "Kiku v2" and "Lapis 1.4" match, "Kikuchi" and "My Kiku" do not.
    private val FAMILY = Regex("^(senren|lapis|kiku)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun family(modelName: String): Family? =
        FAMILY.find(modelName.trim())?.let { Family.valueOf(it.groupValues[1].uppercase()) }

    /** The note type the app creates when nothing suitable exists. Lapis's field names, so Hachidori and desktop GameSentenceMiner can fill the same cards. */
    const val OWN_MODEL = "LFG"
    const val OWN_DECK = "LFG"
    val OWN_FIELDS = listOf(
        "Expression", "ExpressionReading", "MainDefinition", "Sentence", "Picture", "SentenceAudio", "Glossary", "MiscInfo",
    )

    private enum class Slot { EXPRESSION, READING, FURIGANA, DEFINITION, GLOSSARY, SENTENCE, PICTURE, AUDIO, WORD_AUDIO, PITCH, FREQUENCY, MISC }

    private val KIKU_LAPIS = mapOf(
        "Expression" to Slot.EXPRESSION, "ExpressionReading" to Slot.READING, "ExpressionFurigana" to Slot.FURIGANA,
        "MainDefinition" to Slot.DEFINITION, "Glossary" to Slot.GLOSSARY, "Sentence" to Slot.SENTENCE,
        "Picture" to Slot.PICTURE, "SentenceAudio" to Slot.AUDIO, "ExpressionAudio" to Slot.WORD_AUDIO,
        "PitchPosition" to Slot.PITCH, "FreqSort" to Slot.FREQUENCY, "MiscInfo" to Slot.MISC,
    )

    // Senren's names are matched without case or punctuation, as Hachidori's fieldKey does.
    private val SENREN = mapOf(
        "word" to Slot.EXPRESSION, "reading" to Slot.READING, "definition" to Slot.DEFINITION,
        "glossary" to Slot.GLOSSARY, "sentence" to Slot.SENTENCE, "picture" to Slot.PICTURE, "sentenceaudio" to Slot.AUDIO,
        "wordaudio" to Slot.WORD_AUDIO, "pitchpositions" to Slot.PITCH, "freqsort" to Slot.FREQUENCY, "miscinfo" to Slot.MISC,
    )

    private fun fieldKey(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private fun slotOf(family: Family, field: String): Slot? =
        if (family == Family.SENREN) SENREN[fieldKey(field)] else KIKU_LAPIS[field]

    /**
     * A note type that only shares a family's name maps fewer than the core a
     * mined card needs: the expression, its reading, the sentence and a
     * definition body.
     */
    fun coreMapped(family: Family, fields: List<String>): Boolean {
        val slots = fields.mapNotNull { slotOf(family, it) }.toSet()
        return Slot.EXPRESSION in slots && Slot.READING in slots && Slot.SENTENCE in slots &&
            (Slot.DEFINITION in slots || Slot.GLOSSARY in slots)
    }

    data class Candidate(val id: Long, val name: String, val fields: List<String>, val noteCount: Int)

    data class Choice(val candidate: Candidate, val family: Family)

    /**
     * The supported note type holding the most notes. Hachidori stops and asks
     * when two tie or none has notes; this app never asks, so a tie goes to the
     * older note type (the lower id) and an unused one is still used.
     */
    fun choose(candidates: List<Candidate>): Choice? =
        candidates.mapNotNull { c ->
            val family = family(c.name) ?: return@mapNotNull null
            if (coreMapped(family, c.fields)) Choice(c, family) else null
        }.sortedWith(compareByDescending<Choice> { it.candidate.noteCount }.thenBy { it.candidate.id }).firstOrNull()

    /** The value of every field of the note type, in its own order. Unmapped fields stay empty. */
    fun fill(
        family: Family, fields: List<String>, content: CardContent, pictureHtml: String = "", wordAudio: String = "",
        /** The line's explanation and what was asked about it, as HTML; empty for none. */
        explained: String = "",
    ): Array<String> {
        val lookupFamily = if (family == Family.OWN) Family.LAPIS else family
        val definition = definitionHtml(content.senses)
        val hasDefinitionField = fields.any { slotOf(lookupFamily, it) == Slot.DEFINITION }
        return fields.map { field ->
            when (slotOf(lookupFamily, field)) {
                Slot.EXPRESSION -> Sentence.escapeHtml(content.expression)
                Slot.READING -> Sentence.escapeHtml(content.reading)
                Slot.FURIGANA -> Sentence.escapeHtml(Sentence.furiganaPlain(content.expression, content.reading))
                Slot.DEFINITION -> definition
                // The full glossary is the same text until stage 3 renders dictionaries properly;
                // it is left empty beside a definition field so the card does not show it twice.
                Slot.GLOSSARY -> if (hasDefinitionField) "" else definition
                Slot.SENTENCE -> if (family == Family.SENREN) {
                    "<span class=\"group\">" + Sentence.mark(
                        content.sentence, content.spanStart, content.spanEnd, "<span class=\"highlight\">", "</span>",
                    ) + "</span>"
                } else {
                    Sentence.mark(content.sentence, content.spanStart, content.spanEnd)
                }
                Slot.PICTURE -> pictureHtml
                // Sound arrives after the card: it is cut when the next line appears.
                Slot.AUDIO -> ""
                Slot.WORD_AUDIO -> wordAudio
                // Yomitan's {pitch-accent-positions}: one number alone, several as a list.
                Slot.PITCH -> when (content.pitchPositions.size) {
                    0 -> ""
                    1 -> content.pitchPositions[0].toString()
                    else -> content.pitchPositions.joinToString("", "<ol>", "</ol>") { "<li>$it</li>" }
                }
                Slot.FREQUENCY -> content.frequencyRank?.toString() ?: ""
                Slot.MISC -> misc(content.gameName, explained)
                null -> ""
            }
        }.toTypedArray()
    }

    /** Between the game's name and the explanation in the field that holds both. */
    const val MISC_BREAK = "<br><br>"

    /** The game's name, and under it the line's explanation and what was asked about it ([explained], HTML) when there is one. */
    fun misc(gameName: String, explained: String): String =
        Sentence.escapeHtml(gameName) + if (explained.isEmpty()) "" else MISC_BREAK + explained

    /** Which field of the note type takes the game's name and the explanation, if it has one. */
    fun miscField(family: Family, fields: List<String>): Int? {
        val lookupFamily = if (family == Family.OWN) Family.LAPIS else family
        return fields.indexOfFirst { slotOf(lookupFamily, it) == Slot.MISC }.takeIf { it >= 0 }
    }

    /** Whether the note type has a field for the word's own sound. */
    fun hasWordAudioField(family: Family, fields: List<String>): Boolean {
        val lookupFamily = if (family == Family.OWN) Family.LAPIS else family
        return fields.any { slotOf(lookupFamily, it) == Slot.WORD_AUDIO }
    }

    /** Which field of the note type takes the sentence's sound, if it has one. */
    fun audioField(family: Family, fields: List<String>): Int? {
        val lookupFamily = if (family == Family.OWN) Family.LAPIS else family
        return fields.indexOfFirst { slotOf(lookupFamily, it) == Slot.AUDIO }.takeIf { it >= 0 }
    }

    fun definitionHtml(senses: List<String>): String = when (senses.size) {
        0 -> ""
        1 -> Sentence.escapeHtml(senses[0])
        else -> senses.joinToString("", "<ol>", "</ol>") { "<li>${Sentence.escapeHtml(it)}</li>" }
    }

    /** `Game::name`, GameSentenceMiner's tag. Anki tags cannot hold spaces. */
    fun gameTag(gameName: String): String {
        val name = gameName.trim().replace(Regex("[\\s\"]+"), "_").replace("::", "_")
        return if (name.isEmpty()) "Game" else "Game::$name"
    }

    const val OWN_FRONT = """<div class="word" lang="ja">{{Expression}}</div>
<div class="sentence" lang="ja">{{Sentence}}</div>"""

    const val OWN_BACK = """<div class="word" lang="ja">{{Expression}}</div>
<div class="reading" lang="ja">{{ExpressionReading}}</div>
<hr id="answer">
<div class="sentence" lang="ja">{{Sentence}}</div>
<div class="definition">{{MainDefinition}}</div>
{{#Glossary}}<div class="definition">{{Glossary}}</div>{{/Glossary}}
{{#SentenceAudio}}<div>{{SentenceAudio}}</div>{{/SentenceAudio}}
<div class="picture">{{Picture}}</div>
<div class="misc">{{MiscInfo}}</div>"""

    const val OWN_CSS = """.card { font-family: "Noto Sans CJK JP", "Noto Sans JP", sans-serif; text-align: center;
  background: #22232c; color: #e8e5eb; font-size: 18px; line-height: 1.6; }
.word { font-size: 44px; font-weight: 650; line-height: 1.2; }
.reading { color: #aaa8b2; }
.sentence b { color: #b1a5ee; }
.definition { text-align: left; display: inline-block; max-width: 34em; }
.picture img { max-width: 100%; max-height: 50vh; }
.misc { color: #aaa8b2; font-size: 12px; text-align: left; display: inline-block; max-width: 44em; }
hr { border: 0; border-top: 1px solid #454653; }"""
}
