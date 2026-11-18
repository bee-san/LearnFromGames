package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.CursorPos
import io.github.beesan.lfg.core.KeyRepeat
import io.github.beesan.lfg.core.ReadingGoals
import io.github.beesan.lfg.core.ReadingStats
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

    // ── reading time, by Rubi's adaptive rule (its tools/tests/test_reading_stats.py) ──

    private fun credit(gapUs: Long, chars: Int, pace: Int) = ReadingStats.adaptiveCredit(gapUs, chars, pace).let { it.us to it.pace }

    @Test
    fun aLineIsCreditedAtMostTwoAndAHalfTimesItsExpectedTime() {
        assertEquals(8_000_000L to 18_165, credit(8_000_000, 10, 0))               // under its 30 s cap; faster: the pace down
        assertEquals(5_812_800L to 18_165, credit(6_000_000, 2, 18_165))           // its cap; too short to teach the pace
        assertEquals(2_906_400L to 18_165, credit(600_000_000, 1, 18_165))         // away from the game: the cap, no sample
        assertEquals(20_344_800L to 18_165, credit(10_800_000_000, 7, 18_165))     // three hours (no sessions): the cap
        assertEquals(2_000_000L to 18_165, credit(10_000_000, 0, 18_165))          // no characters: the 2 s floor
        assertEquals(15_000_000L to 19_866, credit(15_000_000, 11, 18_165))        // slower than the pace: up 3/32
        assertEquals(300_000_000L to 18_165, credit(600_000_000, 1000, 18_165))    // the 5-minute ceiling
    }

    @Test
    fun thePaceLearnsTheReader() {
        var pace = 0
        repeat(300) { // 20 characters in 4 s, again and again
            assertEquals(4_000_000L, credit(4_000_000, 20, pace).first)
            pace = credit(4_000_000, 20, pace).second
        }
        assertEquals(3255, pace) // 208 ms a character: about the reader's 200
        assertEquals(10_416_000L to 3255, credit(600_000_000, 20, pace))
        pace = 0
        repeat(40) { pace = credit(300_000_000, 5, pace).second } // slower than 4.19 s a character: 16 bits
        assertEquals(52_428_000L to 0xFFFF, credit(600_000_000, 5, pace))
    }

    @Test
    fun thePaceHasAFloorAndExactlyOnTimeIsNotSlower() {
        var pace = 0
        var lowest = 0xFFFF
        repeat(300) { // 4,000 characters every 1.5 s
            pace = credit(1_500_000, 4000, pace).second
            lowest = minOf(lowest, pace)
        }
        assertEquals(ReadingStats.PACE_MIN to 32, lowest to pace)
        val credits = List(200) { credit(30_000_000, 30, pace).also { pace = it.second }.first }
        assertEquals(2_000_000L, credits.first())
        assertEquals(65, credits.indexOf(30_000_000L))
        assertEquals(30_000_000L to 16_668, credits.last() to pace)
        assertEquals(2_000_000L to 32, credit(30_000_000, 30, 31))
        assertEquals(2_000_000L to 35, credit(30_000_000, 30, 32))
        for ((gap, want) in listOf(12_000_000L to 18_165, 12_000_063L to 18_165, 12_000_064L to 20_505)) {
            assertEquals(gap to want, credit(gap, 10, 0))
        }
    }

    @Test
    fun threeSkimmedLinesInARowAreASkip() {
        val q = 400_000L
        val s = ReadingStats::skippedLines
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            s(listOf(10_000_000L to 20, q to 20, q to 20, q to 20, q to 3, q to 30, 8_000_000L to 15, 0L to 10)),
        )
        assertEquals(emptyList<Int>(), s(listOf(q to 20, q to 4, q to 20, 5_000_000L to 12))) // two, and a short one between
        assertEquals(listOf(0, 2, 4), s(listOf(q to 20, q to 4, q to 20, q to 11, q to 12)))
        assertEquals(emptyList<Int>(), s(listOf(q to 20, q to 20, 0L to 20, q to 20)))        // a break ends the run
        assertEquals(listOf(4, 5, 6), s(List(4) { 1_000_000L to 20 } + List(3) { 999_999L to 20 })) // 1 s is read
    }

    @Test
    fun skippedLinesCountNeitherCharactersNorTime() {
        val zone = ZoneId.of("UTC")
        val base = LocalDate.of(2026, 11, 17).atTime(21, 0).atZone(zone).toEpochSecond().toDouble()
        val times = listOf(0.0, 10.0, 10.4, 10.8, 11.2, 11.6, 11.8, 19.8)
        val chars = listOf(20, 20, 20, 20, 3, 30, 15, 10)
        val day = ReadingStats.byDay(times.indices.map { Stamp(base + times[it], chars[it], "a") }, zone).single()
        assertEquals(8, day.lines)
        assertEquals(45, day.chars)
        assertEquals(35, day.timedChars)
        assertEquals(18.0, day.seconds, 1e-9)
        assertEquals(7000, day.charsPerHour)
    }

    @Test
    fun onlyJapaneseLettersAndAsciiLettersAndDigitsCount() {
        assertEquals(7, ReadingStats.characters("「森の奥で、光った！」"))
        assertEquals(6, ReadingStats.characters("HP 12/30 …"))
        assertEquals(5, ReadingStats.characters("ｶﾞｰﾄﾞ"))        // half-width katakana, each voicing mark its own
        assertEquals(3, ReadingStats.characters("𠮟る々"))         // a letter outside the first plane; the iteration mark
        assertEquals(0, ReadingStats.characters("ＡＢ　１２！？♪\n"))  // full-width Latin and digits are not letters here
    }

    /** Rubi's simulated hours of play (its host/fixtures/reading_time), each with what the rule credits in its header. */
    @Test
    fun rubisTimelinesAreCreditedExactlyAsRubiDoes() {
        val dir = java.io.File("src/test/resources/reading_time")
        val files = dir.listFiles { f -> f.name.endsWith(".tsv") }!!.sortedBy { it.name }
        assertEquals(11, files.size)
        for (file in files) {
            val lines = file.readLines()
            val want = lines.first { it.startsWith("# adaptive: ") }.removePrefix("# adaptive: ").split(' ')
                .associate { it.substringBefore('=') to it.substringAfter('=').toLong() }
            val stamps = lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
                .filter { it[2] != "-" } // a read that found the box blank: no record
                .map { Stamp(it[0].toLong() / 1e6, it[1].toInt(), "a", us = it[0].toLong()) }
            val reads = ReadingStats.credit(stamps)
            assertEquals(file.name, want["active_us"], reads.sumOf { it.us ?: 0L })
            assertEquals(file.name, want["timed_chars"], stamps.indices.filter { reads[it].us != null }.sumOf { stamps[it].chars }.toLong())
            assertEquals(file.name, want["characters"], stamps.indices.filter { !reads[it].skipped }.sumOf { stamps[it].chars }.toLong())
        }
    }

    @Test
    fun aChangeOfGameOrAClockThatDidNotMoveIsABreak() {
        val reads = ReadingStats.credit(
            listOf(Stamp(0.0, 20, "a"), Stamp(5.0, 20, "a"), Stamp(9.0, 20, "b"), Stamp(9.0, 20, "b"), Stamp(12.0, 20, "b")),
        )
        assertEquals(listOf(5_000_000L, null, null, 3_000_000L, null), reads.map { it.us })
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
        // Five 10 s gaps. The sixth line is followed by another game's, so it has no time and no part in the speed.
        assertEquals(100, days[1].timedChars)
        assertEquals(50.0, days[1].seconds, 1e-9)
        assertEquals(7200, days[1].charsPerHour)
    }

    @Test
    fun aPeriodAddsUpItsDaysAndTheSameLengthBefore() {
        // 17 November 2026 is a Tuesday, so the 30 days start on Monday 19 October.
        val today = LocalDate.of(2026, 11, 17)
        fun day(back: Long, chars: Int) = ReadingStats.DayTotals(today.minusDays(back), 10, chars, chars * 9 / 10, chars / 5.0)
        val days = listOf(day(0, 1000), day(3, 500), day(35, 750), day(90, 9))

        val month = StatsPeriod.DAYS_30.view(days, today, Locale.UK)
        assertEquals(30, month.bars.size)
        assertEquals(StatsPeriod.Totals(1500, 1350, 20, 300.0), month.totals)
        assertEquals(StatsPeriod.Totals(750, 675, 10, 150.0), month.before)
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
    fun goalsCountTheDaysThatMetThemAndTheRunThatMetThemAll() {
        val today = LocalDate.of(2026, 11, 17)
        fun day(back: Long, minutes: Int, chars: Int) = ReadingStats.DayTotals(today.minusDays(back), 10, chars, chars, minutes * 60.0)
        // Yesterday and the day before met both; three days back met only the time; today has met neither yet.
        val days = listOf(day(0, 20, 900), day(1, 60, 5000), day(2, 75, 6000), day(3, 61, 100), day(40, 90, 9000))
        val both = ReadingGoals(minutes = 60, chars = 5000)

        val month = both.progress(days, today.minusDays(29), today)
        assertEquals(today, month.today.day)
        assertEquals(3, month.timeDays)
        assertEquals(2, month.charDays)
        assertEquals(ReadingStats.Streaks(2, 2), month.streaks)       // today not met yet: the run still ends yesterday
        assertEquals(1, both.progress(days, today.minusDays(1), today).charDays)

        val timeOnly = ReadingGoals(minutes = 60)
        assertEquals(ReadingStats.Streaks(3, 3), timeOnly.progress(days, today, today).streaks)
        assertFalse(timeOnly.charsMet(days[1]))                       // a goal that is off is never met
        assertFalse(ReadingGoals().met(days[1]))
        assertEquals(0, ReadingGoals(chars = 1).progress(emptyList(), today, today).today.chars)
    }

    @Test
    fun goalStepsMoveAlongTheListAndStopAtItsEnds() {
        val steps = ReadingGoals.MINUTE_STEPS
        assertEquals(15, ReadingGoals.step(steps, 0, up = true))
        assertEquals(0, ReadingGoals.step(steps, 0, up = false))
        assertEquals(480, ReadingGoals.step(steps, 480, up = true))
        assertEquals(90, ReadingGoals.step(steps, 70, up = true))        // a value between steps goes to the next one either way
        assertEquals(60, ReadingGoals.step(steps, 70, up = false))
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
