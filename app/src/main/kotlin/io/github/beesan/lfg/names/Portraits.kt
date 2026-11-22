// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A character's portrait from VNDB's image server, fitted inside 160 by 200 as image_handler.rs does, as a JPEG. */
object Portraits {
    private const val MAX_WIDTH = 160
    private const val MAX_HEIGHT = 200
    private const val MAX_BYTES = 4 shl 20

    /** The portrait at [url], or null when it cannot be had: a card without its picture is still a card. */
    fun fetch(url: String): Portrait? = try {
        if (!url.startsWith("https://")) null else fit(download(url))
    } catch (_: Exception) {
        null
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LearnFromGames")
        }
        try {
            if (connection.responseCode != 200) error("The portrait answered ${connection.responseCode}.")
            return connection.inputStream.use { it.readNBytes(MAX_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    fun fit(bytes: ByteArray): Portrait? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH && bounds.outHeight / (sample * 2) >= MAX_HEIGHT) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, MAX_WIDTH.toFloat() / decoded.width, MAX_HEIGHT.toFloat() / decoded.height)
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        val fitted = if (width == decoded.width && height == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        val out = ByteArrayOutputStream()
        fitted.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (fitted !== decoded) fitted.recycle()
        decoded.recycle()
        return Portrait(out.toByteArray(), "jpg", width, height)
    }
}
