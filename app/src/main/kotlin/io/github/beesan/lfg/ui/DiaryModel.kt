// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import io.github.beesan.lfg.App
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.Diary
import io.github.beesan.lfg.data.DiaryBook
import io.github.beesan.lfg.data.DiaryPage
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.widget.DiaryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The diary's pages in the app: the shelf, a month, a day, an entry, and an entry being written or shared. */
class DiaryModel(private val app: App, private val scope: CoroutineScope, private val newestLine: () -> GameLine?) {

    data class State(
        val loaded: Boolean = false,
        val books: List<DiaryBook> = emptyList(),
        /** The game whose days the month and the day pages step through, or null for every game's. */
        val gameId: String? = null,
        val month: YearMonth = YearMonth.now(),
        /** The day open: its game and date. */
        val day: Pair<String, LocalDate>? = null,
        val entryId: Long = 0,
    ) {
        /** The days in view, oldest first; on a date two games were read, the one read less comes first. */
        val pages: List<DiaryPage>
            get() = books.filter { gameId == null || it.gameId == gameId }.flatMap { it.pages }.sortedWith(compareBy<DiaryPage> { it.day }.thenBy { it.seconds })
        val page: DiaryPage? get() = day?.let { (game, date) -> books.firstOrNull { it.gameId == game }?.pages?.firstOrNull { it.day == date } }
        val entry: Note? get() = page?.entries?.firstOrNull { it.id == entryId } ?: books.flatMap { it.pages }.flatMap { it.entries }.firstOrNull { it.id == entryId }
        val title: String get() = books.firstOrNull { it.gameId == gameId }?.title ?: "Every game"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val zone get() = ZoneId.systemDefault()

    /** Reads the diary again; [then] follows with what was read. */
    fun load(then: (State) -> Unit = {}) {
        scope.launch {
            val books = app.diary.books()
            _state.update { it.copy(loaded = true, books = books) }
            then(_state.value)
        }
    }

    fun openBook(gameId: String) = _state.update { s ->
        val last = s.books.firstOrNull { it.gameId == gameId }?.last
        s.copy(gameId = gameId, month = last?.let(YearMonth::from) ?: s.month)
    }

    fun openCalendar() = _state.update { s -> s.copy(gameId = null, month = s.books.maxOfOrNull { it.last }?.let(YearMonth::from) ?: s.month) }

    fun month(by: Long) = _state.update { it.copy(month = it.month.plusMonths(by)) }

    fun openDay(page: DiaryPage) = _state.update { it.copy(day = page.gameId to page.day, month = YearMonth.from(page.day)) }

    /** The day before or after the open one, of those in view. */
    fun neighbour(by: Int): DiaryPage? {
        val s = _state.value
        val pages = s.pages
        val at = pages.indexOfFirst { it.gameId == s.day?.first && it.day == s.day?.second }
        return if (at < 0) null else pages.getOrNull(at + by)
    }

    fun openEntry(id: Long) = _state.update { it.copy(entryId = id) }

    /** The widget's day: that game's book, open at it. Returns false when the day is not in the diary. */
    fun openWidgetDay(gameId: String, epochDay: Long, then: (Boolean) -> Unit) = load { s ->
        val page = s.books.firstOrNull { it.gameId == gameId }?.pages?.firstOrNull { it.day.toEpochDay() == epochDay }
        if (page != null) {
            _state.update { it.copy(gameId = gameId) }
            openDay(page)
        }
        then(page != null)
    }

    // ── writing ──────────────────────────────────────────────────────────────

    /** An entry being written: a new one ([id] 0) or one written before, the line it is on, and its picture. */
    data class Draft(
        val id: Long = 0,
        val gameId: String = "",
        val gameName: String = "",
        val line: GameLine? = null,
        val picture: String? = null,
        val title: String = "",
        val text: String = "",
        /** The moment it is about, in milliseconds. */
        val at: Long = 0,
        /** Counts up with each draft started, so the page's fields start again. */
        val serial: Int = 0,
    )

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft

    private fun start(next: Draft) = _draft.update { next.copy(serial = it.serial + 1) }

    private fun on(line: GameLine?) = start(
        if (line == null) Draft(at = System.currentTimeMillis())
        else Draft(gameId = line.gameId, gameName = line.gameName, line = line, picture = pictureOf(line), at = line.createdAt),
    )

    /** A new entry on a day of the diary: on the line the day's picture is of. */
    fun write(page: DiaryPage) {
        start(Draft(gameId = page.gameId, gameName = page.gameName, picture = page.picture, at = page.lineAt))
        scope.launch {
            val line = withContext(Dispatchers.IO) { app.database.lines().byId(page.lineId) } ?: return@launch
            _draft.update { it.copy(line = line, picture = page.picture ?: pictureOf(line), at = line.createdAt) }
        }
    }

    /** A new entry from over a game: on the line with [lineId], or the newest line read. */
    fun writeOn(lineId: String?) {
        on(null)
        scope.launch { on(lineId?.let { id -> withContext(Dispatchers.IO) { app.database.lines().byId(id) } } ?: newestLine()) }
    }

    fun edit(entry: Note) {
        start(Draft(entry.id, entry.gameId, entry.gameName, null, entry.picturePath, entry.title, entry.text, entry.createdAt))
        scope.launch {
            val line = entry.lineId?.let { id -> withContext(Dispatchers.IO) { app.database.lines().byId(id) } } ?: return@launch
            _draft.update { it.copy(line = line) }
        }
    }

    /** The picture of the line read before, or after, the one the entry is on: the entry moves to that line. */
    fun picture(earlier: Boolean) {
        val line = _draft.value.line ?: return
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                if (earlier) app.database.lines().pictureBefore(line.gameId, line.timestamp) else app.database.lines().pictureAfter(line.gameId, line.timestamp)
            } ?: return@launch
            if (!File(next.screenshotPath.orEmpty()).isFile) return@launch
            _draft.update { it.copy(line = next, picture = next.screenshotPath, at = if (it.id == 0L) next.createdAt else it.at) }
        }
    }

    /** Saves the draft, and opens its day and it: [then] follows with its id. */
    fun save(title: String, text: String, then: (Long) -> Unit = {}) {
        val draft = _draft.value
        scope.launch {
            val line = draft.line
            val id = if (draft.id == 0L) {
                app.notes.save(
                    Note(
                        gameId = draft.gameId, gameName = draft.gameName, lineId = line?.id, lineText = line?.lineText.orEmpty(),
                        title = title.trim(), text = text.trim(), inLookups = false, createdAt = draft.at.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                    draft.picture,
                )
            } else {
                val kept = withContext(Dispatchers.IO) { app.database.notes().byId(draft.id) } ?: return@launch
                app.notes.update(
                    kept.copy(title = title.trim(), text = text.trim(), lineId = line?.id ?: kept.lineId, lineText = line?.lineText ?: kept.lineText),
                    draft.picture,
                )
                draft.id
            }
            val day = ReadingStats.dayOf((draft.at.takeIf { it > 0 } ?: System.currentTimeMillis()) / 1000.0, zone)
            _state.update { it.copy(day = draft.gameId to day, entryId = id, month = YearMonth.from(day)) }
            load { then(id) }
            DiaryWidget.update(app)
        }
    }

    // ── sharing ──────────────────────────────────────────────────────────────

    /** The line of the entry being shared, for its text and its translation; [translating] while that is asked for. */
    data class Sharing(val line: GameLine? = null, val translating: Boolean = false, val error: String? = null)

    private val _sharing = MutableStateFlow(Sharing())
    val sharing: StateFlow<Sharing> = _sharing

    fun share(entry: Note) {
        _sharing.value = Sharing()
        scope.launch {
            val line = entry.lineId?.let { id -> withContext(Dispatchers.IO) { app.database.lines().byId(id) } }
            _sharing.value = Sharing(line)
        }
    }

    /** The line's translation, asked for once when it is ticked and there is none. */
    fun translate() {
        val line = _sharing.value.line ?: return
        if (line.translation != null || _sharing.value.translating) return
        _sharing.update { it.copy(translating = true, error = null) }
        scope.launch {
            try {
                val text = app.translator.translate(line)
                // Another entry may be shared by now: only its own line takes the translation.
                _sharing.update { it.copy(line = if (it.line?.id == line.id) line.copy(translation = text) else it.line, translating = false) }
            } catch (e: OpenRouter.Failure) {
                _sharing.update { it.copy(translating = false, error = e.message) }
            }
        }
    }

    private fun pictureOf(line: GameLine): String? =
        line.screenshotPath?.takeIf { File(it).isFile } ?: line.cropPath?.takeIf { File(it).isFile }

    companion object {
        /** "42 min · 3,210 characters". */
        fun stats(page: DiaryPage) = Diary.stats(page.seconds, page.chars)
    }
}
