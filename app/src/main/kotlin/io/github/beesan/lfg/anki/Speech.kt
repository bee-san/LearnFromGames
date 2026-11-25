// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Android's own voice reading a line into a file, for a card the game gave no
 * voice. Started on first use; does nothing without a Japanese voice.
 */
class Speech(private val context: Context) {
    private val lock = Mutex()
    private var engine: TextToSpeech? = null
    private var finished: CompletableDeferred<Boolean>? = null

    private suspend fun ready(): TextToSpeech? {
        engine?.let { return it }
        val started = CompletableDeferred<Boolean>()
        val tts = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> started.complete(status == TextToSpeech.SUCCESS) }
        }
        if (!started.await() || tts.setLanguage(Locale.JAPANESE) < TextToSpeech.LANG_AVAILABLE) {
            Log.i(TAG, "no Japanese voice")
            tts.shutdown()
            return null
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                finished?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finished?.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finished?.complete(false)
            }
        })
        engine = tts
        return tts
    }

    /** Writes [text] spoken to [file] as WAV. Returns false when there is no voice or it failed. */
    suspend fun toFile(text: String, file: File): Boolean = lock.withLock {
        val tts = ready() ?: return false
        val done = CompletableDeferred<Boolean>().also { finished = it }
        if (tts.synthesizeToFile(text, Bundle(), file, "line") != TextToSpeech.SUCCESS) return false
        val ok = withTimeoutOrNull(TIMEOUT_MS) { done.await() } == true && file.length() > WAV_HEADER
        if (!ok) file.delete()
        ok
    }

    /** Says [text] aloud now, in place of anything it was saying. False when there is no voice. */
    suspend fun say(text: String): Boolean = lock.withLock {
        val tts = ready() ?: return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "aloud") == TextToSpeech.SUCCESS
    }

    private companion object {
        const val TAG = "LfgSpeech"
        const val TIMEOUT_MS = 30_000L
        const val WAV_HEADER = 44L
    }
}
