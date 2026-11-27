// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.backup.BackupWorker
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.widget.DiaryWidget
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Writes the diary's days once each is over, a little after the day turns at
 * 04:00. It asks for a game's recap, whose answer tells each day too
 * (ask/Recapper.kt), so only the lines read since the last recap are sent,
 * and at most one request goes for a game a day. Opening the diary never
 * sends anything.
 */
class DiaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val prefs = app.settings.prefs.value
        DiaryWidget.update(app)
        if (prefs.diaryWrite != Prefs.DIARY_EACH_DAY || !prefs.aiReady) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone)
        val written = app.database.diary().all().filter { it.writtenAt > 0 }.associateBy { it.gameId to it.day }
        for (game in app.database.games().allNow()) {
            // Games only: what is read in a browser or a chat is not a story to tell.
            if (game.app) continue
            val lines = app.database.lines().ofGame(game.id)
            val last = app.database.recaps().latest(game.id)
            val fresh = lines.size - (last?.let { io.github.beesan.lfg.ask.Recapper.start(lines, it, app.database.lines().byId(it.lastLineId)) } ?: 0)
            if (!due(lines.map { it.timestamp }, fresh, today, zone) { day, end -> (written[game.id to day.toEpochDay()]?.writtenAt ?: 0) >= end }) continue
            try {
                app.recapper.write(game.id, game.title)
            } catch (_: OpenRouter.Failure) {
                // No network, or the key ran out: the day waits for tomorrow, or for a recap.
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "diary"

        /**
         * Whether a game with lines at [stamps] (seconds, in order), [fresh] of
         * them read since its last recap, has a day to write: one that is
         * over, of [Recapper.DAY_LINES] lines or more, not written since its
         * last line ([written], given the day and that line's time in
         * milliseconds). A backlog of more than one request's lines waits for
         * Recap's own Write, where it is asked for.
         */
        fun due(stamps: List<Double>, fresh: Int, today: LocalDate, zone: ZoneId, written: (LocalDate, Long) -> Boolean): Boolean {
            if (fresh <= 0 || fresh > Recapper.CHUNK) return false
            return stamps.takeLast(fresh).groupBy { ReadingStats.dayOf(it, zone) }.any { (day, read) ->
                val all = stamps.count { ReadingStats.dayOf(it, zone) == day }
                day < today && all >= Recapper.DAY_LINES && !written(day, (read.last() * 1000).toLong())
            }
        }

        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<DiaryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(BackupWorker.untilFour(ZonedDateTime.now()).toMillis() + 10 * 60_000, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
