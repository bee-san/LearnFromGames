// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reading time and speed by Rubi's adaptive rule (Rubi_for_vita's
 * tools/reading_stats.py and core/history_lines.c, in the same integers).
 * Lines go in time order across all games, and each is credited the time to
 * the next one, at most 2.5 times what the game's pace would take for it,
 * 2 s to 5 minutes. A change of game is a break. Sessions only group the
 * lines afterwards: they change no line's time, so a game's sessions, and its
 * days, add up to the totals.
 */
object ReadingStats {
    /** The pace is in units of 64 µs a character, in 16 bits; 0 means not learned yet. */
    private const val PACE_UNIT_SHIFT = 6
    const val PACE_START = 18_750 // 1.2 s a character, 3,000 an hour
    const val PACE_MIN = 32
    private const val PACE_MAX = 0xFFFF
    private const val CAP_FLOOR_US = 2_000_000L
    private const val CAP_CEILING_US = 300_000_000L
    private const val CAP_PER_UNIT_US = 160L // 2.5 × 64 µs
    private const val PACE_MIN_CHARS = 5
    private const val PACE_MIN_GAP_US = 1_000_000L

    /** Skip mode: this many lines in a row, of [SKIM_CHARS] or more, each credited under [SKIM_GAP_US]. */
    private const val SKIM_GAP_US = 1_000_000L
    private const val SKIM_CHARS = 12
    private const val SKIM_LINES = 3

    /** The day turns over at 04:00, so a session past midnight stays on its evening. */
    const val DAY_STARTS_AT_HOUR = 4L

    /** One saved line: when, how many counted characters ([characters]), and whose. */
    data class Stamp(
        val timestamp: Double,
        val chars: Int,
        val gameId: String,
        val us: Long = Math.round(timestamp * 1e6),
    )

    data class DayTotals(
        val day: LocalDate,
        val lines: Int,
        /** Every counted character, skip mode's lines left out. */
        val chars: Int,
        /** The characters of the lines that got a time: only these count towards speed. */
        val timedChars: Int,
        val seconds: Double,
    ) {
        val charsPerHour: Int get() = perHour(timedChars, seconds)
    }

    fun perHour(timedChars: Int, seconds: Double): Int = if (seconds < 1.0) 0 else (timedChars * 3600.0 / seconds).toInt()

