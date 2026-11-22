// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.beesan.lfg.App
import java.util.concurrent.TimeUnit

/** Builds one visual novel's character names in the background, so it carries on while the game is played. */
class CharacterNamesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val vnId = inputData.getString(KEY_VN) ?: return Result.failure()
        val app = applicationContext as App
        val error = app.characterNames.runBuild(vnId) ?: return Result.success()
        // VNDB busy or the network gone: tried again a few times, then left for the game's page.
        return if (runAttemptCount < 2 && ("busy" in error || "connection" in error.lowercase())) Result.retry() else Result.failure()
    }

    companion object {
        private const val KEY_VN = "vn"
        private fun name(vnId: String) = "character-names-$vnId"

        fun start(context: Context, vnId: String, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<CharacterNamesWorker>()
                .setInputData(workDataOf(KEY_VN to vnId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(vnId), ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context, vnId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(vnId))
        }
    }
}
