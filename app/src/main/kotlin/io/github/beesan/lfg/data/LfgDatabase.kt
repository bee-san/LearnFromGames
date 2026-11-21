// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import io.github.beesan.lfg.core.GameStats
import kotlinx.coroutines.flow.Flow

@Dao
interface LineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(line: GameLine)

    @Query("SELECT * FROM game_lines WHERE id = :id")
    suspend fun byId(id: String): GameLine?

    @Query("SELECT * FROM game_lines WHERE source != 'practice' AND interface_text = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<GameLine>>

    /** A game's last lines, newest first, its interface text left out: what a piece just read is held against. */
    @Query("SELECT * FROM game_lines WHERE game_id = :gameId AND interface_text = 0 AND source != 'practice' ORDER BY created_at DESC, rowid DESC LIMIT :limit")
    suspend fun lastLines(gameId: String, limit: Int): List<GameLine>

    /** The newest line of exactly this text in a game, however long ago it was kept. */
    @Query("SELECT * FROM game_lines WHERE game_id = :gameId AND line_text = :text AND interface_text = 0 ORDER BY created_at DESC LIMIT 1")
    suspend fun lineOf(gameId: String, text: String): GameLine?

    /** A line that was read half typed is the whole of it now, with the picture that shows the whole of it. */
    @Query("UPDATE game_lines SET line_text = :text, char_count = :chars, box = :box, screenshot_path = :picture, last_modified = :now WHERE id = :id")
    suspend fun grow(id: String, text: String, chars: Int, box: String, picture: String?, now: Long)

    /** The line read next after a given moment in the same game: where the earlier line's sound ends. */
    @Query("SELECT * FROM game_lines WHERE game_id = :gameId AND created_at > :afterMs ORDER BY created_at LIMIT 1")
    suspend fun nextAfter(gameId: String, afterMs: Long): GameLine?

    /** The text too: stats count its letters, not every code point as `char_count` does. */
    @Query("SELECT timestamp, line_text AS text, game_id AS gameId FROM game_lines WHERE source != 'practice' AND interface_text = 0 ORDER BY timestamp")
    fun stamps(): Flow<List<LineStamp>>

    /** The same, from [since] on: the lines a session still going can be made of. */
    @Query("SELECT timestamp, line_text AS text, game_id AS gameId FROM game_lines WHERE source != 'practice' AND interface_text = 0 AND timestamp >= :since ORDER BY timestamp")
    suspend fun stampsSince(since: Double): List<LineStamp>

    /** Pictures no card was made from, older than [before] (milliseconds). */
    @Query("SELECT DISTINCT screenshot_path FROM game_lines WHERE screenshot_path IS NOT NULL AND note_ids = '' AND created_at < :before")
    suspend fun stalePictures(before: Long): List<String>

    @Query("SELECT DISTINCT screenshot_path FROM game_lines WHERE screenshot_path IS NOT NULL AND note_ids != ''")
    suspend fun keptPictures(): List<String>

    /** The screens of these lines that are still kept. */
    @Query("SELECT DISTINCT screenshot_path FROM game_lines WHERE id IN (:ids) AND screenshot_path IS NOT NULL")
    suspend fun picturesOf(ids: List<String>): List<String>

    /** Every screen a line still points to. */
    @Query("SELECT DISTINCT screenshot_path FROM game_lines WHERE screenshot_path IS NOT NULL")
    suspend fun allPictures(): List<String>

    @Query("UPDATE game_lines SET screenshot_path = NULL WHERE screenshot_path = :path")
    suspend fun forgetPicture(path: String)

    @Query("UPDATE game_lines SET audio_path = :path, last_modified = :now WHERE id = :id")
    suspend fun setAudio(id: String, path: String, now: Long)

    @Query("UPDATE game_lines SET crop_path = :path WHERE id = :id")
    suspend fun setCrop(id: String, path: String)

    @Query("UPDATE game_lines SET translation = :text WHERE id = :id")
    suspend fun setTranslation(id: String, text: String)

    /** The lines of a game read before [before], newest first: what a translation is given as context. */
    @Query("SELECT * FROM game_lines WHERE game_id = :gameId AND timestamp < :before AND source != 'practice' AND interface_text = 0 ORDER BY timestamp DESC LIMIT :count")
    suspend fun before(gameId: String, before: Double, count: Int): List<GameLine>

    /** A game's lines in the order they were read. */
    @Query("SELECT * FROM game_lines WHERE game_id = :gameId AND source != 'practice' AND interface_text = 0 ORDER BY timestamp")
    suspend fun ofGame(gameId: String): List<GameLine>

    /** Every line in the order it was read: for a lines file. */
    @Query("SELECT * FROM game_lines WHERE source != 'practice' AND interface_text = 0 ORDER BY timestamp")
    suspend fun all(): List<GameLine>

    @Query("UPDATE game_lines SET note_ids = :noteIds, last_modified = :now WHERE id = :id")
    suspend fun setNoteIds(id: String, noteIds: String, now: Long)

    /** Every line of [text] in a game, before and after, is interface text: all but one a card was made from. */
    @Query("UPDATE game_lines SET interface_text = 1, last_modified = :now WHERE game_id = :gameId AND line_text = :text AND interface_text = 0 AND note_ids = ''")
    suspend fun markInterface(gameId: String, text: String, now: Long)

    /** The texts already found to be interface text in a game. */
    @Query("SELECT DISTINCT line_text FROM game_lines WHERE game_id = :gameId AND interface_text = 1")
    suspend fun interfaceTexts(gameId: String): List<String>

    /** When each line of [text] in a game was saved, in milliseconds. */
    @Query("SELECT created_at FROM game_lines WHERE game_id = :gameId AND line_text = :text")
    suspend fun savedTimes(gameId: String, text: String): List<Long>

    /** Every line back in the lines list and the stats: telling interface text apart was turned off. */
    @Query("UPDATE game_lines SET interface_text = 0 WHERE interface_text = 1")
    suspend fun forgetInterface()
}

