// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.GlyphBoxes
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.core.mirrored
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * ScreenAI: screenai_ocr (AuroraWright, MPL-2.0), a reimplementation of the
 * OCR in Chrome's Screen AI library, running the model files Google publishes
 * for Chromium. The library is built by tools/build_screenai.sh and is
 * optional; the model files are downloaded, never shipped.
 */
class ScreenAi private constructor(
    /** The language read is written right to left: a row's pieces are ordered and joined from the right. */
    private val rightToLeft: Boolean = false,
) {
    /** [textHeight]: the smallest text's height in pixels, 0 for not known (tools/screenai/0006). */
    fun read(frame: Frame, textHeight: Int = 0): List<TextLine> {
        val bytes = nativeRead(frame.argb, frame.width, frame.height, textHeight) ?: error("ScreenAI could not read the frame.")
        val lines = decode(bytes)
        if (!rightToLeft) return joined(ordered(lines))
        // In a mirror, a row of Arabic runs as one of English does: its first word is the one at the left.
        return joined(ordered(lines.map { it.mirrored() })).map { it.mirrored() }
    }

    companion object {
        private val loaded: Boolean by lazy {
            try {
                // Loaded here first so the wrapper inside liblfg finds it by name.
                System.loadLibrary("chromescreenai")
                System.loadLibrary("lfg")
                nativeAvailable()
            } catch (_: Throwable) {
                false
            }
        }

        /** Whether this build of the app carries the library at all. */
        fun available(): Boolean = loaded

        /**
         * [language] is the tag of the language on screen (`ja`), when it is known: every line is then read
         * once, as that language (tools/screenai/0005). Empty, each line's script is guessed first, as Chrome does.
         */
        fun open(dir: File, language: String = ""): ScreenAi? =
            if (loaded && nativeInit(dir.path.toByteArray(), language.toByteArray())) {
                ScreenAi(io.github.beesan.lfg.core.Language.of(language)?.rightToLeft == true)
            } else {
                null
            }

        @JvmStatic
        private external fun nativeAvailable(): Boolean

        @JvmStatic
        private external fun nativeInit(dir: ByteArray, language: ByteArray): Boolean

        @JvmStatic
        private external fun nativeRead(pixels: IntArray, width: Int, height: Int, textHeight: Int): ByteArray?

        /**
         * The library is asked for its fast mode (tools/screenai/0003), which gives the lines in the order it
         * found them rather than in reading order. Here they are put top to bottom, the pieces of one row left
         * to right, and columns after them from the right: the order a reader of the screen expects of a reader.
         */
        internal fun ordered(lines: List<TextLine>): List<TextLine> {
            val (columns, rows) = lines.partition { it.vertical }
            val byTop = rows.sortedBy { it.box.top }
            val out = ArrayList<TextLine>(lines.size)
            var i = 0
            while (i < byTop.size) {
                // A row: what starts above the middle of its topmost piece.
                val middle = (byTop[i].box.top + byTop[i].box.bottom) / 2
                var j = i + 1
                while (j < byTop.size && byTop[j].box.top < middle) j++
                out += byTop.subList(i, j).sortedBy { it.box.left }
                i = j
            }
            out += columns.sortedWith(compareBy({ -it.box.right }, { it.box.top }))
            return out
        }

        /**
         * How wide a space between two pieces of one row may be, in characters of their type, for them to be
         * one row: on 260 screens of Pokémon Let's Go kept on an AYN Thor the space between two words was 1.0 to
         * 1.55, and from one button of a menu to the next 1.8 and more.
         */
        private const val SPACE = 1.65f

        /** The least one piece's height may be of the other's for both to be the same type: a row of small kana is 0.75 of one with kanji. */
        private const val SAME_TYPE = 0.7f

        private const val OPENS = "「『（(【“\""

        /**
         * The rows the library cut at a space, whole again. A game that sets its words apart, as Pokémon
         * does, is given back whole on one picture and a word at a time on the next (「タイプの」「相性を」), and
         * a line in which a sentence ends, cut after its ! or ?. A word alone is no line: under one
         * another, the words of two rows read as columns, and a short one is taken for a menu's label.
         *
         * [lines] are in reading order ([ordered]). A piece goes on the one before it when both are on one
         * row in the same type and no more than a space apart ([SPACE]); they are joined with the space the
         * library itself puts after a word. A speaker's name stays apart from what is said beside it: speech
         * that opens with a bracket the piece before has none of, or a name in 【】.
         */
        internal fun joined(lines: List<TextLine>): List<TextLine> {
            val out = ArrayList<TextLine>(lines.size)
            for (line in lines) {
                val last = out.lastOrNull()
                val from = if (last == null) -1 else goesOn(last, line)
                if (last == null || from < 0) {
                    out += line
                    continue
                }
                // Whole, it follows a space; what is left of a piece whose first characters were read twice follows as it is.
                val head = if (from == 0) last.text + " " else last.text
                val offset = head.length - from
                out[out.lastIndex] = TextLine(
                    head + line.text.substring(from), last.box.union(line.box),
                    last.chars + line.chars.filter { it.start >= from }.map { CharBox(it.start + offset, it.end + offset, it.box) },
                )
            }
            return out
        }

        /**
         * Whether [next] is the rest of [line]'s row: beside it, in the same type, a space away at most. -1 when
         * it is not; else where in its text what it adds begins: 0, but for a piece the library began on the end
         * of the one before, reading a character or two twice (「冒険を」 and then 「を　楽しもう!」).
         */
        private fun goesOn(line: TextLine, next: TextLine): Int {
            if (line.vertical || next.vertical) return -1
            val a = line.box
            val b = next.box
            val low = minOf(a.height, b.height)
            val tall = maxOf(a.height, b.height)
            if (low <= 0 || low < SAME_TYPE * tall) return -1
            if (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) < 0.6f * low) return -1
            val size = maxOf(pitch(line, next, tall), tall.toFloat())
            val gap = b.left - a.right
            if (gap > SPACE * size) return -1
            val opens = next.text.trimStart().firstOrNull()
            if (opens != null && opens in OPENS && line.text.none { it in OPENS }) return -1
            val name = line.text.trim()
            if (name.startsWith('【') && name.endsWith('】')) return -1
            if (gap >= -0.25f * size) return 0
            val twice = next.chars.sortedBy { it.start }.takeWhile { it.box.left + it.box.right < 2 * a.right }
            if (twice.isEmpty() || twice.size == next.chars.size || twice[0].start != 0) return -1
            val end = twice.last().end
            return if (line.text.endsWith(next.text.substring(0, end))) end else -1
        }

        /**
         * From one character to the next in [a] and [b], middle to middle: the cell their type is set in, which a
         * row of small kana is as wide as any other though it is not as tall. 0 when it cannot be told, or is
         * nothing like the [height] of the rows (letters, which are narrow).
         */
        private fun pitch(a: TextLine, b: TextLine, height: Int): Float {
            val steps = ArrayList<Float>()
            for (line in listOf(a, b)) {
                line.chars.sortedBy { it.start }.zipWithNext { c, d ->
                    val step = (d.box.left + d.box.right - c.box.left - c.box.right) / 2f
                    if (c.end == d.start && step > 0) steps += step
                }
            }
            val least = steps.minOrNull() ?: return 0f
            val near = steps.filter { it <= 1.5f * least }.sorted()
            val pitch = near[near.size / 2]
            return if (pitch >= 0.6f * height && pitch <= 1.6f * height) pitch else 0f
        }

        /**
         * A serialized `chrome_screen_ai.VisualAnnotation` as lines. A
         * Japanese line arrives as words of symbols, each symbol with its own
         * box: exactly the box per character the tap strips need.
         */
        fun decode(bytes: ByteArray): List<TextLine> {
            val lines = mutableListOf<TextLine>()
            Proto(bytes).forEach { field, value ->
                if (field == 2 && value is ByteArray) line(value)?.let { lines += it }
            }
            return lines
        }

        private fun line(bytes: ByteArray): TextLine? {
            var box: Box? = null
            var direction = 0L
            val text = StringBuilder()
            val chars = mutableListOf<CharBox>()
            Proto(bytes).forEach { field, value ->
                when {
                    field == 2 && value is ByteArray -> box = rect(value)
                    field == 7 && value is Long -> direction = value
                    field == 1 && value is ByteArray -> word(value, text, chars)
                }
            }
            val bounds = box ?: return null
            val content = text.toString()
            if (content.isBlank()) return null
            // A space after the last word is the recogniser's, not the line's.
            val trimmed = content.trimEnd()
            val vertical = direction == 3L
            val (fixed, boxes) = ellipses(trimmed, chars.filter { it.start < trimmed.length }, vertical, bounds)
            return TextLine(fixed, bounds, GlyphBoxes.even(fixed, boxes, vertical), vertical = vertical)
        }

        private const val DOTS = "…・.‥･·⋯"

        /**
         * How a run of dots `w` wide, in a line whose characters are `h` tall,
         * becomes a count of ellipses: round(w / (CELL * h) + LEAN). Fitted on
         * 356 runs of the OCR bench (tools/ocr_bench.py), where it gives the
         * game's own count for 343; one `…` spans about 0.94 h, two about 2.2 h.
         */
        private const val CELL = 1.4
        private const val LEAN = 0.3

        /**
         * ScreenAI reads an ellipsis as a smear of dots of any number: `……`
         * comes out as `……………` or `・…・・`. Each run of dots is read again
         * from how much of the line it covers, so
         * the text says what the game drew. A lone `・` is a middle dot, as in
         * a name, and stays.
         */
        internal fun ellipses(text: String, chars: List<CharBox>, vertical: Boolean, line: Box): Pair<String, List<CharBox>> {
            fun symbol(c: CharBox) = text.substring(c.start, c.end)
            fun dot(c: CharBox) = symbol(c).let { it.length == 1 && it[0] in DOTS }
            if (chars.none(::dot)) return text to chars
            fun across(b: Box) = if (vertical) b.width else b.height
            fun lo(b: Box) = if (vertical) b.top else b.left
            fun hi(b: Box) = if (vertical) b.bottom else b.right
            val solid = chars.filterNot(::dot).map { across(it.box) }.sorted()
            val cell = CELL * (if (solid.isEmpty()) across(line) else solid[solid.size / 2]).coerceAtLeast(1)

            val out = StringBuilder(text.substring(0, chars.first().start))
            val boxes = ArrayList<CharBox>(chars.size)
            var i = 0
            while (i < chars.size) {
                if (!dot(chars[i])) {
                    boxes += CharBox(out.length, out.length + chars[i].end - chars[i].start, chars[i].box)
                    out.append(text, chars[i].start, chars.getOrNull(i + 1)?.start ?: text.length)
                    i++
                    continue
                }
                var j = i
                while (j < chars.size && dot(chars[j])) j++
                val run = chars.subList(i, j)
                val start = run.minOf { lo(it.box) }
                val end = run.maxOf { hi(it.box) }
                if (run.size == 1 && symbol(run[0]) != "…") {
                    boxes += CharBox(out.length, out.length + 1, run[0].box)
                    out.append(symbol(run[0]))
                } else {
                    val count = Math.round((end - start) / cell + LEAN).toInt().coerceAtLeast(1)
                    val first = run.minOf { if (vertical) it.box.left else it.box.top }
                    val last = run.maxOf { if (vertical) it.box.right else it.box.bottom }
                    for (k in 0 until count) {
                        val a = start + (end - start) * k / count
                        val b = start + (end - start) * (k + 1) / count
                        boxes += CharBox(out.length, out.length + 1, if (vertical) Box(first, a, last, b) else Box(a, first, b, last))
                        out.append('…')
                    }
                }
                // Whatever followed the run in the text, such as a space.
                out.append(text, run.last().end, chars.getOrNull(j)?.start ?: text.length)
                i = j
            }
            return out.toString() to boxes
        }

        private fun word(bytes: ByteArray, text: StringBuilder, chars: MutableList<CharBox>) {
            var spaceAfter = false
            var wordText = ""
            var wordBox: Box? = null
            var symbols = 0
            Proto(bytes).forEach { field, value ->
                when {
                    field == 1 && value is ByteArray -> {
                        var symbolBox: Box? = null
                        var symbol = ""
                        Proto(value).forEach { f, v ->
                            if (f == 1 && v is ByteArray) symbolBox = rect(v)
                            if (f == 2 && v is ByteArray) symbol = v.decodeToString()
                        }
                        val b = symbolBox
                        if (symbol.isNotEmpty() && b != null) {
                            chars += CharBox(text.length, text.length + symbol.length, b)
                            text.append(symbol)
                            symbols++
                        }
                    }
                    field == 2 && value is ByteArray -> wordBox = rect(value)
                    field == 3 && value is ByteArray -> wordText = value.decodeToString()
                    field == 6 && value is Long -> spaceAfter = value != 0L
                }
            }
            val b = wordBox
            if (symbols == 0 && wordText.isNotEmpty() && b != null) {
                // No symbols for this word: the word is one tappable piece.
                chars += CharBox(text.length, text.length + wordText.length, b)
                text.append(wordText)
            }
            if (spaceAfter) text.append(' ')
        }

        /** The upright box around a Rect; its angle (a rotation about its corner) is ignored. */
        private fun rect(bytes: ByteArray): Box {
            var x = 0
            var y = 0
            var w = 0
            var h = 0
            Proto(bytes).forEach { field, value ->
                if (value is Long) when (field) {
                    1 -> x = value.toInt()
                    2 -> y = value.toInt()
                    3 -> w = value.toInt()
                    4 -> h = value.toInt()
                }
            }
            return Box(x, y, x + w, y + h)
        }
    }

    /** Just enough of protobuf's wire format: varints, length-delimited fields, and skipping the fixed ones. */
    private class Proto(private val data: ByteArray) {
        private var at = 0

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (at < data.size) {
                val b = data[at++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) break
            }
            return result
        }

        /** Calls [use] with each field: a Long for a varint, a ByteArray for length-delimited. */
        inline fun forEach(use: (field: Int, value: Any) -> Unit) {
            while (at < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> use(field, varint())
                    1 -> at += 8
                    2 -> {
                        val length = varint().toInt()
                        if (length < 0 || at + length > data.size) return
                        use(field, data.copyOfRange(at, at + length))
                        at += length
                    }
                    5 -> at += 4
                    else -> return
                }
            }
        }
    }
}

