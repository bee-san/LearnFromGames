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
import io.github.beesan.lfg.ask.Explainer
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.backup.Backup
import android.content.Intent
import io.github.beesan.lfg.ask.Recap
import io.github.beesan.lfg.games.GameNameSources
import io.github.beesan.lfg.data.QuestionRow
import io.github.beesan.lfg.data.RecapRow
import kotlinx.serialization.json.Json
import io.github.beesan.lfg.ask.Translator
import io.github.beesan.lfg.anki.AnkiTarget
import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.CardResult
import io.github.beesan.lfg.capture.LfgAccessibilityService
import io.github.beesan.lfg.core.DialogueBox
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.beesan.lfg.capture.DialogueSamples
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

    /** Settings › Profiles. */
    val profiles: StateFlow<List<io.github.beesan.lfg.Profile>> = app.profiles.list

    /** What came of asking for a profile's quick settings tile. */
    private val _profileMessage = MutableStateFlow<String?>(null)
    val profileMessage: StateFlow<String?> = _profileMessage

    fun useProfile(id: String) {
        _profileMessage.value = null
        app.profiles.use(id)
    }

    fun newProfile(language: io.github.beesan.lfg.core.Language) {
        _profileMessage.value = null
        app.profiles.add(language)
    }

    fun renameProfile(id: String, name: String) = app.profiles.rename(id, name)

    fun removeProfile(id: String) {
        _profileMessage.value = null
        app.profiles.remove(id)
    }

    fun profileTile(message: String?) {
        _profileMessage.value = message
    }

    /** Settings › Games and apps: each game given a profile, and the profile's id. */
    val gameProfiles: StateFlow<Map<String, String>> = app.profiles.games

    fun gameProfile(gameId: String, profileId: String?) = app.profiles.link(gameId, profileId)

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
    val lineLookup = LookupController(viewModelScope, { text, gameId -> app.dictionaries.lookup(text, app.characterNames.vnFor(gameId)) }, app.cards, app.anki, app.lines, app.wordAudio, decorate = { app.notes.withNotes(it) }, selection = { app.settings.prefs.value.picking },
        vnQuestion = { app.characterNames.question(it) }, vnAnswer = { question, yes -> app.characterNames.answer(question, yes) },
        lookupKanji = { app.dictionaries.lookupKanji(it, app.settings.prefs.value.kanjiDictionary) },
        // A card made from a page chimes as one made over a game does; the page's own buttons already click.
        heard = { if (it == io.github.beesan.lfg.lookup.Heard.CARD_MADE || it == io.github.beesan.lfg.lookup.Heard.CARD_FAILED) GameSounds.play(it) },
    )

    private val _styles = MutableStateFlow<List<EntryHtml.Style>>(emptyList())
    val styles: StateFlow<List<EntryHtml.Style>> = _styles

    private val _dictionaries = MutableStateFlow(DictionariesState())
    val dictionaries: StateFlow<DictionariesState> = _dictionaries

    private val _sources = MutableStateFlow<List<DictionarySource>>(emptyList())
    val sources: StateFlow<List<DictionarySource>> = _sources

    init {
        viewModelScope.launch { LfgAccessibilityService.running.collect { refresh() } }
        viewModelScope.launch { app.wordAudio.refreshInfo() }
        // Another profile, from here, a tile or the wheel: where its cards go in Anki is its own.
        viewModelScope.launch { app.profiles.list.map { all -> all.firstOrNull { it.inUse }?.id }.distinctUntilChanged().drop(1).collect { refresh() } }
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

    /** "Check now" on Settings › Dictionaries: every dictionary that can be updated is asked about behind the page. */
    fun checkDictionaryUpdates() = io.github.beesan.lfg.dict.DictionaryUpdateWorker.now(app)

    fun dictionaryUpdatesCheckedAt(): Long = app.dictionaries.updatesCheckedAt()

    fun retryDownloads() = DictionaryWorker.start(app, replace = true, wifiOnly = app.settings.prefs.value.wifiOnly)

    /** Waits for Wi-Fi, or not, from now on; downloads still to do start again under the new rule. */
    fun setWifiOnly(on: Boolean) {
        app.settings.update { it.copy(wifiOnly = on) }
        if (app.dictionaries.defaultsPending() || app.dictionaries.state.value !is DictState.Ready || app.models.screenAiDir() == null) {
            DictionaryWorker.start(app, replace = true, wifiOnly = on)
        }
    }

    // ── set-up ───────────────────────────────────────────────────────────────

    /** The controller the device has or is connected to, by its name; null for none. Looked for while set-up asks about one. */
    private val _pad = MutableStateFlow(findPad())
    val pad: StateFlow<String?> = _pad

    fun lookForPad() {
        _pad.value = findPad()
    }

    private fun findPad(): String? {
        val gamepad = android.view.InputDevice.SOURCE_GAMEPAD
        for (id in android.view.InputDevice.getDeviceIds()) {
            val device = android.view.InputDevice.getDevice(id) ?: continue
            if (!device.isVirtual && device.sources and gamepad == gamepad) return device.name?.takeIf { it.isNotBlank() } ?: "a controller"
        }
        return null
    }

    /** The buttons pressed at "Pick your LFG button", as [io.github.beesan.lfg.core.Shortcut] saves them; null before any. */
    private val _shortcutPressed = MutableStateFlow<String?>(null)
    val shortcutPressed: StateFlow<String?> = _shortcutPressed
    private val held = LinkedHashSet<Int>()
    private var chord = emptySet<Int>()

    /**
     * A button went down or up while set-up asks for the shortcut. The answer is every button that was
     * down together, taken when the first of them is let go. Returns whether the press was the answer's:
     * the D-pad and the face buttons are not, so the page can still be moved about and pressed.
     */
    fun shortcutKey(code: Int, down: Boolean): Boolean {
        if (!android.view.KeyEvent.isGamepadButton(code) || !io.github.beesan.lfg.core.Shortcut.allowed(code)) return false
        if (down) {
            if (held.isEmpty()) chord = emptySet()
            held += code
            chord = chord + code
        } else {
            held -= code
            if (chord.isNotEmpty()) io.github.beesan.lfg.core.Shortcut.of(chord.take(2))?.let { _shortcutPressed.value = it }
        }
        return true
    }

    /** The shortcut's step was left: the next visit starts from what is saved. */
    fun forgetShortcutPressed() {
        held.clear()
        chord = emptySet()
        _shortcutPressed.value = null
    }

    // ── lines ────────────────────────────────────────────────────────────────

    /** The stored line open in [lineLookup]. */
    private var opened: GameLine? = null

    fun openLine(line: GameLine) {
        opened = line
        _translation.value = TranslationState(line.translation)
        _explanation.value = ExplainState(app.explainer.kept(line.id))
        // Explained long ago, it is not in memory: it is read from the line's row.
        viewModelScope.launch {
            val kept = app.explainer.conversation(line.id)
            if (opened?.id == line.id && _explanation.value.talk == null) _explanation.update { it.copy(talk = kept) }
        }
        lineLookup.setLines(listOf(LookupLine(line.lineText, line.id, picture(line), line.gameName, gameId = line.gameId)))
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

    /**
     * The open line's explanation and what was asked about it since ([talk]): being explained ([busy]) or
     * why it was not ([error]); the question waiting for its answer ([asking]) or why it got none ([askError]).
     */
    data class ExplainState(
        val talk: Explainer.Conversation? = null,
        val busy: Boolean = false,
        val error: String? = null,
        val asking: String? = null,
        val askError: String? = null,
    )

    /** The lines explained, as far as memory holds them: marked in the list of lines. */
    val explained: StateFlow<Map<String, Explainer.Conversation>> get() = app.explainer.conversations

    private val _explanation = MutableStateFlow(ExplainState())
    val explanation: StateFlow<ExplainState> = _explanation

    /** Explains the open line, in place of any explanation and questions it has. */
    fun explainLine() {
        val line = opened ?: return
        if (_explanation.value.busy) return
        _explanation.update { it.copy(busy = true, error = null, asking = null, askError = null) }
        viewModelScope.launch {
            val answer = try {
                ExplainState(app.explainer.explain(line))
            } catch (e: OpenRouter.Failure) {
                ExplainState(_explanation.value.talk, error = e.message)
            }
            // Another line may have been opened while this one was asked about.
            if (opened?.id == line.id) _explanation.value = answer
        }
    }

    /** Asks [question] about the open line, after its explanation. */
    fun askLine(question: String) {
        val line = opened ?: return
        val asked = question.trim()
        val now = _explanation.value
        if (asked.isEmpty() || now.busy || now.talk == null || (now.asking != null && now.askError == null)) return
        _explanation.update { it.copy(asking = asked, askError = null) }
        viewModelScope.launch {
            val answer = try {
                ExplainState(app.explainer.ask(line, asked))
            } catch (e: OpenRouter.Failure) {
                _explanation.value.copy(askError = e.message)
            }
            if (opened?.id == line.id) _explanation.value = answer
        }
    }

    /**
     * A line opened from over a game to have a question written about it: [lineId]'s page, or nothing
     * when the line is gone.
     */
    fun askFromGame(lineId: String?, then: (Boolean) -> Unit) {
        viewModelScope.launch {
            val line = lineId?.let { id -> withContext(Dispatchers.IO) { app.database.lines().byId(id) } }
            if (line != null) openLine(line)
            then(line != null)
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

    // ── the wheel ────────────────────────────────────────────────────────────

    /** Every wheel kept: the shared one under [io.github.beesan.lfg.Wheels.SHARED], and each game's own under its id. */
    val wheels = app.wheels.sets

    /** Whose wheel Settings › Wheel is about: a game's id, or [io.github.beesan.lfg.Wheels.SHARED]. */
    private val _wheelGame = MutableStateFlow(io.github.beesan.lfg.Wheels.SHARED)
    val wheelGame: StateFlow<String> = _wheelGame

    /** What saving or loading a wheel's file did, or what went wrong. */
    private val _wheelMessage = MutableStateFlow<String?>(null)
    val wheelMessage: StateFlow<String?> = _wheelMessage

    fun chooseWheel(gameId: String) {
        _wheelGame.value = gameId
        _wheelMessage.value = null
    }

    /** The game whose own wheel is chosen, or the shared one's place while every game has the same wheel. */
    private fun wheelOwner() = _wheelGame.value.takeIf { app.settings.prefs.value.wheelPerGame }.orEmpty()

    /** The wheel chosen as it is now: the game's own, or the shared one it uses until its own is changed. */
    private fun wheelNow() = app.wheels.of(wheelOwner(), perGame = true)

    /** The wheel chosen is [set] from now on; a game that used the shared one has its own. */
    fun setWheel(set: io.github.beesan.lfg.core.WheelSet) = app.wheels.set(wheelOwner(), set)

    /** A game's wheel goes back to the shared one, and the shared one to the wheel LFG starts with. */
    fun resetWheel() {
        app.wheels.set(wheelOwner(), null)
        _wheelMessage.value = null
    }

    fun saveWheelTo(uri: Uri?) {
        uri ?: return
        val text = wheelNow().encode()
        viewModelScope.launch(Dispatchers.IO) {
            _wheelMessage.value = try {
                app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } ?: throw java.io.IOException()
                "Saved."
            } catch (_: Exception) {
                "The wheel couldn’t be written there. Try another folder."
            }
        }
    }

    fun loadWheelFrom(uri: Uri?) {
        uri ?: return
        val owner = wheelOwner()
        viewModelScope.launch(Dispatchers.IO) {
            val set = try {
                // A wheel's file is a few kilobytes: anything much bigger is not one.
                app.contentResolver.openInputStream(uri)?.use { it.readNBytes(WHEEL_FILE_BYTES).decodeToString() }?.let(io.github.beesan.lfg.core.WheelSet::decode)
            } catch (_: Exception) {
                null
            }
            if (set != null) app.wheels.set(owner, set)
            _wheelMessage.value = if (set != null) "Loaded." else "That file is not a wheel."
        }
    }

    // ── search ───────────────────────────────────────────────────────────────

    /** Looks [text] up from its first character, in [lineLookup], as a word typed on the Search page. */
    fun search(text: String) {
        opened = null
        val word = text.trim()
        lineLookup.setLines(if (word.isEmpty()) emptyList() else listOf(LookupLine(word, null, null, "")))
        if (word.isNotEmpty()) lineLookup.select(0, 0)
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

    // ── ask ──────────────────────────────────────────────────────────────────

    /** A game's questions, newest first, and the one being asked. */
    data class AskState(
        val gameId: String? = null,
        val history: List<QuestionRow> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _asking = MutableStateFlow(AskState())
    val asking: StateFlow<AskState> = _asking

    /** Ask opens on the game read last. */
    fun openAsk() = askGame(lines.value.firstOrNull()?.gameId ?: games.value.firstOrNull()?.id)

    fun askGame(gameId: String?) {
        _asking.value = AskState(gameId)
        if (gameId == null) return
        viewModelScope.launch {
            val history = app.database.questions().recent(gameId)
            _asking.update { if (it.gameId == gameId) it.copy(history = history) else it }
        }
    }

    fun ask(question: String, withScreen: Boolean) {
        val gameId = _asking.value.gameId ?: return
        if (_asking.value.busy || question.isBlank()) return
        val name = games.value.firstOrNull { it.id == gameId }?.title.orEmpty()
        _asking.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = try {
                app.asker.ask(gameId, name, question, withScreen)
                null
            } catch (e: OpenRouter.Failure) {
                e.message
            }
            val history = app.database.questions().recent(gameId)
            _asking.update { if (it.gameId == gameId) it.copy(history = history, busy = false, error = error) else it }
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

    // ── diary ────────────────────────────────────────────────────────────────

    val diary = DiaryModel(app, viewModelScope) { lines.value.firstOrNull() }

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

    /** The dialogue kept for training a better reader: how much, and the zip made to send it. */
    data class DialogueState(
        val kept: DialogueSamples.Kept? = null,
        val busy: Boolean = false,
        val zip: File? = null,
        val message: String? = null,
    )

    private val _dialogue = MutableStateFlow(DialogueState())
    val dialogue: StateFlow<DialogueState> = _dialogue

    fun measureDialogue() = viewModelScope.launch(Dispatchers.IO) {
        _dialogue.update { it.copy(kept = DialogueSamples.kept(app.filesDir), zip = null) }
    }

    /** Zips every kept screen; the page hands [DialogueState.zip] to the mail app once it is made. */
    fun zipDialogue() = viewModelScope.launch(Dispatchers.IO) {
        _dialogue.update { it.copy(busy = true, message = null, zip = null) }
        val zip = runCatching { DialogueSamples.zip(app.filesDir, File(app.cacheDir, DialogueSamples.DIR)) }
        _dialogue.update {
            it.copy(
                busy = false, zip = zip.getOrNull(),
                message = when {
                    zip.isFailure -> "Could not make the zip: ${zip.exceptionOrNull()?.message}"
                    zip.getOrNull() == null -> "Nothing kept yet."
                    else -> null
                },
            )
        }
    }

    fun dialogueSent() = _dialogue.update { it.copy(zip = null) }

    fun clearDialogue() = viewModelScope.launch(Dispatchers.IO) {
        val gone = DialogueSamples.clear(app.filesDir)
        File(app.cacheDir, DialogueSamples.DIR).listFiles()?.forEach { it.delete() }
        _dialogue.value = DialogueState(DialogueSamples.kept(app.filesDir), message = "Deleted $gone screen${if (gone == 1) "" else "s"}.")
    }

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics

    fun gatherDiagnostics() = viewModelScope.launch(Dispatchers.IO) {
        _diagnostics.value = null
        _diagnostics.value = Diagnostics.report(app)
    }

    /** The dialogue box drawn for each game, by its id: box 1. */
    val dialogueBoxes = app.dialogueBoxes.boxes

    /** Each game's boxes after the dialogue box, by its id: box 2 onwards. */
    val extraBoxes = app.extraBoxes.boxes

    /** The game's box [slot] is taken away; for box 1 the reading notices at its next look, and reads the whole screen. */
    fun removeBox(game: Game, slot: Int) {
        if (slot == 1) app.dialogueBoxes[game.id] = null else app.extraBoxes.set(game.id, slot, null)
    }

    /** The game's box [slot] is drawn in Settings, or added past the last: the reading notices at its next look. */
    fun saveBox(game: Game, slot: Int, box: DialogueBox) {
        // A box drawn again is read as it was: all the time, or when picked.
        val always = app.extraBoxes[game.id].getOrNull(slot - 2)?.always == true
        if (slot == 1) app.dialogueBoxes[game.id] = box else app.extraBoxes.set(game.id, slot, box.copy(always = always))
    }

    /** The game's box [slot] (2 onwards) is read all the time beside box 1, or only when picked on the wheel. */
    fun setBoxAlways(game: Game, slot: Int, always: Boolean) = app.extraBoxes.setAlways(game.id, slot, always)

    /** The newest picture kept of a game's screen, if its file is still there. */
    suspend fun lastPicture(gameId: String): String? = withContext(Dispatchers.IO) {
        app.database.lines().lastPicture(gameId)?.takeIf { java.io.File(it).isFile }
    }

    /** A box being drawn in Settings: the game, which of its boxes, and the picture it is drawn on. */
    data class BoxDraw(val game: Game, val slot: Int, val path: String?)

    private val _boxDraw = MutableStateFlow<BoxDraw?>(null)
    val boxDraw: StateFlow<BoxDraw?> = _boxDraw

    fun startBoxDraw(game: Game, slot: Int) {
        // Nothing of the last box drawn shows while this game's picture is found.
        _boxDraw.value = null
        viewModelScope.launch { _boxDraw.value = BoxDraw(game, slot, lastPicture(game.id)) }
    }

    // ── character names ──────────────────────────────────────────────────

    /** Character-name builds under way or failed, by VNDB ID. */
    val nameBuilds: StateFlow<Map<String, io.github.beesan.lfg.names.CharacterNames.Build>> = app.characterNames.builds

    /** The search on a game's page for its visual novel. */
    data class VnSearch(
        val gameId: String? = null,
        val searching: Boolean = false,
        val results: List<io.github.beesan.lfg.names.VnResult> = emptyList(),
        val error: String? = null,
        val searched: Boolean = false,
    )

    private val _vnSearch = MutableStateFlow(VnSearch())
    val vnSearch: StateFlow<VnSearch> = _vnSearch

    fun vnTitle(vnId: String): String? = app.characterNames.title(vnId)

    fun startVnSearch(game: Game) {
        _vnSearch.value = VnSearch(gameId = game.id)
        searchVn(game.title)
    }

    fun searchVn(query: String) {
        val gameId = _vnSearch.value.gameId
        if (query.isBlank()) return
        _vnSearch.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            val game = gameId?.let { withContext(Dispatchers.IO) { app.database.games().byId(it) } }
            val result = runCatching { app.characterNames.search(query, game) }
            _vnSearch.update {
                it.copy(
                    searching = false, searched = true, results = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "VNDB could not be searched." },
                )
            }
        }
    }

    /** Links the searched game to the visual novel picked, by hand: it is never changed by itself after. */
    fun pickVn(result: io.github.beesan.lfg.names.VnResult) = viewModelScope.launch {
        val game = _vnSearch.value.gameId?.let { withContext(Dispatchers.IO) { app.database.games().byId(it) } } ?: return@launch
        app.characterNames.link(game, result.id, Game.HOW_HAND, result.alttitle ?: result.title)
    }

    fun acceptVn(game: Game) = viewModelScope.launch { app.characterNames.accept(game) }
    fun declineVn(game: Game) = viewModelScope.launch { app.characterNames.decline(game) }
    fun buildNames(vnId: String) = app.characterNames.build(vnId)
    fun stopNames(vnId: String) = app.characterNames.stop(vnId)
    fun removeNames(vnId: String) = viewModelScope.launch { app.characterNames.remove(vnId) }

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

        /** The most of a file that is read as a wheel. */
        private const val WHEEL_FILE_BYTES = 262_144

        fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "${day.dayOfMonth} ${day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${day.year}"
        }
    }
}
