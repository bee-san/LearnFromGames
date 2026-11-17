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
) {
    /** `見た → 見る · past`, or empty when the word was not inflected. */
    val path: String
        get() = if (trace.isEmpty() || matched == expression) "" else
            "$matched → $expression · " + trace.joinToString(" · ") { it.name }

    /** Each sense as one line of plain text, across dictionaries in order. */
    fun senses(): List<String> = meanings.flatMap { GlossaryText.senses(it.glossary) }

    /** The glossaries that are meanings. JMdict also lists an entry's written forms as one, tagged `forms`. */
    val meanings: List<Glossary> get() = glossaries.filter { "forms" !in it.definitionTags.split(' ') }

    val sources: List<String> get() = glossaries.map { it.dictionary }.distinct()
}
