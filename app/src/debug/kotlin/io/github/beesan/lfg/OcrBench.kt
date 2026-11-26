// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Bands
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.TextClean
import io.github.beesan.lfg.ocr.Vocr
import java.io.File

/**
 * Debug builds only: runs every reader over the PNGs in the app's external
 * `ocrbench/` folder and writes what each read, with its time, to
 * `ocrbench/results.jsonl` for tools/ocr_bench.py to score. Each read goes
 * through TextClean, as in the app, and its time counts.
 *
 *     adb shell am instrument -w -e engines screenai,vocr io.github.beesan.lfg/.OcrBench
 *
 * `-e language ja` opens ScreenAI told the language, as the app does; without it each line's script is guessed.
 * `-e clean false` leaves TextClean out, to see what a reader gave before it.
 * `probe` is VOCR as the settle detector sees it (Vocr.text); run it with `-e clean false`.
 *
 * `screenai-bands` is ScreenAI as the app reads with it: only the rows around what VOCR found (Bands), with
 * the stacking and the moving back in its time, but not VOCR's own look, which the app has made already.
 */
class OcrBench : Instrumentation() {
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
            result.putString("stream", "OcrBench failed: ${e.stackTraceToString()}\n")
            finish(1, result)
        }
    }

    private fun run(): String {
        val context = targetContext
        val dir = File(context.getExternalFilesDir(null), "ocrbench")
        val wanted = (arguments?.getString("engines") ?: "screenai,vocr").split(',').map { it.trim() }
        val models = ModelStore(context)
        val language = arguments?.getString("language") ?: ""
        val clean = arguments?.getString("clean") != "false"
        val engines = LinkedHashMap<String, (Frame) -> List<TextLine>>()
        // What an engine is given before its clock starts.
        val prepare = HashMap<String, (Frame) -> Unit>()
        if ("screenai" in wanted) {
            val engine = models.screenAiDir()?.let { ScreenAi.open(it, language) } ?: error("ScreenAI is not installed")
            engines["screenai"] = engine::read
        }
        if ("screenai-bands" in wanted) {
            val engine = models.screenAiDir()?.let { ScreenAi.open(it, language) } ?: error("ScreenAI is not installed")
            val vocr = Vocr.open(context) ?: error("VOCR could not open")
            var found: List<Box>? = null
            var of: Frame? = null
            prepare["screenai-bands"] = { frame ->
                found = vocr.read(frame).map { it.box }
                of = frame
            }
            engines["screenai-bands"] = { frame ->
                val bands = found?.takeIf { of === frame }?.let { Bands.around(it, frame.width, frame.height) }
                if (bands == null) engine.read(frame) else bands.unstack(engine.read(bands.stack(frame)))
            }
        }
        if ("vocr" in wanted) {
            val vocr = Vocr.open(context) ?: error("VOCR could not open")
            engines["vocr"] = { frame -> vocr.read(frame).map { TextLine(it.text, it.box, emptyList()) } }
        }
        if ("probe" in wanted) {
            // What the settle detector is given: VOCR's rows less what a moving picture makes of the spotter.
            val vocr = Vocr.open(context) ?: error("VOCR could not open")
            engines["probe"] = { frame -> Vocr.text(vocr.read(frame)).map { TextLine(it.text, it.box, emptyList()) } }
        }
        val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
        val out = File(dir, "results.jsonl").bufferedWriter()
        // One untimed read each first, so loading the models is not counted against the first picture.
        files.firstOrNull()?.let { first -> frame(first).let { f -> engines.forEach { (name, read) -> prepare[name]?.invoke(f); read(f) } } }
        for (file in files) {
            val frame = frame(file)
            for ((name, read) in engines) {
                prepare[name]?.invoke(frame)
                val start = System.nanoTime()
                val lines = read(frame).let { rows -> if (clean) rows.mapNotNull(TextClean::line) else rows }
                val ms = (System.nanoTime() - start) / 1e6
                out.write("{\"file\":${json(file.name)},\"engine\":\"$name\",\"ms\":${"%.2f".format(ms)},\"lines\":${dump(lines)}}\n")
            }
        }
        out.close()
        return "OcrBench: ${files.size} pictures, ${engines.keys}"
    }

    private fun frame(file: File): Frame {
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inScaled = false })
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Frame(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }

    private fun json(text: String) = buildString {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun dump(lines: List<TextLine>) = lines.joinToString(",", "[", "]") { line ->
        val b = line.box
        val chars = line.chars.joinToString(",", "[", "]") { "[${it.start},${it.end},${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}]" }
        "{\"text\":${json(line.text)},\"box\":[${b.left},${b.top},${b.right},${b.bottom}],\"vertical\":${line.vertical},\"chars\":$chars}"
    }
}
