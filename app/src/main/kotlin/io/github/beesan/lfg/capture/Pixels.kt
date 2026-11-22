// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Bitmap
import android.util.Log

/** A screen's pixels read where Android keeps them (lfg_pixels.cpp), without a copy into Java. */
object Pixels {
    init {
        System.loadLibrary("lfg")
    }

    /** A fingerprint of every [step]th row of an ARGB_8888 [bitmap]; 0 when it cannot be read. */
    fun rowsHash(bitmap: Bitmap, step: Int): Long = nativeRowsHash(bitmap, step)

    /**
     * [bitmap]'s pixels as they are stored, each 0xAARRGGBB, written into
     * [out], which must hold them all. For an opaque sRGB picture that is
     * what [Bitmap.getPixels] gives; a wide-gamut screen's colours are left
     * as the screen has them, where getPixels converts each to sRGB, which no
     * reader of text needs. False when the picture cannot be read this way:
     * the caller then asks getPixels.
     *
     * The first picture of a run is read both ways and what was found is
     * logged: how long each took and whether they agree.
     */
    fun argb(bitmap: Bitmap, out: IntArray): Boolean {
        if (bitmap.config != Bitmap.Config.ARGB_8888) return false
        val started = System.nanoTime()
        val kind = nativeArgb(bitmap, out)
        if (kind == 0) return false
        if (!compared) {
            compared = true
            val took = System.nanoTime() - started
            val proof = IntArray(bitmap.width * bitmap.height)
            val before = System.nanoTime()
            bitmap.getPixels(proof, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val slow = System.nanoTime() - before
            val differ = proof.indices.count { proof[it] != out[it] }
            Log.i(
                TAG,
                "%d x %d, %s, %s: read here in %.1f ms, by getPixels in %.1f ms, %d pixels differ".format(
                    bitmap.width, bitmap.height, bitmap.colorSpace?.name, if (kind == 1) "opaque" else "see-through somewhere",
                    took / 1e6, slow / 1e6, differ,
                ),
            )
        }
        return true
    }

    private const val TAG = "LfgPixels"

    @Volatile
    private var compared = false

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
    private external fun nativeArgb(bitmap: Bitmap, out: IntArray): Int

    @JvmStatic
    private external fun nativeEncodeWebp(bitmap: Bitmap, quality: Int, method: Int): ByteArray?
}
