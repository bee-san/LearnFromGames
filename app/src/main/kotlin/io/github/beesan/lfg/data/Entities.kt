// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per line read. The first eleven columns carry GameSentenceMiner's
 * names and meanings so its stats rules and a later export carry over; the rest
 * are what the desktop app does not keep. Rows are never deleted when old
 * pictures are cleared.
 */
@Entity(tableName = "game_lines", indices = [Index("timestamp"), Index("game_id")])
data class GameLine(
    /** A UUID, as on desktop. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "game_name") val gameName: String,
    @ColumnInfo(name = "line_text") val lineText: String,
    /** Seconds since 1970, with a fraction, as on desktop. */
    val timestamp: Double,
    val language: String = "ja",
    @ColumnInfo(name = "screenshot_path") val screenshotPath: String? = null,
    @ColumnInfo(name = "audio_path") val audioPath: String? = null,
    /** Comma-separated Anki note ids made from this line. */
    @ColumnInfo(name = "note_ids") val noteIds: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    /** `ocr`, `tree`, `selection`, `hook` or `practice`. */
    val source: String,
    /** The reader that produced the text, e.g. `screenai`; empty for a hook. */
    val engine: String = "",
    @ColumnInfo(name = "char_count") val charCount: Int,
    @ColumnInfo(name = "display_id") val displayId: Int = 0,
    /** `left,top,right,bottom` in frame pixels. */
    val box: String = "",
    /** A picture of the line alone, cut from the screen. Kept when the screen's picture is cleared. */
    @ColumnInfo(name = "crop_path") val cropPath: String? = null,
    /** The line's translation, once asked for. */
    val translation: String? = null,
)

/**
 * A game is the app in front, by default. An emulator, or a Steam player, also
 * has a row for each game named inside it ([detected]), from Settings › Game
 * names; its own row, with [detected] empty, holds what nothing named.
 */
@Entity(tableName = "games", indices = [Index("package", "detected", unique = true)])
data class Game(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "package") val packageName: String,
    val title: String,
    /** The named game's key ([io.github.beesan.lfg.games.GameTitle.key]), or empty for the app itself. */
    @ColumnInfo(defaultValue = "") val detected: String = "",
    /** What named it: Cocoon, ES-DE, RetroArch, Eden or Steam; empty for the app itself. */
    @ColumnInfo(name = "named_by", defaultValue = "") val namedBy: String = "",
    /** Invisible tap strips over each line; off for touch-driven games. */
    @ColumnInfo(name = "tap_strips") val tapStrips: Boolean = true,
    /**
     * [READ_AUTO], [READ_ON], [READ_OFF] or [READ_ON_DEMAND]. Automatic reads
     * the app while it is fullscreen, or always if it says it is a game: an
     * ordinary app with its status bar showing is left alone, so taps on its
     * text stay its own, and is read only when asked, as with on demand.
     */
    val reading: Int = READ_AUTO,
    /**
     * An ordinary app rather than a game: its row was made by a reading asked
     * for over it. Its lines can be left out of reading time, and its cards
     * get neither sentence audio nor a moving picture.
     */
    @ColumnInfo(name = "app", defaultValue = "0") val app: Boolean = false,
) {
    companion object {
        const val READ_AUTO = 0
        const val READ_ON = 1
        const val READ_OFF = 2

        /** Never read by itself, even fullscreen: only when a reading is asked for. */
        const val READ_ON_DEMAND = 3
    }
}

/** The dictionary form looked up, not the inflected text, as Hachidori records it. */
@Entity(tableName = "lookups", indices = [Index("line_id")])
data class LookupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "line_id") val lineId: String,
    val term: String,
    val reading: String,
    val timestamp: Double,
)

@Entity(tableName = "cards", indices = [Index("line_id"), Index("state")])
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "note_id") val noteId: Long? = null,
    val term: String,
    val reading: String,
    /** [STATE_PENDING], [STATE_ADDED] or [STATE_FAILED]. */
    val state: String,
    @ColumnInfo(name = "picture_name") val pictureName: String? = null,
    @ColumnInfo(name = "audio_name") val audioName: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The card's content as JSON ([io.github.beesan.lfg.anki.CardContent]), so a pending card can be sent later. */
    val payload: String = "",
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_ADDED = "added"
        const val STATE_FAILED = "failed"
    }
}

/**
 * A note about what happened in a game, or about a word in it (an item, a
 * place, a person). With [term], and [inLookups] on, it shows first in that
 * word's lookups, as the dictionary "My notes".
 */
@Entity(tableName = "notes", indices = [Index("term"), Index("game_id")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "game_name") val gameName: String,
    /** The line it was made from, or null for a note on its own. */
    @ColumnInfo(name = "line_id") val lineId: String? = null,
    @ColumnInfo(name = "line_text") val lineText: String = "",
    /** The word it is about, as the dictionary has it, or empty. */
    val term: String = "",
    val reading: String = "",
    /** The word's first meaning, to remind the note's reader. */
    val gloss: String = "",
    val title: String = "",
    val text: String,
    /** Its own copy of the picture, so clearing old pictures never takes it. */
    @ColumnInfo(name = "picture_path") val picturePath: String? = null,
    @ColumnInfo(name = "in_lookups") val inLookups: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A game's story so far, as JSON ([io.github.beesan.lfg.recap.Recap]), from
 * its lines up to [lastLineId]. The next recap carries on from the newest one.
 */
@Entity(tableName = "recaps", indices = [Index("game_id")])
data class RecapRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: String,
    val json: String,
    @ColumnInfo(name = "last_line_id") val lastLineId: String,
    @ColumnInfo(name = "line_count") val lineCount: Int,
    val model: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
