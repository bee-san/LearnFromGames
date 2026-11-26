// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.beesan.lfg.App
import io.github.beesan.lfg.core.Updates
import java.util.concurrent.TimeUnit

/**
 * Once a day, asks GitHub whether there is a newer LFG and fetches its APK ([Updates.check],
 * [Updates.download]), so it is there to install the next time the app is opened. It never
 * installs: that ends LFG, and with it the reading of a game, so it waits for a tap.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        // The daily look asks GitHub first; a fetch that was asked for already knows what to get.
        if (inputData.getBoolean(LOOK, false)) {
            if (!app.settings.prefs.value.updateApp) return Result.success()
            if (Updates.check(app, force = true) != Updates.Checked.NEWER) return Result.success()
        }
        return if (Updates.download(app) || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "app-updates"
        private const val NOW = "app-updates-now"
        private const val LOOK = "look"

        private fun network(wifiOnly: Boolean) = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        /** Starts, changes or stops the daily look. */
        fun schedule(context: Context, on: Boolean, wifiOnly: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(network(wifiOnly)).setRequiresBatteryNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInputData(Data.Builder().putBoolean(LOOK, true).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Fetches the newer release [Updates.state] already knows of, when there is one to fetch.
         * [asked] is a tap, which goes ahead on any network and in place of a fetch still waiting for Wi-Fi.
         */
        fun fetch(context: Context, wifiOnly: Boolean, asked: Boolean = false) {
            if ((Updates.state.value as? Updates.State.Available)?.release?.apk != true) return
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network(wifiOnly && !asked)).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, if (asked) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
