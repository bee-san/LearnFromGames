// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import java.io.File

/**
 * Debug builds only: where ScreenAI's time goes on a cold start and on crops
 * of different sizes, over the pictures in `ocrbench/` (see OcrBench).
 *
 *     adb shell am instrument -w -e pages 30 io.github.beesan.lfg/.OcrTiming
 */
class OcrTiming : Instrumentation() {
    private var arguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            result.putString("stream", run() + "\n")
            finish(0, result)
        } catch (e: Throwable) {
            result.putString("stream", "OcrTiming failed: ${e.stackTraceToString()}\n")
            finish(1, result)
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6

    private fun run(): String = buildString {
        val context = targetContext
        val pages = arguments?.getString("pages")?.toIntOrNull() ?: 30
        val dir = File(context.getExternalFilesDir(null), "ocrbench")
        val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }.take(pages)

        var t = System.nanoTime()
        val engine = ModelStore(context).screenAiDir()?.let { ScreenAi.open(it) } ?: error("ScreenAI is not installed")
        appendLine("open %.0f ms".format(ms(t)))

        // The dialogue box of a letterboxed page, and one line of it.
        fun box(f: Frame) = crop(f, 240, 720, 1680, 330)
        fun line(f: Frame) = crop(f, 300, 780, 1300, 70)

        for (i in 0 until 3) {
            t = System.nanoTime()
            val n = engine.read(box(frame(files[0]))).size
            appendLine("box read #${i + 1} %.0f ms ($n lines)".format(ms(t)))
        }
        fun series(name: String, cut: (Frame) -> Frame) {
            val times = files.map { file -> val c = cut(frame(file)); val s = System.nanoTime(); engine.read(c); ms(s) }
            val sorted = times.sorted()
            appendLine(
                "%-6s n=%d mean %.0f  median %.0f  p90 %.0f  max %.0f ms | %s".format(
                    name, times.size, times.average(), sorted[sorted.size / 2], sorted[(sorted.size * 9) / 10], sorted.last(),
                    times.joinToString(" ") { "%.0f".format(it) },
                ),
            )
        }
        series("line") { line(it) }
        series("box") { box(it) }
        series("full") { it }
        series("box") { box(it) }
    }

    private fun crop(f: Frame, x: Int, y: Int, w: Int, h: Int): Frame {
        val out = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(f.argb, (y + r) * f.width + x, out, r * w, w)
        return Frame(w, h, out)
    }

    private fun frame(file: File): Frame {
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inScaled = false })
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Frame(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }
}
