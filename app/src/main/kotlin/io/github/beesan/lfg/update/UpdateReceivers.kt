// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import io.github.beesan.lfg.core.Updates

/**
 * Hears from Android how an install [Updates.install] began is going. The first time, Android
 * wants the person's yes: its question is opened. A failure is passed back for the screens to show.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Updates.installEnded(
                context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            )
            return
        }
        val question = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        try {
            context.startActivity(checkNotNull(question).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No question came, or nothing on this device shows it: the browser can still get the release.
            Log.w("LfgUpdates", "Android's question could not be opened", e)
            Updates.installEnded(context, PackageInstaller.STATUS_FAILURE, e.message)
        }
    }
}

/**
 * LFG was replaced by another build. The downloaded APK has done its work and is deleted; when
 * LFG installed that build itself, and may post notifications, one quiet notification says so.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Updates.updated(context)) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("LFG ${Updates.installed(context)} is installed")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION, notification)
    }

    private companion object {
        const val CHANNEL = "updates"

        /** Sentence audio's notification is 1. */
        const val NOTIFICATION = 2
    }
}
