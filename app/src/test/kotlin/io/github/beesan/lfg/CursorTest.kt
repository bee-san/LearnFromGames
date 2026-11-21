package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.CursorPos
import io.github.beesan.lfg.core.KeyRepeat
import io.github.beesan.lfg.core.ReadingGoals
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.ReadingStats.Stamp
import io.github.beesan.lfg.core.Selectable
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.core.TextCursor
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Vocr
import io.github.beesan.lfg.widget.WidgetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class CursorTest {
    private fun line(text: String, top: Int, left: Int = 100) = TextLine(
        text, Box(left, top, left + text.length * 50, top + 50),
        text.indices.map { CharBox(it, it + 1, Box(left + it * 50, top, left + it * 50 + 50, top + 50)) },
    )

    private fun cursor(vararg lines: TextLine, japaneseOnly: Boolean = false) = TextCursor(lines.toList(), japaneseOnly)

    // ── the cursor, by Rubi for Vita's rules (its core/text_rows.c and docs/touch-lookup.md) ──

    @Test
    fun theCursorStartsOnTheDialogueNotTheSpeakersName() {
        val c = cursor(line("ユイ", 0), line("昨日の夜、森の奥で", 100))
        assertEquals(CursorPos(1, 0), c.first())
        // Punctuation never holds the cursor: a line of it has nowhere to start.
        assertNull(cursor(line("……", 0)).first())
    }

    @Test
    fun rightGoesPastTheWordShownAndLeftRetracesIt() {
        //                     0 1 2 3 4 5 6 7 8 9 10 11
        val c = cursor(line("昨日すき焼きを食べました", 0))
        var pos = CursorPos(0, 0)
        pos = c.right(pos, matchedEnd = 2); assertEquals(2, pos.index)   // past 昨日
        pos = c.right(pos, matchedEnd = 6); assertEquals(6, pos.index)   // past すき焼き
        pos = c.right(pos, matchedEnd = 7); assertEquals(7, pos.index)   // past を
        // No word shown (its lookup is not in, or Character selection): one character.
        assertEquals(CursorPos(0, 8), c.right(pos, matchedEnd = 0))
        c.left(CursorPos(0, 8))
        // Left retraces each Right, newest first, then steps a character at a time.
        pos = c.left(pos); assertEquals(6, pos.index)
        pos = c.left(pos); assertEquals(2, pos.index)
        pos = c.left(pos); assertEquals(0, pos.index)
        assertEquals(CursorPos(0, 3), c.left(CursorPos(0, 4)))
    }

    @Test
    fun theStickGoesACharacterAtATimeWhateverWordIsShown() {
        // The owner's example: from 食べました the stick's right is べ, then ま, where the D-pad's Right goes past the whole word.
        val c = cursor(line("昨日すき焼きを食べました", 0))
        val word = c.right(c.right(c.right(CursorPos(0, 0), 2), 6), 7)
        assertEquals(CursorPos(0, 7), word)
        val be = c.stickRight(word)
        assertEquals(CursorPos(0, 8), be)
        val ma = c.stickRight(be)
        assertEquals(CursorPos(0, 9), ma)
        // The stick's left steps back and never retraces; the Rights that started at or after where it lands are done with.
        assertEquals(CursorPos(0, 8), c.stickLeft(ma))
        assertEquals(CursorPos(0, 7), c.stickLeft(CursorPos(0, 8)))
        // The D-pad's Left then retraces the Rights made before those: back to を's start, then すき焼き's.
        assertEquals(CursorPos(0, 6), c.left(CursorPos(0, 7)))
        assertEquals(CursorPos(0, 2), c.left(CursorPos(0, 6)))
    }

    @Test
    fun theHistoryHoldsSixteenRightsAndATapForgetsThem() {
        val c = cursor(line("あ".repeat(30), 0))
        var pos = CursorPos(0, 0)
        repeat(20) { pos = c.right(pos) }
        assertEquals(20, pos.index)
        repeat(16) { pos = c.left(pos) }
        assertEquals(4, pos.index)
        // The seventeenth has nothing to retrace: one character back.
        assertEquals(CursorPos(0, 3), c.left(pos))
        repeat(3) { pos = c.right(pos) }
        c.forget()
        assertEquals(CursorPos(0, pos.index - 1), c.left(pos))
    }

    @Test
    fun punctuationIsPassedOverAndJapaneseOnlyLeavesLatinOutToo() {
        val text = "夜、森 A。"
        val all = cursor(line(text, 0))
        assertEquals(listOf(2, 4), generateSequence(CursorPos(0, 0)) { p -> all.stickRight(p).takeIf { it != p } }.drop(1).map { it.index }.toList())
        val japanese = cursor(line(text, 0), japaneseOnly = true)
        assertEquals(CursorPos(0, 2), japanese.stickRight(CursorPos(0, 0)))
        assertEquals(CursorPos(0, 2), japanese.stickRight(CursorPos(0, 2)))
        assertEquals(CursorPos(0, 0), japanese.stickLeft(CursorPos(0, 2)))
        assertTrue(Selectable.at("々ー𠮟", 0, true) && Selectable.at("々ー𠮟", 1, true) && Selectable.at("々ー𠮟", 2, true))
        assertFalse(Selectable.at("ａ　!", 0, true))   // full-width Latin is not a Japanese letter
        assertTrue(Selectable.at("ａ　!", 0, false))
        assertFalse(Selectable.at("ａ　!", 1, false))  // an ideographic space never holds the cursor
        assertFalse(Selectable.at("ａ　!", 2, false))  // nor punctuation
        // The D-pad's Right from a word goes past the comma after it to the next word.
        assertEquals(CursorPos(0, 4), cursor(line("今なお、それは", 0)).right(CursorPos(0, 0), matchedEnd = 3))
        assertFalse(Selectable.at("𠮟", 1, false))     // the second half of a pair is not a character
    }

    @Test
    fun leftAndRightRunOnAcrossRowsAndLinesAndStopAtTheTextsEnds() {
        val c = cursor(line("ユイ", 0), line("森の奥で", 100))
        assertEquals(CursorPos(1, 0), c.right(CursorPos(0, 1)))
        assertEquals(CursorPos(1, 3), c.right(CursorPos(1, 3)))       // the last: more than one row, so it stays
        assertEquals(CursorPos(0, 1), c.stickLeft(CursorPos(1, 0)))
        assertEquals(CursorPos(0, 0), c.left(CursorPos(0, 0)))
        // A word that ends its row: Right from it goes to the next row's first character.
        assertEquals(CursorPos(1, 0), c.right(CursorPos(0, 0), matchedEnd = 2))
    }

    @Test
    fun aTextOnOneRowGoesRoundItsEndsForTheDpadOnly() {
        val c = cursor(line("森の奥", 0))
        assertEquals(CursorPos(0, 0), c.right(CursorPos(0, 2)))
        // Left retraces that wrap like any other Right.
        assertEquals(CursorPos(0, 2), c.left(CursorPos(0, 0)))
        c.forget()
        assertEquals(CursorPos(0, 2), c.left(CursorPos(0, 0)))
        assertEquals(CursorPos(0, 2), c.stickRight(CursorPos(0, 2)))
        assertEquals(CursorPos(0, 0), c.stickLeft(CursorPos(0, 0)))
    }

    @Test
    fun upAndDownGoToTheNearestCharacterAndKeepTheirColumnAcrossAShortRow() {
        val c = cursor(line("昨日の夜、森の奥で", 0), line("森", 100), line("でも、近づいたら消えてしまった", 200))
        // From 奥 (index 7) down: the short row has only 森, then the long row is entered at the column 奥 was in.
        val short = c.down(CursorPos(0, 7))
        assertEquals(CursorPos(1, 0), short)
        assertEquals(CursorPos(2, 7), c.down(short))
        assertEquals(CursorPos(0, 7), c.up(c.up(CursorPos(2, 7))))
        assertEquals(CursorPos(0, 0), c.up(CursorPos(0, 0)))
        assertEquals(CursorPos(2, 0), c.down(CursorPos(2, 0)))
        // A sideways step drops the column: down from the short row is then straight below it.
        c.down(CursorPos(0, 7))
        c.stickLeft(CursorPos(1, 0))
        assertEquals(CursorPos(2, 0), c.down(CursorPos(1, 0)))
        // Up and Down forget the Rights: Left then steps a character.
        val right = c.right(CursorPos(0, 0), matchedEnd = 2)
        assertEquals(CursorPos(0, 1), c.left(c.up(c.down(right)).let { CursorPos(0, 2) }))
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
        assertEquals(CursorPos(1, 14), c.down(CursorPos(1, 5)))
        assertEquals(CursorPos(1, 14), c.down(CursorPos(1, 14)))
        assertEquals(CursorPos(1, 5), c.up(CursorPos(1, 14)))
        // Up from the first row leaves the sentence for the name.
        assertEquals(CursorPos(0, 0), c.up(CursorPos(1, 0)))
        // Steps run on through the line break, and two rows never go round.
        assertEquals(CursorPos(1, 9), c.right(CursorPos(1, 8)))
        assertEquals(CursorPos(1, 16), c.right(CursorPos(1, 16)))
    }

    @Test
    fun aLineWithNoPlaceOnScreenIsOneRowOfCharacters() {
        // A line from the store, looked into beside the one on screen: it has no boxes.
        val stored = TextLine("森の奥で", Box(0, 0, 0, 0), emptyList())
        val c = cursor(line("ユイ", 0), stored)
        assertEquals(CursorPos(1, 1), c.stickRight(CursorPos(1, 0)))
        assertEquals(CursorPos(0, 1), c.stickLeft(CursorPos(1, 0)))
        assertEquals(CursorPos(1, 3), c.right(CursorPos(1, 0), matchedEnd = 3))
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

    @Test
    fun theSticksStepsComeSoonerEachTimeDownToTwentyASecond() {
        val r = KeyRepeat.stick()
        var now = 0L
        assertTrue(r.press(1, now))
        val gaps = List(12) {
            val due = r.nextRepeatAt
            assertFalse(r.press(1, due - 1))
            assertTrue(r.press(1, due))
            (due - now).also { now = due }
        }
        assertEquals(listOf(167L, 154L, 141L, 128L, 115L, 102L, 89L, 76L, 63L, 50L, 50L, 50L), gaps)
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
    fun theWeekWidgetChartsAgainstTheGoalAndTodayIsLast() {
        val today = LocalDate.of(2026, 11, 18) // a Wednesday
        fun day(back: Long, minutes: Int, chars: Int) = ReadingStats.DayTotals(today.minusDays(back), 10, chars, chars, minutes * 60.0)
        val days = listOf(day(0, 30, 2000), day(1, 90, 6000), day(3, 1, 50))

        val timed = WidgetData.of(days, ReadingGoals(minutes = 60, chars = 5000), today)
        assertFalse(timed.weekInChars)
        val chart = timed.chart(Locale.UK)
        assertEquals(listOf("T", "F", "S", "S", "M", "T", "W"), chart.bars.map { it.letter })
        assertEquals(listOf(false, false, false, false, false, false, true), chart.bars.map { it.today })
        assertEquals(900, chart.bars[5].level)                     // the tallest day reaches nine tenths
        assertEquals(600, chart.goal)                              // 1 h of 1.5 h
        assertEquals(listOf(5), chart.bars.indices.filter { chart.bars[it].met })
        assertEquals(WidgetData.MIN_LEVEL, chart.bars[3].level)    // a minute still shows
        assertEquals(0, chart.bars[2].level)
        assertEquals(listOf(0.5f to WidgetData.Ring.TIME, 0.4f to WidgetData.Ring.CHARS), timed.rings)
        assertEquals(2, timed.streak)

        val chars = WidgetData.of(days, ReadingGoals(chars = 8000), today)
        assertTrue(chars.weekInChars)
        assertEquals(900, chars.chart(Locale.UK).goal)             // the goal is above every day
        assertNull(WidgetData.of(days, ReadingGoals(), today).chart(Locale.UK).goal)
        assertEquals(listOf("1" to "h", "5" to "min"), WidgetData.timeParts(3900.0))

        // A cards goal adds a third ring, inside the other two, and each day of the week gets its rings.
        val cardDays = listOf(today, today, today, today.minusDays(2))
        val carded = WidgetData.of(days, ReadingGoals(minutes = 60, chars = 5000), today, cardDays, cardGoal = 4)
        assertEquals(listOf(0.5f to WidgetData.Ring.TIME, 0.4f to WidgetData.Ring.CHARS, 0.75f to WidgetData.Ring.CARDS), carded.rings)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 3), carded.weekCards)
        assertEquals(0.25f to WidgetData.Ring.CARDS, carded.weekRings[4][2])
        assertFalse(carded.cardsMet)
        assertEquals(timed.rings, WidgetData.of(days, ReadingGoals(minutes = 60, chars = 5000), today, cardDays).rings) // no goal, no ring
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
