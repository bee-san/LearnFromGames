// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.backup.Backup
import android.content.Intent
import io.github.beesan.lfg.ask.Recap
import io.github.beesan.lfg.games.GameNameSources
import io.github.beesan.lfg.data.RecapRow
import kotlinx.serialization.json.Json
import io.github.beesan.lfg.ask.Translator
import io.github.beesan.lfg.anki.AnkiTarget
import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.CardResult
import io.github.beesan.lfg.capture.LfgAccessibilityService
import io.github.beesan.lfg.core.GameStats
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.data.PictureKeeper
import io.github.beesan.lfg.Diagnostics
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

/** [progress] says how adding is going when there is more to say than "Adding…" (a Rubi dictionary's conversion). */
data class DictionariesState(val busy: Boolean = false, val error: String? = null, val progress: String? = null)

data class StatsState(
    val days: List<ReadingStats.DayTotals> = emptyList(),
    /** Each game's days, and every session. */
    val summary: ReadingStats.Summary = ReadingStats.Summary(emptyList(), emptyMap(), emptyList()),
    val cards: List<GameStats.CardMade> = emptyList(),
    val streaks: ReadingStats.Streaks = ReadingStats.Streaks(0, 0),
    /** The lines have been counted: until then an empty [days] means not yet, not nothing read. */
    val loaded: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val ui = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _setup = MutableStateFlow(SetupState())
    val setup: StateFlow<SetupState> = _setup

    val dictionary: StateFlow<DictState> = app.dictionaries.state
    val fetchingDictionary: StateFlow<DictState?> = app.dictionaries.fetching
    val reader: StateFlow<ModelState> = app.models.screenAi
    val prefs: StateFlow<Prefs> = app.settings.prefs

    private val _practice = MutableStateFlow(PracticeState())
    val practice: StateFlow<PracticeState> = _practice

    /** Set-up's last screen has been seen: from then on the app opens on Lines. */
    private val _practiceDone = MutableStateFlow(ui.getBoolean("practiceDone", false))
    val practiceDone: StateFlow<Boolean> = _practiceDone

    /** The newest lines; null until the store has answered, so an empty list really is empty. */
    private val recentLines: StateFlow<List<GameLine>?> =
        app.database.lines().recent(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lines: StateFlow<List<GameLine>> = recentLines.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val linesLoaded: StateFlow<Boolean> = recentLines.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val games: StateFlow<List<Game>> =
        app.database.games().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<StatsState> = combine(
        app.database.lines().stamps(), app.database.cards().madeByGame(), app.database.games().appIds(),
        app.settings.prefs.map { it.appsInReadingTime }.distinctUntilChanged(),
    ) { stamps, cards, apps, appsCount ->
        withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            val left = if (appsCount) emptySet() else apps.toSet()
            val all = stamps.filter { it.gameId !in left }.map { ReadingStats.Stamp(it.timestamp, ReadingStats.characters(it.text), it.gameId) }
            val summary = ReadingStats.summarise(all, zone)
            val days = summary.days
            StatsState(
                days = days,
                summary = summary,
                cards = cards,
                streaks = ReadingStats.streaks(days.map { it.day }, ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone)),
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    /** Lookup in a stored line, for a card made after the session. */
    val lineLookup = LookupController(viewModelScope, { app.dictionaries.lookup(it) }, app.cards, app.anki, app.lines, app.wordAudio, decorate = { app.notes.withNotes(it) }, selection = { app.settings.prefs.value.picking })

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
        viewModelScope.launch { app.wordAudio.refreshInfo() }
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
        // AnkiDroid back (installed again, or its permission given again): cards made meanwhile go now.
        if (status == AnkiStatus.READY && app.anki.target != null) viewModelScope.launch { runCatching { app.cards.flushPending() } }
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

    /** The starter set, asked for from Settings › Dictionaries: what is missing of it is downloaded behind the page. */
    fun fetchStarterDictionaries() {
        app.dictionaries.requestDefaults()
        DictionaryWorker.start(app, replace = true, wifiOnly = app.settings.prefs.value.wifiOnly)
    }

    fun starterMissing(): List<String> = app.dictionaries.defaultsMissing().map { it.name }

    fun retryDownloads() = DictionaryWorker.start(app, replace = true, wifiOnly = app.settings.prefs.value.wifiOnly)

    /** Waits for Wi-Fi, or not, from now on; downloads still to do start again under the new rule. */
    fun setWifiOnly(on: Boolean) {
        app.settings.update { it.copy(wifiOnly = on) }
        if (app.dictionaries.defaultsPending() || app.dictionaries.state.value !is DictState.Ready || app.models.screenAiDir() == null) {
            DictionaryWorker.start(app, replace = true, wifiOnly = on)
        }
    }

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

    /** The stored line open in [lineLookup]. */
    private var opened: GameLine? = null

    fun openLine(line: GameLine) {
        opened = line
        _translation.value = TranslationState(line.translation)
        _explanation.value = TranslationState(app.explainer.kept(line.id))
        lineLookup.setLines(listOf(LookupLine(line.lineText, line.id, picture(line), line.gameName)))
    }

    /** The screen's picture is cleared after two weeks; the line's own picture stays and stands in for it. */
    private fun picture(line: GameLine): String? =
        line.screenshotPath?.takeIf { File(it).isFile } ?: line.cropPath?.takeIf { File(it).isFile }

    // ── translation ──────────────────────────────────────────────────────────

    /** The open line's translation: kept, being asked for, or what went wrong. */
    data class TranslationState(val text: String? = null, val busy: Boolean = false, val error: String? = null)

    private val _translation = MutableStateFlow(TranslationState())
    val translation: StateFlow<TranslationState> = _translation

    fun translateLine() {
        val line = opened ?: return
        if (_translation.value.busy) return
        _translation.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            _translation.value = try {
                TranslationState(app.translator.translate(line))
            } catch (e: OpenRouter.Failure) {
                TranslationState(_translation.value.text, error = e.message)
            }
        }
    }

    /** The open line's explanation: kept since the app started, being asked for, or what went wrong. */
    private val _explanation = MutableStateFlow(TranslationState())
    val explanation: StateFlow<TranslationState> = _explanation

    fun explainLine() {
        val line = opened ?: return
        if (_explanation.value.busy) return
        _explanation.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val answer = try {
                TranslationState(app.explainer.explain(line))
            } catch (e: OpenRouter.Failure) {
                TranslationState(_explanation.value.text, error = e.message)
            }
            // Another line may have been opened while this one was asked about.
            if (opened?.id == line.id) _explanation.value = answer
        }
    }

    /** What "Test the key" found: a translation of a sample line, or what went wrong. */
    private val _keyTest = MutableStateFlow<Pair<Boolean, String>?>(null)
    val keyTest: StateFlow<Pair<Boolean, String>?> = _keyTest

    fun testKey() {
        _keyTest.value = true to "Asking OpenRouter…"
        viewModelScope.launch {
            val p = app.settings.prefs.value
            _keyTest.value = try {
                val answer = OpenRouter.ask(p.openRouterKey, p.model, Translator.system(p.translateInto), Translator.prompt("", emptyList(), TEST_LINE))
                true to "Works: 「$TEST_LINE」 → “$answer”"
            } catch (e: OpenRouter.Failure) {
                false to (e.message ?: "")
            }
        }
    }

    // ── backup ───────────────────────────────────────────────────────────────

    /** What the last backup, export or restore did, or what went wrong. */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage

    /** A backup picked to restore from, waiting for "Restore" to be pressed. */
    private val _pendingRestore = MutableStateFlow<Uri?>(null)
    val pendingRestore: StateFlow<Uri?> = _pendingRestore

    fun backUpTo(uri: Uri?) {
        uri ?: return
        _backupMessage.value = "Backing up…"
        viewModelScope.launch(Dispatchers.IO) {
            _backupMessage.value = try {
                app.contentResolver.openOutputStream(uri)?.use { Backup.write(app, app.database, it, app.settings.prefs.value.backupPictures) }
                    ?: throw java.io.IOException()
                "Backed up."
            } catch (_: Exception) {
                "The backup couldn’t be written there. Try another folder."
            }
        }
    }

    fun exportLinesTo(uri: Uri?) {
        uri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _backupMessage.value = try {
                val all = app.database.lines().all()
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { Backup.linesCsv(all, it) } ?: throw java.io.IOException()
                "Exported %,d lines.".format(all.size)
            } catch (_: Exception) {
                "The lines couldn’t be written there. Try another folder."
            }
        }
    }

    // ── game names ───────────────────────────────────────────────────────────

    private val _gameNameStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    /** What each source's folder holds, in words, by source. */
    val gameNameStatus: StateFlow<Map<String, String>> = _gameNameStatus

    private val _steamTest = MutableStateFlow<String?>(null)
    val steamTest: StateFlow<String?> = _steamTest

    fun checkGameNames() = viewModelScope.launch {
        _gameNameStatus.value = GameNameSources.ALL.associateWith { app.gameFinder.describe(it) }
    }

    fun testSteam() = viewModelScope.launch {
        _steamTest.value = "Asking Steam…"
        _steamTest.value = app.gameFinder.testSteam()
    }

    /** A source's folder, kept with Android's leave to read it after a restart. */
    fun chooseGameFolder(source: String, uri: Uri?) {
        uri ?: return
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some folders can be read now but not kept; it is still worth trying this session.
        }
        updatePrefs { GameNameSources.withFolder(it, source, uri.toString()) }
        checkGameNames()
    }

    fun forgetGameFolder(source: String) {
        val old = GameNameSources.folder(app.settings.prefs.value, source)
        if (old.isNotEmpty()) {
            try {
                app.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
        updatePrefs { GameNameSources.withFolder(it, source, "") }
        checkGameNames()
    }

    /** "Change folder…": the folder is kept, with Android's leave to write to it after a restart. */
    fun chooseBackupFolder(uri: Uri?) {
        uri ?: return
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        updatePrefs { it.copy(backupFolder = uri.toString()) }
    }

    fun askRestore(uri: Uri?) {
        _pendingRestore.value = uri
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    /** Backs up what is here first (into the backup folder, or the app's own files), then restores and starts again. */
    fun restore() {
        val uri = _pendingRestore.value ?: return
        _pendingRestore.value = null
        _backupMessage.value = "Restoring…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = app.settings.prefs.value
                if (prefs.backupFolder.isNotEmpty()) {
                    Backup.toFolder(app, app.database, prefs.copy(backupKeep = 0, gsmFile = false))
                } else {
                    val dir = File(app.filesDir, "backups").apply { mkdirs() }
                    File(dir, Backup.fileName()).outputStream().use { Backup.write(app, app.database, it, prefs.backupPictures) }
                }
                (app.contentResolver.openInputStream(uri) ?: throw java.io.IOException()).use { Backup.restore(app, app.database, it) }
            } catch (e: Exception) {
                _backupMessage.value = e.message?.takeIf { it.startsWith("This file") } ?: "The restore failed; nothing was changed. Try again."
                return@launch
            }
            withContext(Dispatchers.Main) { app.restart() }
        }
    }

    // ── recap ────────────────────────────────────────────────────────────────

    /** A game's recap: the one kept, or being written, or what went wrong. */
    data class RecapState(
        val gameId: String? = null,
        val recap: Recap? = null,
        val lineCount: Int = 0,
        val model: String = "",
        val writtenAt: Long = 0,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _recap = MutableStateFlow(RecapState())
    val recap: StateFlow<RecapState> = _recap

    /** Recap opens on the game read last, and writes the story on when something has been read since the kept recap. */
    fun openRecap() = recapGame(lines.value.firstOrNull()?.gameId ?: games.value.firstOrNull()?.id)

    fun recapGame(gameId: String?) {
        _recap.value = RecapState(gameId)
        if (gameId == null) return
        viewModelScope.launch {
            val row = app.database.recaps().latest(gameId)
            show(gameId, row)
            val newest = lines.value.firstOrNull { it.gameId == gameId }
            if ((row == null || newest?.id != row.lastLineId) && app.settings.prefs.value.openRouterKey.isNotBlank()) writeRecap()
        }
    }

    fun writeRecap() {
        val gameId = _recap.value.gameId ?: return
        if (_recap.value.busy) return
        val name = games.value.firstOrNull { it.id == gameId }?.title.orEmpty()
        _recap.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                show(gameId, app.recapper.write(gameId, name))
            } catch (e: OpenRouter.Failure) {
                _recap.update { if (it.gameId == gameId) it.copy(busy = false, error = e.message) else it }
            }
        }
    }

    private fun show(gameId: String, row: RecapRow?) = _recap.update {
        if (it.gameId != gameId) return@update it
        if (row == null) return@update it.copy(busy = false)
        it.copy(
            recap = Json.decodeFromString<Recap>(row.json), lineCount = row.lineCount, model = row.model,
            writtenAt = row.createdAt, busy = false,
        )
    }

    // ── notes ────────────────────────────────────────────────────────────────

    val notes: StateFlow<List<Note>> = app.notes.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A note being written in the app: the line it is on, its place in [lines], and the picture it keeps. */
    data class NoteDraft(val line: GameLine? = null, val lineIndex: Int = 0, val picture: String? = null)

    private val _draft = MutableStateFlow(NoteDraft())
    val draft: StateFlow<NoteDraft> = _draft

    /** "New note…": on the newest line read, with its picture; tap a word in it to note that word. */
    fun newNote() = noteOn(0)

    /** "Use the line before". */
    fun noteOnLineBefore() = noteOn(_draft.value.lineIndex + 1)

    private fun noteOn(index: Int) {
        val line = lines.value.getOrNull(index)
        if (line == null) {
            if (index == 0) _draft.value = NoteDraft()
            return
        }
        _draft.value = NoteDraft(line, index, picture(line))
        openLine(line)
    }

    /** The note button of an entry on the open line: a note on that word. */
    fun noteFromLine(entryIndex: Int) {
        val line = opened ?: return
        lineLookup.selectEntry(entryIndex)
        _draft.value = NoteDraft(line, lines.value.indexOfFirst { it.id == line.id }.coerceAtLeast(0), picture(line))
    }

    /**
     * A note asked for from over a game: on the line with [lineId] (the newest
     * line when it is null or gone), and on the word starting at [at] of it
     * when that is not negative.
     */
    fun noteFromGame(lineId: String?, at: Int) {
        viewModelScope.launch {
            val line = lineId?.let { id -> withContext(Dispatchers.IO) { app.database.lines().byId(id) } } ?: lines.value.firstOrNull()
            if (line == null) {
                _draft.value = NoteDraft()
                return@launch
            }
            _draft.value = NoteDraft(line, lines.value.indexOfFirst { it.id == line.id }.coerceAtLeast(0), picture(line))
            openLine(line)
            if (at >= 0) lineLookup.select(0, at)
        }
    }

    fun removeNotePicture() = _draft.update { it.copy(picture = null) }

    /** The note's word is the entry the line's lookup is on; empty when none is. */
    fun saveNote(title: String, text: String, inLookups: Boolean) {
        val draft = _draft.value
        val line = draft.line
        val entry = lineLookup.state.value.entry
        val note = Note(
            gameId = line?.gameId.orEmpty(), gameName = line?.gameName.orEmpty(), lineId = line?.id, lineText = line?.lineText.orEmpty(),
            term = entry?.expression.orEmpty(), reading = entry?.reading.orEmpty(), gloss = entry?.senses()?.firstOrNull().orEmpty(),
            title = title.trim(), text = text.trim(), inLookups = inLookups, createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch { app.notes.save(note, draft.picture) }
    }

    // ── dictionaries ─────────────────────────────────────────────────────────

    /** "Add dictionary…": the picked Yomitan zip, or Rubi .vjdict, is copied in and imported. */
    fun addDictionary(uri: Uri) {
        if (_dictionaries.value.busy) return
        _dictionaries.value = DictionariesState(busy = true)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val copy = File(app.cacheDir, "import-${System.currentTimeMillis()}.zip")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                        ?: return@withContext "The file could not be opened."
                    app.dictionaries.addPicked(copy, displayName(uri)) { _dictionaries.value = DictionariesState(busy = true, progress = it) }
                } catch (e: Exception) {
                    e.message ?: "The file could not be read."
                } finally {
                    copy.delete()
                }
            }
            _dictionaries.value = DictionariesState(error = error)
        }
    }

    /** The picked file's own name (`plaintext.vjdict`), or empty. */
    private fun displayName(uri: Uri): String = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: ""

    val wordAudio = app.wordAudio.info

    /** "Add word audio…": local-audio-yomichan's android.db, copied in. */
    fun addWordAudio(uri: Uri) {
        if (_dictionaries.value.busy) return
        _dictionaries.value = DictionariesState(busy = true, progress = "Copying word audio…")
        viewModelScope.launch {
            val error = app.wordAudio.install({ app.contentResolver.openInputStream(uri) }) {
                _dictionaries.value = DictionariesState(busy = true, progress = it)
            }
            _dictionaries.value = DictionariesState(error = error)
        }
    }

    fun removeWordAudio() = viewModelScope.launch(Dispatchers.IO) { app.wordAudio.remove() }

    fun moveDictionary(title: String, up: Boolean) = viewModelScope.launch { app.dictionaries.move(title, up) }
    fun removeDictionary(title: String) = viewModelScope.launch { app.dictionaries.remove(title) }

    fun mediaBlocking(dictionary: String, path: String): ByteArray? = app.dictionaries.mediaBlocking(dictionary, path)

    // ── settings ─────────────────────────────────────────────────────────────

    fun updatePrefs(change: (Prefs) -> Prefs) = app.settings.update(change)

    /** Off puts every line found to be interface text back in Lines and the stats; on finds them afresh. */
    fun setInterfaceApart(on: Boolean) {
        app.settings.update { it.copy(interfaceApart = on) }
        if (!on) viewModelScope.launch(Dispatchers.IO) { app.database.lines().forgetInterface() }
    }

    fun downloadScreenAi() = viewModelScope.launch { app.models.ensureScreenAi() }

    /** What each kind of thing LFG keeps takes on this device, and the last clean-up's result. */
    data class StorageState(val usage: PictureKeeper.Usage? = null, val message: String? = null)

    private val _storage = MutableStateFlow(StorageState())
    val storage: StateFlow<StorageState> = _storage

    fun measureStorage() = viewModelScope.launch(Dispatchers.IO) {
        _storage.value = _storage.value.copy(usage = app.pictures.usage(app.getDatabasePath("lfg.db")))
    }

    fun cleanUpStorage() = viewModelScope.launch(Dispatchers.IO) {
        val before = _storage.value.usage?.screens
        val gone = app.pictures.sweep(app.settings.prefs.value.keepScreensDays)
        val usage = app.pictures.usage(app.getDatabasePath("lfg.db"))
        val freed = before?.let { (it - usage.screens).coerceAtLeast(0) }
        val size = freed?.let { if (it < 1_048_576) ", ${(it + 1023) / 1024} KB" else ", ${(it + 524_288) / 1_048_576} MB" } ?: ""
        _storage.value = StorageState(usage, if (gone == 0) "Nothing to clear." else "Cleared $gone screen${if (gone == 1) "" else "s"}$size.")
    }

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics

    fun gatherDiagnostics() = viewModelScope.launch(Dispatchers.IO) {
        _diagnostics.value = null
        _diagnostics.value = Diagnostics.report(app)
    }

    /** The dialogue box drawn for each game, by its id. */
    val dialogueBoxes = app.dialogueBoxes.boxes

    /** The game's dialogue box is taken away: the reading notices at its next look. */
    fun readWholeScreen(game: Game) {
        app.dialogueBoxes[game.id] = null
    }

    fun updateGame(game: Game) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val old = app.database.games().byId(game.id)
            app.database.games().update(game)
            if (old != null && old.title != game.title) app.database.games().renameLines(game.id, game.title)
        }
        LfgAccessibilityService.gamesChanged = true
    }

    companion object {
        private const val TEST_LINE = "新しい剣を手に入れた！"
        const val PRACTICE_GAME = "LFG practice"

        fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "${day.dayOfMonth} ${day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${day.year}"
        }
    }
}
