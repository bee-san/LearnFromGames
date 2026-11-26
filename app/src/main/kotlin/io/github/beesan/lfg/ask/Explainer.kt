// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.data.ExplanationRow
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What a line means and how it is put together, for someone learning, and
 * then a conversation about it: each answer comes with three questions to ask
 * next, and any other can be typed. Asked of the model in Settings, with the
 * lines read before it in the same game so it knows who is speaking; a
 * question is sent with the explanation and everything asked since. All of it
 * is kept with the line ([ExplanationRow]), so it is there after a restart, on
 * the lines' screen over a game and on the app's own line page alike, and on
 * the line's cards ([onChanged]). "Explain again" starts it over.
 */
class Explainer(
    private val db: LfgDatabase,
    private val prefs: () -> Prefs,
    /** A line's conversation was written or added to. */
    private val onChanged: suspend (lineId: String) -> Unit = {},
) {
    /** A question asked about a line, and its answer. */
    @Serializable
    data class Turn(val question: String, val answer: String)

    /** A line's explanation, what was asked after it, and the questions offered next. */
    data class Conversation(val explanation: String, val turns: List<Turn> = emptyList(), val suggestions: List<String> = emptyList()) {
        /** No more can be asked of this line. */
        val full: Boolean get() = turns.size >= MAX_QUESTIONS
    }

    private val _kept = MutableStateFlow<Map<String, Conversation>>(emptyMap())

    /** The conversations in memory, by line: the newest kept, and every one opened or written since the app started. */
    val conversations: StateFlow<Map<String, Conversation>> = _kept

    /** The line's conversation if it is in memory: what a screen that cannot wait shows. */
    fun kept(lineId: String): Conversation? = _kept.value[lineId]

    /** Brings the newest conversations into memory, as the app starts. */
    suspend fun load() {
        val rows = db.explanations().newest(KEEP).associate { it.lineId to conversation(it) }
        // One written while these were read is the newer.
        _kept.update { rows + it }
    }

    /** The line's conversation, from memory or the database; null when it was never explained. */
    suspend fun conversation(lineId: String): Conversation? =
        kept(lineId) ?: db.explanations().of(lineId)?.let(::conversation)?.also { found -> _kept.update { it + (lineId to found) } }

    /** Asks for [line]'s explanation, in place of any it has. Throws [OpenRouter.Failure] with a message to show. */
    suspend fun explain(line: GameLine): Conversation {
        val p = prefs()
        // The language the line was read as, which the one being learnt now may not be.
        val language = Language.orJapanese(line.language)
        val answer = OpenRouter.ask(
            p.openRouterKey, p.model, system(p.translateInto, language), prompt(line.gameName, before(line, p), line.lineText, language),
        )
        val (text, next) = split(answer)
        return save(line.id, Conversation(text, suggestions = next), p.model)
    }

    /** Asks [question] about [line], after its explanation and what was asked before. Throws [OpenRouter.Failure] with a message to show. */
    suspend fun ask(line: GameLine, question: String): Conversation {
        val p = prefs()
        val asked = question.trim().take(QUESTION_LENGTH)
        val so = conversation(line.id) ?: throw OpenRouter.Failure("Explain the line first.")
        if (asked.isEmpty()) return so
        if (so.full) throw OpenRouter.Failure("That is $MAX_QUESTIONS questions on this line. Explain again starts over.")
        val language = Language.orJapanese(line.language)
        val body = OpenRouter.body(
            p.model, followUp(p.translateInto, language), prompt(line.gameName, before(line, p), line.lineText, language), talk = talk(so, asked),
        )
        val (text, next) = split(OpenRouter.answer(OpenRouter.request(p.openRouterKey, body)))
        // "Explain again" may have run meanwhile: the answer belongs to the conversation it was asked in.
        val now = conversation(line.id)?.takeIf { it.explanation == so.explanation } ?: so
        return save(line.id, now.copy(turns = now.turns + Turn(asked, text), suggestions = next), p.model)
    }

    private suspend fun before(line: GameLine, p: Prefs): List<String> =
        if (p.translateContext > 0) db.lines().before(line.gameId, line.timestamp, p.translateContext).reversed().map { it.lineText } else emptyList()

    private suspend fun save(lineId: String, conversation: Conversation, model: String): Conversation {
        db.explanations().put(
            ExplanationRow(
                lineId, conversation.explanation, Json.encodeToString(conversation.turns), conversation.suggestions.joinToString("\n"),
                model, System.currentTimeMillis(),
            ),
        )
        _kept.update { it + (lineId to conversation) }
        onChanged(lineId)
        return conversation
    }

    companion object {
        /** Conversations brought into memory as the app starts. */
        const val KEEP = 300

        /** Questions one line can be asked. */
        const val MAX_QUESTIONS = 12

        /** Questions offered after each answer. */
        const val SUGGESTIONS = 3

        const val QUESTION_LENGTH = 300

        /** The line of an answer that the questions to ask next come after. */
        const val MARKER = "QUESTIONS"

        private val MARKER_LINE = Regex("^[\\s*#_-]*$MARKER[\\s*:_-]*$", RegexOption.IGNORE_CASE)
        private val BULLET = Regex("^\\s*(?:[-*•・]|\\d+[.)])\\s*")

        private fun suggest(into: String, fresh: Boolean) =
            " After that, on a line of its own, write $MARKER, and under it $SUGGESTIONS short questions in $into, one a line with no " +
                "numbering, that the learner might ask next about this line: its grammar, a word in it, its tone or how else it could be said" +
                (if (fresh) "" else ", none of them asked already") + ". Each under eight words."

        /** Each part of the line comes with its reading where the writing does not show it: hiragana over kanji, pinyin in Chinese. */
        fun system(into: String, language: Language = Language.JAPANESE): String {
            val reading = when (language) {
                Language.JAPANESE -> " its reading in hiragana in brackets where it has kanji,"
                Language.CHINESE -> " its reading in pinyin in brackets,"
                else -> ""
            }
            return "You explain lines of ${language.games} to someone learning ${language.title}, in $into. Answer in plain text, no Markdown, " +
                "in three parts. First the line's meaning in natural $into, on its own line. Then the line taken apart in " +
                "order, one part a line, each as: the ${language.title},$reading a dash, " +
                "and what it means or does there (the particle's job, the verb's dictionary form and how it is conjugated). " +
                "Then, only if there is one, a single short note on a nuance, an idiom, the register or who it implies. " +
                "Be brief: no greeting,${if (language.japanese) " no romaji," else ""} nothing about lines other than the one asked about." +
                suggest(into, fresh = true)
        }

        /** The model's instructions for a question asked after the explanation. */
        fun followUp(into: String, language: Language = Language.JAPANESE) =
            "You are helping someone learning ${language.title} with one line of ${language.aGame}, in $into. You have explained the line; " +
                "now answer their question about it in plain text, no Markdown, in two to four sentences, with " +
                (if (language.japanese) "a Japanese example where one helps and no romaji" else "an example in ${language.title} where one helps") +
                ". Stay on this line: its words, its grammar, its tone and who is speaking. If the " +
                "question is about the game and not the line (where to go, what happens next), say in one sentence that Ask on " +
                "the wheel answers questions about the game. No greeting, and do not repeat the question." + suggest(into, fresh = false)

        /** The line to explain, after the lines before it, which are context and are not explained. */
        fun prompt(game: String, before: List<String>, line: String, language: Language = Language.JAPANESE): String = buildString {
            append("A line from ").append(game.ifBlank { language.aGame }).append('.')
            if (before.isNotEmpty()) {
                append(" The lines just before it, oldest first, are for context only; don't explain them:\n<before>\n")
                before.forEach { append(it.replace('\n', ' ')).append('\n') }
                append("</before>")
            }
            append("\n\nExplain this line:\n<line>\n").append(line).append("\n</line>")
        }

        /** What was said after the request to explain: the explanation, each question and its answer, then [question]. */
        fun talk(so: Conversation, question: String): List<String> =
            listOf(so.explanation) + so.turns.flatMap { listOf(it.question, it.answer) } + question

        /**
         * An answer and the questions to ask next, which come under the last line that says [MARKER].
         * An answer without one has no questions.
         */
        fun split(answer: String): Pair<String, List<String>> {
            val lines = answer.trim().lines()
            val at = lines.indexOfLast { MARKER_LINE.matches(it) }
            if (at < 0) return answer.trim() to emptyList()
            val questions = lines.drop(at + 1).map { it.replace(BULLET, "").trim() }.filter { it.isNotEmpty() }.distinct().take(SUGGESTIONS)
            return lines.take(at).joinToString("\n").trim() to questions
        }

        fun conversation(row: ExplanationRow) = Conversation(
            row.explanation,
            try {
                Json.decodeFromString<List<Turn>>(row.turns)
            } catch (_: Exception) {
                emptyList()
            },
            row.suggestions.lines().filter { it.isNotBlank() },
        )

        /** The conversation as a card's field: the explanation, then each question in bold over its answer. */
        fun html(conversation: Conversation, escape: (String) -> String): String = buildString {
            append(escape(conversation.explanation).replace("\n", "<br>"))
            for (turn in conversation.turns) {
                append("<br><br><b>").append(escape(turn.question)).append("</b><br>").append(escape(turn.answer).replace("\n", "<br>"))
            }
        }
    }
}
