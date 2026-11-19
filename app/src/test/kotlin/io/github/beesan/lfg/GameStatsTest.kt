package io.github.beesan.lfg

import io.github.beesan.lfg.core.GameStats
import io.github.beesan.lfg.core.GameStats.CardMade
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.ReadingStats.Stamp
import io.github.beesan.lfg.core.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GameStatsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 17)
    private val base = today.atTime(20, 0).atZone(zone).toEpochSecond().toDouble()

    /** Game a for ten minutes, b for two, a again 23 minutes after its last line, then a after 40 minutes away. */
    private val stamps =
        List(11) { Stamp(base + it * 60, 30, "a") } +
            List(3) { Stamp(base + 660 + it * 60, 30, "b") } +
            List(4) { Stamp(base + 2000 + it * 60, 30, "a") } +
            List(2) { Stamp(base + 2180 + 2400 + it * 60, 30, "a") }

    @Test
    fun eachGamesDaysAddUpToTheTotal() {
        val summary = ReadingStats.summarise(stamps, zone)
        val total = summary.days.single()
        val games = summary.gameDays.values.map { it.single() }
        assertEquals(total.lines, games.sumOf { it.lines })
        assertEquals(total.chars, games.sumOf { it.chars })
        assertEquals(total.timedChars, games.sumOf { it.timedChars })
        assertEquals(total.seconds, games.sumOf { it.seconds }, 1e-9)
        assertEquals(ReadingStats.byDay(stamps, zone), summary.days)
    }

    @Test
    fun aSessionEndsAfterHalfAnHourAwayFromItsGame() {
        val sessions = ReadingStats.summarise(stamps, zone).sessions
        // a's 23 minutes on b and away keep its session; 40 minutes end it.
        assertEquals(listOf("a", "b", "a"), sessions.map { it.gameId })
        val (first, b, last) = sessions
        assertEquals(15, first.lines)
        assertEquals(base, first.start, 1e-9)
        assertEquals(base + 2180, first.end, 1e-9)
        assertEquals(3, b.lines)
        assertEquals(2, last.lines)
        // Sessions only group the lines: their time is the days' time.
        assertEquals(ReadingStats.summarise(stamps, zone).days.single().seconds, sessions.sumOf { it.seconds }, 1e-9)
        assertTrue(GameStats.live(last, last.end + 1799))
        assertFalse(GameStats.live(last, last.end + 1801))
    }

    @Test
    fun cardsCountTowardsTheirGameAndSession() {
        val summary = ReadingStats.summarise(stamps, zone)
        val ms = { s: Double -> (s * 1000).toLong() }
        val cards = listOf(
            CardMade(ms(base + 30), "a"),
            CardMade(ms(base + 2700), "a"), // after a's last line, inside its gap
            CardMade(ms(base + 5100), "a"), // in the last session
            CardMade(ms(base + 700), "b"),
        )
        val bySession = GameStats.sessionCards(summary.sessions, cards)
        assertEquals(listOf(2, 1, 1), summary.sessions.map { bySession[it] })

        val rows = GameStats.rows(summary, cards, StatsPeriod.DAYS_7, today, zone, GameStats.Sort.TIME)
        assertEquals(listOf("a", "b"), rows.map { it.gameId })
        assertEquals(listOf(3, 1), rows.map { it.cards })
        val recent = GameStats.rows(summary, cards, StatsPeriod.DAYS_7, today, zone, GameStats.Sort.RECENT)
        assertEquals(listOf("a", "b"), recent.map { it.gameId })
        // Nothing read in the week before: no rows.
        assertEquals(emptyList<GameStats.Row>(), GameStats.rows(summary, cards, StatsPeriod.DAYS_7, today.plusDays(10), zone, GameStats.Sort.TIME))
    }

    @Test
    fun theSessionsToStepThroughAreTodaysOrTheLastOne() {
        val sessions = ReadingStats.summarise(stamps, zone).sessions
        assertEquals(3, GameStats.recent(sessions, today, zone).size)
        assertEquals(listOf(sessions.last()), GameStats.recent(sessions, today.plusDays(2), zone))
    }

    @Test
    fun thePanelLine() {
        val s = ReadingStats.Session("a", 0.0, 3000.0, 100, 5310, 5310, 42 * 60.0 + 10)
        assertEquals("ドラクエ · 42 min · 5,310 characters · 7,555 an hour · 6 cards", GameStats.strip("ドラクエ", s, 6))
        assertEquals("X · 0 min · 0 characters · 1 card", GameStats.strip("X", s.copy(chars = 0, timedChars = 0, seconds = 0.0), 1))
    }
}
