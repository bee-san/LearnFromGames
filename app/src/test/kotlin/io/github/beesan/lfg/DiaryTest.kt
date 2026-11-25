package io.github.beesan.lfg

import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.core.DayPicture
import io.github.beesan.lfg.core.WidgetDay
import io.github.beesan.lfg.data.Diary
import io.github.beesan.lfg.data.DiaryWorker
import io.github.beesan.lfg.data.GameLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DiaryTest {
    private fun line(id: String, screen: String? = "/$id.webp", card: Boolean = false, lookups: Int = 0) = DayPicture.Line(id, screen, card, lookups)

    @Test
    fun aDaysPictureIsACardsThenAMomentsThenTheMostLookedUpThenTheLast() {
        val day = listOf(line("a", lookups = 1), line("b", lookups = 3), line("c", card = true), line("d"), line("e", screen = null, card = true))
        assertEquals("c", DayPicture.line(day, setOf("d"))?.id)
        assertEquals("d", DayPicture.line(day.filter { !it.card }, setOf("d"))?.id)
        assertEquals("b", DayPicture.line(day.filter { !it.card }, emptySet())?.id)
        assertEquals("d", DayPicture.line(listOf(line("a"), line("d"), line("e", screen = null)), emptySet())?.id)
        // No screen is left at all: the diary falls back to the line's own picture.
        assertNull(DayPicture.line(listOf(line("e", screen = null, card = true)), setOf("e")))
    }

    @Test
    fun theWidgetLooksBackAYearThenMonthsThenAWeek() {
        val today = LocalDate.of(2026, 11, 25)
        val month = LocalDate.of(2026, 10, 25)
        val year = LocalDate.of(2025, 11, 25)
        assertEquals(WidgetDay.Pick(year, "A year ago today"), WidgetDay.pick(today, setOf(month, year)))
        assertEquals(WidgetDay.Pick(month, "One month ago today"), WidgetDay.pick(today, setOf(month, today)))
        // A day with an entry wins over one without.
        assertEquals(month, WidgetDay.pick(today, setOf(month, year), written = setOf(month))?.day)
        // Nothing on the date: the nearest day within three days, and the label says so.
        assertEquals(WidgetDay.Pick(LocalDate.of(2026, 10, 27), "About a month ago"), WidgetDay.pick(today, setOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 20))))
        // Still nothing: a day from the past under its date alone, the same one all day.
        val far = setOf(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 9))
        val pick = WidgetDay.pick(today, far)!!
        assertTrue(pick.day in far)
        assertEquals(WidgetDay.date(pick.day), pick.label)
        assertEquals(pick, WidgetDay.pick(today, far))
        // Today is never shown, and a month back from the 31st is the month's last day.
        assertNull(WidgetDay.pick(today, setOf(today)))
        assertEquals("One month ago today", WidgetDay.pick(LocalDate.of(2026, 12, 31), setOf(LocalDate.of(2026, 11, 30)))?.label)
    }

    private fun lines(day: Int, count: Int) = (0 until count).map { i ->
        val at = LocalDate.of(2026, 11, day).atTime(20, 0).toEpochSecond(ZoneOffset.UTC) + i
        GameLine(id = "$day-$i", gameId = "g", gameName = "Quest", lineText = "灯台の明かりが消えた。", timestamp = at.toDouble(), createdAt = at * 1000, lastModified = 0, source = "ocr", charCount = 11)
    }

    @Test
    fun aRecapsAnswerTellsEachDayOfTwentyLinesOrMore() {
        val read = lines(2, 25) + lines(3, 5)
        val answer = """{"arcs": [], "last": "x", "moments": [], "days": [
            {"date": "2 November 2026", "title": "The lighthouse", "summary": "The light went out."},
            {"date": "3 November 2026", "title": "Short", "summary": "Too little."},
            {"date": "not a date", "title": "?", "summary": "?"}]}"""
        assertEquals(listOf(Recapper.Companion.DayText(LocalDate.of(2026, 11, 2), "The lighthouse", "The light went out.")), Recapper.days(answer, read, ZoneOffset.UTC))
        assertTrue(Recapper.days("no json here", read, ZoneOffset.UTC).isEmpty())
        assertTrue(Recapper.prompt("Quest", read, null, "English", ZoneOffset.UTC).contains("\"days\""))
    }

    @Test
    fun aDayIsWrittenOnceItIsOverAndLongEnough() {
        val stamps = (lines(2, 25) + lines(3, 5)).map { it.timestamp }
        val never = { _: LocalDate, _: Long -> false }
        assertTrue(DiaryWorker.due(stamps, stamps.size, LocalDate.of(2026, 11, 4), ZoneOffset.UTC, never))
        // Not while the day is still going, nor for a day of a few lines, nor when it was written since its last line.
        assertFalse(DiaryWorker.due(stamps.take(25), 25, LocalDate.of(2026, 11, 2), ZoneOffset.UTC, never))
        assertFalse(DiaryWorker.due(stamps, 5, LocalDate.of(2026, 11, 4), ZoneOffset.UTC, never))
        assertFalse(DiaryWorker.due(stamps, stamps.size, LocalDate.of(2026, 11, 4), ZoneOffset.UTC) { _, _ -> true })
        // Nothing new, or more than one request's worth: that waits for Recap's own Write.
        assertFalse(DiaryWorker.due(stamps, 0, LocalDate.of(2026, 11, 4), ZoneOffset.UTC, never))
        assertFalse(DiaryWorker.due(stamps, Recapper.CHUNK + 1, LocalDate.of(2026, 11, 4), ZoneOffset.UTC, never))
    }

    @Test
    fun aDaysFiguresReadAsTimeAndCharacters() {
        assertEquals("42 min · 3,210 characters", Diary.stats(42 * 60.0 + 10, 3210))
        assertEquals("1 h 5 min · 12 characters", Diary.stats(65 * 60.0, 12))
    }
}
