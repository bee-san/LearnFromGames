// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Bitmap

/** A screen's pixels read where Android keeps them (lfg_pixels.cpp), without a copy into Java. */
object Pixels {
    init {
        System.loadLibrary("lfg")
    }

    /** A fingerprint of every [step]th row of an ARGB_8888 [bitmap]; 0 when it cannot be read. */
    fun rowsHash(bitmap: Bitmap, step: Int): Long = nativeRowsHash(bitmap, step)

    /**
     * A still lossy WebP, or null. [method] trades size for speed, 0 to 6: Android's own
     * Bitmap.compress always takes the slowest.
     */
    fun webp(bitmap: Bitmap, quality: Int, method: Int = FAST): ByteArray? = nativeEncodeWebp(bitmap, quality, method)

    /** On 40 of the OCR bench's 1080p game screens (PixelsBench): 127 ms and 128 KB a screen, against 337 ms and 123 KB for Bitmap.compress. */
    const val FAST = 2

    @JvmStatic
    private external fun nativeRowsHash(bitmap: Bitmap, step: Int): Long

    @JvmStatic
    private external fun nativeEncodeWebp(bitmap: Bitmap, quality: Int, method: Int): ByteArray?
}