/**
 * The Screen AI component from Chromium's package store (CIPD). The package
 * is a zip that also holds Chrome's own library, which is not wanted: only
 * the model files the Japanese reader opens are kept, about 14 MB of 78.
 */
object ScreenAiFiles {
    private const val API = "https://chrome-infra-packages.appspot.com/prpc/cipd.Repository/"
    private const val PACKAGE = "chromium/third_party/screen-ai/linux"

    /** The instance the reader was tested against; its SHA-256 is the instance id itself. */
    const val INSTANCE = "b9c06974122103e4dcfe4bbc942c5a90668a1b42df880786bcd86e450fde33ad"

    private const val RECOGNISERS = "gocr/gocr_models/line_recognition_mobile_convnext320_omni/"

    /**
     * Whether a file of the package is one the reader needs. Everything under
     * `resources/` is kept except the recognisers of other scripts, the
     * main-content models and Chrome's library. Told the language is Japanese
     * the reader opens only the Jpan recogniser; the multi-script and Hani
     * ones stay for a library that cannot be told.
     */
    fun wanted(entry: String): String? {
        val path = entry.removePrefix("resources/")
        if (path == entry || path.endsWith("/") || path.contains("..")) return null
        if (path.endsWith(".so") || path.endsWith(".dll") || path.endsWith(".dylib") || path.startsWith("screen2x")) return null
        if (path.startsWith(RECOGNISERS)) {
            val name = path.removePrefix(RECOGNISERS)
            val keep = listOf("gocr_mobile_und", "hanijpan", "jpan_", "hani_", "tflite_langid").any { name.startsWith(it) }
            return if (keep) path else null
        }
        return if (path.startsWith("aksara/") || path.startsWith("gocr/")) path else null
    }

