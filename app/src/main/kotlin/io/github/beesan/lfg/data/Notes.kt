// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.Glossary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Notes on what happened in a game, and on words in it. A note keeps its own
 * copy of its picture in [dir], since the screen's pictures are cleared after
 * two weeks. A note on a word shows in that word's lookups, first, as the
 * dictionary [Entry.MY_NOTES]: the notes are read at each lookup, so an edit
 * or a new note shows at once, with no dictionary file to rebuild.
 */
class Notes(private val db: LfgDatabase, private val dir: File) {
    val all: Flow<List<Note>> get() = db.notes().all()

    /** Saves [note] with a copy of [picture], when there is one. Returns its id. */
    suspend fun save(note: Note, picture: String?): Long = withContext(Dispatchers.IO) {
        val source = picture?.let(::File)?.takeIf { it.isFile }
        val kept = source?.let {
            dir.mkdirs()
            it.copyTo(File(dir, "${UUID.randomUUID()}.${it.extension}")).path
        }
        db.notes().insert(note.copy(picturePath = kept))
    }

    /**
     * A diary entry written again: its title and text, and [picture] in place
     * of the one it had when that is another file ([picture] null leaves it).
     */
    suspend fun update(note: Note, picture: String?) = withContext(Dispatchers.IO) {
        val source = picture?.takeIf { it != note.picturePath }?.let(::File)?.takeIf { it.isFile }
        val kept = source?.let {
            dir.mkdirs()
            it.copyTo(File(dir, "${UUID.randomUUID()}.${it.extension}")).path
        }
        if (kept != null) note.picturePath?.let { File(it).delete() }
        db.notes().update(if (kept != null) note.copy(picturePath = kept) else note)
    }

    /** [entries] with the notes on each one's word first, as the dictionary [Entry.MY_NOTES]. */
    suspend fun withNotes(entries: List<Entry>): List<Entry> {
        if (entries.isEmpty()) return entries
        val notes = withContext(Dispatchers.IO) { db.notes().forTerms(entries.map { it.expression }.distinct()) }
        if (notes.isEmpty()) return entries
        return entries.map { entry ->
            val mine = notes.filter { it.term == entry.expression && (it.reading.isEmpty() || it.reading == entry.reading) }
            if (mine.isEmpty()) entry else entry.copy(glossaries = mine.map(::glossary) + entry.glossaries)
        }
    }

    companion object {
        /**
         * Notes on words are put away for now: a note is a diary entry, on a
         * line or a game. Those written before still show in their words'
         * lookups; none can be started.
         */
        const val WORD_NOTES = false

        private val time = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

        /** A note as a glossary: its title, its text, then when and in which game it was made. */
        fun glossary(note: Note): Glossary {
            val parts = listOfNotNull(
                note.title.takeIf { it.isNotBlank() },
                note.text.takeIf { it.isNotBlank() },
                time.format(Date(note.createdAt)) + " · " + note.gameName,
            )
            return Glossary(dictionary = Entry.MY_NOTES, glossary = Json.encodeToString(parts))
        }
    }
}
