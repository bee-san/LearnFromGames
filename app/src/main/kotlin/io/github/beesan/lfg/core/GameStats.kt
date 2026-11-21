// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.time.LocalDate
import java.time.ZoneId

/**
 * Stats for each game, and for a session, out of [ReadingStats.summarise].
 * A game's time is counted as the totals count it, so the games add up to them.
 */
object GameStats {
    /** One game in the Games list, over the period picked. [last] is its newest line, in seconds since 1970. */
    data class Row(val gameId: String, val totals: StatsPeriod.Totals, val cards: Int, val last: Double)

    /** When a card was made, in milliseconds, and the game of the line it was made from. */
    data class CardMade(val madeAt: Long, val gameId: String)

    enum class Sort { TIME, RECENT }

    /** The games read in [period], with their cards made in it: most time, or most recent, first. */
    fun rows(
        summary: ReadingStats.Summary,
        cards: List<CardMade>,
        period: StatsPeriod,
        today: LocalDate,
        zone: ZoneId,
        sort: Sort,
    ): List<Row> {
        val last = HashMap<String, Double>()
        summary.sessions.forEach { s -> if (s.end > (last[s.gameId] ?: 0.0)) last[s.gameId] = s.end }
        val rows = summary.gameDays.mapNotNull { (gameId, days) ->
            val view = period.view(days, today)
            if (view.totals.lines == 0) return@mapNotNull null
            val made = cards.count { it.gameId == gameId && ReadingStats.dayOf(it.madeAt / 1000.0, zone) in view.start..today }
            Row(gameId, view.totals, made, last[gameId] ?: 0.0)
        }
        return when (sort) {
            Sort.TIME -> rows.sortedWith(compareByDescending<Row> { it.totals.seconds }.thenByDescending { it.totals.chars })
            Sort.RECENT -> rows.sortedByDescending { it.last }
        }
    }

    /**
     * The cards made in each session: from its first line until the gap that
     * ends it has passed, or the game's next session starts.
     */
    fun sessionCards(sessions: List<ReadingStats.Session>, cards: List<CardMade>): Map<ReadingStats.Session, Int> {
        val byGame = cards.groupBy { it.gameId }
        val out = HashMap<ReadingStats.Session, Int>()
        sessions.groupBy { it.gameId }.forEach { (gameId, list) ->
            val made = byGame[gameId].orEmpty()
            val ordered = list.sortedBy { it.start }
            ordered.forEachIndexed { i, s ->
                val from = s.start * 1000
                val until = minOf(s.end + ReadingStats.SESSION_GAP_SECONDS, ordered.getOrNull(i + 1)?.start ?: Double.MAX_VALUE) * 1000
                out[s] = made.count { it.madeAt >= from && it.madeAt < until }
            }
        }
        return out
    }

    /** A session is still going while its game's next line could still join it. */
    fun live(session: ReadingStats.Session, now: Double): Boolean = now - session.end <= ReadingStats.SESSION_GAP_SECONDS

    /** The sessions the Stats screen steps through: today's, oldest first, or else the last one there was. */
    fun recent(sessions: List<ReadingStats.Session>, today: LocalDate, zone: ZoneId): List<ReadingStats.Session> {
        val todays = sessions.filter { ReadingStats.dayOf(it.start, zone) == today }
        return todays.ifEmpty { listOfNotNull(sessions.maxByOrNull { it.end }) }
    }

    /** The line the second screen's panel shows for a session still going: "Game · 42 min · 5,310 characters · 7,590 an hour · 6 cards". */
    fun strip(title: String, session: ReadingStats.Session, cards: Int): String {
        val minutes = (session.seconds / 60).toLong()
        val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        val speed = session.charsPerHour.takeIf { it > 0 }?.let { "%,d an hour".format(it) }
        return listOfNotNull(title.ifEmpty { null }, time, "%,d characters".format(session.chars), speed, if (cards == 1) "1 card" else "$cards cards").joinToString(" · ")
    }
}
