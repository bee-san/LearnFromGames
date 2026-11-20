// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import io.github.beesan.lfg.capture.Pixels
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Debug builds only: times how a 1080p screen is fingerprinted and written, the old way
 * (rows copied into Java; Bitmap.compress) against lfg_pixels.cpp's, on the OCR bench's
 * pages in `ocrbench/` (tools/ocr_bench.py).
 *
 *     adb shell am instrument -w -e count 40 io.github.beesan.lfg/.PixelsBench
 */
class PixelsBench : Instrumentation() {
    private var arguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        result.putString("stream", try { run() } catch (e: Throwable) { "PixelsBench failed: ${e.stackTraceToString()}" } + "\n")
        finish(0, result)
    }

    private fun run(): String {
        val dir = File(targetContext.getExternalFilesDir(null), "ocrbench")
        val count = arguments?.getString("count")?.toIntOrNull() ?: 40
        val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }.let { all -> all.filterIndexed { i, _ -> i % (all.size / count).coerceAtLeast(1) == 0 }.take(count) }
        val times = LinkedHashMap<String, MutableList<Double>>()
        val sizes = LinkedHashMap<String, MutableList<Int>>()
        fun time(name: String, block: () -> Int?) {
            val start = System.nanoTime()
            val size = block()
            times.getOrPut(name) { mutableListOf() } += (System.nanoTime() - start) / 1e6
            if (size != null) sizes.getOrPut(name) { mutableListOf() } += size
        }
        var rows = IntArray(0)
        for (file in files) {
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            time("rows into Java") {
                val count = (bitmap.height + 3) / 4
                if (rows.size != count * bitmap.width) rows = IntArray(count * bitmap.width)
                for (i in 0 until count) bitmap.getPixels(rows, i * bitmap.width, bitmap.width, 0, i * 4, bitmap.width, 1)
                null
            }
            time("rows hashed in place") { Pixels.rowsHash(bitmap, 4); null }
            time("Bitmap.compress q85") { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }.size() }
            for (method in 0..4) time("libwebp q85 method $method") { Pixels.webp(bitmap, 85, method)?.size }
            bitmap.recycle()
        }
        // Silero VAD over 30 s of noise, as a card's sentence audio is searched for speech.
        val vad = targetContext.assets.open(io.github.beesan.lfg.capture.VoiceFinder.ASSET).use { io.github.beesan.lfg.capture.SileroVad.load(it) }
        val random = java.util.Random(1)
        val input = FloatArray(io.github.beesan.lfg.capture.SileroVad.INPUT)
        val state = FloatArray(io.github.beesan.lfg.capture.SileroVad.STATE)
        val chunks = 30 * 16_000 / io.github.beesan.lfg.capture.SileroVad.CHUNK
        repeat(3) { round ->
            time("VAD, 30 s of sound") {
                repeat(chunks) {
                    for (i in input.indices) input[i] = (random.nextGaussian() * 0.05).toFloat()
                    vad.run(input, state)
                }
                null
            }
        }
        return buildString {
            append("PixelsBench: ${files.size} screens, ${files.firstOrNull()?.let { BitmapFactory.decodeFile(it.path).let { b -> "${b.width}x${b.height}" } }}\n")
            for ((name, list) in times) {
                val sorted = list.sorted()
                append("%-26s median %7.1f ms  mean %7.1f ms".format(name, sorted[sorted.size / 2], list.average()))
                sizes[name]?.let { append("  %6.0f KB".format(it.average() / 1024)) }
                append('\n')
            }
        }
    }
}
