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
     * [bitmap]'s pixels as [Bitmap.getPixels] gives them, written into [out],
     * which must hold them all. False when this cannot give exactly what
     * getPixels would (the picture is not ARGB_8888 in sRGB, or is see-through
     * somewhere): the caller then asks getPixels. The first picture of a run
     * is read both ways and compared, and if the two ever differed this would
     * stand aside for good.
     */
    fun argb(bitmap: Bitmap, out: IntArray): Boolean {
        if (!trusted || bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.colorSpace?.isSrgb == false) return false
        if (!nativeArgb(bitmap, out)) return false
        if (!checked) {
            checked = true
            val proof = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(proof, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            trusted = (0 until proof.size).all { proof[it] == out[it] }
            if (trusted) Log.i(TAG, "pixels read natively are those getPixels gives") else Log.w(TAG, "pixels read natively differ from getPixels: getPixels is used")
        }
        return trusted
    }

    private const val TAG = "LfgPixels"

    @Volatile
    private var checked = false

    @Volatile
    private var trusted = true

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
    private external fun nativeArgb(bitmap: Bitmap, out: IntArray): Boolean

    @JvmStatic
    private external fun nativeEncodeWebp(bitmap: Bitmap, quality: Int, method: Int): ByteArray?
}
