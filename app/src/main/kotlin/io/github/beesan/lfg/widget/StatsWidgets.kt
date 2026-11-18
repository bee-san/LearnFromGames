// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.beesan.lfg.App
import io.github.beesan.lfg.R
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Today against the goals, as rings. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = StatsWidgets.refresh(this, context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = StatsWidgets.refresh(this, context)
}

/** The last seven days, a bar each, against the goal. */
class WeekWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = StatsWidgets.refresh(this, context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = StatsWidgets.refresh(this, context)
}

/**
 * Draws both widgets from the saved lines. They redraw when Android asks
 * (placed, resized, every half hour, which also carries them over the 04:00
 * day turn), when a goal changes, and half a minute after a line is saved,
 * at most once in that half minute; nothing is done while none is placed.
 */
object StatsWidgets {
    private const val SETTLE_MS = 30_000L
    private const val TEXT = 0xFFE8E5EB.toInt()
    private const val DIM = 0xFFAAA8B2.toInt()
    private const val ACCENT = 0xFFB1A5EE.toInt()
    private const val OK = 0xFF8BB79B.toInt()
    /** Each layout and the smallest size, in dp, it is drawn at; Android picks the largest that fits. */
    private val TODAY_SQUARE = SizeF(100f, 100f)
    private val TODAY_WIDE = SizeF(230f, 100f)
    private val WEEK_COMPACT = SizeF(170f, 80f)
    private val WEEK_FULL = SizeF(170f, 150f)
    private const val RING_PX = 320

    private val BARS = intArrayOf(R.id.bar0, R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4, R.id.bar5, R.id.bar6)
    private val DAYS = intArrayOf(R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6)

    private var pending: Job? = null

    /** A line was saved. */
    fun requestUpdate(app: App) = synchronized(this) {
        if (pending?.isActive == true || !placed(app)) return
        pending = app.scope.launch {
            delay(SETTLE_MS)
            update(app)
        }
    }

    /** Android asked a widget to redraw: done in the background, inside the receiver's time. */
    fun refresh(receiver: BroadcastReceiver, context: Context) {
        val app = context.applicationContext as App
        val result = receiver.goAsync()
        app.scope.launch {
            try {
                update(app)
            } finally {
                result.finish()
            }
        }
    }

