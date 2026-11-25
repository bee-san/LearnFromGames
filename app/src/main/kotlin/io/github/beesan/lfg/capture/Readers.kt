// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.content.Context
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.Vocr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The second pass: turns a settled frame into lines with a box per character.
 * ScreenAI sits behind it.
 */
interface TextReader {
    /** Stored in `game_lines.engine`, e.g. `screenai`. */
    val name: String

    suspend fun read(frame: Frame): List<TextLine>
}

/**
 * The cheap first pass, run on every picture: something that changes when the
 * text on screen changes and is empty when there is none.
 */
interface ChangeProbe {
    suspend fun signature(frame: Frame): String

    /** The same, with where on the frame it found anything that may be text; null from a probe that cannot say. */
    suspend fun look(frame: Frame): Look = Look(signature(frame), null)
}

/** What a probe made of a frame: its signature, and the boxes it found text in, for reading only there ([Bands]). */
class Look(val signature: String, val found: List<Box>?)

/** VOCR as the probe: the signature is the text it reads. */
class VocrProbe(private val vocr: Vocr) : ChangeProbe {
    override suspend fun signature(frame: Frame): String = look(frame).signature

    /** Every box the spotter gave counts as found, the faint ones too: a box too many only makes a band taller. */
    override suspend fun look(frame: Frame): Look = withContext(Dispatchers.Default) {
        val lines = vocr.read(frame)
        Look(Vocr.signature(lines), lines.map { it.box })
    }
}

/**
 * The fallback when VOCR cannot load: a coarse fingerprint of the frame's
 * brightness. It fires on animations and blinking cursors, which is why it is
 * only the fallback.
 */
class PixelProbe : ChangeProbe {
    override suspend fun signature(frame: Frame): String {
        val cells = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val sy = (y * 2 + 1) * frame.height / (HEIGHT * 2)
            for (x in 0 until WIDTH) cells[y * WIDTH + x] = frame.argb[sy * frame.width + (x * 2 + 1) * frame.width / (WIDTH * 2)]
        }
        return fingerprint(cells)
    }

    companion object {
        const val WIDTH = 32
        const val HEIGHT = 18

        /** Brightness of each cell in 16 steps, so compression noise and faint gradients do not count as change. */
        fun fingerprint(argb: IntArray): String = buildString(argb.size) {
            for (p in argb) {
                val luma = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                append("0123456789abcdef"[luma shr 4])
            }
        }
    }
}

/**
 * The reader as its own probe, for a language VOCR cannot read
 * ([Language.Probe.READER]): the signature is what it reads. A look costs a
 * whole read, less the lines it remembers from the look before.
 */
class ReaderProbe(private val reader: TextReader) : ChangeProbe {
    override suspend fun signature(frame: Frame): String = look(frame).signature

    override suspend fun look(frame: Frame): Look {
        val lines = reader.read(frame)
        return Look(lines.joinToString("\n") { it.text }, lines.map { it.box })
    }
}

class ScreenAiReader(private val engine: ScreenAi) : TextReader {
    override val name = "screenai"
    override suspend fun read(frame: Frame): List<TextLine> = withContext(Dispatchers.Default) { engine.read(frame) }
}

/** The engines in use. With no reader the service takes no pictures. */
object Readers {
    @Volatile
    var reader: TextReader? = null
        private set

    @Volatile
    var probe: ChangeProbe = PixelProbe()
        private set

    private var vocr: Vocr? = null
    private var opened = ""

    /**
     * Opens ScreenAI for [language] once its library and files are there. Until then there
     * is no reader, and the service takes no pictures. The probe is the language's:
     * VOCR for Japanese, the reader itself for one VOCR cannot read.
     */
    @Synchronized
    fun refresh(context: Context, models: ModelStore, language: Language = Language.JAPANESE) {
        val dir = models.screenAiDir()
        val wanted = if (dir != null && ScreenAi.available()) "screenai:${language.tag}" else ""
        if (wanted != opened) {
            reader = try {
                if (wanted.isEmpty()) null else ScreenAi.open(dir!!, language.tag)?.let(::ScreenAiReader)
            } catch (e: Throwable) {
                android.util.Log.w("LfgReaders", "could not open ScreenAI", e)
                null
            }
            opened = if (reader != null) wanted else ""
        }
        if (language.probe == Language.Probe.VOCR && vocr == null) vocr = Vocr.open(context)
        probe = probeFor(language, vocr?.let(::VocrProbe), reader)
    }

    /** The probe [language] asks for, of what could be opened; the pixels when it could not. */
    internal fun probeFor(language: Language, vocr: ChangeProbe?, reader: TextReader?): ChangeProbe = when (language.probe) {
        Language.Probe.VOCR -> vocr
        Language.Probe.READER -> reader?.let(::ReaderProbe)
    } ?: PixelProbe()
}
