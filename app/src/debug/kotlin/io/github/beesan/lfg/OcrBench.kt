// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import io.github.beesan.lfg.capture.MeikiReader
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.Vocr
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug builds only: runs every reader over the PNGs in the app's external
 * `ocrbench/` folder and writes what each read, with its time, to
 * `ocrbench/results.jsonl` for tools/ocr_bench.py to score.
 *
 *     adb shell am instrument -w -e engines screenai,meiki,vocr io.github.beesan.lfg/.OcrBench
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
        val wanted = (arguments?.getString("engines") ?: "screenai,meiki,vocr").split(',').map { it.trim() }
        val models = ModelStore(context)
        val engines = LinkedHashMap<String, (Frame) -> List<TextLine>>()
        if ("screenai" in wanted) {
            val engine = models.screenAiDir()?.let { ScreenAi.open(it) } ?: error("ScreenAI is not installed")
            engines["screenai"] = engine::read
        }
        if ("meiki" in wanted) {
            val reader = MeikiReader.open(models.meikiFiles() ?: error("meikiocr is not installed"))
            engines["meiki"] = { frame -> runBlocking { reader.read(frame) } }
        }
        if ("vocr" in wanted) {
            val vocr = Vocr.open(context) ?: error("VOCR could not open")
            engines["vocr"] = { frame -> vocr.read(frame).map { TextLine(it.text, it.box, emptyList()) } }
        }
        val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
        val out = File(dir, "results.jsonl").bufferedWriter()
        // One untimed read each first, so loading the models is not counted against the first picture.
        files.firstOrNull()?.let { first -> frame(first).let { f -> engines.values.forEach { it(f) } } }
        for (file in files) {
            val frame = frame(file)
            for ((name, read) in engines) {
                val start = System.nanoTime()
                val lines = read(frame)
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
