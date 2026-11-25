// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Language
import java.util.UUID

/** Saving lines and lookups: step 7 of the plan, "every line is saved for stats later". [onSaved] follows each line saved. */
class LineStore(
    private val db: LfgDatabase,
    /** The tag of the language being learnt, which a line read now is in. */
    private val language: () -> String = { Language.JAPANESE.tag },
    private val onSaved: (GameLine) -> Unit = {},
) {

    suspend fun gameOrNull(packageName: String): Game? = db.games().byPackage(packageName)

    /** The app's row, made the first time; [app] marks a row first made by a reading asked for over an ordinary app. */
    suspend fun gameFor(packageName: String, title: String, app: Boolean = false): Game {
        db.games().byPackage(packageName)?.let { return it }
        db.games().insert(Game(id = UUID.randomUUID().toString(), packageName = packageName, title = title, app = app))
        // Re-read: a concurrent insert of the same package is ignored, and its row wins.
        return db.games().byPackage(packageName)!!
    }

    /**
     * The row for a game named inside [host]: made the first time it is seen,
     * reading and tap strips as the app's own row has them.
     */
    suspend fun namedGame(host: Game, detected: String, title: String, namedBy: String): Game {
        db.games().byDetected(host.packageName, detected)?.let { return it }
        db.games().insert(
            Game(
                id = UUID.randomUUID().toString(), packageName = host.packageName, title = title, detected = detected,
                namedBy = namedBy, tapStrips = host.tapStrips, reading = host.reading,
            ),
        )
        return db.games().byDetected(host.packageName, detected)!!
    }

    suspend fun saveLine(
        game: Game,
        text: String,
        source: String,
        engine: String = "",
        screenshotPath: String? = null,
        displayId: Int = 0,
        box: Box? = null,
        nowMs: Long = System.currentTimeMillis(),
        interfaceText: Boolean = false,
    ): GameLine {
        val line = GameLine(
            id = UUID.randomUUID().toString(),
            gameId = game.id,
            gameName = game.title,
            lineText = text,
            timestamp = nowMs / 1000.0,
            language = language(),
            screenshotPath = screenshotPath,
            createdAt = nowMs,
            lastModified = nowMs,
            source = source,
            engine = engine,
            charCount = text.codePointCount(0, text.length),
            displayId = displayId,
            box = box?.encode() ?: "",
            interfaceText = interfaceText,
        )
        db.lines().insert(line)
        onSaved(line)
        return line
    }

    /** Every line of [text] in [game] is interface text from now on, and those saved before it. */
    suspend fun markInterface(game: Game, text: String, nowMs: Long = System.currentTimeMillis()) = db.lines().markInterface(game.id, text, nowMs)

    suspend fun interfaceTexts(game: Game): Set<String> = db.lines().interfaceTexts(game.id).toSet()

    suspend fun savedTimes(game: Game, text: String): List<Long> = db.lines().savedTimes(game.id, text)

    suspend fun setCrop(lineId: String, path: String) = db.lines().setCrop(lineId, path)

    /** A game's last lines, newest first, its interface text left out: what a piece just read is held against. */
    suspend fun recentLines(game: Game, limit: Int): List<GameLine> = db.lines().lastLines(game.id, limit)

    /** The line of exactly this text in a game, however long ago it was kept. */
    suspend fun lineOf(game: Game, text: String): GameLine? = db.lines().lineOf(game.id, text)

    suspend fun line(id: String): GameLine? = db.lines().byId(id)

    /**
     * A line that was read half typed has been read whole: its row holds all
     * of it now, where it is on screen and the picture that shows all of it.
     * It keeps the moment it first appeared.
     */
    suspend fun grow(line: GameLine, text: String, box: Box?, screenshotPath: String?, nowMs: Long = System.currentTimeMillis()): GameLine {
        val grown = line.copy(
            lineText = text, charCount = text.codePointCount(0, text.length), box = box?.encode() ?: line.box,
            screenshotPath = screenshotPath ?: line.screenshotPath, lastModified = nowMs,
        )
        db.lines().grow(grown.id, grown.lineText, grown.charCount, grown.box, grown.screenshotPath, nowMs)
        onSaved(grown)
        return grown
    }

    suspend fun recordLookup(lineId: String, term: String, reading: String, nowMs: Long = System.currentTimeMillis()) {
        db.lookups().insert(LookupRecord(lineId = lineId, term = term, reading = reading, timestamp = nowMs / 1000.0))
    }

    suspend fun attachNote(lineId: String, noteId: Long, nowMs: Long = System.currentTimeMillis()) {
        val line = db.lines().byId(lineId) ?: return
        val ids = line.noteIds.split(',').filter { it.isNotBlank() } + noteId.toString()
        db.lines().setNoteIds(lineId, ids.distinct().joinToString(","), nowMs)
    }

    companion object {
        const val SOURCE_OCR = "ocr"

        /** Text an app gave through accessibility: exact, with no reader involved. */
        const val SOURCE_TREE = "tree"

        /** Text selected in an app and sent to LFG from Android's text-selection menu. */
        const val SOURCE_SELECTION = "selection"
        const val SOURCE_PRACTICE = "practice"
    }
}
