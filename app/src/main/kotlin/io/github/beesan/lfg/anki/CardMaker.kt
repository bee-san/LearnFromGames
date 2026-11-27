// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.ask.Explainer
import io.github.beesan.lfg.core.BlackBars
import io.github.beesan.lfg.core.Sentence
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
                // The word may have been added since the card was made, or twice while AnkiDroid was away: it is the note already there.
                val there = anki.target?.let { anki.noteId(it, content.expression) }
                if (there != null) {
                    db.cards().update(card.copy(noteId = there, state = Card.STATE_ADDED))
                    store.attachNote(card.lineId, there)
                    continue
                }
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
        val trim = prefs().trimBars && !content.fromApp
        val moving = if (prefs().animatedPicture && !content.fromApp) FrameRing.animation(content.lineStartMs, mediaDir, pictureName, trim) else null
        val still = content.picturePath?.let(::File)?.takeIf { moving == null && it.isFile }
        val made = still?.let { forCard(it, pictureName, trim) }
        val picture = moving ?: made ?: still
        val pictureHtml = picture?.let { anki.addPicture(it, pictureName) } ?: ""
        moving?.delete()
        made?.delete()
        // The word said by a person, from the word audio, when the note type has a field for it.
        val soundName = "lfg_word_${card.createdAt}"
        val sound = if (wordAudio != null && !content.kanji && NoteTypes.hasWordAudioField(target.family, target.fields)) {
            wordAudio.export(content.expression, content.reading, mediaDir, soundName)
        } else {
            null
        }
        val soundTag = sound?.let { anki.addSound(it, soundName) } ?: ""
        sound?.delete()
        val fields = NoteTypes.fill(target.family, target.fields, content, pictureHtml, soundTag, explained(card.lineId))
        // The game's name as it is now: a card that waited for AnkiDroid may have outlasted a rename.
        val gameName = db.lines().byId(card.lineId)?.gameName ?: content.gameName
        val noteId = anki.addNote(target, fields, setOfNotNull("LFG", NoteTypes.gameTag(gameName), "Kanji".takeIf { content.kanji }))
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

    /**
     * Puts [picture] on the card made last, in place of the one it has: for when
     * the right moment comes after the line. Says what was done, in a few words.
     */
    suspend fun pictureToLast(picture: File?): String = withContext(Dispatchers.IO) {
        if (picture == null || !picture.isFile) return@withContext "There is no picture of this screen yet."
        if (anki.status() != AnkiStatus.READY) return@withContext "AnkiDroid is not connected."
        val card = db.cards().newestAdded() ?: return@withContext "No card has been made yet."
        val noteId = card.noteId ?: return@withContext "No card has been made yet."
        try {
            val target = anki.target ?: return@withContext "AnkiDroid is not connected."
            val index = NoteTypes.pictureField(target.family, target.fields) ?: return@withContext "The note type has no picture field."
            val name = "lfg_${card.createdAt}_${System.currentTimeMillis()}"
            val made = forCard(picture, name, prefs().trimBars)
            val html = anki.addPicture(made ?: picture, name)
            made?.delete()
            if (html == null || !anki.setField(noteId, index, html)) return@withContext "AnkiDroid refused the picture."
            db.cards().update(card.copy(pictureName = name))
            "This screen is on the card of " + if (card.term.length > 12) card.term.take(12) + "…" else card.term
        } catch (e: Exception) {
            android.util.Log.w("LfgCards", "picture to the last card failed", e)
            "AnkiDroid did not answer. Open AnkiDroid once, then try again."
        }
    }

    /** The line's explanation and what was asked about it, as the card's field has it; empty when there is none. */
    private suspend fun explained(lineId: String): String =
        db.explanations().of(lineId)?.let { Explainer.html(Explainer.conversation(it), Sentence::escapeHtml) }.orEmpty()

    /**
     * The line was explained, or asked about, after its cards were made: they take what was said now.
     * Only where the field still holds what this app put there, so nothing written by hand is lost.
     */
    suspend fun refreshExplained(lineId: String) = withContext(Dispatchers.IO) {
        try {
            if (anki.status() != AnkiStatus.READY) return@withContext
            val cards = db.cards().addedFor(lineId)
            if (cards.isEmpty()) return@withContext
            val target = anki.target ?: return@withContext
            val index = NoteTypes.miscField(target.family, target.fields) ?: return@withContext
            val text = explained(lineId)
            for (card in cards) {
                val noteId = card.noteId ?: continue
                val game = try {
                    Json.decodeFromString<CardContent>(card.payload).gameName
                } catch (_: Exception) {
                    continue
                }
                anki.setFieldIf(noteId, index, NoteTypes.misc(game, text)) { it == NoteTypes.misc(game, "") || it.startsWith(NoteTypes.misc(game, "") + NoteTypes.MISC_BREAK) }
            }
        } catch (e: Exception) {
            android.util.Log.w("LfgCards", "a card's explanation was not updated", e)
        }
    }

    /**
     * [picture] as the card takes it, without its black bars when [trim]: an AVIF, or when none can be
     * written the JPEG trim it always was. A new file to send and delete; null to send [picture] as it is,
     * which is also what happens when the AVIF of an untrimmed picture comes out no smaller.
     */
    private fun forCard(picture: File, name: String, trim: Boolean): File? {
        val bitmap = try {
            BitmapFactory.decodeFile(picture.path)
        } catch (e: Throwable) {
            android.util.Log.w("LfgCards", "reading a picture failed", e)
            null
        } ?: return null
        val inside = if (trim) withoutBars(bitmap) else bitmap
        try {
            mediaDir.mkdirs()
            val avif = File(mediaDir, "$name.avif")
            if (inside != null && Avif.encode(inside, avif)) {
                if (inside !== bitmap || avif.length() < picture.length()) return avif
                avif.delete()
                return null
            }
            // No AVIF: the trim, if there is one, as before.
            if (inside == null || inside === bitmap) return null
            return File(mediaDir, "$name.trim.jpg").also { out ->
                out.outputStream().use { inside.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        } catch (e: Throwable) {
            android.util.Log.w("LfgCards", "making a card's picture failed", e)
            return null
        } finally {
            if (inside != null && inside !== bitmap) inside.recycle()
            bitmap.recycle()
        }
    }

    /** [bitmap] without its black bars; [bitmap] itself when it has none, null when that cannot be found. */
    private fun withoutBars(bitmap: Bitmap): Bitmap? = try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val box = BlackBars.inside(pixels, bitmap.width, bitmap.height)
        if (box.width == bitmap.width && box.height == bitmap.height) bitmap else Bitmap.createBitmap(bitmap, box.left, box.top, box.width, box.height)
    } catch (e: Throwable) {
        android.util.Log.w("LfgCards", "trimming a picture failed", e)
        null
    }
}
