// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val DictJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ImportOutcome(
    val success: Boolean = false,
    val title: String = "",
    val terms: Long = 0,
    val frequencies: Long = 0,
    val pitches: Long = 0,
    val kanji: Long = 0,
    val media: Long = 0,
    val error: String = "",
)

@Serializable
data class LookupResponse(val results: List<Entry> = emptyList())

@Serializable
data class Transform(val name: String = "", val description: String = "")

@Serializable
data class Glossary(
    val dictionary: String = "",
    /** Raw Yomitan glossary JSON; [GlossaryText] flattens it. */
    val glossary: String = "",
    val definitionTags: String = "",
    val termTags: String = "",
)

@Serializable
data class FrequencyValue(val value: Int = 0, val display: String = "")

/** One frequency dictionary's figures for an entry. */
@Serializable
data class Frequency(val dictionary: String = "", val values: List<FrequencyValue> = emptyList())

/** One pitch accent: the mora after which the pitch drops (0 for none), or a pattern of H and L. */
@Serializable
data class PitchValue(val position: Int = 0, val pattern: String = "")

/** One pitch dictionary's accents for an entry. */
@Serializable
data class Pitch(val dictionary: String = "", val values: List<PitchValue> = emptyList())

/** One dictionary entry found from a character of a line. */
@Serializable
data class Entry(
    /** The text of the line that matched, as it appears there (`見た`). */
    val matched: String = "",
    val deinflected: String = "",
    val trace: List<Transform> = emptyList(),
    /** The dictionary form (`見る`). */
    val expression: String = "",
    val reading: String = "",
    val rules: String = "",
    val score: Int = 0,
    val glossaries: List<Glossary> = emptyList(),
    val frequencies: List<Frequency> = emptyList(),
    val pitches: List<Pitch> = emptyList(),
) {
    /** `見た → 見る · past`, or empty when the word was not inflected. */
    val path: String
        get() = if (trace.isEmpty() || matched == expression) "" else
            "$matched → $expression · " + trace.joinToString(" · ") { it.name }

    /** Each sense as one line of plain text, across dictionaries in order. */
    fun senses(): List<String> = meanings.flatMap { GlossaryText.senses(it.glossary) }

    /**
     * The glossaries that are meanings. JMdict also lists an entry's written
     * forms as one, tagged `forms`; the user's own notes are not meanings either.
     */
    val meanings: List<Glossary> get() = glossaries.filter { "forms" !in it.definitionTags.split(' ') && it.dictionary != MY_NOTES }

    val sources: List<String> get() = glossaries.map { it.dictionary }.distinct()

    companion object {
        /** The dictionary a word's notes show as, first in its entry. */
        const val MY_NOTES = "My notes"
    }
}
