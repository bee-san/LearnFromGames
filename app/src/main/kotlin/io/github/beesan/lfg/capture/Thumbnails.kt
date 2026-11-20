// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.util.Log
import java.nio.ByteOrder

/**
 * A fingerprint of a screenshot taken without copying it out of graphics
 * memory: the GPU shrinks it to a quarter of its width and height, and only
 * that small picture is read back. Copying a whole 1080p screen out costs
 * tens of milliseconds, and most looks find the screen as it was.
 *
 * Every pixel of the screen counts towards the small picture, so a change
 * anywhere moves its fingerprint. Used from one thread only.
 */
class Thumbnails : AutoCloseable {
    private var renderer: HardwareRenderer? = null
    private var reader: ImageReader? = null
    private val node = RenderNode("lfg-thumbnail")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var width = 0
    private var height = 0

    /** 0 when the picture cannot be read this way; the caller then copies it and fingerprints that. */
    fun hash(buffer: HardwareBuffer, colorSpace: ColorSpace?): Long = try {
        val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
        if (bitmap == null) 0L else hashOf(bitmap).also { bitmap.recycle() }
    } catch (e: Exception) {
        Log.w(TAG, "could not shrink a screenshot", e)
        close()
        0L
    }

    private fun hashOf(bitmap: Bitmap): Long {
        val w = (bitmap.width / SCALE).coerceAtLeast(1)
        val h = (bitmap.height / SCALE).coerceAtLeast(1)
        if (w != width || h != height || renderer == null) open(w, h)
        val canvas = node.beginRecording(w, h)
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, w, h), paint)
        } finally {
            node.endRecording()
        }
        renderer!!.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader!!.acquireLatestImage() ?: return 0L
        return try {
            val plane = image.planes[0]
            val ints = plane.buffer.order(ByteOrder.nativeOrder()).asIntBuffer()
            val stride = plane.rowStride / 4
            val row = IntArray(w)
            // FNV-1a over each row's pixels, not the padding after them.
            var hash = -0x340d631b7bdddcdbL
            for (y in 0 until h) {
                ints.position(y * stride)
                ints.get(row)
                for (p in row) hash = (hash xor (p.toLong() and 0xffffffffL)) * 0x100000001b3L
            }
            if (hash == 0L) 1L else hash
        } finally {
            image.close()
        }
    }

    private fun open(w: Int, h: Int) {
        close()
        val imageReader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        node.setPosition(0, 0, w, h)
        renderer = HardwareRenderer().apply {
            setContentRoot(node)
            setSurface(imageReader.surface)
        }
        reader = imageReader
        width = w
        height = h
    }

    override fun close() {
        renderer?.destroy()
        renderer = null
        reader?.close()
        reader = null
        width = 0
        height = 0
    }

    companion object {
        private const val TAG = "LfgThumbnails"
        private const val SCALE = 4
    }
}
