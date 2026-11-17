// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import android.content.Context
import io.github.beesan.lfg.core.Box

/** A text box VOCR found, in frame pixels. A vertical box has no text: the bundled recogniser reads horizontal lines only. */
data class VocrLine(val box: Box, val text: String)

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

        /** `x,y,w,h<TAB>text` per line, as lfg_vocr_read writes them. */
        fun parse(output: String): List<VocrLine> = output.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val n = line.substring(0, tab).split(',').mapNotNull { it.toIntOrNull() }
            if (n.size != 4) return@mapNotNull null
            VocrLine(Box(n[0], n[1], n[0] + n[2], n[1] + n[3]), line.substring(tab + 1))
        }.toList()

        /**
         * What the settle detector compares: the text itself, so a blinking
         * cursor or a moving background is not a change. A box with no text
         * (a vertical column) counts by its rough place instead.
         */
        fun signature(lines: List<VocrLine>): String = lines.joinToString("\n") {
            it.text.ifEmpty { "[${it.box.left / 32},${it.box.top / 32},${it.box.width / 32},${it.box.height / 32}]" }
        }
    }
}
