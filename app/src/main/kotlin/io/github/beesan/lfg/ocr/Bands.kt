// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.TextLine

/**
 * Reading only where the text is. Most of ScreenAI's time goes on looking for text, and the probe has already
 * said where the text is and how big it is. So the reader is given the parts of the picture around the probe's
 * boxes and nothing else, each band under the last with a strip of black between, and told how tall the
 * smallest text is ([textHeight]): it then looks for text at the scale that keeps that just tall enough to find
 * (tools/screenai/0006), whatever the size of the picture it is given, so a narrower picture is less work. What it
 * reads is then moved back to where it was on the picture.
 *
 * On 994 Vita and PSP screens with known text, as much of it was read right from the bands as from the whole
 * picture (2.8% of characters wrong against 2.7%). On 616 screens of dialogue kept on an AYN Thor (Clannad,
 * Pokémon Let's Go, Senren Banka) the bands were half the picture, and a read on the Thor took 43 ms where
 * the whole picture took 54. Cut to the text's columns as well and looked for at its own size, a read of the
 * same Vita screens took 5 ms on a desktop where the rows alone at the detector's own size took 22, with
 * 7.1% of characters wrong for 7.5% (2026-11-27).
 *
 * What is no longer read is text away from anything the probe found. On those screens that was mostly what
 * the reader made of the scenery (a shop's shutter, boxes on a shelf); a real sign or a button's label that
 * the probe misses goes with it.
 */
class Bands private constructor(
    /** Each band's first row and the row after its last, top to bottom, on the picture they were found on. */
    val rows: List<IntRange>,
    /** Each band's first column and the column after its last. */
    val columns: List<IntRange>,
    private val width: Int,
) {
    /** How wide the stacked picture is: its widest band. */
    val stackedWidth: Int = columns.maxOf { it.last - it.first }

    /** How tall the stacked picture is. */
    val height: Int = rows.sumOf { it.last - it.first } + SEAM * (rows.size - 1)

    /** The bands of [frame] one under another, each at the left, in [into] when that is big enough. */
    fun stack(frame: Frame, into: IntArray? = null): Frame {
        require(frame.width == width)
        val w = stackedWidth
        val out = into?.takeIf { it.size >= w * height } ?: IntArray(w * height)
        var y = 0
        for ((i, band) in rows.withIndex()) {
            val tall = band.last - band.first
            val left = columns[i].first
            val wide = columns[i].last - left
            for (r in 0 until tall) {
                val at = (y + r) * w
                System.arraycopy(frame.argb, (band.first + r) * width + left, out, at, wide)
                if (wide < w) java.util.Arrays.fill(out, at + wide, at + w, BLACK)
            }
            y += tall
            if (i < rows.lastIndex) {
                java.util.Arrays.fill(out, y * w, (y + SEAM) * w, BLACK)
                y += SEAM
            }
        }
        return Frame(w, height, out)
    }

    /** Lines read from the stacked picture, where they are on the picture itself. One read across a seam is of nothing real. */
    fun unstack(lines: List<TextLine>): List<TextLine> = lines.mapNotNull { line ->
        val middle = (line.box.top + line.box.bottom) / 2
        var y = 0
        for ((i, band) in rows.withIndex()) {
            val tall = band.last - band.first
            if (middle >= y && middle < y + tall) return@mapNotNull line.moved(columns[i].first, band.first - y)
            y += tall + SEAM
        }
        null
    }

    companion object {
        /**
         * How much of the tallest line's height is kept around the text found: the probe misses a short
         * last row now and then, and a speaker's name of one character over the first.
         */
        const val PAD = 2f

        /** Bands nearer than this much of that height are one band. */
        const val JOIN = 1.0f

        /** Rows of black between two bands. */
        const val SEAM = 32

        /** Bands larger than this share of the picture are not worth the trouble: the whole picture is read. */
        const val WORTH = 0.8f

        /** A box this thin is no text anyone could read, and says nothing of the text's size. */
        const val LEAST_TEXT = 8

        private const val BLACK = 0xFF000000.toInt()

        /**
         * How tall the smallest text among [found] is: the narrow side of its row. 0 when none is large enough to
         * tell ([LEAST_TEXT]), and where there is a column of text: smaller, columns came apart (on 37 lines of
         * vertical text, 11% of characters wrong where the detector's own size had 6%). A box too small only
         * makes the read slower, never worse.
         */
        fun textHeight(found: List<Box>): Int {
            if (found.any { it.height > COLUMN * it.width }) return 0
            return found.map { minOf(it.width, it.height) }.filter { it >= LEAST_TEXT }.minOrNull() ?: 0
        }

        /** A box this many times taller than wide is a column of text. */
        private const val COLUMN = 2

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
            // Each band: top, bottom, left, right.
            val bands = ArrayList<IntArray>()
            for (box in boxes.sortedBy { it.top }) {
                val top = (box.top - pad).coerceIn(0, height)
                val bottom = (box.bottom + pad).coerceIn(top, height)
                val left = (box.left - pad).coerceIn(0, width)
                val right = (box.right + pad).coerceIn(left, width)
                val last = bands.lastOrNull()
                if (last != null && top <= last[1] + JOIN * type) {
                    last[1] = maxOf(last[1], bottom)
                    last[2] = minOf(last[2], left)
                    last[3] = maxOf(last[3], right)
                } else {
                    bands += intArrayOf(top, bottom, left, right)
                }
            }
            val kept = bands.filter { it[1] > it[0] && it[3] > it[2] }
            if (kept.isEmpty()) return null
            val made = Bands(kept.map { it[0]..it[1] }, kept.map { it[2]..it[3] }, width)
            return made.takeIf { it.stackedWidth.toLong() * it.height <= WORTH * width.toLong() * height }
        }
    }
}
