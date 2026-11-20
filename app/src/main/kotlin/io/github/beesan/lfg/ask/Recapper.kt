// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.ask.OpenRouter.text
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase
import io.github.beesan.lfg.data.RecapRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A part of the story: what happens in it, and the days it covers. */
@Serializable
data class Arc(val title: String = "", val from: String = "", val to: String = "", val summary: String = "")

/** A line to look back at, with what is happening at it, and its picture when one is kept. */
@Serializable
data class Moment(val lineId: String = "", val text: String = "", val picture: String? = null, val caption: String = "")

/** A game's story so far in its arcs, the last session, and the moments to look back at. */
@Serializable
data class Recap(val arcs: List<Arc> = emptyList(), val last: String = "", val moments: List<Moment> = emptyList())

/**
 * "The story so far" for a game, after Telly's "Previously on" (bee-san/Telly,
 * src-tauri/src/ask.rs): the story in at most five arcs, the last session,
 * and a few moments, from only what has been read, so nothing ahead is
 * spoiled. Each recap carries on from the one kept before it, so only the
 * lines read since are sent; a long history goes in pieces of [CHUNK] lines,
 * oldest first, each carrying on from the last, so no line is left out.
 */
class Recapper(private val db: LfgDatabase, private val prefs: () -> Prefs, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** The recap of [gameId] up to its newest line: the kept one when nothing has been read since. */
    suspend fun write(gameId: String, gameName: String): RecapRow {
        val p = prefs()
        var row = db.recaps().latest(gameId)
        val all = db.lines().ofGame(gameId)
        val start = row?.let { kept -> all.indexOfFirst { it.id == kept.lastLineId } + 1 } ?: 0
        val fresh = all.drop(start.coerceAtLeast(0))
        if (fresh.isEmpty()) return row ?: throw OpenRouter.Failure("Nothing has been read in this game yet.")
        for (piece in fresh.chunked(CHUNK)) {
            val earlier = row?.let { Json.decodeFromString<Recap>(it.json) }
            val answer = OpenRouter.ask(p.openRouterKey, p.model, SYSTEM, prompt(gameName, piece, earlier, p.translateInto, zone))
            val recap = parse(answer, piece, earlier)
            row = RecapRow(
                gameId = gameId, json = Json.encodeToString(recap), lastLineId = piece.last().id,
                lineCount = (row?.lineCount ?: 0) + piece.size, model = p.model, createdAt = System.currentTimeMillis(),
            )
            db.recaps().insert(row)
        }
        return row!!
    }

    companion object {
        /** Lines sent in one request: well inside gpt-oss's context, with room for the earlier recap and the answer. */
        const val CHUNK = 2000
        private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

        /** The prompt for the lines read since [earlier]: each day's lines, numbered across all of them. */
        fun prompt(game: String, lines: List<GameLine>, earlier: Recap?, into: String, zone: ZoneId): String = buildString {
            append("I’m playing ").append(game.ifBlank { "a game" }).append(", a Japanese game. Here is its Japanese text as I read it, ")
            append("by day, each line numbered:\n")
            var day = ""
            lines.forEachIndexed { i, line ->
                val d = ReadingStats.dayOf(line.timestamp, zone).format(DAY)
                if (d != day) {
                    if (day.isNotEmpty()) append("</day>\n")
                    append("<day date=\"").append(d).append("\">\n")
                    day = d
                }
                append('[').append(i + 1).append("] ").append(line.lineText.replace('\n', ' ')).append('\n')
            }
            append("</day>\n")
            if (earlier != null) {
                append("\nThe story before these lines, as you told it to me then, with the moments you chose:\n<earlier>\n")
                append(Json.encodeToString(earlier.copy(moments = earlier.moments.map { it.copy(lineId = "", picture = null) })))
                append("\n</earlier>\nCarry on from the earlier story: keep its arcs, run the latest one on or add a new one, and fold the ")
                append("oldest together when there would be more than five.")
            }
            append("\n\nWrite in ").append(into).append(", only from what is above: never guess at what comes next. Answer with JSON alone:\n")
            append("{\"arcs\": [{\"title\": \"a few words\", \"from\": \"the first day's date\", \"to\": \"the last day's date\", ")
            append("\"summary\": \"two or three sentences\"}], ")
            append("\"last\": \"what happened on the last day above, three or four sentences, ending where I stopped\", ")
            append("\"moments\": [{\"line\": the number of a line above, \"caption\": \"what is happening at it, a few words\"}]}\n")
            append("Arcs are the whole story so far, oldest first, at most five. Choose three or four moments that matter to the story")
            if (earlier != null) append(": from the lines above, or keep an earlier one by giving its place in the earlier list as \"earlier\" instead of \"line\"")
            append(", with the last one near where I stopped.")
        }

        /** The recap in [answer], its moments pointing at [lines] (by number) or at [earlier]'s moments (by place). */
        fun parse(answer: String, lines: List<GameLine>, earlier: Recap?): Recap {
            val json = OpenRouter.jsonIn(answer)
            val arcs = (json["arcs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                Arc(it.text("title"), it.text("from"), it.text("to"), it.text("summary"))
            }
            val moments = (json["moments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { m ->
                val caption = m.text("caption")
                val number = (m["line"] as? JsonPrimitive)?.content?.toIntOrNull()
                val back = (m["earlier"] as? JsonPrimitive)?.content?.toIntOrNull()
                when {
                    number != null -> lines.getOrNull(number - 1)?.let { Moment(it.id, it.lineText, picture(it), caption) }
                    back != null -> earlier?.moments?.getOrNull(back)?.let { kept -> kept.copy(caption = caption.ifBlank { kept.caption }) }
                    else -> null
                }
            }
            if (arcs.isEmpty() && json.text("last").isBlank()) throw OpenRouter.Failure("The answer wasn’t in the expected form. Try again.")
            return Recap(arcs, json.text("last"), moments)
        }

        /**
         * The screen while it is kept, which then stays for good (data/PictureKeeper.kt), so a
         * moment is the scene as it was; else the line's own picture, which is never cleared.
         */
        private fun picture(line: GameLine): String? =
            line.screenshotPath?.takeIf { File(it).isFile } ?: line.cropPath?.takeIf { File(it).isFile }

        const val SYSTEM =
            "You write recaps of Japanese games for someone learning Japanese who is coming back to one: the story so far, " +
                "like a TV recap, from only what they have read. You answer with JSON alone."
    }
}
