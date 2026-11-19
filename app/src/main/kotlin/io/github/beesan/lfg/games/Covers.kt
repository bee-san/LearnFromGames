// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import io.github.beesan.lfg.data.Game
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A picture for each game on the Stats screen. A Steam game has its store
 * header, fetched once from Steam's CDN and kept; any other game, or a Steam
 * game whose header has not come yet, has the icon of the app it is read in.
 */
object Covers {
    private const val TAG = "LfgCovers"
    private const val PREFS = "covers"
    private const val MAX_BYTES = 2_000_000

    fun headerUrl(appId: String) = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/header.jpg"

    private fun file(context: Context, appId: String) = File(context.filesDir, "covers/steam-$appId.jpg")

    /** Notes the Steam game behind a game's row. Returns true when its header is still to be fetched. */
    fun noteSteam(context: Context, gameId: String, appId: String): Boolean {
        if (appId.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(gameId, null) != appId) prefs.edit { putString(gameId, appId) }
        return !file(context, appId).exists()
    }

    /** Fetches a Steam header once; a failure is tried again the next time the game is seen. Not on the main thread. */
    fun fetchSteam(context: Context, appId: String) {
        val target = file(context, appId)
        if (target.exists()) return
        try {
            val connection = URL(headerUrl(appId)).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                if (connection.responseCode != 200) return
                val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES) }
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                part.writeBytes(bytes)
                part.renameTo(target)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No header for Steam app $appId: ${e.message}")
        }
    }

    /** The Steam header kept for [game], if there is one. Not on the main thread. */
    fun steamHeader(context: Context, game: Game): Bitmap? {
        val appId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(game.id, null) ?: return null
        val f = file(context, appId)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    /** The icon of the app [game] is read in, or null when that app is gone. */
    fun appIcon(context: Context, game: Game, size: Int): Bitmap? = try {
        val icon = context.packageManager.getApplicationIcon(game.packageName)
        createBitmap(size, size).also { bitmap ->
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
        }
    } catch (_: Exception) {
        null
    }
}
