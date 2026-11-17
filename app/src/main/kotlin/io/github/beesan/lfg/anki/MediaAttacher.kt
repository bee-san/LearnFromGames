// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.content.Context
import android.util.Log
import io.github.beesan.lfg.capture.SampleRing
import io.github.beesan.lfg.capture.SoundCapture
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
 * remade.
 */
class MediaAttacher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val anki: AnkiBridge,
    private val db: LfgDatabase,
) {
    private class Waiting(val card: Card, val target: AnkiTarget, val startMs: Long)

    private val waiting = ArrayList<Waiting>()

    /** Called when a card has been added while game sound is being kept. */
    fun audioFor(card: Card, target: AnkiTarget, lineStartMs: Long?) {
        if (!SoundCapture.running.value || card.noteId == null) return
        if (NoteTypes.audioField(target.family, target.fields) == null) return
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
            val noteId = pending.card.noteId ?: return
            val index = NoteTypes.audioField(pending.target.family, pending.target.fields) ?: return
            // The next line is noticed a moment after it appears, and its voice starts with it: stop short of that.
            val end = minOf(endMs - NEXT_LINE_GUARD_MS, pending.startMs + MAX_CLIP_MS)
            val raw = SoundCapture.clip(pending.startMs - LEAD_IN_MS, end)
            val samples = raw?.let { SampleRing.trimSilence(it, SoundCapture.SAMPLE_RATE) }
            Log.i(TAG, "clip of ${end - pending.startMs} ms: ${raw?.size ?: -1} samples, ${samples?.size ?: -1} after trimming, peak ${raw?.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: -1}")
            if (samples == null) return
            // A line with no voice has no sound worth a field.
            if (samples.size < SoundCapture.SAMPLE_RATE / 4) return
            val name = "lfg_${pending.card.createdAt}"
            val file = File(File(context.filesDir, "media").apply { mkdirs() }, "$name.m4a")
            if (!SoundCapture.encode(samples, file)) return
            val tag = anki.addSound(file, name)
            if (tag == null || !anki.setField(noteId, index, tag)) {
                file.delete()
                return
            }
            db.cards().setAudio(pending.card.id, name)
            db.lines().setAudio(pending.card.lineId, file.path, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "sentence audio failed", e)
        }
    }

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
