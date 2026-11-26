// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import android.content.Context
import io.github.beesan.lfg.core.Box

/**
 * A text box VOCR found, in frame pixels. A vertical box has no text: the bundled recogniser reads horizontal lines only.
 * [conf] is the spotter's confidence that it is text, 1 to 127.
 */
data class VocrLine(val box: Box, val text: String, val vertical: Boolean = text.isEmpty(), val conf: Int = 127)

/**
 * VOCR (bee-san/vita-vn-ocr): a 52 KB text spotter and a 1.2 MB line
 * recogniser, small enough to run on every picture. Its weights ship inside
 * the app under their own non-commercial terms (assets/vocr).
 */
class Vocr private constructor(private var handle: Long) : AutoCloseable {

    @Synchronized
    fun read(frame: Frame): List<VocrLine> {
        if (handle == 0L) return emptyList()
        return parse(nativeRead(handle, frame.argb, frame.width, frame.height).decodeToString())
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private external fun nativeRead(handle: Long, pixels: IntArray, width: Int, height: Int): ByteArray
    private external fun nativeClose(handle: Long)

    companion object {
        const val RECOGNISER = "vocr/VOCR6.2_N_w8g2.vocr"
        const val SPOTTER = "vocr/textspot8_t64n8c16_w8.vtsp"

        @JvmStatic
        private external fun nativeOpen(recogniser: ByteArray, spotter: ByteArray): Long

        fun open(context: Context): Vocr? = try {
            System.loadLibrary("lfg")
            val handle = nativeOpen(
                context.assets.open(RECOGNISER).use { it.readBytes() },
                context.assets.open(SPOTTER).use { it.readBytes() },
            )
            if (handle == 0L) null else Vocr(handle)
        } catch (_: Throwable) {
            null
        }

        /** `x,y,w,h,vertical,conf<TAB>text` per line, as lfg_vocr_read writes them; `x,y,w,h<TAB>text` as it did. */
        fun parse(output: String): List<VocrLine> = output.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val n = line.substring(0, tab).split(',').mapNotNull { it.toIntOrNull() }
            if (n.size != 4 && n.size != 6) return@mapNotNull null
            val box = Box(n[0], n[1], n[0] + n[2], n[1] + n[3])
            val text = line.substring(tab + 1)
            if (n.size == 4) VocrLine(box, text) else VocrLine(box, text, vertical = n[4] != 0, conf = n[5])
        }.toList()

        /**
         * What the settle detector compares: the text itself, so a blinking
         * cursor or a moving background is not a change. A box with no text
         * (a vertical column) counts by its rough place instead. What a
         * moving picture makes of the spotter is left out ([text]).
         */
        fun signature(lines: List<VocrLine>): String = text(lines).joinToString("\n", transform = ::mark)

        /** What one row is in the signature: its text, or its rough place when it has none. */
        fun mark(line: VocrLine): String =
            line.text.ifEmpty { "[${line.box.left / 32},${line.box.top / 32},${line.box.width / 32},${line.box.height / 32}]" }

        /**
         * The boxes that are text to follow. A bird flying across the top of
         * the screen, a sparkle or a character walking about is found by the
         * spotter as a faint box, or as one the recogniser reads nothing or a
         * single character from, standing apart from the text; counted, each
         * look at it would make the line on screen look new.
         *
         * - A box fainter than [FAINT] is never followed. On 558 real game
         *   screens, of 806 lines of dialogue only a lone ⁉ was that faint;
         *   the boxes a bird made were 3 to 8.
         * - A horizontal box read as nothing is followed only when nothing on
         *   screen could be read, when where text is found is all there is.
         * - A box read as one character is followed beside longer text (the
         *   next row's first character, a name plate), or when there is none.
         *
         * - The box's cursor (▼, ▶) is left out, at the end of a row or as a
         *   box of its own, so its blinking is no change.
         * - A box read as only the kanji a tiled picture is read as (田, 回)
         *   is never followed ([TextClean.tiles]): walking over grass is no change.
         *
         * Vertical columns, which are never read, keep their places.
         */
        fun text(lines: List<VocrLine>): List<VocrLine> {
            val sure = lines.filter { it.conf >= FAINT }.mapNotNull { line ->
                if (line.text.isEmpty()) return@mapNotNull line
                val text = TextClean.uncursored(line.text)
                if (text.isEmpty() || TextClean.tiles(text)) null else if (text == line.text) line else line.copy(text = text)
            }
            val read = sure.filter { it.text.isNotEmpty() }
            val longer = read.filter { it.text.codePointCount(0, it.text.length) > 1 }
            return sure.filter { line ->
                when {
                    line.vertical -> true
                    line.text.isEmpty() -> read.isEmpty()
                    line.text.codePointCount(0, line.text.length) == 1 -> longer.isEmpty() || longer.any { near(it.box, line.box) }
                    else -> true
                }
            }
        }

        /** Whether [b] is within a line's height of [a]: the row after it, or a name plate over it. */
        private fun near(a: Box, b: Box): Boolean {
            val room = maxOf(a.height, b.height)
            return a.left - room < b.right && b.left < a.right + room && a.top - room < b.bottom && b.top < a.bottom + room
        }

        /** The spotter's confidence under which a box is not taken for text: real dialogue is 24 and over. */
        const val FAINT = 24
    }
}
