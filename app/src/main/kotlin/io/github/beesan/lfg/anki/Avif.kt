// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Still AVIF for card pictures, through libavif and libaom (liblfg_avif.so): Android can read AVIF but
 * not write it. Often a fifth smaller than the screen's own WebP, and a third the size of a JPEG, at the
 * same look. Its own library, loaded the first time a card's picture is written.
 */
object Avif {
    /** libavif's 0 to 100. On game screens 65 scores as the WebP frame it is made from (SSIM, PSNR), text included. */
    private const val QUALITY = 65

    /** libavif's 0 (slowest) to 10. 8: about 0.2 s for a 1080p screen on a laptop's four threads; 7 is under 1 % smaller and half as fast. */
    private const val SPEED = 8

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("lfg_avif")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "no AVIF encoder", e)
            false
        }
    }

    /**
     * Writes [bitmap] to [file] as a still AVIF. False when it could not (no encoder, a picture it cannot
     * read, a failed write), and then nothing is left at [file]. Never throws.
     */
    fun encode(bitmap: Bitmap, file: File): Boolean {
        if (!loaded) return false
        return try {
            // The encoder reads ARGB_8888 only; a picture held otherwise (hardware, wide colour) is copied first.
            val pixels = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                file.parentFile?.mkdirs()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                val result = nativeWrite(pixels, file.path.toByteArray(), QUALITY, SPEED, threads)
                if (result != 0) Log.w(TAG, "AVIF not written: $result")
                result == 0
            } finally {
                if (pixels !== bitmap) pixels.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AVIF not written", e)
            file.delete()
            false
        }
    }

    private const val TAG = "LfgAvif"

    @JvmStatic
    private external fun nativeWrite(bitmap: Bitmap, path: ByteArray, quality: Int, speed: Int, threads: Int): Int
}
