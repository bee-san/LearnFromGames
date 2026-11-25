// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * The black bars round a game's picture: an emulator showing a 4:3 or a
 * handheld's game on a wide screen fills the rest with black, and a card's
 * picture is better without it.
 */
object BlackBars {
    /** A pixel this dark in every channel is the bar's black; a scaler leaves it a little off zero. */
    private const val DARK = 18

    /** A bar is at least this share of the side it is on: a thinner dark edge is the game's own. */
    private const val MIN_BAR = 0.015

    /** What is left is at least this share of each side: less, and it is a dark scene, not bars. */
    private const val MIN_LEFT = 0.35

    private fun dark(argb: Int) = (argb shr 16 and 0xFF) <= DARK && (argb shr 8 and 0xFF) <= DARK && (argb and 0xFF) <= DARK

    /**
     * The picture inside its bars, as a box in the picture's pixels; the whole
     * picture when it has none, or when what they leave is too small to be
     * the game.
     */
    fun inside(argb: IntArray, width: Int, height: Int): Box {
        val whole = Box(0, 0, width, height)
        if (width < 8 || height < 8 || argb.size < width * height) return whole
        val stepX = (width / 256).coerceAtLeast(1)
        val stepY = (height / 256).coerceAtLeast(1)
        fun rowDark(y: Int, from: Int, to: Int): Boolean {
            var x = from
            while (x < to) {
                if (!dark(argb[y * width + x])) return false
                x += stepX
            }
            return true
        }
        fun columnDark(x: Int, from: Int, to: Int): Boolean {
            var y = from
            while (y < to) {
                if (!dark(argb[y * width + x])) return false
                y += stepY
            }
            return true
        }
        var top = 0
        while (top < height && rowDark(top, 0, width)) top++
        if (top == height) return whole
        var bottom = height
        while (bottom > top && rowDark(bottom - 1, 0, width)) bottom--
        var left = 0
        while (left < width && columnDark(left, top, bottom)) left++
        var right = width
        while (right > left && columnDark(right - 1, top, bottom)) right--
        // The side bars may have hidden more of the top and bottom ones: a corner's overlay, say.
        while (top < bottom && rowDark(top, left, right)) top++
        while (bottom > top && rowDark(bottom - 1, left, right)) bottom--

        val minX = (width * MIN_BAR).toInt().coerceAtLeast(2)
        val minY = (height * MIN_BAR).toInt().coerceAtLeast(2)
        if (top < minY) top = 0
        if (height - bottom < minY) bottom = height
        if (left < minX) left = 0
        if (width - right < minX) right = width
        if (right - left < width * MIN_LEFT || bottom - top < height * MIN_LEFT) return whole
        return Box(left, top, right, bottom)
    }

    /** The pixels of [box], row by row. */
    fun crop(argb: IntArray, width: Int, box: Box): IntArray {
        val out = IntArray(box.width * box.height)
        for (y in 0 until box.height) System.arraycopy(argb, (box.top + y) * width + box.left, out, y * box.width, box.width)
        return out
    }
}
