// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.anki.AnkiTarget
import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.CardResult
import io.github.beesan.lfg.capture.LfgAccessibilityService
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.dict.DictionarySource
import io.github.beesan.lfg.dict.DictionaryWorker
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.CardButton
import io.github.beesan.lfg.lookup.LookupController
import io.github.beesan.lfg.lookup.LookupLine
import io.github.beesan.lfg.ocr.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

data class SetupState(
    val serviceOn: Boolean = false,
    /** Installed by tapping an APK: Android greys out the switch until restricted settings are allowed. */
    val installedFromFile: Boolean = false,
    val anki: AnkiStatus = AnkiStatus.NOT_INSTALLED,
    val ankiTarget: AnkiTarget? = null,
    val ankiBusy: Boolean = false,
    val ankiError: String? = null,
    /** AnkiDroid has never synced: a first sync later keeps only one side, so it should happen before the first card. */
    val ankiNeverSynced: Boolean = false,
) {
    fun complete(dictionary: DictState, reader: ModelState) =
        serviceOn && dictionary is DictState.Ready && reader is ModelState.Ready &&
            (anki == AnkiStatus.NOT_INSTALLED || (anki == AnkiStatus.READY && ankiTarget != null))
}

/** The practice sentence that ends set-up with a real lookup. */
data class PracticeState(
    val sentence: String = PRACTICE_SENTENCE,
    val spanStart: Int = 0,
    val spanEnd: Int = 0,
    val entry: Entry? = null,
    val button: CardButton = CardButton.ADD,
    val message: String? = null,
)

const val PRACTICE_SENTENCE = "昨日の夜、森の奥で不思議な光を見たんだ。"

data class DictionariesState(val busy: Boolean = false, val error: String? = null)

