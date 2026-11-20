// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.capture.FrameRing
import io.github.beesan.lfg.data.Card
import io.github.beesan.lfg.data.LfgDatabase
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.WordAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

sealed interface CardResult {
    data class Added(val noteId: Long) : CardResult
    data object Duplicate : CardResult

    /** Kept in the app; sent when AnkiDroid is there. */
    data class Waiting(val reason: String) : CardResult
    data class Failed(val reason: String) : CardResult
}

/**
 * Makes a card whole in one step. The tap already names the line, so there is
 * no polling of Anki and no sentence matching as on desktop: check for a
 * duplicate, store the picture, add the note, record it.
 */
class CardMaker(
    private val anki: AnkiBridge,
    private val db: LfgDatabase,
    private val store: LineStore,
    private val prefs: () -> Prefs,
    private val media: MediaAttacher,
    private val mediaDir: File,
    private val wordAudio: WordAudio? = null,
    /** A card was made: the Today widget counts them. */
    private val onCard: () -> Unit = {},
) {
    suspend fun add(lineId: String, content: CardContent): CardResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val row = Card(
            lineId = lineId, term = content.expression, reading = content.reading,
            state = Card.STATE_PENDING, createdAt = now, payload = Json.encodeToString(content),
        )
        if (anki.status() != AnkiStatus.READY) {
            db.cards().insert(row)
            onCard()
            return@withContext CardResult.Waiting(
                if (anki.status() == AnkiStatus.NOT_INSTALLED) "AnkiDroid is not installed." else "AnkiDroid is not connected.",
            )
        }
        var id = 0L
        try {
            if (anki.target?.let { anki.isDuplicate(it, content.expression) } == true) return@withContext CardResult.Duplicate
            id = db.cards().insert(row)
            onCard()
            send(row.copy(id = id), content)
        } catch (e: Exception) {
            android.util.Log.w("LfgCards", "making a card failed", e)
            // Shown as failed, so it is added again by hand: left pending, a later flush would send it a second time.
            if (id != 0L) runCatching { db.cards().update(row.copy(id = id, state = Card.STATE_FAILED)) }
            CardResult.Failed("AnkiDroid did not answer. Open AnkiDroid once, then try again.")
        }
    }

    /** Sends cards that were made while AnkiDroid was missing. Returns how many were added. */
    suspend fun flushPending(): Int = withContext(Dispatchers.IO) {
        // One at a time: the service's start and the app's return to the front can both ask at once.
        flushing.withLock { flushNow() }
    }

    private val flushing = Mutex()

    private suspend fun flushNow(): Int {
        if (anki.status() != AnkiStatus.READY) return 0
        var added = 0
        for (card in db.cards().pending()) {
            val content = try {
                Json.decodeFromString<CardContent>(card.payload)
            } catch (_: Exception) {
                db.cards().update(card.copy(state = Card.STATE_FAILED))
                continue
            }
            try {
                if (send(card, content) is CardResult.Added) added++
            } catch (_: Exception) {
                // AnkiDroid stopped answering: the rest stay pending for the next attempt.
                break
            }
        }
        return added
    }

    private suspend fun send(card: Card, content: CardContent): CardResult {
        // The saved choice is used as is; it is looked for again when it has gone, and while it is
        // only the app's own note type: Kiku, Lapis or Senren installed since then is what was wanted.
        var target = anki.target ?: anki.chooseTarget()
        if (target.family == Family.OWN || !anki.targetStillValid(target)) target = anki.chooseTarget()

        val pictureName = "lfg_${card.createdAt}"
        // A short moving picture of the line appearing, when that is switched on and the frames are there; else the still.
        val moving = if (prefs().animatedPicture && !content.fromApp) FrameRing.animation(content.lineStartMs, mediaDir, pictureName) else null
        val picture = moving ?: content.picturePath?.let(::File)?.takeIf { it.isFile }
        val pictureHtml = picture?.let { anki.addPicture(it, pictureName) } ?: ""
        moving?.delete()
        // The word said by a person, from the word audio, when the note type has a field for it.
        val soundName = "lfg_word_${card.createdAt}"
        val sound = if (wordAudio != null && NoteTypes.hasWordAudioField(target.family, target.fields)) {
            wordAudio.export(content.expression, content.reading, mediaDir, soundName)
        } else {
            null
        }
        val soundTag = sound?.let { anki.addSound(it, soundName) } ?: ""
        sound?.delete()
        val fields = NoteTypes.fill(target.family, target.fields, content, pictureHtml, soundTag)
        val noteId = anki.addNote(target, fields, setOf("LFG", NoteTypes.gameTag(content.gameName)))
        if (noteId == null) {
            db.cards().update(card.copy(state = Card.STATE_FAILED))
            return CardResult.Failed("AnkiDroid refused the card.")
        }
        val added = card.copy(noteId = noteId, state = Card.STATE_ADDED, pictureName = pictureName.takeIf { pictureHtml.isNotEmpty() })
        db.cards().update(added)
        store.attachNote(card.lineId, noteId)
        media.audioFor(added, target, content.lineStartMs, content.fromApp)
        return CardResult.Added(noteId)
    }
}
