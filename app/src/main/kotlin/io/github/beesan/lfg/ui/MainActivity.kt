// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import io.github.beesan.lfg.lookup.Move
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.core.Updates
import io.github.beesan.lfg.backup.Backup
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.beesan.lfg.anki.AnkiBridge
import io.github.beesan.lfg.capture.DialogueSamples
import io.github.beesan.lfg.capture.LfgAccessibilityService
import io.github.beesan.lfg.capture.SoundCapture
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.ocr.ScreenAi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    /** A page asked for from outside: a widget opens Stats. */
    private val requested = MutableStateFlow<Page?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) toSecondScreen()
        if (savedInstanceState == null) take(intent)
        setContent {
            val setup by model.setup.collectAsStateWithLifecycle()
            val dictionary by model.dictionary.collectAsStateWithLifecycle()
            val fetchingDictionary by model.fetchingDictionary.collectAsStateWithLifecycle()
            val reader by model.reader.collectAsStateWithLifecycle()
            val prefs by model.prefs.collectAsStateWithLifecycle()
            val practice by model.practice.collectAsStateWithLifecycle()
            val practiceDone by model.practiceDone.collectAsStateWithLifecycle()
            val lines by model.lines.collectAsStateWithLifecycle()
            val linesLoaded by model.linesLoaded.collectAsStateWithLifecycle()
            val games by model.games.collectAsStateWithLifecycle()
            val stats by model.stats.collectAsStateWithLifecycle()
            val lookup by model.lineLookup.state.collectAsStateWithLifecycle()
            val diary by model.diary.state.collectAsStateWithLifecycle()
            val draft by model.diary.draft.collectAsStateWithLifecycle()
            val sharing by model.diary.sharing.collectAsStateWithLifecycle()
            val translation by model.translation.collectAsStateWithLifecycle()
            val explanation by model.explanation.collectAsStateWithLifecycle()
            val explained by model.explained.collectAsStateWithLifecycle()
            val keyTest by model.keyTest.collectAsStateWithLifecycle()
            val recap by model.recap.collectAsStateWithLifecycle()
            val asking by model.asking.collectAsStateWithLifecycle()
            val backupMessage by model.backupMessage.collectAsStateWithLifecycle()
            val pendingRestore by model.pendingRestore.collectAsStateWithLifecycle()
            val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { model.backUpTo(it) }
            val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.askRestore(it) }
            val saveLines = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { model.exportLinesTo(it) }
            val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { model.chooseBackupFolder(it) }
            val wheels by model.wheels.collectAsStateWithLifecycle()
            val wheelGame by model.wheelGame.collectAsStateWithLifecycle()
            val wheelMessage by model.wheelMessage.collectAsStateWithLifecycle()
            val saveWheel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.saveWheelTo(it) }
            val openWheel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.loadWheelFrom(it) }
            val gameNameStatus by model.gameNameStatus.collectAsStateWithLifecycle()
            val steamTest by model.steamTest.collectAsStateWithLifecycle()
            // Which source the folder being picked is for; kept across the picker in case Android recreates this activity.
            var pickingFor by rememberSaveable { mutableStateOf("") }
            val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { model.chooseGameFolder(pickingFor, it) }
            val styles by model.styles.collectAsStateWithLifecycle()
            val dictionaries by model.dictionaries.collectAsStateWithLifecycle()
            val sources by model.sources.collectAsStateWithLifecycle()
            val wordAudio by model.wordAudio.collectAsStateWithLifecycle()
            val nameBuilds by model.nameBuilds.collectAsStateWithLifecycle()
            val vnSearch by model.vnSearch.collectAsStateWithLifecycle()
            val dialogueBoxes by model.dialogueBoxes.collectAsStateWithLifecycle()
            val extraBoxes by model.extraBoxes.collectAsStateWithLifecycle()
            val boxDraw by model.boxDraw.collectAsStateWithLifecycle()
            val recording by SoundCapture.running.collectAsStateWithLifecycle()
            val update by Updates.available.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { Updates.check(applicationContext) }
            val notListening = prefs.sentenceAudio && !recording
            // The colours Settings picked, light or dark as the theme says, "Match system" following the system.
            val palette = Palette.of(prefs.colours, prefs.theme, isSystemInDarkTheme())
            if (Lfg.palette != palette) Lfg.palette = palette
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(palette.bg))
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !palette.dark
                    isAppearanceLightNavigationBars = !palette.dark
                }
            }
            var page by rememberSaveable { mutableStateOf(Page.HOME) }
            // Where a line was opened from, Home or Lines, so back returns there.
            var lineFrom by rememberSaveable { mutableStateOf(Page.HOME) }
            // The game whose own stats page is open, and the period it opened on.
            var statsGame by rememberSaveable { mutableStateOf("") }
            var statsGamePeriod by rememberSaveable { mutableStateOf(StatsPeriod.DAYS_30) }
            // Where an entry's writing page was opened from, so leaving it returns there.
            var writeFrom by rememberSaveable { mutableStateOf(Page.DIARY) }
            val openDiary = {
                model.diary.load()
                page = Page.DIARY
            }
            val saved = rememberSaveableStateHolder()

            val ankiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) model.connectAnki() else model.refresh()
            }
            val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.addDictionary(uri)
            }
            val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.addWordAudio(uri)
            }
            val recordConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode == RESULT_OK && data != null) SoundCapture.start(this, result.resultCode, data)
            }
            val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                // Android routes game audio capture through its recording permission.
                if (granted) recordConsent.launch(SoundCapture.consentIntent(this))
            }
            val asked by requested.collectAsState()
            LaunchedEffect(asked) {
                asked?.let { page = it }
                requested.value = null
            }
            val ready = setup.complete(dictionary, reader) && prefs.keepDialogueAsked && Language.of(prefs.language) != null
            val showPractice = page == Page.PRACTICE || (page == Page.HOME && ready && !practiceDone)

            // The first word is looked up before anything is tapped, as the controller cursor is.
            LaunchedEffect(showPractice) {
                if (showPractice && practice.entry == null) model.lookupPractice(PRACTICE_SENTENCE.indexOf("不思議"))
            }
            fun back() {
                // Stats keeps its period and chart across the goals page, and opens on its defaults again after Home.
                if (page == Page.STATS) saved.removeState(Page.STATS)
                page = when (page) {
                    Page.LINE -> lineFrom
                    Page.LINES, Page.DIARY, Page.RECAP, Page.ASK, Page.STATS, Page.SETTINGS, Page.SEARCH -> Page.HOME
                    Page.WHEEL_EDIT -> Page.WHEEL
                    Page.DIARY_MONTH -> Page.DIARY
                    Page.DIARY_DAY -> Page.DIARY_MONTH
                    Page.DIARY_ENTRY -> Page.DIARY_DAY
                    Page.DIARY_WRITE -> writeFrom
                    Page.DIARY_SHARE -> Page.DIARY_ENTRY
                    Page.SOURCES -> Page.DICTIONARIES
                    Page.VN_SEARCH, Page.BOX_DRAW -> Page.GAMES
                    Page.GOALS, Page.GAME_STATS -> Page.STATS
                    Page.HOME -> Page.HOME
                    else -> Page.SETTINGS
                }
            }
            // A line's page opened from over a game to ask about the line goes back to the game's lines.
            BackHandler(enabled = page != Page.HOME) { if ((page == Page.LINE && askFromGame) || (page == Page.DIARY_WRITE && noteFromGame)) finish() else back() }

            Box(Modifier.fillMaxSize().background(Lfg.Bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                val frame = Modifier.widthIn(max = 980.dp).padding(horizontal = 24.dp, vertical = 20.dp)
                Box(frame) {
                    when {
                        page == Page.HOME && !ready -> {
                            val scroll = rememberScrollState()
                            Box(Modifier.padScroll(scroll).verticalScroll(scroll).firstFocus()) {
                                SetupScreen(
                                    setup = setup,
                                    dictionary = dictionary,
                                    reader = reader,
                                    onTurnOn = ::openAccessibilitySettings,
                                    onOpenAppInfo = ::openAppInfo,
                                    onConnectAnki = { ankiPermission.launch(AnkiBridge.PERMISSION) },
                                    onInstallAnki = ::openAnkiDroidListing,
                                    onRetryDownloads = model::retryDownloads,
                                    wifiOnly = prefs.wifiOnly,
                                    onWifiOnly = model::setWifiOnly,
                                    language = Language.of(prefs.language),
                                    onLanguage = { picked -> model.updatePrefs { it.copy(language = picked.tag) } },
                                    askKeepDialogue = !prefs.keepDialogueAsked,
                                    onKeepDialogue = { yes -> model.updatePrefs { it.copy(keepDialogue = yes, keepDialogueAsked = true) } },
                                )
                            }
                        }
                        showPractice -> {
                            val scroll = rememberScrollState()
                            Box(Modifier.padScroll(scroll).verticalScroll(scroll).firstFocus()) {
                                ReadyScreen(
                                    setup = setup,
                                    dictionaryTitles = (dictionary as? DictState.Ready)?.dictionaries?.joinToString { it.title } ?: "",
                                    practice = practice,
                                    shortcut = shortcutLabel(prefs),
                                    onTapCharacter = model::lookupPractice,
                                    onAddCard = model::addPracticeCard,
                                    onDone = {
                                        model.finishPractice()
                                        page = Page.HOME
                                    },
                                )
                            }
                        }
                        page == Page.HOME -> HomeScreen(
                            lines = lines,
                            stats = stats,
                            prefs = prefs,
                            reading = setup.serviceOn,
                            shortcut = shortcutLabel(prefs),
                            loaded = linesLoaded,
                            problem = if (notListening) ("Sentence audio is not listening to game sound yet" to "Start")
                                else update?.let { "LFG ${it.version} is out" to "Update" },
                            onProblem = {
                                if (notListening) microphone.launch(android.Manifest.permission.RECORD_AUDIO)
                                else update?.let { open(Intent(Intent.ACTION_VIEW, Uri.parse(it.url))) }
                            },
                            onTurnOn = ::openAccessibilitySettings,
                            onOpenLine = {
                                model.openLine(it)
                                lineFrom = Page.HOME
                                page = Page.LINE
                            },
                            onLines = { page = Page.LINES },
                            onStats = { page = Page.STATS },
                            onGoals = { page = Page.GOALS },
                            onDiary = openDiary,
                            onRecap = {
                                model.openRecap()
                                page = Page.RECAP
                            },
                            onAsk = {
                                model.openAsk()
                                page = Page.ASK
                            },
                            onSettings = { page = Page.SETTINGS },
                        )
                        page == Page.LINES -> LinesScreen(
                            lines = lines,
                            explained = explained,
                            shortcut = shortcutLabel(prefs),
                            loaded = linesLoaded,
                            // Sentence audio is on from the start, and Android has to be asked each session before it can listen.
                            problem = ("Sentence audio is not listening to game sound yet" to "Start").takeIf { prefs.sentenceAudio && !recording },
                            onProblem = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) },
                            onOpenLine = {
                                model.openLine(it)
                                lineFrom = Page.LINES
                                page = Page.LINE
                            },
                            onStats = { page = Page.STATS },
                            onSettings = { page = Page.SETTINGS },
                            onDiary = openDiary,
                            onRecap = {
                                model.openRecap()
                                page = Page.RECAP
                            },
                            onAsk = {
                                model.openAsk()
                                page = Page.ASK
                            },
                        )
                        page == Page.ASK -> AskScreen(
                            state = asking,
                            games = games,
                            on = prefs.ask,
                            hasKey = prefs.openRouterKey.isNotBlank(),
                            onGame = model::askGame,
                            onAsk = model::ask,
                            onTurnOn = { model.updatePrefs { it.copy(ask = true) } },
                            onKey = { page = Page.TRANSLATION },
                            onBack = ::back,
                        )
                        page == Page.RECAP -> RecapScreen(
                            state = recap,
                            games = games,
                            hasKey = prefs.openRouterKey.isNotBlank(),
                            onWrite = model::writeRecap,
                            onKey = { page = Page.TRANSLATION },
                            onBack = ::back,
                        )
                        page == Page.DIARY -> DiaryShelfScreen(
                            state = diary,
                            onBook = {
                                model.diary.openBook(it)
                                page = Page.DIARY_MONTH
                            },
                            onCalendar = {
                                model.diary.openCalendar()
                                page = Page.DIARY_MONTH
                            },
                            onSettings = { page = Page.DIARY_SETTINGS },
                            onBack = ::back,
                        )
                        page == Page.DIARY_MONTH -> DiaryMonthScreen(
                            state = diary,
                            today = diaryToday(),
                            onMonth = { model.diary.month(it.toLong()) },
                            onOpen = {
                                model.diary.openDay(it)
                                page = Page.DIARY_DAY
                            },
                            onBack = ::back,
                        )
                        page == Page.DIARY_DAY -> diary.page.let { open ->
                            if (open == null) {
                                // The day is being read, or is not in the diary after all.
                                if (diary.loaded) LaunchedEffect(Unit) { page = Page.DIARY }
                            } else {
                                DiaryDayScreen(
                                    page = open,
                                    today = diaryToday(),
                                    before = model.diary.neighbour(-1),
                                    after = model.diary.neighbour(1),
                                    onStep = { by -> model.diary.neighbour(by)?.let(model.diary::openDay) },
                                    onEntry = {
                                        model.diary.openEntry(it.id)
                                        page = Page.DIARY_ENTRY
                                    },
                                    onWrite = {
                                        model.diary.write(open)
                                        writeFrom = Page.DIARY_DAY
                                        page = Page.DIARY_WRITE
                                    },
                                    onBack = ::back,
                                )
                            }
                        }
                        page == Page.DIARY_ENTRY -> diary.entry.let { entry ->
                            if (entry == null) {
                                if (diary.loaded) LaunchedEffect(Unit) { page = Page.DIARY_DAY }
                            } else {
                                DiaryEntryScreen(
                                    entry = entry,
                                    page = diary.page,
                                    onShare = {
                                        model.diary.share(entry)
                                        page = Page.DIARY_SHARE
                                    },
                                    onEdit = {
                                        model.diary.edit(entry)
                                        writeFrom = Page.DIARY_ENTRY
                                        page = Page.DIARY_WRITE
                                    },
                                    onBack = ::back,
                                )
                            }
                        }
                        page == Page.DIARY_WRITE -> DiaryWriteScreen(
                            draft = draft,
                            onPicture = model.diary::picture,
                            onSave = { title, text ->
                                // Once it is kept: the entry's page has nothing to show before, and leaving ends the saving.
                                model.diary.save(title, text) { if (noteFromGame) finish() else page = Page.DIARY_ENTRY }
                            },
                            onShare = { title, text ->
                                // Saved first, then shared: there is one entry, not a saved one and a shared one.
                                model.diary.save(title, text) {
                                    model.diary.state.value.entry?.let(model.diary::share)
                                    page = Page.DIARY_SHARE
                                }
                            },
                            onCancel = { if (noteFromGame) finish() else back() },
                        )
                        page == Page.DIARY_SHARE -> diary.entry?.let { entry ->
                            DiaryShareScreen(
                                entry = entry,
                                picture = entry.picturePath ?: diary.page?.picture,
                                sharing = sharing,
                                prefs = prefs,
                                onTranslate = model.diary::translate,
                                onShare = { choice -> shareEntry(entry, entry.picturePath ?: diary.page?.picture, shareWords(entry, sharing, choice)) },
                                onBack = ::back,
                            )
                        }
                        page == Page.DIARY_SETTINGS -> DiarySettingsScreen(prefs, model::updatePrefs, ::back)
                        page == Page.LINE -> LineLookupScreen(
                            state = lookup,
                            styles = styles,
                            media = model::mediaBlocking,
                            onTap = { model.lineLookup.select(0, it) },
                            onLink = { link, index ->
                                when (link) {
                                    EntryLink.CARD -> model.lineLookup.addCard(index)
                                    EntryLink.AUDIO -> model.lineLookup.playAudio(index)
                                    // Notes on words are put away for now (data/Notes.kt).
                                    EntryLink.NOTE -> Unit
                                    EntryLink.VN_YES -> model.lineLookup.answerVn(true)
                                    EntryLink.VN_NO -> model.lineLookup.answerVn(false)
                                    EntryLink.KANJI -> model.lineLookup.lookUpKanji(index)
                                    EntryLink.BACK -> model.lineLookup.backToWord()
                                }
                            },
                            translation = translation,
                            explanation = explanation,
                            ai = prefs.aiReady,
                            translateContext = prefs.translateContext,
                            onTranslate = model::translateLine,
                            onExplain = model::explainLine,
                            onAsk = model::askLine,
                            askNow = askFromGame,
                            onBack = { if (askFromGame) finish() else back() },
                            pad = LinePad(
                                onFocused = model.lineLookup::startCursor,
                                onStep = { forward -> model.lineLookup.move(if (forward) Move.RIGHT else Move.LEFT) },
                                onCard = { model.lineLookup.addCard() },
                                onAudio = { model.lineLookup.playAudio() },
                                onNote = {},
                                onEntry = { next -> if (next) model.lineLookup.nextEntry() else model.lineLookup.previousEntry() },
                            ),
                        )
                        page == Page.STATS -> saved.SaveableStateProvider(Page.STATS) {
                            StatsScreen(
                                stats, games, prefs.goals, { page = Page.GOALS },
                                onGame = { id, period ->
                                    statsGame = id
                                    statsGamePeriod = period
                                    page = Page.GAME_STATS
                                },
                                onBack = ::back,
                            )
                        }
                        page == Page.GAME_STATS -> GameStatsScreen(stats, games.firstOrNull { it.id == statsGame }, statsGame, statsGamePeriod, ::back)
                        page == Page.GOALS -> GoalsScreen(prefs, model::updatePrefs, ::back)
                        page == Page.SETTINGS -> SettingsScreen(onOpen = { page = it }, onBack = ::back)
                        page == Page.DICTIONARIES -> DictionariesScreen(
                            state = dictionary,
                            fetching = fetchingDictionary,
                            busy = dictionaries,
                            onAdd = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            onMove = { title, up -> model.moveDictionary(title, up) },
                            onRemove = { model.removeDictionary(it) },
                            onSources = { page = Page.SOURCES },
                            onRemoveNames = { model.removeNames(it) },
                            wordAudio = wordAudio,
                            onAddAudio = { pickAudio.launch(arrayOf("*/*")) },
                            onRemoveAudio = { model.removeWordAudio() },
                            starterMissing = remember(dictionary, fetchingDictionary) { model.starterMissing() },
                            onStarter = model::fetchStarterDictionaries,
                            updates = prefs.updateDictionaries,
                            onUpdates = { on -> model.updatePrefs { it.copy(updateDictionaries = on) } },
                            updatesCheckedAt = remember(dictionary, fetchingDictionary) { model.dictionaryUpdatesCheckedAt() },
                            onCheckUpdates = model::checkDictionaryUpdates,
                            kanjiDictionary = prefs.kanjiDictionary,
                            onKanjiDictionary = { title -> model.updatePrefs { it.copy(kanjiDictionary = title) } },
                            onBack = ::back,
                        )
                        page == Page.SOURCES -> SourcesScreen(sources, ::back)
                        page == Page.ANKI -> AnkiScreen(setup, model::connectAnki, { ankiPermission.launch(AnkiBridge.PERMISSION) }, ::back)
                        page == Page.APPEARANCE -> AppearanceScreen(prefs, model::updatePrefs, ::back)
                        page == Page.BACKUP -> BackupScreen(
                            prefs = prefs,
                            onChange = model::updatePrefs,
                            message = backupMessage,
                            pendingRestore = pendingRestore?.lastPathSegment?.substringAfterLast('/'),
                            onBackUp = { saveBackup.launch(Backup.fileName()) },
                            onPickRestore = { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                            onRestore = model::restore,
                            onCancelRestore = model::cancelRestore,
                            onExport = { saveLines.launch("lfg-lines-export.csv") },
                            onChooseFolder = { pickFolder.launch(null) },
                            onBack = ::back,
                        )
                        page == Page.TRANSLATION -> TranslationScreen(prefs, model::updatePrefs, keyTest, model::testKey, ::back)
                        page == Page.CONTROLS -> ControlsScreen(prefs, model::updatePrefs, ::back)
                        page == Page.WHEEL -> {
                            // Games a wheel can be given to: the one read last first, then the rest by name.
                            val played = lines.map { it.gameId }.distinct()
                            val wheelGames = games.filter { !it.app }.sortedBy { g -> played.indexOf(g.id).let { if (it < 0) Int.MAX_VALUE else it } }
                            WheelSettingsScreen(
                                prefs, model::updatePrefs, wheels, wheelGames, wheelGame, model::chooseWheel, wheelMessage,
                                onEdit = { page = Page.WHEEL_EDIT },
                                onSave = { saveWheel.launch("lfg-wheel.json") },
                                onLoad = { openWheel.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                                onReset = model::resetWheel,
                                onControls = { page = Page.CONTROLS },
                                onBack = ::back,
                            )
                        }
                        page == Page.WHEEL_EDIT -> {
                            val game = games.firstOrNull { it.id == wheelGame }?.takeIf { prefs.wheelPerGame }
                            WheelEditScreen(
                                set = game?.let { wheels[it.id] } ?: wheels[io.github.beesan.lfg.Wheels.SHARED] ?: io.github.beesan.lfg.core.WheelSet.DEFAULT,
                                whose = game?.title.orEmpty(),
                                onChange = model::setWheel,
                                onReset = model::resetWheel,
                                onBack = ::back,
                            )
                        }
                        page == Page.SEARCH -> SearchScreen(
                            lookup, styles, model::mediaBlocking, model::search,
                            onTap = { model.lineLookup.select(0, it) },
                            onLink = { link, index ->
                                when (link) {
                                    EntryLink.CARD -> model.lineLookup.addCard(index)
                                    EntryLink.AUDIO -> model.lineLookup.playAudio(index)
                                    EntryLink.KANJI -> model.lineLookup.lookUpKanji(index)
                                    EntryLink.BACK -> model.lineLookup.backToWord()
                                    else -> Unit
                                }
                            },
                            onBack = ::back,
                        )
                        page == Page.RECOGNITION -> RecognitionScreen(
                            reader, ScreenAi.available(), { model.downloadScreenAi() }, prefs.interfaceApart, model::setInterfaceApart, ::back,
                        )
                        page == Page.STORAGE -> {
                            val storage by model.storage.collectAsStateWithLifecycle()
                            LaunchedEffect(Unit) { model.measureStorage() }
                            StorageScreen(
                                storage.usage, prefs.keepScreensDays, storage.message,
                                onKeepDays = { days -> model.updatePrefs { it.copy(keepScreensDays = days) } },
                                onCleanUp = model::cleanUpStorage,
                                onBack = ::back,
                            )
                        }
                        page == Page.DIALOGUE -> {
                            val dialogue by model.dialogue.collectAsStateWithLifecycle()
                            LaunchedEffect(Unit) { model.measureDialogue() }
                            LaunchedEffect(dialogue.zip) {
                                dialogue.zip?.let {
                                    sendDialogue(it)
                                    model.dialogueSent()
                                }
                            }
                            DialogueScreen(
                                keep = prefs.keepDialogue,
                                state = dialogue,
                                onKeep = { on -> model.updatePrefs { it.copy(keepDialogue = on, keepDialogueAsked = true) } },
                                onSend = model::zipDialogue,
                                onClear = model::clearDialogue,
                                onDiscord = { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DialogueSamples.DISCORD))) },
                                onBack = ::back,
                            )
                        }
                        page == Page.HELP -> {
                            val report by model.diagnostics.collectAsStateWithLifecycle()
                            LaunchedEffect(Unit) { model.gatherDiagnostics() }
                            HelpScreen(report, onCopy = { report?.let(::copyReport) }, onShare = { report?.let(::shareReport) }, onBack = ::back)
                        }
                        page == Page.GAMES -> GamesScreen(
                            games, packageName, { model.updateGame(it) }, ::back,
                            boxes = dialogueBoxes, extraBoxes = extraBoxes, picture = model::lastPicture,
                            onDrawBox = { game, slot ->
                                model.startBoxDraw(game, slot)
                                page = Page.BOX_DRAW
                            },
                            onRemoveBox = model::removeBox,
                            names = (dictionary as? DictState.Ready)?.dictionaries.orEmpty().filter { it.vndb != null },
                            builds = nameBuilds,
                            namesActions = NamesRowActions(
                                title = model::vnTitle,
                                onSearch = { game ->
                                    model.startVnSearch(game)
                                    page = Page.VN_SEARCH
                                },
                                onAccept = { model.acceptVn(it) },
                                onDecline = { model.declineVn(it) },
                                onBuild = model::buildNames,
                                onStop = model::stopNames,
                                onRemove = { model.removeNames(it) },
                            ),
                        )
                        page == Page.BOX_DRAW -> boxDraw?.let { draw ->
                            val all = listOf(dialogueBoxes[draw.game.id]) + extraBoxes[draw.game.id].orEmpty()
                            BoxDrawScreen(
                                draw.game, draw.slot, draw.path,
                                others = all.filterIndexed { i, _ -> i != draw.slot - 1 }.filterNotNull(),
                                swapConfirm = prefs.swapConfirm,
                                onSave = { box ->
                                    model.saveBox(draw.game, draw.slot, box)
                                    back()
                                },
                                onBack = ::back,
                            )
                        }
                        page == Page.VN_SEARCH -> {
                            val game = games.firstOrNull { it.id == vnSearch.gameId }
                            VnSearchScreen(
                                game, vnSearch, model::searchVn,
                                onPick = {
                                    model.pickVn(it)
                                    page = Page.GAMES
                                },
                                onDecline = {
                                    game?.let(model::declineVn)
                                    page = Page.GAMES
                                },
                                onBack = ::back,
                            )
                        }
                        page == Page.CHARACTER_NAMES -> CharacterNamesScreen(prefs, model::updatePrefs, ::back)
                        page == Page.GAME_NAMES -> GameNamesScreen(
                            prefs = prefs,
                            status = gameNameStatus,
                            steamTest = steamTest,
                            onCheck = model::checkGameNames,
                            onChoose = { source ->
                                pickingFor = source
                                pickGameFolder.launch(null)
                            },
                            onForget = model::forgetGameFolder,
                            onChange = model::updatePrefs,
                            onTestSteam = model::testSteam,
                            onBack = ::back,
                        )
                        page == Page.APPS -> AppsScreen(prefs, model::updatePrefs, onBack = ::back)
                        page == Page.MEDIA -> MediaScreen(
                            prefs, recording, model::updatePrefs,
                            onStartRecording = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) },
                            onBack = ::back,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        toSecondScreen()
        take(intent)
    }

    /**
     * On a two-screen handheld the app's pages belong on the second screen:
     * the first is the game's. Started on the first (a launcher there, a
     * widget), the app starts itself again on the other and this one goes.
     */
    private fun toSecondScreen(): Boolean {
        // Asked once for each start: if the other screen sends it back here, here it stays.
        if (intent?.getBooleanExtra(EXTRA_MOVED, false) == true) return false
        if (!(application as io.github.beesan.lfg.App).settings.prefs.value.appOnSecondScreen) return false
        val here = display?.displayId ?: return false
        if (here != android.view.Display.DEFAULT_DISPLAY) return false
        val other = getSystemService(android.hardware.display.DisplayManager::class.java).displays.firstOrNull {
            it.displayId != here && it.state != android.view.Display.STATE_OFF && it.flags and android.view.Display.FLAG_PRIVATE == 0
        } ?: return false
        val again = Intent(intent).setClass(this, MainActivity::class.java).putExtra(EXTRA_MOVED, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return try {
            // The one task is carried to the other screen, with this page in it.
            startActivity(again, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(other.displayId).toBundle())
            false
        } catch (_: Exception) {
            // The other screen will not take it: the page stays where it is.
            false
        }
    }

    /** The diary entry's page was opened from over a game: saving or leaving it goes back to the game's lines. */
    private var noteFromGame = false

    private fun diaryToday(): java.time.LocalDate =
        io.github.beesan.lfg.core.ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, java.time.ZoneId.systemDefault())

    /** The entry's picture, with [words] written on it, handed to whatever app is picked to share it with. */
    private fun shareEntry(entry: io.github.beesan.lfg.data.Note, picture: String?, words: Postcard.Words) {
        lifecycleScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                // One at a time: the last one shared is all that is kept.
                dir.listFiles()?.forEach { it.delete() }
                val out = java.io.File(dir, "lfg-diary-${entry.id}-${System.currentTimeMillis()}.jpg")
                val card = Postcard.render(Postcard.decode(picture), words)
                out.outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                out
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri("", uri)
            open(Intent.createChooser(send, "Share the entry"))
        }
    }

    /** A line's page was opened from over a game to write a question on: leaving it goes back to the game's lines. */
    private var askFromGame by mutableStateOf(false)

    private fun take(intent: Intent?) {
        if (intent?.action == ACTION_STATS) requested.value = Page.STATS
        // A page picked on the wheel over a game.
        if (intent?.action == ACTION_OPEN) {
            val game = intent.getStringExtra(EXTRA_GAME).orEmpty()
            when (intent.getStringExtra(EXTRA_PAGE)) {
                PAGE_STATS -> requested.value = Page.STATS
                PAGE_SETTINGS -> requested.value = Page.SETTINGS
                // A game's characters are on its row of Games and apps: the visual novel it is, and its names.
                PAGE_NAMES -> requested.value = Page.GAMES
                PAGE_SEARCH -> {
                    model.search("")
                    requested.value = Page.SEARCH
                }
                // Today's page of the game; with nothing kept of it today, the shelf.
                PAGE_DIARY -> model.diary.openWidgetDay(game, diaryToday().toEpochDay()) { found ->
                    requested.value = if (found) Page.DIARY_DAY else Page.DIARY
                }
            }
        }
        askFromGame = false
        if (intent?.action == ACTION_ASK_LINE) {
            model.askFromGame(intent.getStringExtra(EXTRA_LINE)) { found ->
                askFromGame = found
                if (found) requested.value = Page.LINE
            }
        }
        noteFromGame = intent?.action == ACTION_NOTE
        if (noteFromGame) {
            model.diary.writeOn(intent?.getStringExtra(EXTRA_LINE))
            requested.value = Page.DIARY_WRITE
        }
        if (intent?.action == ACTION_DIARY_DAY) {
            // The widget's day; a day that is gone opens the shelf instead.
            model.diary.openWidgetDay(intent.getStringExtra(EXTRA_GAME).orEmpty(), intent.getLongExtra(EXTRA_DAY, 0)) { found ->
                requested.value = if (found) Page.DIARY_DAY else Page.DIARY
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    /** Android's own page, opened at this app's entry so the path is switch, then Allow. */
    private fun openAccessibilitySettings() {
        model.awaitService()
        val component = ComponentName(this, LfgAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
        open(intent)
    }

    private fun openAppInfo() =
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun openAnkiDroidListing() {
        if (!open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AnkiBridge.ANKIDROID_PACKAGE}")))) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ankidroid/Anki-Android/releases/latest")))
        }
    }

    private fun copyReport(report: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LFG diagnostics", report))
        // Android 13 and later show their own notice of what was copied.
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shareReport(report: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "LFG diagnostics")
            .putExtra(Intent.EXTRA_TEXT, report)
        open(Intent.createChooser(send, "Send the report"))
    }

    /** Hands the zip of kept dialogue to a mail app, addressed and titled. */
    private fun sendDialogue(zip: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", zip)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(DialogueSamples.EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "LFG dialogue screenshots")
            .putExtra(Intent.EXTRA_TEXT, "Screens of game dialogue saved by LFG, with the text it read from each, to train a better reader.")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("", uri)
        open(Intent.createChooser(send, "Send the dialogue"))
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    companion object {
        const val ACTION_STATS = "io.github.beesan.lfg.action.STATS"

        /** A diary entry written from over a game: [EXTRA_LINE] is the line's id. */
        const val ACTION_NOTE = "io.github.beesan.lfg.action.NOTE"
        /** A day of the diary, opened from its widget: [EXTRA_GAME] is the game's id, [EXTRA_DAY] the day, in days since 1970. */
        const val ACTION_DIARY_DAY = "io.github.beesan.lfg.action.DIARY_DAY"
        const val EXTRA_GAME = "game"
        const val EXTRA_DAY = "day"
        /** A question to be written about an explained line, from over a game: [EXTRA_LINE] is the line's id. */
        const val ACTION_ASK_LINE = "io.github.beesan.lfg.action.ASK_LINE"
        const val EXTRA_LINE = "line"
        /**
         * One of the app's pages, opened from the wheel over a game: [EXTRA_PAGE] is one of the PAGE_ names,
         * and [EXTRA_GAME] the id of the game in front, for the pages that are about one game.
         */
        const val ACTION_OPEN = "io.github.beesan.lfg.action.OPEN"
        const val EXTRA_PAGE = "page"
        const val PAGE_STATS = "stats"
        const val PAGE_NAMES = "names"
        const val PAGE_SEARCH = "search"
        const val PAGE_SETTINGS = "settings"
        const val PAGE_DIARY = "diary"
        private const val EXTRA_MOVED = "moved"
    }
}