data class StatsState(
    val days: List<ReadingStats.DayTotals> = emptyList(),
    val games: List<Pair<String, ReadingStats.GameTotals>> = emptyList(),
    val streaks: ReadingStats.Streaks = ReadingStats.Streaks(0, 0),
) {
    val lines get() = days.sumOf { it.lines }
    val chars get() = days.sumOf { it.chars }
    val seconds get() = days.sumOf { it.seconds }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val ui = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _setup = MutableStateFlow(SetupState())
    val setup: StateFlow<SetupState> = _setup

    val dictionary: StateFlow<DictState> = app.dictionaries.state
    val reader: StateFlow<ModelState> = app.models.meiki
    val screenAi: StateFlow<ModelState> = app.models.screenAi
    val prefs: StateFlow<Prefs> = app.settings.prefs

    private val _practice = MutableStateFlow(PracticeState())
    val practice: StateFlow<PracticeState> = _practice

    /** Set-up's last screen has been seen: from then on the app opens on Lines. */
    private val _practiceDone = MutableStateFlow(ui.getBoolean("practiceDone", false))
    val practiceDone: StateFlow<Boolean> = _practiceDone

    val lines: StateFlow<List<GameLine>> =
        app.database.lines().recent(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val games: StateFlow<List<Game>> =
        app.database.games().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<StatsState> = combine(app.database.lines().stamps(), app.database.games().all()) { stamps, games ->
        withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            val all = stamps.map { ReadingStats.Stamp(it.timestamp, it.chars, it.gameId) }
            val days = ReadingStats.byDay(all, zone)
            val titles = games.associate { it.id to it.title }
            StatsState(
                days = days,
                games = ReadingStats.byGame(all, zone).map { (titles[it.gameId] ?: "Unknown") to it },
                streaks = ReadingStats.streaks(days.map { it.day }, ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone)),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    /** Lookup in a stored line, for a card made after the session. */
    val lineLookup = LookupController(viewModelScope, { app.dictionaries.lookup(it) }, app.cards, app.anki, app.lines)

    private val _styles = MutableStateFlow<List<EntryHtml.Style>>(emptyList())
    val styles: StateFlow<List<EntryHtml.Style>> = _styles

    private val _dictionaries = MutableStateFlow(DictionariesState())
    val dictionaries: StateFlow<DictionariesState> = _dictionaries

    private val _sources = MutableStateFlow<List<DictionarySource>>(emptyList())
    val sources: StateFlow<List<DictionarySource>> = _sources

    /** What became of each word carded from the practice line, so its button reads the same on return. */
    private val practiceDoneWords = HashMap<String, CardButton>()
    private var practiceLineId: String? = null

    init {
        viewModelScope.launch { LfgAccessibilityService.running.collect { refresh() } }
        viewModelScope.launch {
            dictionary.collect {
                _styles.value = app.dictionaries.styles()
                _sources.value = withContext(Dispatchers.IO) { app.dictionaries.sources() }
            }
        }
    }

    /** Re-reads everything Android or AnkiDroid may have changed while the app was away. */
    fun refresh() {
        val status = app.anki.status()
        _setup.update {
            it.copy(
                serviceOn = LfgAccessibilityService.enabled(app),
                installedFromFile = installedFromFile(),
                anki = status,
                ankiTarget = app.anki.target,
            )
        }
        if (status == AnkiStatus.READY && app.anki.target == null) connectAnki()
        if (status == AnkiStatus.READY) {
            viewModelScope.launch {
                val never = withContext(Dispatchers.IO) { runCatching { app.anki.neverSynced() }.getOrDefault(false) }
                _setup.update { it.copy(ankiNeverSynced = never) }
            }
        }
    }

    private fun installedFromFile(): Boolean = try {
        when (app.packageManager.getInstallSourceInfo(app.packageName).packageSource) {
            PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    fun awaitService() = LfgAccessibilityService.awaitConnection(app)

    /** After the permission dialog, and for "Find again": pick where cards go and remember it. */
    fun connectAnki() {
        if (_setup.value.ankiBusy) return
        _setup.update { it.copy(ankiBusy = true, ankiError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.anki.chooseTarget() } }
            _setup.update {
                it.copy(
                    ankiBusy = false,
                    anki = app.anki.status(),
                    ankiTarget = result.getOrNull() ?: it.ankiTarget,
                    ankiError = result.exceptionOrNull()?.let { e -> e.message ?: "AnkiDroid did not answer." },
                )
            }
            if (result.isSuccess) app.cards.flushPending()
        }
    }

    fun retryDownloads() = DictionaryWorker.start(app, replace = true)

    fun finishPractice() {
        ui.edit().putBoolean("practiceDone", true).apply()
        _practiceDone.value = true
    }

    fun showPracticeAgain() {
        _practiceDone.value = false
    }

    // ── the practice line ────────────────────────────────────────────────────

    /** Looks up the longest word starting at [index] of the practice sentence. */
    fun lookupPractice(index: Int) {
        val sentence = _practice.value.sentence
        if (index !in sentence.indices) return
        viewModelScope.launch {
            val entry = app.dictionaries.lookup(sentence.substring(index)).firstOrNull() ?: return@launch
            val end = (index + entry.matched.length).coerceAtMost(sentence.length)
            _practice.update {
                it.copy(
                    spanStart = index, spanEnd = end, entry = entry, message = null,
                    button = practiceDoneWords[entry.expression] ?: CardButton.ADD,
                )
            }
        }
    }

    fun addPracticeCard() {
        val state = _practice.value
        val entry = state.entry ?: return
        if (state.button != CardButton.ADD) return
        _practice.update { it.copy(button = CardButton.ADDING, message = null) }
        viewModelScope.launch {
            val lineId = practiceLineId ?: run {
                val game = app.lines.gameFor(app.packageName, PRACTICE_GAME)
                app.lines.saveLine(game, state.sentence, LineStore.SOURCE_PRACTICE).id.also { practiceLineId = it }
            }
            val content = CardContent(
                expression = entry.expression, reading = entry.reading, sentence = state.sentence,
                spanStart = state.spanStart, spanEnd = state.spanEnd, senses = entry.senses(), gameName = PRACTICE_GAME,
            )
            val result = app.cards.add(lineId, content)
            when (result) {
                is CardResult.Added, CardResult.Duplicate -> practiceDoneWords[entry.expression] = CardButton.IN_ANKI
                is CardResult.Waiting -> practiceDoneWords[entry.expression] = CardButton.WAITING
                is CardResult.Failed -> Unit
            }
            _practice.update {
                // The reply may arrive after another word was tapped; leave that word's button alone.
                if (it.entry?.expression != entry.expression) it else when (result) {
                    is CardResult.Added -> it.copy(button = CardButton.IN_ANKI, message = "Card added")
                    CardResult.Duplicate -> it.copy(button = CardButton.IN_ANKI)
                    is CardResult.Waiting -> it.copy(button = CardButton.WAITING, message = result.reason + " The card is kept here until it is.")
                    is CardResult.Failed -> it.copy(button = CardButton.ADD, message = result.reason)
                }
            }
        }
    }

    // ── lines ────────────────────────────────────────────────────────────────

    fun openLine(line: GameLine) {
        lineLookup.setLines(listOf(LookupLine(line.lineText, line.id, line.screenshotPath?.takeIf { File(it).isFile }, line.gameName)))
    }

    // ── dictionaries ─────────────────────────────────────────────────────────

    /** "Add dictionary…": the picked Yomitan zip is copied in and imported. */
    fun addDictionary(uri: Uri) {
        if (_dictionaries.value.busy) return
        _dictionaries.value = DictionariesState(busy = true)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val copy = File(app.cacheDir, "import-${System.currentTimeMillis()}.zip")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                        ?: return@withContext "The file could not be opened."
                    app.dictionaries.addFromZip(copy)
                } catch (e: Exception) {
                    e.message ?: "The file could not be read."
                } finally {
                    copy.delete()
                }
            }
            _dictionaries.value = DictionariesState(error = error)
        }
    }

    fun moveDictionary(title: String, up: Boolean) = viewModelScope.launch { app.dictionaries.move(title, up) }
    fun removeDictionary(title: String) = viewModelScope.launch { app.dictionaries.remove(title) }

    fun mediaBlocking(dictionary: String, path: String): ByteArray? = app.dictionaries.mediaBlocking(dictionary, path)

    // ── settings ─────────────────────────────────────────────────────────────

    fun updatePrefs(change: (Prefs) -> Prefs) = app.settings.update(change)

    fun downloadScreenAi() = viewModelScope.launch { app.models.ensureScreenAi() }

    fun updateGame(game: Game) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val old = app.database.games().byPackage(game.packageName)
            app.database.games().update(game)
            if (old != null && old.title != game.title) app.database.games().renameLines(game.id, game.title)
        }
        LfgAccessibilityService.gamesChanged = true
    }

    companion object {
        const val PRACTICE_GAME = "LFG practice"

        fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "${day.dayOfMonth} ${day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${day.year}"
        }
    }
}
