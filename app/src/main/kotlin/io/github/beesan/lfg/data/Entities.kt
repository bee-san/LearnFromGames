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
    /** `ocr`, `hook` or `practice`. */
    val source: String,
    /** The reader that produced the text, e.g. `screenai`; empty for a hook. */
    val engine: String = "",
    @ColumnInfo(name = "char_count") val charCount: Int,
    @ColumnInfo(name = "display_id") val displayId: Int = 0,
    /** `left,top,right,bottom` in frame pixels. */
    val box: String = "",
)

/** A game is the app in front, by default: an emulator is one "game" until it is renamed or split. */
@Entity(tableName = "games", indices = [Index("package", unique = true)])
data class Game(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "package") val packageName: String,
    val title: String,
    /** Invisible tap strips over each line; off for touch-driven games. */
    @ColumnInfo(name = "tap_strips") val tapStrips: Boolean = true,
    /**
     * [READ_AUTO], [READ_ON] or [READ_OFF]. Automatic reads the app while it
     * is fullscreen, or always if it says it is a game: an ordinary app with
     * its status bar showing is left alone, so taps on its text stay its own.
     */
    val reading: Int = READ_AUTO,
) {
    companion object {
        const val READ_AUTO = 0
        const val READ_ON = 1
        const val READ_OFF = 2
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
