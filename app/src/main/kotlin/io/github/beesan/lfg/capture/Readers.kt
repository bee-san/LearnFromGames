// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.MeikiOcr
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.Vocr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The second pass: turns a settled frame into lines with a box per character.
 * ScreenAI and meikiocr each sit behind this one interface.
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
}

/** VOCR as the probe: the signature is the text it reads. */
class VocrProbe(private val vocr: Vocr) : ChangeProbe {
    override suspend fun signature(frame: Frame): String = withContext(Dispatchers.Default) { Vocr.signature(vocr.read(frame)) }
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

class MeikiReader(private val ocr: MeikiOcr) : TextReader {
    override val name = "meikiocr"
    override suspend fun read(frame: Frame): List<TextLine> = withContext(Dispatchers.Default) {
        synchronized(ocr) { ocr.read(frame) }
    }

    companion object {
        fun open(files: ModelStore.MeikiFiles): MeikiReader {
            val env = OrtEnvironment.getEnvironment()
            fun session(file: File) = env.createSession(
                file.path,
                OrtSession.SessionOptions().apply {
                    // A game is running beside this: leave it most of the cores.
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                },
            )
            return MeikiReader(MeikiOcr(env, session(files.detector), session(files.recogniser), session(files.vertical)))
        }
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
     * Opens whatever the settings ask for and the device has. ScreenAI is the
     * default once its library and files are there; meikiocr reads until then,
     * and whenever ScreenAI cannot start.
     */
    @Synchronized
    fun refresh(context: Context, models: ModelStore, prefs: Prefs) {
        if (vocr == null) {
            vocr = Vocr.open(context)
            vocr?.let { probe = VocrProbe(it) }
        }
        val screenAi = models.screenAiDir()
        val meiki = models.meikiFiles()
        val wanted = when {
            prefs.engine != Prefs.ENGINE_MEIKI && screenAi != null && ScreenAi.available() -> "screenai"
            meiki != null -> "meiki"
            else -> ""
        }
        if (wanted == opened) return
        reader = try {
            when (wanted) {
                "screenai" -> ScreenAi.open(screenAi!!)?.let(::ScreenAiReader) ?: meiki?.let(MeikiReader::open)
                "meiki" -> MeikiReader.open(meiki!!)
                else -> null
            }
        } catch (e: Throwable) {
            android.util.Log.w("LfgReaders", "could not open $wanted", e)
            null
        }
        opened = if (reader != null) wanted else ""
    }
}
