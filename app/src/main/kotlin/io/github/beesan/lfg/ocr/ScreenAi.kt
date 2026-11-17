// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
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
class ScreenAi private constructor() {
    fun read(frame: Frame): List<TextLine> {
        val bytes = nativeRead(frame.argb, frame.width, frame.height) ?: error("ScreenAI could not read the frame.")
        return decode(bytes)
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

        fun open(dir: File): ScreenAi? = if (loaded && nativeInit(dir.path.toByteArray())) ScreenAi() else null

        @JvmStatic
        private external fun nativeAvailable(): Boolean

        @JvmStatic
        private external fun nativeInit(dir: ByteArray): Boolean

        @JvmStatic
        private external fun nativeRead(pixels: IntArray, width: Int, height: Int): ByteArray?

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
            return TextLine(trimmed, bounds, chars.filter { it.start < trimmed.length }, vertical = direction == 3L)
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
     * main-content models and Chrome's library. Kanji-only lines use the Hani
     * recogniser and kana lines the Jpan one, so both stay.
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
