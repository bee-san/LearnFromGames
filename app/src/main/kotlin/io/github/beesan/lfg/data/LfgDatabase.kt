// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(line: GameLine)

    @Query("SELECT * FROM game_lines WHERE id = :id")
    suspend fun byId(id: String): GameLine?

    @Query("SELECT * FROM game_lines WHERE source != 'practice' ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<GameLine>>

    @Query("SELECT * FROM game_lines WHERE game_id = :gameId ORDER BY created_at DESC LIMIT :limit")
    suspend fun lastOf(gameId: String, limit: Int): List<GameLine>

    /** The line read next after a given moment in the same game: where the earlier line's sound ends. */
    @Query("SELECT * FROM game_lines WHERE game_id = :gameId AND created_at > :afterMs ORDER BY created_at LIMIT 1")
    suspend fun nextAfter(gameId: String, afterMs: Long): GameLine?

    /** The text too: stats count its letters, not every code point as `char_count` does. */
    @Query("SELECT timestamp, line_text AS text, game_id AS gameId FROM game_lines WHERE source != 'practice' ORDER BY timestamp")
    fun stamps(): Flow<List<LineStamp>>

    /** Pictures no card was made from, older than [before] (milliseconds). */
    @Query("SELECT DISTINCT screenshot_path FROM game_lines WHERE screenshot_path IS NOT NULL AND note_ids = '' AND created_at < :before")
    suspend fun stalePictures(before: Long): List<String>

    @Query("SELECT DISTINCT screenshot_path FROM game_lines WHERE screenshot_path IS NOT NULL AND note_ids != ''")
    suspend fun keptPictures(): List<String>

    @Query("UPDATE game_lines SET screenshot_path = NULL WHERE screenshot_path = :path")
    suspend fun forgetPicture(path: String)

    @Query("UPDATE game_lines SET audio_path = :path, last_modified = :now WHERE id = :id")
    suspend fun setAudio(id: String, path: String, now: Long)

    @Query("UPDATE game_lines SET note_ids = :noteIds, last_modified = :now WHERE id = :id")
    suspend fun setNoteIds(id: String, noteIds: String, now: Long)
}

data class LineStamp(val timestamp: Double, val text: String, val gameId: String)

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(game: Game)

    @Query("SELECT * FROM games WHERE package = :packageName")
    suspend fun byPackage(packageName: String): Game?

    @Update
    suspend fun update(game: Game)

    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    fun all(): Flow<List<Game>>

    @Query("UPDATE game_lines SET game_name = :title WHERE game_id = :gameId")
    suspend fun renameLines(gameId: String, title: String)
}

@Dao
interface LookupDao {
    @Insert
    suspend fun insert(lookup: LookupRecord)
}

@Dao
interface CardDao {
    @Insert
    suspend fun insert(card: Card): Long

    @Update
    suspend fun update(card: Card)

    @Query("UPDATE cards SET audio_name = :name WHERE id = :id")
    suspend fun setAudio(id: Long, name: String)

    /** When each card was made, added or still waiting; a failed one was never made. */
    @Query("SELECT created_at FROM cards WHERE state != 'failed'")
    suspend fun madeTimes(): List<Long>

    @Query("SELECT * FROM cards WHERE state = 'pending' ORDER BY created_at")
    suspend fun pending(): List<Card>

    @Query("SELECT * FROM cards WHERE line_id = :lineId AND state = 'added'")
    suspend fun addedFor(lineId: String): List<Card>

    @Query("SELECT * FROM cards WHERE line_id IN (:lineIds)")
    fun forLines(lineIds: List<String>): Flow<List<Card>>
}

/** Schema changes add a numbered migration; nothing here is ever rebuilt destructively. */
@Database(entities = [GameLine::class, Game::class, LookupRecord::class, Card::class], version = 1, exportSchema = true)
abstract class LfgDatabase : RoomDatabase() {
    abstract fun lines(): LineDao
    abstract fun games(): GameDao
    abstract fun lookups(): LookupDao
    abstract fun cards(): CardDao

    companion object {
        fun open(context: Context): LfgDatabase =
            Room.databaseBuilder(context, LfgDatabase::class.java, "lfg.db").build()
    }
}