    fun download(target: File, cache: File, progress: (Long, Long) -> Unit) {
        val url = signedUrl()
        val staging = File(cache, "screenai-staging").apply { deleteRecursively(); mkdirs() }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (connection.responseCode != 200) error("The ScreenAI download answered ${connection.responseCode}.")
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            val counted = Counting(DigestInputStream(connection.inputStream, digest)) { progress(it, total) }
            var kept = 0
            ZipInputStream(counted).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = wanted(entry.name) ?: continue
                    val file = File(staging, path)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                    kept++
                }
                // The checksum covers the whole file, including what the unzipper did not need to read.
                val rest = ByteArray(64 * 1024)
                while (counted.read(rest) >= 0) Unit
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != INSTANCE) error("The ScreenAI package did not match its checksum.")
            if (kept == 0) error("The ScreenAI package held no model files.")
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
            connection.disconnect()
        }
    }

    private fun signedUrl(): String {
        val body = """{"package":"$PACKAGE","instance":{"hashAlgo":"SHA256","hexDigest":"$INSTANCE"}}"""
        val connection = (URL(API + "GetInstanceURL").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != 200) error("Chromium's package store answered ${connection.responseCode}.")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            // The reply is JSON behind an anti-hijacking prefix.
            return Regex("\"signedUrl\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.replace("\\u0026", "&")
                ?: error("Chromium's package store gave no download address.")
        } finally {
            connection.disconnect()
        }
    }

    private class Counting(private val input: InputStream, private val onProgress: (Long) -> Unit) : InputStream() {
        private var count = 0L
        override fun read(): Int = input.read().also { if (it >= 0) onProgress(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also {
            if (it > 0) {
                count += it
                onProgress(count)
            }
        }
        override fun close() = input.close()
    }
}