    private fun ids(app: Context, provider: Class<*>): IntArray =
        AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, provider))

    private fun placed(app: Context) = ids(app, TodayWidget::class.java).isNotEmpty() || ids(app, WeekWidget::class.java).isNotEmpty()

    suspend fun update(app: App) {
        val today = ids(app, TodayWidget::class.java)
        val week = ids(app, WeekWidget::class.java)
        if (today.isEmpty() && week.isEmpty()) return
        val data = withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            val stamps = app.database.lines().stamps().first()
            val days = ReadingStats.byDay(stamps.map { ReadingStats.Stamp(it.timestamp, ReadingStats.characters(it.text), it.gameId) }, zone)
            WidgetData.of(days, app.settings.prefs.value.goals, ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone))
        }
        val manager = AppWidgetManager.getInstance(app)
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_STATS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (today.isNotEmpty()) {
            val rings = data.rings.takeIf { it.isNotEmpty() }?.let { r -> rings(r.map { (f, ring) -> f to if (ring == WidgetData.Ring.TIME) ACCENT else OK }) }
            val views = RemoteViews(mapOf(TODAY_SQUARE to todayViews(app, data, rings, wide = false, open), TODAY_WIDE to todayViews(app, data, rings, wide = true, open)))
            today.forEach { manager.updateAppWidget(it, views) }
        }
        if (week.isNotEmpty()) {
            val views = RemoteViews(mapOf(WEEK_COMPACT to weekViews(app, data, compact = true, open), WEEK_FULL to weekViews(app, data, compact = false, open)))
            week.forEach { manager.updateAppWidget(it, views) }
        }
    }

    // ── Today ────────────────────────────────────────────────────────────────

    private fun todayViews(context: Context, data: WidgetData, rings: Bitmap?, wide: Boolean, open: PendingIntent): RemoteViews {
        val v = RemoteViews(context.packageName, if (wide) R.layout.widget_today_wide else R.layout.widget_today)
        val t = data.today
        val g = data.goals
        v.setOnClickPendingIntent(android.R.id.background, open)
        if (rings != null) {
            v.setImageViewBitmap(R.id.today_rings, rings)
            v.setViewVisibility(R.id.today_rings, View.VISIBLE)
        } else {
            v.setViewVisibility(R.id.today_rings, View.GONE)
        }
        // Square, the title only takes the rings' place when there are none; wide, it heads the figures.
        v.setViewVisibility(R.id.today_title, if (wide || rings == null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.today_streak, streak(data.streak))

        val time = figure(WidgetData.timeParts(t.seconds), if (g.minutes > 0) "of " + WidgetData.goalTime(g.minutes) else "", g.timeMet(t))
        v.setTextViewText(R.id.today_time, time)
        v.setTextColor(R.id.today_time, if (g.minutes > 0) ACCENT else TEXT)
        val chars = figure(listOf("%,d".format(t.chars) to ""), (if (g.chars > 0) "of " + "%,d".format(g.chars) + " " else "") + "chars", g.charsMet(t))
        v.setTextViewText(R.id.today_chars, chars)
        v.setTextColor(R.id.today_chars, if (g.chars > 0) OK else TEXT)
        if (wide) {
            val speed = ReadingStats.perHour(t.timedChars, t.seconds)
            v.setTextViewText(
                R.id.today_detail,
                when {
                    speed > 0 -> "%,d an hour".format(speed)
                    t.lines > 0 -> if (t.lines == 1) "1 line" else "%,d lines".format(t.lines)
                    g.any -> "Nothing read yet today"
                    else -> "Set daily goals in Stats"
                },
            )
        }
        v.setContentDescription(android.R.id.background, "Today: $time, $chars")
        return v
    }

    /**
     * Rings from the outside in, each a [fraction] of the way round from the
     * top. Past a full turn the ring goes on round over itself, its end shadowed
     * so the overlap shows, up to twice round.
     */
    private fun rings(rings: List<Pair<Float, Int>>): Bitmap {
        val size = RING_PX.toFloat()
        val bitmap = Bitmap.createBitmap(RING_PX, RING_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = size * if (rings.size == 1) 0.14f else 0.12f
        val gap = size * 0.03f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val c = size / 2
        var radius = c - stroke / 2 - 2
        for ((fraction, color) in rings) {
            val oval = RectF(c - radius, c - radius, c + radius, c + radius)
            paint.clearShadowLayer()
            paint.color = color and 0x00FFFFFF or (0x30 shl 24)
            canvas.drawCircle(c, c, radius, paint)
            paint.color = color
            val turn = fraction.coerceIn(0f, 2f)
            when {
                turn >= 1f -> {
                    canvas.drawCircle(c, c, radius, paint)
                    if (turn > 1f) {
                        paint.setShadowLayer(stroke * 0.2f, 0f, 0f, 0x99000000.toInt())
                        canvas.drawArc(oval, -90f, 360f * (turn - 1f), false, paint)
                    }
                }
                turn > 0f -> canvas.drawArc(oval, -90f, 360f * turn, false, paint)
            }
            radius -= stroke + gap
        }
        return bitmap
    }

    // ── Last 7 days ──────────────────────────────────────────────────────────

    private fun weekViews(context: Context, data: WidgetData, compact: Boolean, open: PendingIntent): RemoteViews {
        val v = RemoteViews(context.packageName, if (compact) R.layout.widget_week_compact else R.layout.widget_week)
        val w = data.week.totals
        v.setOnClickPendingIntent(android.R.id.background, open)
        val time = WidgetData.timeParts(w.seconds)
        val chars = listOf("%,d".format(w.chars) to "chars")
        val total = if (data.weekInChars) chars else time
        v.setTextViewText(R.id.week_total, figure(total, "", false))
        if (!compact) {
            v.setTextViewText(R.id.week_streak, streak(data.streak))
            val other = if (data.weekInChars) time.joinToString(" ") { (n, u) -> "$n $u" } else "%,d characters".format(w.chars)
            v.setTextViewText(
                R.id.week_detail,
                listOfNotNull(other, "%,d an hour".format(w.charsPerHour).takeIf { w.charsPerHour > 0 }).joinToString(" · "),
            )
        }
        val chart = data.chart()
        chart.bars.forEachIndexed { i, bar ->
            v.setProgressBar(BARS[i], WidgetData.LEVELS, bar.level, false)
            v.setColorStateList(BARS[i], "setProgressTintList", ColorStateList.valueOf(if (bar.met) OK else ACCENT))
            v.setTextViewText(DAYS[i], bar.letter)
            v.setTextColor(DAYS[i], if (bar.today) TEXT else DIM)
        }
        if (chart.goal != null) {
            v.setViewVisibility(R.id.week_goal, View.VISIBLE)
            v.setProgressBar(R.id.week_goal, WidgetData.LEVELS, chart.goal, false)
        } else {
            v.setViewVisibility(R.id.week_goal, View.GONE)
        }
        v.setContentDescription(
            android.R.id.background,
            "Last 7 days: " + time.joinToString(" ") { (n, u) -> "$n $u" } + ", " + "%,d characters".format(w.chars),
        )
        return v
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    /** Figures at full size and their units smaller, then [after] smaller and dimmer, then a tick once met. */
    private fun figure(parts: List<Pair<String, String>>, after: String, met: Boolean): CharSequence {
        val s = SpannableStringBuilder()
        parts.forEachIndexed { i, (n, unit) ->
            if (i > 0) s.append(' ')
            s.append(n)
            if (unit.isNotEmpty()) small(s, " $unit", null)
        }
        if (after.isNotEmpty()) small(s, " $after", DIM)
        if (met) small(s, "  ✓", null)
        return s
    }

    private fun small(s: SpannableStringBuilder, text: String, color: Int?) {
        val start = s.length
        s.append(text)
        s.setSpan(RelativeSizeSpan(0.62f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (color != null) s.setSpan(ForegroundColorSpan(color), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "Streak 4", the number brighter; nothing without one. */
    private fun streak(days: Int): CharSequence {
        if (days == 0) return ""
        val s = SpannableStringBuilder("Streak ")
        val start = s.length
        s.append(days.toString())
        s.setSpan(ForegroundColorSpan(TEXT), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}
