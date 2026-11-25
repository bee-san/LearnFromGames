// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import io.github.beesan.lfg.App
import io.github.beesan.lfg.R
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.WidgetDay
import io.github.beesan.lfg.data.DiaryPage
import io.github.beesan.lfg.ui.MainActivity
import io.github.beesan.lfg.ui.Postcard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * A day from the diary on the home screen: "One month ago today", with the
 * day's picture and the entry written on it, or the line the picture is of
 * when none was. It changes once a day ([WidgetDay]); a press opens the day.
 */
class DiaryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    private fun refresh(context: Context) {
        val app = context.applicationContext as App
        val result = goAsync()
        app.scope.launch {
            try {
                update(app)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        suspend fun update(app: App) {
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, DiaryWidget::class.java))
            if (ids.isEmpty()) return
            val shown = withContext(Dispatchers.Default) { pick(app) }
            if (shown == null) {
                val views = RemoteViews(app.packageName, R.layout.widget_diary).apply {
                    setViewVisibility(R.id.diary_picture, View.GONE)
                    setViewVisibility(R.id.diary_empty, View.VISIBLE)
                    setOnClickPendingIntent(android.R.id.background, open(app, null))
                }
                ids.forEach { manager.updateAppWidget(it, views) }
                return
            }
            val (page, label) = shown
            val picture = withContext(Dispatchers.IO) { Postcard.decode(page.entries.lastOrNull { it.picturePath != null }?.picturePath ?: page.picture, 1000) }
            val entry = page.entries.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
            val words = Postcard.Words(
                label = label,
                // With no entry, the line the picture is of stands in for one.
                line = if (entry.isEmpty()) page.lineText else "",
                entry = entry,
                meta = "${page.gameName} · ${WidgetDay.date(page.day)}",
            )
            val density = app.resources.displayMetrics.density
            val landscape = app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            for (id in ids) {
                // Drawn at the widget's own shape, so nothing written on it is cut off: a launcher gives
                // its width at its widest and its height at its shortest when the screen lies on its side.
                val options = manager.getAppWidgetOptions(id)
                val w = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
                val h = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 160
                // No larger than a widget's picture may be, whatever the screen.
                val fit = minOf(1f, 1000f / (w * density), 700f / (h * density))
                val scale = density * fit * 0.5f * (minOf(w, h) / 180f).coerceIn(0.6f, 1f)
                val views = RemoteViews(app.packageName, R.layout.widget_diary).apply {
                    setImageViewBitmap(R.id.diary_picture, Postcard.render(picture, words, (w * density * fit).toInt(), (h * density * fit).toInt(), scale, watermark = false))
                    setViewVisibility(R.id.diary_picture, View.VISIBLE)
                    setViewVisibility(R.id.diary_empty, View.GONE)
                    setOnClickPendingIntent(android.R.id.background, open(app, page))
                }
                manager.updateAppWidget(id, views)
            }
        }

        /** The day to show and what to call it: on its date, a day with an entry first, then the game read most. */
        private suspend fun pick(app: App): Pair<DiaryPage, String>? {
            val pages = app.diary.books().flatMap { it.pages }
            val today = ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, ZoneId.systemDefault())
            val pick = WidgetDay.pick(today, pages.map { it.day }.toSet(), pages.filter { it.entries.isNotEmpty() }.map { it.day }.toSet()) ?: return null
            val page = pages.filter { it.day == pick.day }.maxWithOrNull(compareBy<DiaryPage> { it.entries.size.coerceAtMost(1) }.thenBy { it.seconds }) ?: return null
            return page to pick.label
        }

        private fun open(context: Context, page: DiaryPage?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_DIARY_DAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (page != null) intent.putExtra(MainActivity.EXTRA_GAME, page.gameId).putExtra(MainActivity.EXTRA_DAY, page.day.toEpochDay())
            return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
