// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.beesan.lfg.core.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Android's own voice reading a line, aloud or into a file for a card the game
 * gave no voice. Started on first use. It reads in the voice of the language
 * being learnt, and does nothing when the device has no voice for it.
 */
class Speech(
    private val context: Context,
    /** The language being learnt. Asked before each line, since it can be changed while the app runs. */
    private val language: () -> Language = { Language.JAPANESE },
) {
    private val lock = Mutex()
    private var engine: TextToSpeech? = null
    private var finished: CompletableDeferred<Boolean>? = null

    /** The engine, set to the voice of the language being learnt now; null when there is no such voice. */
    private suspend fun ready(voiceOf: Language? = null): TextToSpeech? {
        val tts = engine ?: start() ?: return null
        val learning = voiceOf ?: language()
        if (tts.setLanguage(Locale.forLanguageTag(learning.voice)) < TextToSpeech.LANG_AVAILABLE) {
            Log.i(TAG, "no ${learning.title} voice")
            return null
        }
        return tts
    }

    private suspend fun start(): TextToSpeech? {
        val started = CompletableDeferred<Boolean>()
        val tts = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> started.complete(status == TextToSpeech.SUCCESS) }
        }
        if (!started.await()) {
            Log.i(TAG, "no voice: text-to-speech did not start")
            tts.shutdown()
            return null
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            // Only the file's own utterance ends the wait: a line said aloud meanwhile is not it.
            override fun onDone(utteranceId: String?) {
                if (utteranceId == FILE) finished?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == FILE) finished?.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == FILE) finished?.complete(false)
            }
        })
        engine = tts
        return tts
    }

    /** Writes [text] spoken to [file] as WAV, in [language]'s voice (the one being learnt when null). Returns false when there is no voice or it failed. */
    suspend fun toFile(text: String, file: File, language: Language? = null): Boolean = lock.withLock {
        val tts = ready(language) ?: return false
        val done = CompletableDeferred<Boolean>().also { finished = it }
        if (tts.synthesizeToFile(text, Bundle(), file, FILE) != TextToSpeech.SUCCESS) return false
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
        const val FILE = "line"
        const val TIMEOUT_MS = 30_000L
        const val WAV_HEADER = 44L
    }
}
