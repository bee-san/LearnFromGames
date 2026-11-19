// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The automatic backup, daily or weekly at about 04:00 (the stats' day turn,
 * when no one is reading), into the folder Settings picked, with the lines
 * file for GameSentenceMiner when that is on. Android may run it a little
 * later, to save battery; it runs on the next chance if the phone was off.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val prefs = app.settings.prefs.value
        if (prefs.backupEvery == Prefs.BACKUP_OFF || prefs.backupFolder.isEmpty()) return Result.success()
        return try {
            val size = Backup.toFolder(app, app.database, prefs)
            app.settings.update { it.copy(backupLast = System.currentTimeMillis(), backupLastSize = size) }
            Result.success()
        } catch (_: Exception) {
            // The folder was gone, or full: tried again later, as WorkManager backs off.
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "backup"

        /** Starts, changes or stops the automatic backup for [every] ([Prefs.BACKUP_OFF] and the others). */
        fun schedule(context: Context, every: String) {
            val work = WorkManager.getInstance(context)
            if (every == Prefs.BACKUP_OFF) {
                work.cancelUniqueWork(NAME)
                return
            }
            val days = if (every == Prefs.BACKUP_WEEKLY) 7L else 1L
            val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                .setInitialDelay(untilFour(ZonedDateTime.now()).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** How long from [now] to the next 04:00. */
        fun untilFour(now: ZonedDateTime): Duration {
            val zone: ZoneId = now.zone
            var next = ZonedDateTime.of(LocalDate.from(now), LocalTime.of(4, 0), zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }
}