    fun dayOf(timestamp: Double, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli((timestamp * 1000).toLong()).atZone(zone).minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

    /** Unicode 17.0's Han, Hiragana and Katakana letters (and the combining dakuten), first and last code point. */
    private val JAPANESE_LETTERS = intArrayOf(
        0x3005, 0x3007, 0x3021, 0x3029, 0x3031, 0x3035, 0x3038, 0x303C, 0x3041, 0x3096,
        0x3099, 0x309A, 0x309D, 0x309F, 0x30A1, 0x30FA, 0x30FC, 0x30FF, 0x31F0, 0x31FF,
        0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xF900, 0xFA6D, 0xFA70, 0xFAD9, 0xFF66, 0xFF9F,
        0x16FE3, 0x16FE3, 0x16FF2, 0x16FF6, 0x1AFF0, 0x1AFF3, 0x1AFF5, 0x1AFFB, 0x1AFFD, 0x1AFFE,
        0x1B000, 0x1B122, 0x1B132, 0x1B132, 0x1B150, 0x1B152, 0x1B155, 0x1B155, 0x1B164, 0x1B167,
        0x20000, 0x2A6DF, 0x2A700, 0x2B81D, 0x2B820, 0x2CEAD, 0x2CEB0, 0x2EBE0, 0x2EBF0, 0x2EE5D,
        0x2F800, 0x2FA1D, 0x30000, 0x3134A, 0x31350, 0x33479,
    )

    private fun counts(cp: Int): Boolean {
        if (cp < 0x80) return cp in 0x30..0x39 || cp in 0x41..0x5A || cp in 0x61..0x7A
        var i = 0
        while (i < JAPANESE_LETTERS.size && cp >= JAPANESE_LETTERS[i]) {
            if (cp <= JAPANESE_LETTERS[i + 1]) return true
            i += 2
        }
        return false
    }

    /** The characters of a line that count as read: Japanese letters and ASCII letters and digits, no punctuation, spaces or symbols. */
    fun characters(text: String): Int = text.codePoints().filter(::counts).count().toInt()

    data class Credit(val us: Long, val pace: Int)

    /**
     * The microseconds credited to a line of [chars] characters whose next line
     * came [gapUs] (> 0) later, and the game's pace after it. The pace is a
     * running 75th percentile: 3/32 up after a line read more slowly than it,
     * 1/32 down after one read faster.
     */
    fun adaptiveCredit(gapUs: Long, chars: Int, pace: Int): Credit {
        val gap = minOf(gapUs, 0xFFFFFFFFL)
        var p = if (pace == 0) PACE_START else pace
        val expect = p.toLong() * chars
        val cap = maxOf(if (expect >= CAP_CEILING_US / CAP_PER_UNIT_US) CAP_CEILING_US else expect * CAP_PER_UNIT_US, CAP_FLOOR_US)
        var after = pace
        if (chars >= PACE_MIN_CHARS && gap in PACE_MIN_GAP_US..CAP_CEILING_US) {
            p = if ((gap shr PACE_UNIT_SHIFT) > expect) p + 3 * (p shr 5) else p - (p shr 5)
            after = p.coerceIn(PACE_MIN, PACE_MAX)
        }
        return Credit(minOf(gap, cap), after)
    }

    /**
     * [lines]: the microseconds each line was credited (0: none, a break) and
     * its characters, in order. The indexes of the lines skip mode takes out:
     * three skimmed lines of 12 or more characters in a row, and every skimmed
     * line after them, until a line is read or the interval breaks. Shorter
     * skimmed lines neither count towards the three nor end the run.
     */
    fun skippedLines(lines: List<Pair<Long, Int>>): List<Int> {
        val out = ArrayList<Int>()
        val run = ArrayList<Int>()
        var skip = false
        lines.forEachIndexed { i, (credit, chars) ->
            if (credit == 0L || credit >= SKIM_GAP_US) {
                run.clear()
                skip = false
            } else if (skip) {
                out += i
            } else if (chars >= SKIM_CHARS) {
                run += i
                if (run.size == SKIM_LINES) {
                    out += run
                    run.clear()
                    skip = true
                }
            }
        }
        return out
    }

    /** What one line was given: its time in microseconds (null: none), or skipped (neither characters nor time). */
    data class Read(val us: Long?, val skipped: Boolean)

    /** [stamps] in time order. */
    fun credit(stamps: List<Stamp>): List<Read> {
        val paces = HashMap<String, Int>()
        val credits = ArrayList<Pair<Long, Int>>(stamps.size)
        for (i in stamps.indices) {
            val line = stamps[i]
            val next = stamps.getOrNull(i + 1)
            var us = 0L
            // Another game's line next, the same time or an earlier one: no interval. The last line has none either.
            if (next != null && next.gameId == line.gameId && next.us > line.us) {
                val c = adaptiveCredit(next.us - line.us, line.chars, paces[line.gameId] ?: 0)
                us = c.us
                paces[line.gameId] = c.pace
            }
            credits += us to line.chars
        }
        val skipped = skippedLines(credits).toHashSet()
        return credits.mapIndexed { i, (us, _) -> Read(if (us == 0L || i in skipped) null else us, i in skipped) }
    }

    /** A session of a game ends when this long passes with no line from it. */
    const val SESSION_GAP_SECONDS = 30 * 60.0

    /** A run of one game's lines with no gap longer than [SESSION_GAP_SECONDS]; [start] and [end] are its first and last line. */
    data class Session(
        val gameId: String,
        val start: Double,
        val end: Double,
        val lines: Int,
        val chars: Int,
        val timedChars: Int,
        val seconds: Double,
    ) {
        val charsPerHour: Int get() = perHour(timedChars, seconds)
    }

    /** Every day, each game's days, and every session, oldest session first: all from one pass of [credit]. */
    data class Summary(
        val days: List<DayTotals>,
        val gameDays: Map<String, List<DayTotals>>,
        val sessions: List<Session>,
    )

    private class Sum(var lines: Int = 0, var chars: Int = 0, var timed: Int = 0, var us: Long = 0) {
        fun add(chars: Int, read: Read) {
            lines++
            if (!read.skipped) this.chars += chars
            if (read.us != null) {
                timed += chars
                us += read.us
            }
        }

        fun day(day: LocalDate) = DayTotals(day, lines, chars, timed, us / 1e6)
    }

    /** Newest day first, in [Summary.days] and in each game's list. The pace is learned across the whole history, not day by day. */
    fun summarise(stamps: List<Stamp>, zone: ZoneId): Summary {
        val sorted = stamps.sortedBy { it.us }
        val reads = credit(sorted)
        val days = HashMap<LocalDate, Sum>()
        val gameDays = HashMap<String, HashMap<LocalDate, Sum>>()
        class Open(val start: Double, var end: Double, val sum: Sum = Sum())
        val open = HashMap<String, Open>()
        val sessions = ArrayList<Session>()
        val close = { gameId: String, o: Open -> sessions += Session(gameId, o.start, o.end, o.sum.lines, o.sum.chars, o.sum.timed, o.sum.us / 1e6) }
        sorted.forEachIndexed { i, line ->
            val day = dayOf(line.timestamp, zone)
            val read = reads[i]
            days.getOrPut(day) { Sum() }.add(line.chars, read)
            gameDays.getOrPut(line.gameId) { HashMap() }.getOrPut(day) { Sum() }.add(line.chars, read)
            val last = open[line.gameId]
            val session = if (last != null && line.timestamp - last.end <= SESSION_GAP_SECONDS) {
                last
            } else {
                last?.let { close(line.gameId, it) }
                Open(line.timestamp, line.timestamp).also { open[line.gameId] = it }
            }
            session.end = line.timestamp
            session.sum.add(line.chars, read)
        }
        open.forEach { (gameId, o) -> close(gameId, o) }
        val newestFirst = { m: Map<LocalDate, Sum> -> m.map { (day, s) -> s.day(day) }.sortedByDescending { it.day } }
        return Summary(newestFirst(days), gameDays.mapValues { newestFirst(it.value) }, sessions.sortedBy { it.start })
    }

    /** Newest day first. The pace is learned across the whole history, not day by day. */
    fun byDay(stamps: List<Stamp>, zone: ZoneId): List<DayTotals> = summarise(stamps, zone).days

    data class Streaks(val current: Int, val longest: Int)

    /** Runs of consecutive days with at least one line. The current run may end today or yesterday. */
    fun streaks(days: Collection<LocalDate>, today: LocalDate): Streaks {
        val set = days.toSortedSet()
        if (set.isEmpty()) return Streaks(0, 0)
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in set) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        var current = 0
        var cursor = if (today in set) today else today.minusDays(1)
        while (cursor in set) {
            current++
            cursor = cursor.minusDays(1)
        }
        return Streaks(current, longest)
    }
}
