// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.widget

import io.github.beesan.lfg.core.ReadingGoals
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.StatsPeriod
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What the home screen widgets show, worked out from the stats as the Stats screen counts them. */
data class WidgetData(
    val today: ReadingStats.DayTotals,
    val goals: ReadingGoals,
    /** The reading streak, as the Stats screen's. */
    val streak: Int,
    val week: StatsPeriod.View,
) {
    /** The week is charted in characters only when that is the one goal set; otherwise in time. */
    val weekInChars: Boolean get() = goals.chars > 0 && goals.minutes == 0

    /** A day's bar: its height out of [LEVELS], whether it met the goal it is charted against, and its day. */
    data class Bar(val level: Int, val met: Boolean, val letter: String, val today: Boolean)

    data class Chart(val bars: List<Bar>, /** The goal's height out of [LEVELS], or null for none. */ val goal: Int?)

    /** The tallest bar or the goal, whichever is higher, reaches nine tenths of the chart, so the goal line never sits on its edge. */
    fun chart(locale: Locale = Locale.getDefault()): Chart {
        val value = { b: StatsPeriod.Bar -> if (weekInChars) b.chars.toDouble() else b.seconds / 3600 }
        val goal = if (weekInChars) goals.chars.toDouble() else goals.minutes / 60.0
        val top = maxOf(week.bars.maxOf(value), goal) / 0.9
        val level = { v: Double -> if (top <= 0) 0 else (v / top * LEVELS).roundToInt() }
        val bars = week.bars.mapIndexed { i, b ->
            val v = value(b)
            Bar(
                // A day with any reading shows at least a dot.
                level = if (v > 0) maxOf(level(v), MIN_LEVEL) else 0,
                met = goal > 0 && v >= goal,
                letter = b.first.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                today = i == week.bars.lastIndex,
            )
        }
        return Chart(bars, if (goal > 0) level(goal) else null)
    }

    /** Today's share of each goal set, time first: what the rings draw. */
    val rings: List<Pair<Float, Ring>>
        get() = listOfNotNull(
            if (goals.minutes > 0) (today.seconds / (goals.minutes * 60)).toFloat() to Ring.TIME else null,
            if (goals.chars > 0) today.chars.toFloat() / goals.chars to Ring.CHARS else null,
        )

    enum class Ring { TIME, CHARS }

    companion object {
        const val LEVELS = 1000
        const val MIN_LEVEL = 40

        /** `days` as [ReadingStats.byDay] gives them. */
        fun of(days: List<ReadingStats.DayTotals>, goals: ReadingGoals, today: LocalDate) = WidgetData(
            today = days.firstOrNull { it.day == today } ?: ReadingStats.DayTotals(today, 0, 0, 0, 0.0),
            goals = goals,
            streak = ReadingStats.streaks(days.map { it.day }, today).current,
            week = StatsPeriod.DAYS_7.view(days, today),
        )

        /** "42 min", "1 h 5 min": the figure and unit pairs, so the units can be drawn smaller. */
        fun timeParts(seconds: Double): List<Pair<String, String>> {
            val minutes = (seconds / 60).toLong()
            return if (minutes >= 60) listOf("${minutes / 60}" to "h", "${minutes % 60}" to "min") else listOf("$minutes" to "min")
        }

        /** A goal's time: "45 min", "1 h", "1 h 30 min". */
        fun goalTime(minutes: Int) = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
