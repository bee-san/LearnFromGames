// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The one picture a day of a game keeps for good in the diary, chosen just
 * before that day's screens are cleared: a diary entry's picture, else a
 * screen a card was made from, else a recap's moment, else the line looked up
 * most, else the last line read.
 */
object DayPicture {
    /** A line of the day: its screen while one is kept, whether a card was made from it, and how often it was looked up. */
    data class Line(val id: String, val screen: String?, val card: Boolean = false, val lookups: Int = 0)

    /** The line of [lines] (in the order read) whose screen is the day's picture, or null when no screen is left. */
    fun line(lines: List<Line>, moments: Set<String>): Line? {
        val shown = lines.filter { it.screen != null }
        return shown.firstOrNull { it.card }
            ?: shown.firstOrNull { it.id in moments }
            ?: shown.filter { it.lookups > 0 }.maxByOrNull { it.lookups }
            ?: shown.lastOrNull()
    }
}

/** Which day the diary's widget looks back at, and what it calls it. */
object WidgetDay {
    data class Pick(val day: LocalDate, val label: String)

    private val BACK: List<Pair<String, (LocalDate) -> LocalDate>> = listOf(
        "A year ago" to { d -> d.minusYears(1) },
        "Two years ago" to { d -> d.minusYears(2) },
        "Three years ago" to { d -> d.minusYears(3) },
        "Six months ago" to { d -> d.minusMonths(6) },
        "Three months ago" to { d -> d.minusMonths(3) },
        "One month ago" to { d -> d.minusMonths(1) },
        "A week ago" to { d -> d.minusWeeks(1) },
    )

    /**
     * The day to show on [today] out of the [played] days: the first of a year
     * ago, two and three years, six months, three months, a month and a week
     * that was played, to the day ("One month ago today") or within three days
     * of it ("About a month ago"); else a day from the past, the same one all
     * day, under its date alone. A day in [written] wins over one that is not,
     * and today is never shown. `minusMonths` already takes a month's last day
     * for a date the earlier month lacks.
     */
    fun pick(today: LocalDate, played: Set<LocalDate>, written: Set<LocalDate> = emptySet()): Pick? {
        val past = played.filter { it < today }
        if (past.isEmpty()) return null
        val exact = BACK.mapNotNull { (label, back) -> back(today).takeIf { it in played && it < today }?.let { Pick(it, "$label today") } }
        (exact.firstOrNull { it.day in written } ?: exact.firstOrNull())?.let { return it }
        val near = BACK.mapNotNull { (label, back) ->
            val target = back(today)
            val close = past.filter { kotlin.math.abs(it.toEpochDay() - target.toEpochDay()) <= 3 }
            val day = close.filter { it in written }.minByOrNull { kotlin.math.abs(it.toEpochDay() - target.toEpochDay()) }
                ?: close.minByOrNull { kotlin.math.abs(it.toEpochDay() - target.toEpochDay()) }
            day?.let { Pick(it, "About " + label.replaceFirstChar(Char::lowercase).replace("one month", "a month")) }
        }
        (near.firstOrNull { it.day in written } ?: near.firstOrNull())?.let { return it }
        val pool = past.filter { it in written }.ifEmpty { past }.sorted()
        val day = pool[Math.floorMod(today.toEpochDay() * 31, pool.size.toLong()).toInt()]
        return Pick(day, date(day))
    }

    /** "19 November 2026". */
    fun date(day: LocalDate): String = "${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${day.year}"
}
