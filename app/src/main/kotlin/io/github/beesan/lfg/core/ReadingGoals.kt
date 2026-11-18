// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.time.LocalDate

/**
 * What a day of reading aims for: [minutes] of reading time and [chars]
 * characters, each 0 for no goal. Both count as the stats screen counts, by
 * [ReadingStats]' rule, and a day turns over at 04:00 as its days do.
 */
data class ReadingGoals(val minutes: Int = 0, val chars: Int = 0) {
    val any: Boolean get() = minutes > 0 || chars > 0

    fun timeMet(day: ReadingStats.DayTotals) = minutes > 0 && day.seconds >= minutes * 60.0

    fun charsMet(day: ReadingStats.DayTotals) = chars > 0 && day.chars >= chars

    /** Every goal that is set was met; never when none is. */
    fun met(day: ReadingStats.DayTotals) = any && (minutes == 0 || timeMet(day)) && (chars == 0 || charsMet(day))

    data class Progress(
        val today: ReadingStats.DayTotals,
        /** The days from the period's first through today that met the time goal, and the characters goal. */
        val timeDays: Int,
        val charDays: Int,
        /** Runs of days that met every goal set, over all of history as the reading streak is. */
        val streaks: ReadingStats.Streaks,
    )

    /** `days` as [ReadingStats.byDay] gives them, in any order. */
    fun progress(days: List<ReadingStats.DayTotals>, from: LocalDate, today: LocalDate): Progress {
        val period = days.filter { it.day >= from && it.day <= today }
        return Progress(
            today = days.firstOrNull { it.day == today } ?: ReadingStats.DayTotals(today, 0, 0, 0, 0.0),
            timeDays = period.count(::timeMet),
            charDays = period.count(::charsMet),
            streaks = ReadingStats.streaks(days.filter(::met).map { it.day }, today),
        )
    }

    companion object {
        /** The goals a step of − or + moves between; 0 is off. */
        val MINUTE_STEPS = listOf(0, 15, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480)
        val CHAR_STEPS = listOf(0, 500, 1_000, 2_000, 3_000, 4_000, 5_000, 7_500, 10_000, 15_000, 20_000, 25_000, 30_000, 40_000, 50_000, 75_000, 100_000)

        /** The step after [current] up or down, from a value on the list or between two of them; it stops at either end. */
        fun step(steps: List<Int>, current: Int, up: Boolean): Int =
            if (up) steps.firstOrNull { it > current } ?: steps.last() else steps.lastOrNull { it < current } ?: steps.first()
    }
}
