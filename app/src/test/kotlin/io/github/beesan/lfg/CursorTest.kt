package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.CursorPos
import io.github.beesan.lfg.core.KeyRepeat
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.ReadingStats.Gap
import io.github.beesan.lfg.core.ReadingStats.Stamp
import io.github.beesan.lfg.core.Segmenter
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.core.TextCursor
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Vocr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class CursorTest {
    private val words = listOf("昨日", "の", "夜", "森", "奥", "で", "不思議", "な", "光", "を", "見た", "ん", "だ", "でも", "近づいたら", "消えて", "しまった", "ユイ")

    /** A stand-in for the dictionary: the longest listed word at the start of the text. */
    private val match: suspend (String) -> Int = { text -> words.filter { text.startsWith(it) }.maxOfOrNull { it.length } ?: 0 }

    private fun line(text: String, top: Int, left: Int = 100) = TextLine(
        text, Box(left, top, left + text.length * 50, top + 50),
        text.indices.map { CharBox(it, it + 1, Box(left + it * 50, top, left + it * 50 + 50, top + 50)) },
    )

    private fun cursor(vararg lines: TextLine) = TextCursor(lines.toList(), lines.map { runBlocking { Segmenter.wordStarts(it.text, match) } })

    @Test
    fun aLineIsSplitWhereTheDictionarysLongestWordsEnd() = runBlocking {
        //                       0 1 2 3 4 5 6 7 8 9 ...
        val starts = Segmenter.wordStarts("昨日の夜、森の奥で不思議な光を見たんだ。", match)
        assertEquals(listOf(0, 2, 3, 5, 6, 7, 8, 9, 12, 13, 14, 15, 17, 18), starts)
    }

    @Test
    fun punctuationIsNeverAWordAndUnknownCharactersAreOneEach() = runBlocking {
        assertEquals(listOf(1, 2, 4), Segmenter.wordStarts("「謎々、光」", match))
        assertEquals(emptyList<Int>(), Segmenter.wordStarts("……。", match))
    }

    @Test
    fun theCursorStartsOnTheDialogueNotTheSpeakersName() {
        val c = cursor(line("ユイ", 0), line("昨日の夜、森の奥で", 100))
        assertEquals(CursorPos(1, 0), c.first())
        assertNull(cursor(line("……", 0)).first())
    }

    @Test
    fun wordStepsStopAtTheEndsOfTheLine() {
        val c = cursor(line("昨日の夜、森", 0))
        var pos = c.first()!!
        pos = c.nextWord(pos); assertEquals(2, pos.index)
        pos = c.nextWord(pos); assertEquals(3, pos.index)
        pos = c.nextWord(pos); assertEquals(5, pos.index)
        assertEquals(pos, c.nextWord(pos))
        pos = c.previousWord(pos); assertEquals(3, pos.index)
        assertEquals(CursorPos(0, 0), c.previousWord(c.previousWord(c.previousWord(pos))))
    }

    @Test
    fun characterStepsSkipPunctuationAndStopAtTheEnds() {
        val c = cursor(line("夜、森。", 0))
        assertEquals(CursorPos(0, 2), c.nextCharacter(CursorPos(0, 0)))
        assertEquals(CursorPos(0, 2), c.nextCharacter(CursorPos(0, 2)))
        assertEquals(CursorPos(0, 0), c.previousCharacter(CursorPos(0, 2)))
        assertEquals(CursorPos(0, 0), c.previousCharacter(CursorPos(0, 0)))
    }

    @Test
    fun lineStepsKeepTheColumn() {
        // "でも、近づいたら消えてしまった" starts: 0, 3, 8, 11. "昨日の夜、森の奥で" starts: 0, 2, 3, 5, 6, 7, 8.
        val c = cursor(line("昨日の夜、森の奥で", 0), line("……", 100), line("でも、近づいたら消えてしまった", 200))
        // From 奥 (index 7, x centre 475) down, past the line with no words, to the word nearest that column: 近づいたら at 3 (centre 275) or 消えて at 8 (525).
        assertEquals(CursorPos(2, 8), c.lineDown(CursorPos(0, 7)))
        assertEquals(CursorPos(0, 3), c.lineUp(CursorPos(2, 3)))
        assertEquals(CursorPos(0, 0), c.lineUp(CursorPos(0, 0)))
        assertEquals(CursorPos(2, 0), c.lineDown(CursorPos(2, 0)))
    }

    @Test
    fun upAndDownMoveBetweenTheRowsOfAWrappedSentence() {
        // One sentence over two rows, as Blocks.group gives it, under a speaker's name.
        val name = line("ユイ", 0)
        val sentence = io.github.beesan.lfg.core.Blocks.group(listOf(line("昨日の夜、森の奥で", 100), line("不思議な光を見た", 190))).single()
        val c = cursor(name, sentence)
        // The longest line is where the cursor starts.
        assertEquals(CursorPos(1, 0), c.first())
        // 森 is the sixth character of the first row; straight below it in the second row is を (text index 14).
        assertEquals(CursorPos(1, 14), c.lineDown(CursorPos(1, 5)))
        assertEquals(CursorPos(1, 14), c.lineDown(CursorPos(1, 14)))
        assertEquals(CursorPos(1, 5), c.lineUp(CursorPos(1, 14)))
        // Up from the first row leaves the sentence for the name.
        assertEquals(CursorPos(0, 0), c.lineUp(CursorPos(1, 0)))
        // Word steps run on through the line break.
        assertEquals(CursorPos(1, 9), c.nextWord(CursorPos(1, 8)))
    }

    @Test
    fun heldButtonsRepeatOnRubisTimings() {
        val r = KeyRepeat()
        assertTrue(r.press(1, 0))
        assertFalse(r.press(1, 200))
        assertTrue(r.press(1, 350))
        assertFalse(r.press(1, 400))
        assertTrue(r.press(1, 450))
        assertTrue(r.press(2, 460)) // another direction steps at once
        r.release()
        assertTrue(r.press(2, 470))
    }

    // ── reading time, after GameSentenceMiner's stats_util ───────────────────

    @Test
    fun aGapIsCappedAtTwoAndAHalfTimesTheSessionsPace() {
        // 20 characters every 10 s is 2 chars/s; a 20-character line may then take 25 s at most.
        val steady = List(6) { Gap(10.0, 20) }
        assertEquals(2.0, ReadingStats.medianCharsPerSecond(steady), 1e-9)
        assertEquals(25.0, ReadingStats.capSeconds(20, 2.0), 1e-9)
        assertEquals(60.0 + 25.0, ReadingStats.readingSeconds(steady + Gap(240.0, 20)), 1e-9)
    }

    @Test
    fun noGapCountsForMoreThanFiveMinutesAndShortLinesHaveAFloor() {
        assertEquals(300.0, ReadingStats.capSeconds(5000, 2.0), 1e-9)
        assertEquals(2.0, ReadingStats.capSeconds(1, 2.0), 1e-9)
        assertEquals(0.0, ReadingStats.capSeconds(0, 2.0), 1e-9)
        // Before a pace is known: three seconds a character.
        assertEquals(30.0, ReadingStats.capSeconds(10, 0.0), 1e-9)
    }

    @Test
    fun tinyLinesAndBurstsDoNotTeachThePace() {
        assertEquals(0.0, ReadingStats.medianCharsPerSecond(listOf(Gap(10.0, 4), Gap(0.5, 30), Gap(400.0, 30))), 1e-9)
    }

    @Test
    fun theDayTurnsOverAtFour() {
        val zone = ZoneId.of("UTC")
        fun at(hour: Int) = LocalDate.of(2026, 11, 17).atTime(hour, 30).atZone(zone).toEpochSecond().toDouble()
        assertEquals(LocalDate.of(2026, 11, 16), ReadingStats.dayOf(at(3), zone))
        assertEquals(LocalDate.of(2026, 11, 17), ReadingStats.dayOf(at(4), zone))
    }

    @Test
    fun totalsPerDay() {
        val zone = ZoneId.of("UTC")
        val base = LocalDate.of(2026, 11, 17).atTime(21, 0).atZone(zone).toEpochSecond().toDouble()
        val stamps = List(6) { Stamp(base + it * 10, 20, "a") } + List(2) { Stamp(base + 86_400 + it * 10, 30, "b") }
        val days = ReadingStats.byDay(stamps, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17)), days.map { it.day })
        assertEquals(120, days[1].chars)
        assertEquals(6, days[1].lines)
        // Five 10 s gaps, and the last line credited at the session's pace: 20 chars at 2 chars/s.
        assertEquals(60.0, days[1].seconds, 1e-9)
        assertEquals(7200, days[1].charsPerHour)
    }

    @Test
    fun aPeriodAddsUpItsDaysAndTheSameLengthBefore() {
        // 17 November 2026 is a Tuesday, so the 30 days start on Monday 19 October.
        val today = LocalDate.of(2026, 11, 17)
        fun day(back: Long, chars: Int) = ReadingStats.DayTotals(today.minusDays(back), 10, chars, chars / 5.0)
        val days = listOf(day(0, 1000), day(3, 500), day(35, 750), day(90, 9))

        val month = StatsPeriod.DAYS_30.view(days, today, Locale.UK)
        assertEquals(30, month.bars.size)
        assertEquals(StatsPeriod.Totals(1500, 20, 300.0), month.totals)
        assertEquals(StatsPeriod.Totals(750, 10, 150.0), month.before)
        assertEquals(2, month.daysRead)
        assertEquals(1000, month.bars.last().chars)
        assertEquals("19 October – 17 November 2026", month.range)
        assertEquals(listOf("19 Oct", ""), month.bars.take(2).map { it.axis })
        assertEquals("Up 100% on the 30 days before", StatsPeriod.change(1500.0, 750.0, month.beforeName))

        val year = StatsPeriod.MONTHS_12.view(days, today, Locale.UK)
        assertEquals(12, year.bars.size)
        assertEquals(2259, year.totals.chars)
        assertEquals(1500, year.bars.last().chars)
        assertEquals(listOf("Dec 2025", "Jan 2026", "Feb"), year.bars.take(3).map { it.axis })
        assertNull(year.before)
    }

    @Test
    fun streaks() {
        val d = LocalDate.of(2026, 11, 17)
        val days = listOf(d, d.minusDays(1), d.minusDays(2), d.minusDays(5), d.minusDays(6), d.minusDays(7), d.minusDays(8))
        assertEquals(ReadingStats.Streaks(3, 4), ReadingStats.streaks(days, d))
        assertEquals(ReadingStats.Streaks(3, 4), ReadingStats.streaks(days, d.plusDays(1)))
        assertEquals(ReadingStats.Streaks(0, 4), ReadingStats.streaks(days, d.plusDays(2)))
        assertEquals(ReadingStats.Streaks(0, 0), ReadingStats.streaks(emptyList(), d))
    }

    // ── VOCR's output ────────────────────────────────────────────────────────

    @Test
    fun vocrLinesParseAndTheSignatureIsTheText() {
        val lines = Vocr.parse("118,806,1124,76\t昨日の夜、森の奥で不思議な光を見たんだ。\n1688,70,74,548\t\n")
        assertEquals(2, lines.size)
        assertEquals(Box(118, 806, 1242, 882), lines[0].box)
        assertEquals("昨日の夜、森の奥で不思議な光を見たんだ。\n[52,2,2,17]", Vocr.signature(lines))
        assertEquals("", Vocr.signature(Vocr.parse("")))
    }
}
