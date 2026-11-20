// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.beesan.lfg.App
import io.github.beesan.lfg.ocr.ScreenAi
import java.util.concurrent.TimeUnit

/** The first run's downloads, the starter dictionaries and ScreenAI's files: started without being asked, and carried on if the app is closed. */
class DictionaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        // The dictionaries first: with Jitendex in, the practice lookup works while the reader's files arrive.
        val dictionaries = app.dictionaries.ensureDefaults()
        val installed = (!ScreenAi.available() || app.models.ensureScreenAi()) and dictionaries
        return when {
            installed -> Result.success()
            runAttemptCount < 5 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val NAME = "first-dictionary"

        /** With [wifiOnly] the downloads wait for an unmetered network (about 180 MB in all). */
        fun start(context: Context, replace: Boolean = false, wifiOnly: Boolean = false) {
            val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<DictionaryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
