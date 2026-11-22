// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.beesan.lfg.App
import java.util.concurrent.TimeUnit

/**
 * Once a day, asks each dictionary's publisher whether it has another release and installs it
 * ([DictionaryRepository.updateAll]): Jitendex and JMdict change every few weeks. Asking is a few
 * kilobytes; a release is fetched on the network the first run's downloads were allowed.
 */
class DictionaryUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as App).dictionaries.updateAll()
        return Result.success()
    }

    companion object {
        private const val NAME = "dictionary-updates"
        private const val NOW = "dictionary-updates-now"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Starts, changes or stops the daily look. */
        fun schedule(context: Context, on: Boolean, wifiOnly: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<DictionaryUpdateWorker>(1, TimeUnit.DAYS).setConstraints(constraints(wifiOnly)).build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** "Check now": on any network, since it was asked for. */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<DictionaryUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
