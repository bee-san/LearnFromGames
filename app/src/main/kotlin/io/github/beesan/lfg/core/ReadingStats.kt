// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * GameSentenceMiner's reading-time rules (its util/stats/stats_util.py), which
 * need nothing but line times and lengths: the gap after a line counts as
 * reading it, capped at 2.5 times what that line should take at the session's
 * own median pace, and never more than five minutes.
 */
object ReadingStats {
    const val MAX_SEC_PER_CHAR = 3.0
    const val ABSOLUTE_CEILING = 300.0
    const val MIN_CHARS_FOR_SPEED = 5
    const val MIN_GAP_FOR_SPEED = 1.0
    const val ADAPTIVE_FLOOR_SECONDS = 2.0
    const val ADAPTIVE_TOLERANCE = 2.5

    /** The day turns over at 04:00, so a session past midnight stays on its evening. */
    const val DAY_STARTS_AT_HOUR = 4L

    /** A gap after a line: how long until the next one, and how long the line was. */
    data class Gap(val seconds: Double, val chars: Int)

    data class Stamp(val timestamp: Double, val chars: Int, val gameId: String)

    data class DayTotals(
        val day: LocalDate,
        val lines: Int,
        val chars: Int,
        val seconds: Double,
    ) {
        val charsPerHour: Int get() = if (seconds < 1.0) 0 else (chars / seconds * 3600).toInt()
    }

    fun dayOf(timestamp: Double, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli((timestamp * 1000).toLong()).atZone(zone).minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

    private fun speedSample(gap: Gap): Double? {
        if (gap.chars < MIN_CHARS_FOR_SPEED || !gap.seconds.isFinite()) return null
        if (gap.seconds < MIN_GAP_FOR_SPEED || gap.seconds > ABSOLUTE_CEILING) return null
        return gap.chars / gap.seconds
    }

    fun medianCharsPerSecond(gaps: List<Gap>): Double {
        val speeds = gaps.mapNotNull(::speedSample).sorted()
        if (speeds.isEmpty()) return 0.0
        val mid = speeds.size / 2
        return if (speeds.size % 2 == 1) speeds[mid] else (speeds[mid - 1] + speeds[mid]) / 2.0
    }

    fun capSeconds(chars: Int, medianCps: Double): Double {
        if (chars <= 0) return 0.0
        val cap = if (medianCps.isFinite() && medianCps > 0) {
            maxOf(ADAPTIVE_FLOOR_SECONDS, chars / medianCps * ADAPTIVE_TOLERANCE)
        } else {
            maxOf(ADAPTIVE_FLOOR_SECONDS, chars * MAX_SEC_PER_CHAR)
        }
        return minOf(cap, ABSOLUTE_CEILING)
    }

    fun readingSeconds(gaps: List<Gap>): Double {
        val median = medianCharsPerSecond(gaps)
        return gaps.sumOf { minOf(it.seconds, capSeconds(it.chars, median)) }
    }

    /**
     * Gaps of one session: lines in time order, each paired with the time to
     * the next. The last line has no next one, so it is credited its cap at the
     * session's pace.
     */
    private fun sessionSeconds(stamps: List<Stamp>): Double {
        if (stamps.isEmpty()) return 0.0
        val sorted = stamps.sortedBy { it.timestamp }
        val gaps = sorted.zipWithNext { a, b -> Gap(b.timestamp - a.timestamp, a.chars) }
        val median = medianCharsPerSecond(gaps)
        return readingSeconds(gaps) + capSeconds(sorted.last().chars, median) / ADAPTIVE_TOLERANCE
    }

    /** A session is one game on one day. Newest day first. */
    fun byDay(stamps: List<Stamp>, zone: ZoneId): List<DayTotals> =
        stamps.groupBy { dayOf(it.timestamp, zone) }.map { (day, ofDay) ->
            DayTotals(
                day = day,
                lines = ofDay.size,
                chars = ofDay.sumOf { it.chars },
                seconds = ofDay.groupBy { it.gameId }.values.sumOf(::sessionSeconds),
            )
        }.sortedByDescending { it.day }

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
