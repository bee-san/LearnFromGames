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

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/** ScreenAI's model files, downloaded at first run rather than shipped. */
class ModelStore(context: Context) {
    private val root = File(context.filesDir, "models")
    private val cache = context.cacheDir
    private val lock = Mutex()

    private val _screenAi = MutableStateFlow(if (screenAiDir() != null) ModelState.Ready else ModelState.Missing)
    val screenAi: StateFlow<ModelState> = _screenAi

    /** meikiocr's files, from before ScreenAI became the only reader: 45 MB that nothing reads now. */
    fun forgetMeiki() {
        File(root, "meiki").takeIf { it.isDirectory }?.deleteRecursively()
    }

    /** The directory ScreenAI reads its files from, once every file of the manifest is there. */
    fun screenAiDir(): File? {
        val dir = File(root, "screenai")
        return if (File(dir, COMPLETE).isFile) dir else null
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

    companion object {
        const val COMPLETE = ".complete"

        /** ScreenAI's download as it was when this build was made: the zip, not what is kept of it. */
        const val SCREENAI_BYTES = 78_000_000L
    }
}
