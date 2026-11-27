// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.content.Context
import android.util.Log
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.capture.SoundCapture
import io.github.beesan.lfg.capture.VoiceFinder
import io.github.beesan.lfg.core.Wav
import io.github.beesan.lfg.data.Card
import io.github.beesan.lfg.data.LfgDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sentence audio for a card that already exists. The sound of a line runs
 * from when the line began to appear until the next line does, so it cannot be
 * cut when the card is made: the card gains it a moment later, without being
 * remade. Only the voice in that sound is kept. A card that gets no voice from
 * the game, because its sound is not being kept or the line was not spoken,
 * gets the line read by Android's voice when that is switched on.
 */
class MediaAttacher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val anki: AnkiBridge,
    private val db: LfgDatabase,
    private val prefs: () -> Prefs,
    private val speech: Speech,
) {
    private class Waiting(val card: Card, val target: AnkiTarget, val startMs: Long)

    private val waiting = ArrayList<Waiting>()

    private val voice by lazy { context.assets.open(VoiceFinder.ASSET).use { VoiceFinder.load(it) } }

    /** Called when a card has been added. */
    fun audioFor(card: Card, target: AnkiTarget, lineStartMs: Long?, fromApp: Boolean = false) {
        if (card.noteId == null) return
        if (NoteTypes.audioField(target.family, target.fields) == null) return
        // An app's sound is not its text's voice: a line from an app is only ever spoken by Android.
        if (fromApp || !prefs().sentenceAudio || !SoundCapture.running.value) {
            if (prefs().spokenLines) scope.launch(Dispatchers.IO) { speak(card, target) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val line = db.lines().byId(card.lineId) ?: return@launch
            val start = lineStartMs ?: line.createdAt
            // A card made from an older line: the next line has already come, so its sound is complete.
            val next = db.lines().nextAfter(line.gameId, line.createdAt)
            if (next != null) {
                finish(Waiting(card, target, start), next.createdAt)
                return@launch
            }
            val pending = Waiting(card, target, start)
            synchronized(waiting) { waiting += pending }
            // No next line yet. If none comes, the sound so far is the sentence's.
            delay(MAX_WAIT_MS)
            val still = synchronized(waiting) { waiting.remove(pending) }
            if (still) finish(pending, System.currentTimeMillis() + NEXT_LINE_GUARD_MS)
        }
    }

    /** A new line began to appear: that is where the sound of every waiting card ends. */
    fun onNextLine(appearedAtMs: Long) {
        val ready = synchronized(waiting) {
            val due = waiting.filter { appearedAtMs > it.startMs + MIN_CLIP_MS }
            waiting.removeAll(due.toSet())
            due
        }
        for (pending in ready) scope.launch(Dispatchers.IO) { finish(pending, appearedAtMs) }
    }

    private suspend fun finish(pending: Waiting, endMs: Long) {
        try {
            // The next line is noticed a moment after it appears, and its voice starts with it: stop short of that.
            val end = minOf(endMs - NEXT_LINE_GUARD_MS, pending.startMs + MAX_CLIP_MS)
            val raw = SoundCapture.clip(pending.startMs - LEAD_IN_MS, end)
            val samples = raw?.let { voice.trim(it, SoundCapture.SAMPLE_RATE) }
            Log.i(TAG, "clip of ${end - pending.startMs} ms: ${raw?.size ?: -1} samples, ${samples?.size ?: -1} of voice")
            // A line with no voice has no sound worth a field.
            if (samples == null || samples.size < SoundCapture.SAMPLE_RATE / 4) {
                if (prefs().spokenLines) speak(pending.card, pending.target)
                return
            }
            val name = "lfg_${pending.card.createdAt}"
            val file = File(mediaDir(), "$name.m4a")
            if (SoundCapture.encode(samples, file)) attach(pending.card, pending.target, file, name)
        } catch (e: Exception) {
            Log.w(TAG, "sentence audio failed", e)
        }
    }

    private suspend fun speak(card: Card, target: AnkiTarget) {
        try {
            // In the voice of the language the line was read as: a card waiting may outlast a change of profile.
            val line = db.lines().byId(card.lineId) ?: return
            val text = line.lineText
            val name = "lfg_${card.createdAt}"
            val wav = File(mediaDir(), "$name.wav")
            if (!speech.toFile(text, wav, io.github.beesan.lfg.core.Language.orJapanese(line.language))) return
            // Android's voice writes WAV, ten times the size of the game's own sound: made AAC like it, kept WAV if that fails.
            val m4a = File(mediaDir(), "$name.m4a")
            val pcm = Wav.read(wav.readBytes())
            if (pcm != null && pcm.samples.isNotEmpty() && SoundCapture.encode(pcm.samples, m4a, pcm.rate)) {
                wav.delete()
                attach(card, target, m4a, name)
            } else {
                attach(card, target, wav, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "spoken line failed", e)
        }
    }

    /** Puts a sound file on the card's audio field and records it. */
    private suspend fun attach(card: Card, target: AnkiTarget, file: File, name: String) {
        val noteId = card.noteId ?: return
        val index = NoteTypes.audioField(target.family, target.fields) ?: return
        val tag = anki.addSound(file, name)
        if (tag == null || !anki.setField(noteId, index, tag)) {
            file.delete()
            return
        }
        db.cards().setAudio(card.id, name)
        db.lines().setAudio(card.lineId, file.path, System.currentTimeMillis())
    }

    private fun mediaDir() = File(context.filesDir, "media").apply { mkdirs() }

    private companion object {
        const val TAG = "LfgMedia"
        const val MAX_WAIT_MS = 20_000L
        const val MAX_CLIP_MS = 30_000L
        const val MIN_CLIP_MS = 300L
        const val NEXT_LINE_GUARD_MS = 450L

        /** The reader notices a line a moment after it appears; the voice starts with it. */
        const val LEAD_IN_MS = 400L
    }
}
