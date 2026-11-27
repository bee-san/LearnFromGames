// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import io.github.beesan.lfg.core.Knowing
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Where cards go: decided once at set-up, saved, and reused for every card. */
@Serializable
data class AnkiTarget(
    val modelId: Long,
    val modelName: String,
    val deckId: Long,
    val deckName: String,
    val family: Family,
    val fields: List<String>,
    /** True when the app created the note type and deck itself. */
    val created: Boolean = false,
)

enum class AnkiStatus { NOT_INSTALLED, NEEDS_PERMISSION, READY }

/**
 * Everything said to AnkiDroid, through its content provider API. It answers
 * while AnkiDroid is closed. None of this has run on a device yet: the plan's
 * "Prove first" list still has AnkiDroid answering after a cold start on AYN
 * firmware as an open question.
 */
class AnkiBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("anki", Context.MODE_PRIVATE)
    private val api by lazy { AddContentApi(context) }

    fun status(): AnkiStatus = when {
        AddContentApi.getAnkiDroidPackageName(context) == null -> AnkiStatus.NOT_INSTALLED
        ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED -> AnkiStatus.NEEDS_PERMISSION
        else -> AnkiStatus.READY
    }

    var target: AnkiTarget?
        get() = prefs.getString(KEY_TARGET, null)?.let {
            try {
                Json.decodeFromString<AnkiTarget>(it)
            } catch (_: Exception) {
                null
            }
        }
        private set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TARGET) else putString(KEY_TARGET, Json.encodeToString(value))
            }.apply()
        }

    /** Where cards go, as it is kept: a profile takes it along when it is put away, and brings it back ([io.github.beesan.lfg.Profiles]). */
    var targetKept: String?
        get() = prefs.getString(KEY_TARGET, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_TARGET) else putString(KEY_TARGET, value) }.apply()
        }

    /**
     * Picks where cards go and saves the choice. Tried in order: an installed
     * Kiku, Lapis or Senren note type with the deck holding most of its notes;
     * otherwise a note type and deck of the app's own.
     */
    fun chooseTarget(): AnkiTarget {
        val models = api.modelList ?: emptyMap()
        val candidates = models.mapNotNull { (id, name) ->
            if (NoteTypes.family(name) == null) return@mapNotNull null
            val fields = api.getFieldList(id)?.toList() ?: return@mapNotNull null
            NoteTypes.Candidate(id, name, fields, noteIds(id, limit = Int.MAX_VALUE).size)
        }
        val choice = NoteTypes.choose(candidates)
        val chosen = if (choice != null) {
            val deck = busiestDeck(choice.candidate.id) ?: ownDeck()
            AnkiTarget(choice.candidate.id, choice.candidate.name, deck.first, deck.second, choice.family, choice.candidate.fields)
        } else {
            ownTarget(models)
        }
        target = chosen
        return chosen
    }

    private fun ownTarget(models: Map<Long, String>): AnkiTarget {
        val deck = ownDeck()
        // LFG's own, even with fields the player added to it (cards are filled by the fields' names): not made again beside it.
        val existing = models.entries.firstNotNullOfOrNull { (id, name) ->
            if (name != NoteTypes.OWN_MODEL) return@firstNotNullOfOrNull null
            api.getFieldList(id)?.toList()?.takeIf { it.containsAll(NoteTypes.OWN_FIELDS) }?.let { id to it }
        }
        val modelId = existing?.first ?: api.addNewCustomModel(
            NoteTypes.OWN_MODEL,
            NoteTypes.OWN_FIELDS.toTypedArray(),
            arrayOf("Card 1"),
            arrayOf(NoteTypes.OWN_FRONT),
            arrayOf(NoteTypes.OWN_BACK),
            NoteTypes.OWN_CSS,
            deck.first,
            0,
        ) ?: error("AnkiDroid would not create the note type.")
        return AnkiTarget(modelId, NoteTypes.OWN_MODEL, deck.first, deck.second, Family.OWN, existing?.second ?: NoteTypes.OWN_FIELDS, created = existing == null)
    }

    private fun ownDeck(): Pair<Long, String> {
        api.deckList?.entries?.firstOrNull { it.value == NoteTypes.OWN_DECK }?.let { return it.key to it.value }
        val id = api.addNewDeck(NoteTypes.OWN_DECK) ?: error("AnkiDroid would not create the deck.")
        return id to NoteTypes.OWN_DECK
    }

    private fun noteIds(modelId: Long, limit: Int): List<Long> {
        val ids = mutableListOf<Long>()
        context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI, arrayOf(FlashCardsContract.Note._ID), "mid:$modelId", null, null,
        )?.use { cursor ->
            while (cursor.moveToNext() && ids.size < limit) ids += cursor.getLong(0)
        }
        return ids
    }

    /** The deck holding most of a note type's notes, judged from a sample so a large collection stays quick. */
    private fun busiestDeck(modelId: Long): Pair<Long, String>? {
        val decks = api.deckList ?: return null
        val counts = HashMap<Long, Int>()
        for (noteId in noteIds(modelId, limit = DECK_SAMPLE)) {
            val cards = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, "$noteId/cards")
            context.contentResolver.query(cards, arrayOf(FlashCardsContract.Card.DECK_ID), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) counts.merge(cursor.getLong(0), 1, Int::plus)
            }
        }
        val best = counts.filterKeys { it in decks }.maxByOrNull { it.value } ?: return null
        return best.key to decks.getValue(best.key)
    }

    /**
     * True when nothing in AnkiDroid has ever been through a sync. If the
     * account already holds cards elsewhere, the first sync keeps one side
     * only, so it is better done before the first card is made here.
     */
    fun neverSynced(): Boolean {
        context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI, arrayOf(FlashCardsContract.Note.USN), "", null, null,
        )?.use { cursor ->
            var seen = 0
            while (cursor.moveToNext() && seen < 500) {
                if (cursor.getInt(0) >= 0) return false
                seen++
            }
        }
        return true
    }

    /** True when the target's note type and deck are still there with the same fields. */
    fun targetStillValid(target: AnkiTarget): Boolean =
        api.getFieldList(target.modelId)?.toList() == target.fields && api.deckList?.containsKey(target.deckId) == true

    fun isDuplicate(target: AnkiTarget, expression: String): Boolean =
        api.findDuplicateNotes(target.modelId, expression)?.isNotEmpty() == true

    /** The note already holding this word, or null. */
    fun noteId(target: AnkiTarget, expression: String): Long? =
        api.findDuplicateNotes(target.modelId, expression)?.firstOrNull()?.id

    /**
     * Opens AnkiDroid's card browser on one note, through its
     * `anki://x-callback-url/browser?search=` link (AnkiDroid's IntentHandler,
     * in 2.25 and later; with an older AnkiDroid it throws ActivityNotFoundException).
     */
    fun openNote(noteId: Long) {
        val link = Uri.parse("anki://x-callback-url/browser").buildUpon().appendQueryParameter("search", "nid:$noteId").build()
        context.startActivity(Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Stores a picture in AnkiDroid's media folder and returns the `<img>` tag for the field, or null. */
    fun addPicture(file: File, preferredName: String): String? = addMedia(file, preferredName, "image")

    private fun addMedia(file: File, preferredName: String, type: String): String? {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val anki = AddContentApi.getAnkiDroidPackageName(context) ?: return null
        context.grantUriPermission(anki, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            api.addMediaFromUri(uri, preferredName, type)
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Sets one field of a note, as [setField] does, only while [unless] holds for what it has now. */
    fun setFieldIf(noteId: Long, index: Int, value: String, unless: (String) -> Boolean): Boolean {
        val fields = api.getNote(noteId)?.fields ?: return false
        if (index !in fields.indices || !unless(fields[index]) || fields[index] == value) return false
        fields[index] = value
        return api.updateNoteFields(noteId, fields)
    }

    /** Stores a sound in AnkiDroid's media folder and returns the `[sound:…]` tag for the field, or null. */
    fun addSound(file: File, preferredName: String): String? = addMedia(file, preferredName, "audio")

    /**
     * Sets one field of a note that already exists, leaving the rest as they
     * are: how a card gains its audio without being remade.
     */
    fun setField(noteId: Long, index: Int, value: String): Boolean {
        val fields = api.getNote(noteId)?.fields ?: return false
        if (index !in fields.indices) return false
        fields[index] = value
        return api.updateNoteFields(noteId, fields)
    }

    /** What [knowing] found, by where it looked and the word ([knowingKey]), with when. */
    private val knowings = HashMap<String, Pair<Long, Knowing>>()

    private fun knowingKey(everywhere: Boolean, target: AnkiTarget?, expression: String) =
        (if (everywhere) "*" else target?.modelId.toString()) + '\u0000' + expression

    private val _added = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Goes up each time a note is added here, so Known words marks its word again at once. */
    val added: kotlinx.coroutines.flow.StateFlow<Int> = _added

    /**
     * How well Anki knows each of [expressions]: mature once a card has an
     * interval of three weeks, learning while it has a card that has not, new
     * with none. [everywhere], by every note in Anki whose first field, or else
     * any field, holds the word ([AnkiWords]); otherwise by the first field of
     * the notes of [target]'s note type, the one cards go to. Remembered for a
     * few minutes, as a line is asked about again each time it is read; all the
     * words not remembered are asked about together.
     */
    fun knowing(target: AnkiTarget?, expressions: Collection<String>, everywhere: Boolean): Map<String, Knowing> {
        if (!everywhere && target == null) return emptyMap()
        val now = android.os.SystemClock.uptimeMillis()
        val known = HashMap<String, Knowing>()
        val asking = ArrayList<String>()
        synchronized(knowings) {
            for (expression in expressions.distinct()) {
                val kept = knowings[knowingKey(everywhere, target, expression)]?.takeIf { now - it.first < KNOWING_MS }?.second
                if (kept != null) known[expression] = kept else asking += expression
            }
        }
        if (asking.isEmpty()) return known
        val notes = if (everywhere) notesAnywhere(asking) else notesOf(target!!, asking)
        val mature = matureOf(notes.values.flatten())
        synchronized(knowings) {
            for (expression in asking) {
                val ids = notes[expression]
                val found = when {
                    ids.isNullOrEmpty() -> Knowing.NEW
                    ids.any { it in mature } -> Knowing.MATURE
                    else -> Knowing.LEARNING
                }
                knowings[knowingKey(everywhere, target, expression)] = now to found
                known[expression] = found
            }
        }
        return known
    }

    /** The notes of [target]'s note type whose first field is each of [words], as AnkiDroid's duplicate check finds them. */
    private fun notesOf(target: AnkiTarget, words: List<String>): Map<String, List<Long>> {
        val out = HashMap<String, List<Long>>()
        for (chunk in words.chunked(KNOWING_BATCH)) {
            val found = api.findDuplicateNotes(target.modelId, chunk) ?: continue
            for (i in 0 until found.size()) {
                val word = chunk.getOrNull(found.keyAt(i)) ?: continue
                out[word] = found.valueAt(i).mapNotNull { it?.id }
            }
        }
        return out
    }

    /**
     * The notes of any note type holding each of [words]: first by their first
     * field's checksum, which Anki indexes; then, for the words that found none,
     * in any field, with one search over every note ([AnkiWords.anyField]).
     */
    private fun notesAnywhere(words: List<String>): Map<String, MutableSet<Long>> {
        val out = HashMap<String, MutableSet<Long>>()
        val projection = arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.FLDS)
        for (chunk in words.chunked(KNOWING_BATCH)) {
            val byKey = chunk.associateBy { it.lowercase() }
            context.contentResolver.query(FlashCardsContract.Note.CONTENT_URI_V2, projection, AnkiWords.byChecksum(chunk), null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    // A checksum can be shared: the field itself must be the word.
                    val word = cursor.getString(1)?.let { byKey[AnkiWords.firstField(it).lowercase()] }
                    if (word != null) out.getOrPut(word) { HashSet() } += cursor.getLong(0)
                }
            }
        }
        for (chunk in words.filter { it !in out }.chunked(KNOWING_BATCH)) {
            val byKey = chunk.associateBy { it.lowercase() }
            // Null when nothing matches, as AnkiDroid answers a search that finds no note. An AnkiDroid that cannot
            // take the search leaves these words to their first fields alone.
            val cursor = try {
                context.contentResolver.query(FlashCardsContract.Note.CONTENT_URI, projection, AnkiWords.anyField(chunk), null, null)
            } catch (e: RuntimeException) {
                android.util.Log.w("AnkiBridge", "searching every field failed", e)
                null
            }
            cursor?.use { notes ->
                while (notes.moveToNext()) {
                    val id = notes.getLong(0)
                    for (field in AnkiWords.fields(notes.getString(1).orEmpty())) {
                        byKey[field.lowercase()]?.let { out.getOrPut(it) { HashSet() } += id }
                    }
                }
            }
        }
        return out
    }

    /** Those of [noteIds] with a card of three weeks' interval or more. */
    private fun matureOf(noteIds: Collection<Long>): Set<Long> {
        val mature = HashSet<Long>()
        for (chunk in noteIds.distinct().chunked(KNOWING_BATCH)) {
            context.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI, arrayOf(FlashCardsContract.Note._ID), AnkiWords.mature(chunk, MATURE_DAYS), null, null,
            )?.use { cursor -> while (cursor.moveToNext()) mature += cursor.getLong(0) }
        }
        return mature
    }

    /** Adds the note and returns its id, or null when AnkiDroid refuses it. Its words are no longer remembered as new. */
    fun addNote(target: AnkiTarget, fields: Array<String>, tags: Set<String>): Long? =
        api.addNote(target.modelId, target.deckId, fields, tags)?.also {
            // Which field holds the word depends on the note type: every field's text is forgotten, which is the word's among them.
            val words = fields.mapTo(HashSet()) { AnkiWords.plain(it) }
            synchronized(knowings) { knowings.keys.removeAll { it.substringAfter('\u0000') in words } }
            _added.value++
        }

    companion object {
        private const val KEY_TARGET = "target"
        private const val DECK_SAMPLE = 60
        private const val KNOWING_MS = 5 * 60_000L

        /** Words asked about in one query: a screen's words fit in one. */
        private const val KNOWING_BATCH = 50
        private const val MATURE_DAYS = 21
        const val PERMISSION = AddContentApi.READ_WRITE_PERMISSION
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
    }
}
