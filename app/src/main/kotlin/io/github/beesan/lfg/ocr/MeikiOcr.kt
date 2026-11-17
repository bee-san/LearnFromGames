// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** A picture as packed ARGB, free of android.graphics so the reader runs in unit tests. */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size >= width * height)
    }
}

/**
 * meikiocr (rtr46, Apache-2.0; weights LGPL-3.0) on ONNX Runtime: a port of
 * its ocr.py 0.3.5. A detector finds text lines; a recogniser, one for
 * horizontal lines and one for vertical, finds each character with its box.
 * The models decode and scale their own boxes, and their class ids are Unicode
 * code points, so there is no charset file.
 */
class MeikiOcr(
    private val env: OrtEnvironment,
    private val detector: OrtSession,
    private val recogniser: OrtSession,
    /** Without it vertical lines are skipped. */
    private val verticalRecogniser: OrtSession?,
) : AutoCloseable {

    fun read(frame: Frame): List<TextLine> {
        val lines = detect(frame).mapNotNull { box ->
            if (box.width <= 0 || box.height <= 0) return@mapNotNull null
            val vertical = box.height > box.width
            val candidates = if (vertical) recogniseVertical(frame, box) else recogniseHorizontal(frame, box)
            assemble(box, vertical, candidates)
        }
        return lines
    }

    // ── detection ────────────────────────────────────────────────────────────

    internal fun detect(frame: Frame): List<Box> {
        val scale = minOf(DET_WIDTH.toFloat() / frame.width, DET_HEIGHT.toFloat() / frame.height)
        val resizedW = (frame.width * scale).toInt().coerceIn(1, DET_WIDTH)
        val resizedH = (frame.height * scale).toInt().coerceIn(1, DET_HEIGHT)
        val input = FloatArray(3 * DET_HEIGHT * DET_WIDTH)
        resizeInto(frame, 0, 0, frame.width, frame.height, resizedW, resizedH, input, DET_WIDTH, DET_HEIGHT)
        // The model scales its boxes by this, so they come back in frame pixels.
        val sizes = longArrayOf((DET_WIDTH / scale).toLong(), (DET_HEIGHT / scale).toLong())

        return run(detector, input, DET_WIDTH, DET_HEIGHT, sizes) { result ->
            @Suppress("UNCHECKED_CAST")
            val boxes = (result.get("boxes").get().value as Array<Array<FloatArray>>)[0]
            @Suppress("UNCHECKED_CAST")
            val scores = (result.get("scores").get().value as Array<FloatArray>)[0]
            boxes.indices.filter { scores[it] > DET_THRESHOLD }.map { i ->
                val b = boxes[i]
                Box(
                    b[0].coerceIn(0f, frame.width.toFloat()).toInt(), b[1].coerceIn(0f, frame.height.toFloat()).toInt(),
                    b[2].coerceIn(0f, frame.width.toFloat()).toInt(), b[3].coerceIn(0f, frame.height.toFloat()).toInt(),
                )
            }.sortedBy { it.top }
        }
    }

    // ── recognition ──────────────────────────────────────────────────────────

    internal class Candidate(val codePoint: Int, val box: Box, var confidence: Float, val start: Int, val end: Int)

    private fun recogniseHorizontal(frame: Frame, line: Box): List<Candidate> {
        var newH = REC_HEIGHT
        var newW = Math.rint(line.width * (REC_HEIGHT.toDouble() / line.height)).toInt()
        if (newW > REC_WIDTH) {
            newH = Math.rint(newH * (REC_WIDTH.toDouble() / newW)).toInt()
            newW = REC_WIDTH
        }
        if (newW < 1 || newH < 1) return emptyList()
        val input = FloatArray(3 * REC_HEIGHT * REC_WIDTH)
        resizeInto(frame, line.left, line.top, line.width, line.height, newW, newH, input, REC_WIDTH, REC_HEIGHT)

        return run(recogniser, input, REC_WIDTH, REC_HEIGHT, longArrayOf(REC_WIDTH.toLong(), REC_HEIGHT.toLong())) { result ->
            val out = mutableListOf<Candidate>()
            forEachCharacter(result) { code, b, score ->
                if (b[0] >= newW) return@forEachCharacter
                val x1 = minOf(b[0], newW.toFloat()) / newW * line.width
                val x2 = minOf(b[2], newW.toFloat()) / newW * line.width
                // Upstream divides by the model height even when the content is shorter; kept for parity.
                val y1 = b[1] / REC_HEIGHT * line.height
                val y2 = b[3] / REC_HEIGHT * line.height
                val box = Box(line.left + x1.toInt(), line.top + y1.toInt(), line.left + x2.toInt(), line.top + y2.toInt())
                out += Candidate(code, box, score, box.left, box.right)
            }
            out
        }
    }

    private fun recogniseVertical(frame: Frame, line: Box): List<Candidate> {
        val session = verticalRecogniser ?: return emptyList()
        val scale = VREC_WIDTH.toDouble() / line.width
        val maxScaledH: Int
        val segmentH: Double
        val starts = mutableListOf<Double>()
        if (line.height * scale > VREC_HEIGHT) {
            // Too tall for one pass: overlapping segments, the last aligned to the bottom.
            maxScaledH = VREC_MAX_CONTENT_HEIGHT
            segmentH = VREC_MAX_CONTENT_HEIGHT / scale
            val stride = (VREC_MAX_CONTENT_HEIGHT - VREC_OVERLAP) / scale
            var y = line.top.toDouble()
            while (y + segmentH < line.bottom) {
                starts += y
                y += stride
            }
            val last = line.bottom - segmentH
            if (starts.isEmpty() || last > starts.last() + 1.0) starts += last
        } else {
            maxScaledH = VREC_HEIGHT
            segmentH = line.height.toDouble()
            starts += line.top.toDouble()
        }

        val out = mutableListOf<Candidate>()
        for (start in starts) {
            val top = Math.rint(start).toInt().coerceAtLeast(0)
            val bottom = minOf(Math.rint(start + segmentH).toInt(), line.bottom)
            val height = bottom - top
            if (height <= 0) continue
            val newH = minOf(Math.rint(height * scale).toInt(), maxScaledH)
            if (newH < 1) continue
            val input = FloatArray(3 * VREC_HEIGHT * VREC_WIDTH)
            resizeInto(frame, line.left, top, line.width, height, VREC_WIDTH, newH, input, VREC_WIDTH, VREC_HEIGHT)
            out += run(session, input, VREC_WIDTH, VREC_HEIGHT, longArrayOf(VREC_WIDTH.toLong(), VREC_HEIGHT.toLong())) { result ->
                val found = mutableListOf<Candidate>()
                forEachCharacter(result) { code, b, score ->
                    if (b[1] >= newH) return@forEachCharacter
                    val x1 = b[0] / VREC_WIDTH * line.width
                    val x2 = b[2] / VREC_WIDTH * line.width
                    val y1 = minOf(b[1], newH.toFloat()) / newH * height
                    val y2 = minOf(b[3], newH.toFloat()) / newH * height
                    val box = Box(line.left + x1.toInt(), top + y1.toInt(), line.left + x2.toInt(), top + y2.toInt())
                    if (box.bottom > box.top) found += Candidate(code, box, score, box.top, box.bottom)
                }
                found
            }
        }
        return out
    }

    private inline fun forEachCharacter(result: OrtSession.Result, use: (code: Int, box: FloatArray, score: Float) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val codes = (result.get("char_codes").get().value as Array<IntArray>)[0]
        @Suppress("UNCHECKED_CAST")
        val boxes = (result.get("boxes").get().value as Array<Array<FloatArray>>)[0]
        @Suppress("UNCHECKED_CAST")
        val scores = (result.get("scores").get().value as Array<FloatArray>)[0]
        for (i in codes.indices) {
            if (scores[i] < REC_THRESHOLD) continue
            if (!Character.isValidCodePoint(codes[i]) || codes[i] == 0) continue
            use(codes[i], boxes[i], scores[i])
        }
    }

    private fun <T> run(session: OrtSession, input: FloatArray, width: Int, height: Int, sizes: LongArray, use: (OrtSession.Result) -> T): T =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, height.toLong(), width.toLong())).use { images ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(1, 2)).use { target ->
                session.run(mapOf("images" to images, "orig_target_sizes" to target)).use(use)
            }
        }

    override fun close() {
        detector.close()
        recogniser.close()
        verticalRecogniser?.close()
    }

    companion object {
        const val DET_WIDTH = 960
        const val DET_HEIGHT = 544
        const val REC_WIDTH = 960
        const val REC_HEIGHT = 32
        const val VREC_WIDTH = 32
        const val VREC_HEIGHT = 480
        const val VREC_MAX_CONTENT_HEIGHT = 420
        const val VREC_OVERLAP = 64
        const val DET_THRESHOLD = 0.5f
        const val REC_THRESHOLD = 0.1f

        /** Punctuation is the usual false positive between characters; GameSentenceMiner uses this factor too. */
        const val PUNCTUATION_FACTOR = 0.2f
        const val OVERLAP_THRESHOLD = 0.3
        private const val EPSILON = 1e-6

        // Kanji pairs the recogniser tends to emit in the wrong order.
        private val SWAPPED_PAIRS = mapOf(
            "儡傀" to "傀儡", "談冗" to "冗談", "汰淘" to "淘汰", "沱滂" to "滂沱",
            "攣痙" to "痙攣", "酊酩" to "酩酊", "麭麺" to "麺麭", "哭慟" to "慟哭",
        )

        private fun isPunctuation(codePoint: Int) = when (Character.getType(codePoint).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }

        /**
         * Keeps the most confident character wherever candidates overlap along
         * the reading direction, orders what is left, and builds the line.
         */
        internal fun assemble(line: Box, vertical: Boolean, candidates: List<Candidate>): TextLine? {
            for (c in candidates) if (isPunctuation(c.codePoint)) c.confidence *= PUNCTUATION_FACTOR
            val accepted = mutableListOf<Candidate>()
            for (c in candidates.sortedByDescending { it.confidence }) {
                val length = c.end - c.start + EPSILON
                val overlaps = accepted.any { a ->
                    if (c.start >= a.end || a.start >= c.end) return@any false
                    val shared = maxOf(0, minOf(c.end, a.end) - maxOf(c.start, a.start))
                    shared / minOf(length, a.end - a.start + EPSILON) > OVERLAP_THRESHOLD
                }
                if (!overlaps) accepted += c
            }
            if (accepted.isEmpty()) return null
            accepted.sortBy { it.start }

            val codes = accepted.map { it.codePoint }.toMutableList()
            var text = codes.joinToString("") { String(Character.toChars(it)) }
            for ((wrong, right) in SWAPPED_PAIRS) {
                // These pairs are all in the BMP, so a UTF-16 index is a character index when nothing before it is astral.
                val at = text.indexOf(wrong)
                if (at < 0 || text.codePointCount(0, at) != at || at + 1 >= codes.size) continue
                text = text.substring(0, at) + right + text.substring(at + 2)
                codes[at] = right.codePointAt(0)
                codes[at + 1] = right.codePointAt(1)
            }

            val chars = ArrayList<CharBox>(accepted.size)
            var index = 0
            for ((i, c) in accepted.withIndex()) {
                val units = Character.charCount(codes[i])
                chars += CharBox(index, index + units, c.box)
                index += units
            }
            return TextLine(text, line, chars, vertical)
        }

        /**
         * Bilinear resize of a region of [frame] into the top-left of a
         * zero-padded CHW float tensor, BGR, 0..1: what the models were fed in
         * training (OpenCV's INTER_LINEAR with half-pixel centres).
         */
        internal fun resizeInto(
            frame: Frame, srcX: Int, srcY: Int, srcW: Int, srcH: Int,
            dstW: Int, dstH: Int, out: FloatArray, tensorW: Int, tensorH: Int,
        ) {
            val plane = tensorW * tensorH
            val scaleX = srcW.toFloat() / dstW
            val scaleY = srcH.toFloat() / dstH
            val x0s = IntArray(dstW)
            val x1s = IntArray(dstW)
            val fxs = FloatArray(dstW)
            for (dx in 0 until dstW) {
                val sx = ((dx + 0.5f) * scaleX - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt()
                x0s[dx] = srcX + x0
                x1s[dx] = srcX + minOf(x0 + 1, srcW - 1)
                fxs[dx] = sx - x0
            }
            val pixels = frame.argb
            val stride = frame.width
            for (dy in 0 until dstH) {
                val sy = ((dy + 0.5f) * scaleY - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
                val y0 = sy.toInt()
                val fy = sy - y0
                val row0 = (srcY + y0) * stride
                val row1 = (srcY + minOf(y0 + 1, srcH - 1)) * stride
                val o = dy * tensorW
                for (dx in 0 until dstW) {
                    val fx = fxs[dx]
                    val a = pixels[row0 + x0s[dx]]
                    val b = pixels[row0 + x1s[dx]]
                    val c = pixels[row1 + x0s[dx]]
                    val d = pixels[row1 + x1s[dx]]
                    val w00 = (1 - fx) * (1 - fy)
                    val w01 = fx * (1 - fy)
                    val w10 = (1 - fx) * fy
                    val w11 = fx * fy
                    // Channel 0 is blue, 1 green, 2 red.
                    out[o + dx] = ((a and 0xFF) * w00 + (b and 0xFF) * w01 + (c and 0xFF) * w10 + (d and 0xFF) * w11) / 255f
                    out[plane + o + dx] =
                        (((a shr 8) and 0xFF) * w00 + ((b shr 8) and 0xFF) * w01 + ((c shr 8) and 0xFF) * w10 + ((d shr 8) and 0xFF) * w11) / 255f
                    out[2 * plane + o + dx] =
                        (((a shr 16) and 0xFF) * w00 + ((b shr 16) and 0xFF) * w01 + ((c shr 16) and 0xFF) * w10 + ((d shr 16) and 0xFF) * w11) / 255f
                }
            }
        }
    }
}
