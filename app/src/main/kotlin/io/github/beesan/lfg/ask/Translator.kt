// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase

/**
 * A line's translation, by the model Settings picked, with the lines read
 * before it in the same game as context (GameSentenceMiner sends about ten).
 * A translation is kept on its line, so asking again costs nothing until
 * "Translate again".
 */
class Translator(private val db: LfgDatabase, private val prefs: () -> Prefs) {
    suspend fun translate(line: GameLine): String {
        val p = prefs()
        val before = if (p.translateContext > 0) db.lines().before(line.gameId, line.timestamp, p.translateContext).reversed() else emptyList()
        val text = OpenRouter.ask(p.openRouterKey, p.model, system(p.translateInto), prompt(line.gameName, before.map { it.lineText }, line.lineText))
        db.lines().setTranslation(line.id, text)
        return text
    }

    companion object {
        fun system(into: String) =
            "You translate lines of Japanese games into $into for someone learning Japanese. Keep the tone and who is " +
                "speaking. Answer with the translation alone: no notes, no quotation marks, no romaji."

        /** The line to translate, after the lines before it, which are context and are not translated. */
        fun prompt(game: String, before: List<String>, line: String): String = buildString {
            append("A line from ").append(game.ifBlank { "a Japanese game" }).append('.')
            if (before.isNotEmpty()) {
                append(" The lines just before it, oldest first, are for context only; don't translate them:\n<before>\n")
                before.forEach { append(it.replace('\n', ' ')).append('\n') }
                append("</before>")
            }
            append("\n\nTranslate this line:\n<line>\n").append(line).append("\n</line>")
        }
    }
}
