// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase

/**
 * What a line means and how it is put together, for someone learning: asked
 * of the model in Settings, with the lines read before it in the same game so
 * it knows who is speaking. An explanation is kept while the app runs, so the
 * bottom screen and the app's own line page show the same one and asking again
 * costs nothing until "Explain again".
 */
class Explainer(private val db: LfgDatabase, private val prefs: () -> Prefs) {
    private val kept = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KEEP
    }

    /** The explanation of the line with this id, if one was asked for since the app started. */
    fun kept(lineId: String): String? = synchronized(kept) { kept[lineId] }

    /** Asks for [line]'s explanation. Throws [OpenRouter.Failure] with a message to show. */
    suspend fun explain(line: GameLine): String {
        val p = prefs()
        val before = if (p.translateContext > 0) db.lines().before(line.gameId, line.timestamp, p.translateContext).reversed() else emptyList()
        val text = OpenRouter.ask(p.openRouterKey, p.model, system(p.translateInto), prompt(line.gameName, before.map { it.lineText }, line.lineText))
        synchronized(kept) { kept[line.id] = text }
        return text
    }

    companion object {
        const val KEEP = 200

        fun system(into: String) =
            "You explain lines of Japanese games to someone learning Japanese, in $into. Answer in plain text, no Markdown, " +
                "in three parts. First the line's meaning in natural $into, on its own line. Then the line taken apart in " +
                "order, one part a line, each as: the Japanese, its reading in hiragana in brackets where it has kanji, a dash, " +
                "and what it means or does there (the particle's job, the verb's dictionary form and how it is conjugated). " +
                "Then, only if there is one, a single short note on a nuance, an idiom, the register or who it implies. " +
                "Be brief: no greeting, no romaji, nothing about lines other than the one asked about."

        /** The line to explain, after the lines before it, which are context and are not explained. */
        fun prompt(game: String, before: List<String>, line: String): String = buildString {
            append("A line from ").append(game.ifBlank { "a Japanese game" }).append('.')
            if (before.isNotEmpty()) {
                append(" The lines just before it, oldest first, are for context only; don't explain them:\n<before>\n")
                before.forEach { append(it.replace('\n', ' ')).append('\n') }
                append("</before>")
            }
            append("\n\nExplain this line:\n<line>\n").append(line).append("\n</line>")
        }
    }
}