data class LineStamp(val timestamp: Double, val text: String, val gameId: String)

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(game: Game)

    /** The app's own row: what is read in it that no source named. */
    @Query("SELECT * FROM games WHERE package = :packageName AND detected = ''")
    suspend fun byPackage(packageName: String): Game?

    @Query("SELECT * FROM games WHERE package = :packageName AND detected = :detected")
    suspend fun byDetected(packageName: String, detected: String): Game?

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: String): Game?

    @Update
    suspend fun update(game: Game)

    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    fun all(): Flow<List<Game>>

    /** The rows of ordinary apps, whose lines can be left out of reading time. */
    @Query("SELECT id FROM games WHERE app = 1")
    fun appIds(): Flow<List<String>>

    /** Every row linked to a visual novel, or offered one: what character names are built for. */
    @Query("SELECT * FROM games WHERE vndb NOT IN ('', 'none') OR vndb_suggested != ''")
    suspend fun withVn(): List<Game>

    @Query("UPDATE games SET vndb = :vndb, vndb_how = :how, vndb_suggested = :suggested, vndb_asked = :asked WHERE id = :id")
    suspend fun setVn(id: String, vndb: String, how: String, suggested: String, asked: Boolean)

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

    /** Each card made, and the game of the line it was made from. */
    @Query("SELECT cards.created_at AS madeAt, game_lines.game_id AS gameId FROM cards JOIN game_lines ON game_lines.id = cards.line_id WHERE cards.state != 'failed'")
    fun madeByGame(): Flow<List<GameStats.CardMade>>

    @Query("SELECT cards.created_at AS madeAt, game_lines.game_id AS gameId FROM cards JOIN game_lines ON game_lines.id = cards.line_id WHERE cards.state != 'failed' AND cards.created_at >= :since")
    suspend fun madeSince(since: Long): List<GameStats.CardMade>

    @Query("SELECT * FROM cards WHERE state = 'pending' ORDER BY created_at")
    suspend fun pending(): List<Card>

    @Query("SELECT * FROM cards WHERE line_id = :lineId AND state = 'added'")
    suspend fun addedFor(lineId: String): List<Card>

    @Query("SELECT * FROM cards WHERE line_id IN (:lineIds)")
    fun forLines(lineIds: List<String>): Flow<List<Card>>
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes ORDER BY created_at DESC")
    fun all(): Flow<List<Note>>

    /** The notes shown in lookups of these words. */
    @Query("SELECT * FROM notes WHERE in_lookups = 1 AND term IN (:terms) ORDER BY created_at DESC")
    suspend fun forTerms(terms: List<String>): List<Note>

    @Query("SELECT picture_path FROM notes WHERE picture_path IS NOT NULL")
    suspend fun pictures(): List<String>
}

@Dao
interface RecapDao {
    @Insert
    suspend fun insert(recap: RecapRow): Long

    @Query("SELECT * FROM recaps WHERE game_id = :gameId ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(gameId: String): RecapRow?

    @Query("SELECT * FROM recaps")
    suspend fun all(): List<RecapRow>

    @Query("SELECT * FROM recaps WHERE game_id = :gameId ORDER BY created_at DESC LIMIT 1")
    fun latestFlow(gameId: String): Flow<RecapRow?>
}

@Dao
interface QuestionDao {
    @Insert
    suspend fun insert(question: QuestionRow): Long

    /** A game's questions, newest first. */
    @Query("SELECT * FROM questions WHERE game_id = :gameId ORDER BY created_at DESC LIMIT :count")
    suspend fun recent(gameId: String, count: Int = KEEP): List<QuestionRow>

    /** Drops all but a game's newest [keep] questions. */
    @Query("DELETE FROM questions WHERE game_id = :gameId AND id NOT IN (SELECT id FROM questions WHERE game_id = :gameId ORDER BY created_at DESC LIMIT :keep)")
    suspend fun trim(gameId: String, keep: Int = KEEP)

    companion object {
        /** Questions kept for each game. */
        const val KEEP = 20
    }
}

/** Schema changes add a numbered migration; nothing here is ever rebuilt destructively. */
@Database(
    entities = [GameLine::class, Game::class, LookupRecord::class, Card::class, Note::class, RecapRow::class, QuestionRow::class],
    version = 7,
    exportSchema = true,
    // 2: a crop and a translation for each line, notes, and recaps. Room writes it from the exported schemas.
    // 3: a row for each game named inside an emulator.
    // 4: whether a row is an ordinary app, read on demand.
    // 5: whether a line is interface text (a menu label, a name plate), and an index to find a line's text in a game.
    // 6: the visual novel on VNDB a game is linked to, for its character names.
    // 7: questions asked about a game, and their answers.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class LfgDatabase : RoomDatabase() {
    abstract fun lines(): LineDao
    abstract fun games(): GameDao
    abstract fun lookups(): LookupDao
    abstract fun cards(): CardDao
    abstract fun notes(): NoteDao
    abstract fun recaps(): RecapDao
    abstract fun questions(): QuestionDao

    companion object {
        fun open(context: Context): LfgDatabase =
            Room.databaseBuilder(context, LfgDatabase::class.java, "lfg.db").build()
    }
}
