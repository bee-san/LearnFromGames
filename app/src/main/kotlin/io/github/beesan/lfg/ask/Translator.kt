// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A line's translation, by the model Settings picked, with the lines read
 * before it in the same game as context (GameSentenceMiner sends about ten).
 * A translation is kept on its line, so asking again costs nothing until
 * "Translate again".
 *
 * With no internet the line is translated on the device instead, by [offline] where there is
 * one and its models are here, and kept the same way. Each translation the model does make
 * is a sign there is internet, so the first between two languages fetches their models.
 */
class Translator(private val db: LfgDatabase, private val offline: OfflineTranslator? = null, private val prefs: () -> Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The languages whose models have been asked for since the app started, as `ja>English`. */
    private val prepared: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** Throws [OpenRouter.Failure] with a message to show. */
    suspend fun translate(line: GameLine): String {
        val p = prefs()
        // The language the line was read as, which the one being learnt now may not be.
        val language = Language.orJapanese(line.language)
        val before = if (p.translateContext > 0) db.lines().before(line.gameId, line.timestamp, p.translateContext).reversed() else emptyList()
        val text = try {
            OpenRouter.ask(
                p.openRouterKey, p.model, system(p.translateInto, language), prompt(line.gameName, before.map { it.lineText }, line.lineText, language),
            ).also { prepareOffline(language, p) }
        } catch (e: OpenRouter.Failure) {
            // Only for want of internet: a refused key or an empty account is said as it is.
            // It is marked as Google's, which its terms ask for, and which says it is not the model Settings picked.
            (if (e.offline) offline?.translate(line.lineText, language, p.translateInto) else null)?.let { "$it $OFFLINE_MARK" } ?: throw e
        }
        db.lines().setTranslation(line.id, text)
        return text
    }

    /**
     * The language being learnt has its models for translating with no internet fetched now, while there is some:
     * asked as the app starts and when the language or the key changes, so the first line read offline can be translated.
     */
    fun prepareOffline() = prefs().let { p -> if (p.aiReady && p.language.isNotEmpty()) prepareOffline(p.learning, p) }

    /** Fetches the models for translating [language] with no internet, behind the reading, once for each two languages. */
    private fun prepareOffline(language: Language, p: Prefs) {
        val offline = offline ?: return
        if (!prepared.add("${language.tag}>${p.translateInto}")) return
        scope.launch { offline.prepare(language, p.translateInto, p.wifiOnly) }
    }

    companion object {
        /** After a translation made on the device. */
        const val OFFLINE_MARK = "(Google Translate, offline)"

        fun system(into: String, language: Language = Language.JAPANESE) =
            "You translate lines of ${language.games} into $into for someone learning ${language.title}. Keep the tone and who is " +
                "speaking. Answer with the translation alone: no notes, no quotation marks${if (language.japanese) ", no romaji" else ""}."

        /** The line to translate, after the lines before it, which are context and are not translated. */
        fun prompt(game: String, before: List<String>, line: String, language: Language = Language.JAPANESE): String = buildString {
            append("A line from ").append(game.ifBlank { language.aGame }).append('.')
            if (before.isNotEmpty()) {
                append(" The lines just before it, oldest first, are for context only; don't translate them:\n<before>\n")
                before.forEach { append(it.replace('\n', ' ')).append('\n') }
                append("</before>")
            }
            append("\n\nTranslate this line:\n<line>\n").append(line).append("\n</line>")
        }
    }
}

/**
 * How every prompt here names a game in the language being learnt: "a Japanese
 * game" as it always was, else "a game in Spanish", since the game may be from
 * anywhere and "a English game" would not read.
 */
internal val Language.aGame: String get() = if (japanese) "a Japanese game" else "a game in $title"

/** [aGame] for more than one: "Japanese games", "games in Spanish". */
internal val Language.games: String get() = if (japanese) "Japanese games" else "games in $title"
