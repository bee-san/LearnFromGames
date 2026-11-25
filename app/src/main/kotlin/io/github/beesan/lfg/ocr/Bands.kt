// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.TextLine

/**
 * Reading only where the text is. A third of ScreenAI's time goes on looking
 * for text, over the whole picture at one fixed size whatever is in it, and
 * the probe has already said where the text is. So the reader is given the
 * rows of the picture around the probe's boxes and nothing else: the full
 * width, since the detector fits the longer side to its own size and a
 * narrower picture would only be blown up again, and each band of rows under
 * the last with a strip of black between. What it reads is then moved back to
 * where it was on the picture.
 *
 * On 994 Vita and PSP screens with known text, as much of it was read right from the bands as from the whole
 * picture (2.8% of characters wrong against 2.7%). On 616 screens of dialogue kept on an AYN Thor (Clannad,
 * Pokémon Let's Go, Senren Banka) the bands were half the picture, and a read on the Thor took 43 ms where
 * the whole picture took 54.
 *
 * What is no longer read is text away from anything the probe found. On those screens that was mostly what
 * the reader made of the scenery (a shop's shutter, boxes on a shelf); a real sign or a button's label that
 * the probe misses goes with it.
 */
class Bands private constructor(
    /** Each band's first row and the row after its last, top to bottom, on the picture they were found on. */
    val rows: List<IntRange>,
    private val width: Int,
) {
    /** How tall the stacked picture is. */
    val height: Int = rows.sumOf { it.last - it.first } + SEAM * (rows.size - 1)

    /** The bands of [frame] one under another, in [into] when that is big enough. */
    fun stack(frame: Frame, into: IntArray? = null): Frame {
        require(frame.width == width)
        val out = into?.takeIf { it.size >= width * height } ?: IntArray(width * height)
        var y = 0
        for ((i, band) in rows.withIndex()) {
            val tall = band.last - band.first
            System.arraycopy(frame.argb, band.first * width, out, y * width, tall * width)
            y += tall
            if (i < rows.lastIndex) {
                java.util.Arrays.fill(out, y * width, (y + SEAM) * width, BLACK)
                y += SEAM
            }
        }
        return Frame(width, height, out)
    }

    /** Lines read from the stacked picture, where they are on the picture itself. One read across a seam is of nothing real. */
    fun unstack(lines: List<TextLine>): List<TextLine> = lines.mapNotNull { line ->
        val middle = (line.box.top + line.box.bottom) / 2
        var y = 0
        for (band in rows) {
            val tall = band.last - band.first
            if (middle >= y && middle < y + tall) return@mapNotNull line.moved(0, band.first - y)
            y += tall + SEAM
        }
        null
    }

    companion object {
        /**
         * How much of the tallest line's height is kept above and below the text found: the probe misses a short
         * last row now and then, and a speaker's name of one character over the first.
         */
        const val PAD = 2f

        /** Bands nearer than this much of that height are one band. */
        const val JOIN = 1.0f

        /** Rows of black between two bands. */
        const val SEAM = 32

        /** Bands taller than this share of the picture are not worth the trouble: the whole picture is read. */
        const val WORTH = 0.8f

        private const val BLACK = 0xFF000000.toInt()

        /**
         * The bands around [found], the boxes the probe found text in on a picture [width] by [height]; null
         * when the whole picture should be read: nothing was found, or the text is all over it.
         */
        fun around(found: List<Box>, width: Int, height: Int): Bands? {
            val boxes = found.filter { it.width > 0 && it.height > 0 }
            if (boxes.isEmpty() || width <= 0 || height <= 0) return null
            // The size of the type: the narrow side of a row or of a column, of the largest text there is.
            val type = boxes.maxOf { minOf(it.width, it.height) }
            val pad = (PAD * type).toInt()
            val bands = ArrayList<IntArray>()
            for (box in boxes.sortedBy { it.top }) {
                val top = (box.top - pad).coerceIn(0, height)
                val bottom = (box.bottom + pad).coerceIn(top, height)
                val last = bands.lastOrNull()
                if (last != null && top <= last[1] + JOIN * type) last[1] = maxOf(last[1], bottom) else bands += intArrayOf(top, bottom)
            }
            val made = Bands(bands.filter { it[1] > it[0] }.map { it[0]..it[1] }, width)
            return made.takeIf { it.rows.isNotEmpty() && it.height <= WORTH * height }
        }
    }
}
