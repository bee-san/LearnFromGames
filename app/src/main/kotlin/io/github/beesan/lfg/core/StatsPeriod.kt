// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The days of reading in a stretch of time, as the stats screen shows them: one bar a day, or a month. */
enum class StatsPeriod(val label: String) {
    DAYS_7("7 days"),
    DAYS_30("30 days"),
    MONTHS_12("12 months"),
    ALL("All time");

    val byDay get() = this == DAYS_7 || this == DAYS_30

    data class Bar(
        val first: LocalDate,
        /** The day or month in words, for the line above the chart. */
        val name: String,
        /** Its label under the chart; most are blank so the labels never crowd. */
        val axis: String,
        val chars: Int,
        val seconds: Double,
    )

    /** [timedChars]: the characters of the lines that got a time, which alone count towards speed. */
    data class Totals(val chars: Int, val timedChars: Int, val lines: Int, val seconds: Double) {
        val charsPerHour: Int get() = ReadingStats.perHour(timedChars, seconds)
    }

    data class View(
        /** The first day the period counts. */
        val start: LocalDate,
        val range: String,
        val bars: List<Bar>,
        val totals: Totals,
        /** The same length of time just before, for the 7 and 30 day views. */
        val before: Totals?,
        val beforeName: String,
        val daysRead: Int,
        val dayCount: Int,
    )

    /** `days` as [ReadingStats.byDay] gives them, in any order. */
    fun view(days: List<ReadingStats.DayTotals>, today: LocalDate, locale: Locale = Locale.getDefault()): View {
        // Never after today: lines read while the clock was set ahead are no reason for all of time to start in the future.
        val first = minOf(days.minOfOrNull { it.day } ?: today, today)
        val monthName = { m: YearMonth -> m.month.getDisplayName(TextStyle.FULL, locale) + " " + m.year }
        val dayName = { d: LocalDate ->
            d.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + " " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.FULL, locale)
        }
        val start: LocalDate
        val bars: List<Bar>
        if (byDay) {
            val n = if (this == DAYS_7) 7 else 30
            start = today.minusDays(n - 1L)
            bars = List(n) { i ->
                val d = start.plusDays(i.toLong())
                val axis = when {
                    n == 7 -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + d.dayOfMonth
                    d.dayOfWeek.value == 1 -> d.dayOfMonth.toString() + " " + d.month.getDisplayName(TextStyle.SHORT, locale)
                    else -> ""
                }
                Bar(d, dayName(d), axis, 0, 0.0)
            }
        } else {
            val from = if (this == MONTHS_12) YearMonth.from(today).minusMonths(11) else YearMonth.from(first)
            start = if (this == MONTHS_12) from.atDay(1) else first
            val count = ChronoUnit.MONTHS.between(from, YearMonth.from(today)).toInt() + 1
            bars = List(count) { i ->
                val m = from.plusMonths(i.toLong())
                val shown = count <= 18 || m.monthValue % 3 == 1 || i == 0
                val short = m.month.getDisplayName(TextStyle.SHORT, locale)
                val axis = if (!shown) "" else if (i == 0 || m.monthValue == 1) "$short ${m.year}" else short
                Bar(m.atDay(1), monthName(m), axis, 0, 0.0)
            }
        }

        val sums = bars.map { doubleArrayOf(0.0, 0.0) }
        var chars = 0
        var timed = 0
        var lines = 0
        var seconds = 0.0
        var read = 0
        var before = Totals(0, 0, 0, 0.0)
        val beforeStart = start.minusDays(bars.size.toLong())
        for (d in days) {
            if (d.day >= start && d.day <= today) {
                val i = if (byDay) ChronoUnit.DAYS.between(start, d.day).toInt() else ChronoUnit.MONTHS.between(YearMonth.from(bars[0].first), YearMonth.from(d.day)).toInt()
                sums[i][0] += d.chars.toDouble()
                sums[i][1] += d.seconds
                chars += d.chars
                timed += d.timedChars
                lines += d.lines
                seconds += d.seconds
                if (d.lines > 0) read++
            } else if (byDay && d.day >= beforeStart && d.day < start) {
                before = Totals(before.chars + d.chars, before.timedChars + d.timedChars, before.lines + d.lines, before.seconds + d.seconds)
            }
        }
        val range = when (this) {
            DAYS_7, DAYS_30 -> span(start, today, locale)
            MONTHS_12 -> monthName(YearMonth.from(start)) + " – " + monthName(YearMonth.from(today))
            ALL -> "Since " + first.dayOfMonth + " " + first.month.getDisplayName(TextStyle.FULL, locale) + " " + first.year
        }
        return View(
            start = start,
            range = range,
            bars = bars.mapIndexed { i, b -> b.copy(chars = sums[i][0].toInt(), seconds = sums[i][1]) },
            totals = Totals(chars, timed, lines, seconds),
            before = if (byDay) before else null,
            beforeName = if (byDay) "the ${bars.size} days before" else "",
            daysRead = read,
            dayCount = ChronoUnit.DAYS.between(start, today).toInt() + 1,
        )
    }

    companion object {
        /** "Up 12% on the 30 days before"; blank when there is nothing to say. */
        fun change(now: Double, before: Double, what: String): String {
            if (before <= 0.0) return if (now <= 0.0) "" else "None in $what"
            val d = (100 * (now - before) / before).roundToInt()
            return if (d == 0) "Same as $what" else "${if (d > 0) "Up" else "Down"} ${abs(d)}% on $what"
        }

        /** "19 October – 17 November 2026", without repeating what both ends share. */
        fun span(from: LocalDate, to: LocalDate, locale: Locale = Locale.getDefault()): String {
            val month = { d: LocalDate -> d.month.getDisplayName(TextStyle.FULL, locale) }
            return when {
                from.year != to.year -> "${from.dayOfMonth} ${month(from)} ${from.year} – ${to.dayOfMonth} ${month(to)} ${to.year}"
                from.month != to.month -> "${from.dayOfMonth} ${month(from)} – ${to.dayOfMonth} ${month(to)} ${to.year}"
                else -> "${from.dayOfMonth}–${to.dayOfMonth} ${month(to)} ${to.year}"
            }
        }
    }
}
