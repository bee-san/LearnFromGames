// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * The reader's model files, downloaded at first run rather than shipped.
 * meikiocr's come first: they are small, their licence (LGPL-3.0) is known,
 * and they read until ScreenAI's files are there.
 */
class ModelStore(context: Context) {
    private val root = File(context.filesDir, "models")
    private val cache = context.cacheDir
    private val lock = Mutex()

    data class Remote(val name: String, val url: String, val sha256: String, val bytes: Long)

    data class MeikiFiles(val detector: File, val recogniser: File, val vertical: File)

    private val _meiki = MutableStateFlow(if (meikiFiles() != null) ModelState.Ready else ModelState.Missing)
    val meiki: StateFlow<ModelState> = _meiki

    private val _screenAi = MutableStateFlow(if (screenAiDir() != null) ModelState.Ready else ModelState.Missing)
    val screenAi: StateFlow<ModelState> = _screenAi

    fun meikiFiles(): MeikiFiles? {
        val dir = File(root, "meiki")
        val files = MEIKI.map { File(dir, it.name) }
        return if (files.all { it.isFile }) MeikiFiles(files[0], files[1], files[2]) else null
    }

    /** The directory ScreenAI reads its files from, once every file of the manifest is there. */
    fun screenAiDir(): File? {
        val dir = File(root, "screenai")
        return if (File(dir, COMPLETE).isFile) dir else null
    }

    /** Downloads meikiocr's three files unless they are there. Returns true when they are. */
    suspend fun ensureMeiki(): Boolean = lock.withLock {
        if (meikiFiles() != null) {
            _meiki.value = ModelState.Ready
            return true
        }
        val dir = File(root, "meiki")
        val total = MEIKI.sumOf { it.bytes }
        var done = MEIKI.filter { File(dir, it.name).isFile }.sumOf { it.bytes }
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                for (remote in MEIKI) {
                    val target = File(dir, remote.name)
                    if (target.isFile) continue
                    val before = done
                    fetch(remote, target) { got -> _meiki.value = ModelState.Downloading(before + got, total) }
                    done = before + remote.bytes
                }
            }
            _meiki.value = ModelState.Ready
            true
        } catch (e: Exception) {
            _meiki.value = ModelState.Failed(e.message ?: "The text recognition download failed.")
            false
        }
    }

    /**
     * Downloads the Screen AI component Google publishes for Chromium and
     * keeps the files ScreenAI's OCR reads. They cannot ship with the app: no
     * licence for them was found. Returns true when they are in place.
     */
    suspend fun ensureScreenAi(): Boolean = lock.withLock {
        if (screenAiDir() != null) {
            _screenAi.value = ModelState.Ready
            return true
        }
        try {
            withContext(Dispatchers.IO) {
                ScreenAiFiles.download(File(root, "screenai"), cache) { got, total ->
                    _screenAi.value = ModelState.Downloading(got, total)
                }
                File(File(root, "screenai"), COMPLETE).writeText("ok")
            }
            _screenAi.value = ModelState.Ready
            true
        } catch (e: Exception) {
            _screenAi.value = ModelState.Failed(e.message ?: "The ScreenAI download failed.")
            false
        }
    }

    private fun fetch(remote: Remote, target: File, progress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        val connection = (URL(remote.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != 200) error("The download answered ${connection.responseCode}.")
            val digest = MessageDigest.getInstance("SHA-256")
            var got = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        got += n
                        progress(got)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != remote.sha256) error("${remote.name} did not match its checksum.")
            if (!part.renameTo(target)) error("${remote.name} could not be saved.")
        } finally {
            part.delete()
            connection.disconnect()
        }
    }

    companion object {
        const val COMPLETE = ".complete"
        private const val DET = "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6/"
        private const val REC = "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/a28cf5874dc2438ebb1c86336be26bcec51e3375/"

        /** Pinned revisions and checksums: the files the unit tests read with. */
        val MEIKI = listOf(
            Remote(
                "meiki.text.detect.v0.1.960x544.onnx", DET + "meiki.text.detect.v0.1.960x544.onnx",
                "40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b", 14_503_825,
            ),
            Remote(
                "meiki.text.rec.v0.960x32.onnx", REC + "meiki.text.rec.v0.960x32.onnx",
                "3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000", 18_593_254,
            ),
            Remote(
                "meiki.text.rec.v0.vertical.32x480.onnx", REC + "meiki.text.rec.v0.vertical.32x480.onnx",
                "2c2a83a23bc3b7e6c63962175f507ecc6c5e85cc174f17bdec37d9bbd0bf895a", 12_872_961,
            ),
        )
    }
}
