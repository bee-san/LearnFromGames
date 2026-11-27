// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.Box

/** A picture as packed ARGB, free of android.graphics so the readers run in unit tests. */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size >= width * height)
    }

    /**
     * The part of this picture from ([left], [top]), [cutWidth] by [cutHeight],
     * which must lie inside it. Copied into [into] when that is big enough, so
     * a part cut from every picture needs no new memory each time.
     */
    fun cut(left: Int, top: Int, cutWidth: Int, cutHeight: Int, into: IntArray? = null): Frame {
        require(left >= 0 && top >= 0 && cutWidth > 0 && cutHeight > 0 && left + cutWidth <= width && top + cutHeight <= height)
        val out = into?.takeIf { it.size >= cutWidth * cutHeight } ?: IntArray(cutWidth * cutHeight)
        for (row in 0 until cutHeight) System.arraycopy(argb, (top + row) * width + left, out, row * cutWidth, cutWidth)
        return Frame(cutWidth, cutHeight, out)
    }

    /**
     * Paints black every pixel outside [boxes] (this picture's pixels): a part
     * cut round several boxes reads, and changes, only in them.
     */
    fun keepOnly(boxes: List<Box>) {
        for (y in 0 until height) {
            val spans = boxes.filter { y >= it.top && y < it.bottom }
                .map { it.left.coerceIn(0, width) to it.right.coerceIn(0, width) }.sortedBy { it.first }
            var x = 0
            for ((from, to) in spans) {
                if (from > x) java.util.Arrays.fill(argb, y * width + x, y * width + from, BLACK)
                x = maxOf(x, to)
            }
            if (x < width) java.util.Arrays.fill(argb, y * width + x, y * width + width, BLACK)
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
