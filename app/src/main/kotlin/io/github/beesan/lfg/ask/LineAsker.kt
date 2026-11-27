// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase

/**
 * The wheel's tiles that ask the model one thing about one line: its nuance,
 * its grammar, who is speaking, or a question the player wrote for the tile
 * (Prompt). The line goes with the lines before it, as a translation's does.
 * An answer is shown and not kept: asked again, it is written again.
 */
class LineAsker(private val db: LfgDatabase, private val prefs: () -> Prefs) {
    /** Asks what [action] asks of [line]; for a Prompt tile, [question] is its question. Throws [OpenRouter.Failure] with a message to show. */
    suspend fun ask(line: GameLine, action: WheelAction, question: String = ""): String {
        val p = prefs()
        // The language the line was read as, which the one being learnt now may not be.
        val language = Language.orJapanese(line.language)
        val task = task(action, p.translateInto, question, language) ?: throw OpenRouter.Failure("That tile asks nothing of a line.")
        val before = if (p.translateContext > 0) db.lines().before(line.gameId, line.timestamp, p.translateContext, line.id).reversed().map { it.lineText } else emptyList()
        return OpenRouter.ask(p.openRouterKey, p.model, system(p.translateInto, task, language), prompt(line.gameName, before, line.lineText, language)).trim()
            .ifBlank { throw OpenRouter.Failure("The answer came back empty. Try again.") }
    }

    companion object {
        fun system(into: String, task: String, language: Language = Language.JAPANESE) =
            "You help someone learning ${language.title} with one line of ${language.aGame}, answering in $into. $task " +
                "Plain text, no Markdown,${if (language.japanese) " no romaji," else ""} no greeting. Be brief, and stay on the line asked about."

        /**
         * What each tile asks, or null for a tile that asks nothing of a line. Readings are hiragana over
         * kanji in Japanese and pinyin in Chinese; in any other language, how each word is said.
         */
        fun task(action: WheelAction, into: String, question: String = "", language: Language = Language.JAPANESE): String? = when (action) {
            WheelAction.NUANCE -> "Say what the line carries between its words: its tone, how polite or blunt it is, the feeling in it, " +
                "and how it would land on a native speaker. Point to the words or endings that carry each. Three to five sentences."
            WheelAction.CULTURE -> "Point out what a learner might miss in the line: references, customs, idioms, set phrases and wordplay. " +
                "One short item each, the ${language.title} first. If there is nothing of the kind, say so in one sentence."
            WheelAction.GRAMMAR -> "List the grammar points in the line, in order, one a line: the ${language.title} as it appears, a dash, the pattern " +
                "in its dictionary form, and what it does here. Leave out plain vocabulary."
            WheelAction.READINGS -> when (language) {
                Language.JAPANESE -> "List every word of the line that has kanji, in order, one a line: the word as written, then its reading " +
                    "in hiragana as it is read in this line, in brackets. Nothing else."
                Language.CHINESE -> "List every word of the line, in order, one a line: the word as written, then its reading " +
                    "in pinyin as it is read in this line, in brackets. Nothing else."
                else -> "List every word of the line, in order, one a line: the word as written, then how it is said " +
                    "in this line, in brackets. Nothing else."
            }
            WheelAction.SIMPLER -> "Write the line again in easier ${language.title}: common words, short sentences, the same meaning and the same speaker's " +
                "voice. Give the easier ${language.title} alone on the first line, then one sentence in $into on what was changed."
            WheelAction.OTHER_WAYS -> "Give three other ways the line could be said in ${language.title}, one a line, each followed by a dash and how it " +
                "would differ: more polite, more casual, said by someone else, stronger or softer."
            WheelAction.SPEAKER -> "Say who is likely speaking, going by the lines before it, and how they speak: dialect, gender, age, " +
                "politeness and character quirks, each with the word or ending in the line that shows it. If it cannot be told, say so."
            WheelAction.PROMPT -> question.trim().takeIf { it.isNotEmpty() }?.let { "Answer this about the line: $it" }
            else -> null
        }

        /** The line asked about, after the lines before it, which are context. */
        fun prompt(game: String, before: List<String>, line: String, language: Language = Language.JAPANESE): String = buildString {
            append("A line from ").append(game.ifBlank { language.aGame }).append('.')
            if (before.isNotEmpty()) {
                append(" The lines just before it, oldest first, are for context only:\n<before>\n")
                before.forEach { append(it.replace('\n', ' ')).append('\n') }
                append("</before>")
            }
            append("\n\nThe line:\n<line>\n").append(line).append("\n</line>")
        }
    }
}
