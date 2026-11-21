// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase
import io.github.beesan.lfg.data.QuestionDao
import io.github.beesan.lfg.data.QuestionRow
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A question about the game being played ("how do I get this item?"),
 * answered by the model in Settings with OpenRouter's web search turned on
 * for this request alone, so it can look at walkthroughs and wikis. It is
 * told the game, the last [LINES] lines read (where the player is, and how
 * far the story may go), the last few questions, and, when asked to, the
 * screen. Each game keeps its newest [QuestionDao.KEEP] questions.
 */
class Asker(
    private val db: LfgDatabase,
    private val prefs: () -> Prefs,
    /** A picture file as a `data:` URL, small enough to send; null when it can't be read. */
    private val encode: (String) -> String? = ::dataUrl,
) {
    /** Asks [question] about [gameId], keeps the answer, and gives it back. Throws [OpenRouter.Failure] with a message to show. */
    suspend fun ask(gameId: String, gameName: String, question: String, withScreen: Boolean): QuestionRow {
        val p = prefs()
        if (!p.ask) throw OpenRouter.Failure("Turn on Ask in Settings › Translation.")
        val lines = db.lines().before(gameId, Double.MAX_VALUE, LINES).reversed()
        val earlier = db.questions().recent(gameId, EARLIER).reversed()
        val image = if (withScreen) screen(lines)?.let(encode) else null
        if (withScreen && image == null) throw OpenRouter.Failure("There is no picture of the screen to send. Read a line first.")
        val prompt = prompt(gameName, lines.map { it.lineText }, earlier, question, image != null)
        val response = OpenRouter.request(p.openRouterKey, OpenRouter.body(p.model, system(p.translateInto), prompt, webSearch = true, image = image))
        val row = QuestionRow(
            gameId = gameId, question = question.trim(), answer = OpenRouter.answer(response),
            sources = OpenRouter.sources(response).joinToString("\n") { "${it.url}\t${it.title}" },
            withScreen = image != null, model = p.model, createdAt = System.currentTimeMillis(),
        )
        db.questions().insert(row)
        db.questions().trim(gameId)
        return row
    }

    companion object {
        /** Lines read that go with a question: enough to place the player, not the whole story. */
        const val LINES = 30
        /** Earlier questions that go with one, so "and after that?" makes sense. */
        const val EARLIER = 3

        fun system(into: String) =
            "You help someone who is playing a Japanese game, and learning Japanese, with a question about the game. " +
                "Search the web for walkthroughs, guides and wikis when that helps. " +
                "Answer only what was asked, in $into: short and practical, a few sentences or numbered steps they can follow. " +
                "Don't spoil the story past the lines they have read: no later events, twists, deaths or reveals, even when a " +
                "page mentions them. If the answer needs one, say that it would spoil something and leave it out. " +
                "If you aren't sure, or the pages disagree or may be about another version of the game, say so plainly " +
                "instead of guessing. No greeting and no summary of the question."

        /** The question, after the game, the last lines read, and the questions asked before it. */
        fun prompt(game: String, lines: List<String>, earlier: List<QuestionRow>, question: String, withScreen: Boolean): String = buildString {
            append("I’m playing ").append(game.ifBlank { "a Japanese game" }).append('.')
            if (lines.isNotEmpty()) {
                append(" The last lines I read, oldest first, show where I am; the story must not go past them:\n<lines>\n")
                lines.forEach { append(it.replace('\n', ' ')).append('\n') }
                append("</lines>")
            }
            if (earlier.isNotEmpty()) {
                append("\n\nWhat I asked you before, oldest first:\n")
                earlier.forEach { q ->
                    append("<asked>\n").append(q.question).append("\n</asked>\n<answered>\n").append(q.answer).append("\n</answered>\n")
                }
            }
            if (withScreen) append("\n\nThe picture is my screen now.")
            append("\n\nMy question:\n<question>\n").append(question.trim()).append("\n</question>")
        }

        /** The newest of [lines] (oldest first) with a picture still on the device: the whole screen, else the line's crop. */
        fun screen(lines: List<GameLine>): String? = lines.asReversed().firstNotNullOfOrNull { line ->
            line.screenshotPath?.takeIf { File(it).isFile } ?: line.cropPath?.takeIf { File(it).isFile }
        }

        /** Widest picture sent: enough to read a menu, small enough to send quickly. */
        private const val WIDTH = 1280

        /** [path] as a JPEG `data:` URL at most [WIDTH] wide. */
        fun dataUrl(path: String): String? {
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val small = if (bitmap.width > WIDTH) Bitmap.createScaledBitmap(bitmap, WIDTH, bitmap.height * WIDTH / bitmap.width, true) else bitmap
            val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        /** [QuestionRow.sources] as pages. */
        fun sources(row: QuestionRow): List<OpenRouter.Source> = row.sources.lines().filter { it.isNotBlank() }.map {
            val (url, title) = (it.split('\t', limit = 2) + "").let { parts -> parts[0] to parts[1] }
            OpenRouter.Source(url, title)
        }
    }
}
