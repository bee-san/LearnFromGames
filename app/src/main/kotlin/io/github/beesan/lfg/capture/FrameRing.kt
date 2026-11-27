// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import io.github.beesan.lfg.core.BlackBars
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.ocr.Frame
import java.io.File

/**
 * The last few pictures of the game, small, for the animated picture on a
 * card: the line appearing, as it happened. They are the same pictures the
 * reader is given, so nothing of the app's own is ever in them. Nothing is kept
 * unless the animated picture is switched on.
 */
object FrameRing {
    @Volatile
    var enabled = false

    /** How long after a line began to appear its moving picture may run. */

    private const val AFTER_MS = 10_000L


    private const val FRAMES = 14
    private const val MAX_WIDTH = 480

    private class Small(val width: Int, val height: Int, val argb: IntArray, val atMs: Long)

    private val frames = ArrayDeque<Small>()

    @Synchronized
    fun offer(frame: Frame, atMs: Long) {
        if (!enabled) {
            frames.clear()
            return
        }
        val factor = ((frame.width + MAX_WIDTH - 1) / MAX_WIDTH).coerceAtLeast(1)
        val w = frame.width / factor
        val h = frame.height / factor
        if (w < 2 || h < 2) return
        frames.lastOrNull()?.let { if (it.width != w || it.height != h) frames.clear() }
        // Every factor-th pixel: these are thumbnails of flat game art, and this runs three times a second.
        val small = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * factor * frame.width
            for (x in 0 until w) small[y * w + x] = frame.argb[row + x * factor]
        }
        // A picture the same as the last adds nothing: a line that sits on screen must not push out how it arrived.
        if (frames.lastOrNull()?.argb?.contentEquals(small) == true) return
        frames.addLast(Small(w, h, small, atMs))
        while (frames.size > FRAMES) frames.removeFirst()
    }

    /**
     * An animated WebP of the frames from a second and a half before [fromMs] to [AFTER_MS] after it, or
     * null when there are too few to move or the encoder fails. With no [fromMs], or none of its frames
     * held any more (a line opened from the lines' page, a card that waited), there is none: what is on
     * screen now is another moment, and the line's still is used.
     */
    @Synchronized
    fun animation(fromMs: Long?, dir: File, name: String, trimBars: Boolean = false): File? {
        if (fromMs == null) return null
        val chosen = frames.filter { it.atMs >= fromMs - 1500 && it.atMs <= fromMs + AFTER_MS }
        // One picture does not move; the still is better than that.
        if (chosen.size < 2) return null
        val durations = IntArray(chosen.size) { i ->
            if (i + 1 < chosen.size) (chosen[i + 1].atMs - chosen[i].atMs).toInt().coerceIn(40, 1000) else 1500
        }
        // The bars are where the last picture, with the whole line on it, has them.
        val last = chosen.last()
        val whole = Box(0, 0, last.width, last.height)
        val box = if (trimBars) BlackBars.inside(last.argb, last.width, last.height) else whole
        val bytes = try {
            AnimatedWebp.encode(
                Array(chosen.size) { if (box == whole) chosen[it].argb else BlackBars.crop(chosen[it].argb, last.width, box) },
                box.width, box.height, durations, 70,
            )
        } catch (_: Throwable) {
            null
        } ?: return null
        dir.mkdirs()
        return File(dir, "$name.webp").apply { writeBytes(bytes) }
    }
}

/** libwebp's animation encoder: Android can write a still WebP by itself, but not a moving one. */
object AnimatedWebp {
    init {
        System.loadLibrary("lfg")
    }

    /** Frames of packed ARGB, all [width] x [height]; each shown for its duration in milliseconds. */
    fun encode(frames: Array<IntArray>, width: Int, height: Int, durationsMs: IntArray, quality: Int): ByteArray? =
        nativeEncode(frames, width, height, durationsMs, quality)

    private external fun nativeEncode(frames: Array<IntArray>, width: Int, height: Int, durationsMs: IntArray, quality: Int): ByteArray?
}
