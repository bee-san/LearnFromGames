// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

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
import io.github.beesan.lfg.capture.LfgAccessibilityService
import io.github.beesan.lfg.capture.SoundCapture
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.ocr.ScreenAi
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    /** A page asked for from outside: a widget opens Stats. */
    private val requested = MutableStateFlow<Page?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        setContent {
            val setup by model.setup.collectAsStateWithLifecycle()
            val dictionary by model.dictionary.collectAsStateWithLifecycle()
            val reader by model.reader.collectAsStateWithLifecycle()
            val screenAi by model.screenAi.collectAsStateWithLifecycle()
            val prefs by model.prefs.collectAsStateWithLifecycle()
            val practice by model.practice.collectAsStateWithLifecycle()
            val practiceDone by model.practiceDone.collectAsStateWithLifecycle()
            val lines by model.lines.collectAsStateWithLifecycle()
            val games by model.games.collectAsStateWithLifecycle()
            val stats by model.stats.collectAsStateWithLifecycle()
            val lookup by model.lineLookup.state.collectAsStateWithLifecycle()
            val styles by model.styles.collectAsStateWithLifecycle()
            val dictionaries by model.dictionaries.collectAsStateWithLifecycle()
            val fetching by model.dictionaryFetch.collectAsStateWithLifecycle()
            val sources by model.sources.collectAsStateWithLifecycle()
            val recording by SoundCapture.running.collectAsStateWithLifecycle()
            var page by rememberSaveable { mutableStateOf(Page.HOME) }
            val saved = rememberSaveableStateHolder()

            val ankiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) model.connectAnki() else model.refresh()
            }
            val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.addDictionary(uri)
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
            val ready = setup.complete(dictionary, reader)
            val showPractice = page == Page.PRACTICE || (page == Page.HOME && ready && !practiceDone)

            // The first word is looked up before anything is tapped, as the controller cursor is.
            LaunchedEffect(showPractice) {
                if (showPractice && practice.entry == null) model.lookupPractice(PRACTICE_SENTENCE.indexOf("不思議"))
            }
            fun back() {
                // Stats keeps its period and chart across the goals page, and opens on its defaults again after Home.
                if (page == Page.STATS) saved.removeState(Page.STATS)
                page = when (page) {
                    Page.LINE, Page.STATS, Page.SETTINGS -> Page.HOME
                    Page.SOURCES -> Page.DICTIONARIES
                    Page.GOALS -> Page.STATS
                    Page.HOME -> Page.HOME
                    else -> Page.SETTINGS
                }
            }
            BackHandler(enabled = page != Page.HOME) { back() }

            Box(Modifier.fillMaxSize().background(Lfg.Bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                val frame = Modifier.widthIn(max = 980.dp).padding(horizontal = 24.dp, vertical = 20.dp)
                Box(frame) {
                    when {
                        page == Page.HOME && !ready -> Box(Modifier.verticalScroll(rememberScrollState())) {
                            SetupScreen(
                                setup = setup,
                                dictionary = dictionary,
                                reader = reader,
                                onTurnOn = ::openAccessibilitySettings,
                                onOpenAppInfo = ::openAppInfo,
                                onConnectAnki = { ankiPermission.launch(AnkiBridge.PERMISSION) },
                                onInstallAnki = ::openAnkiDroidListing,
                                onRetryDownloads = model::retryDownloads,
                            )
                        }
                        showPractice -> Box(Modifier.verticalScroll(rememberScrollState())) {
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
                        page == Page.HOME -> LinesScreen(
                            lines = lines,
                            problem = null,
                            onProblem = {},
                            onOpenLine = {
                                model.openLine(it)
                                page = Page.LINE
                            },
                            onStats = { page = Page.STATS },
                            onSettings = { page = Page.SETTINGS },
                        )
                        page == Page.LINE -> LineLookupScreen(
                            state = lookup,
                            styles = styles,
                            media = model::mediaBlocking,
                            onTap = { model.lineLookup.select(0, it) },
                            onAddCard = model.lineLookup::addCard,
                            onPreviousEntry = model.lineLookup::previousEntry,
                            onNextEntry = model.lineLookup::nextEntry,
                            onBack = ::back,
                        )
                        page == Page.STATS -> saved.SaveableStateProvider(Page.STATS) {
                            StatsScreen(stats, prefs.goals, { page = Page.GOALS }, ::back)
                        }
                        page == Page.GOALS -> GoalsScreen(prefs, model::updatePrefs, ::back)
                        page == Page.SETTINGS -> SettingsScreen(onOpen = { page = it }, onBack = ::back)
                        page == Page.DICTIONARIES -> DictionariesScreen(
                            state = dictionary,
                            busy = dictionaries,
                            fetching = fetching,
                            onAdd = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            onMove = { title, up -> model.moveDictionary(title, up) },
                            onRemove = { model.removeDictionary(it) },
                            onSources = { page = Page.SOURCES },
                            onBack = ::back,
                        )
                        page == Page.SOURCES -> SourcesScreen(sources, ::back)
                        page == Page.ANKI -> AnkiScreen(setup, model::connectAnki, { ankiPermission.launch(AnkiBridge.PERMISSION) }, ::back)
                        page == Page.CONTROLS -> ControlsScreen(prefs, model::updatePrefs, ::back)
                        page == Page.RECOGNITION -> RecognitionScreen(
                            prefs, reader, screenAi, ScreenAi.available(), model::updatePrefs, { model.downloadScreenAi() }, ::back,
                        )
                        page == Page.GAMES -> GamesScreen(games, packageName, { model.updateGame(it) }, ::back)
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
        take(intent)
    }

    private fun take(intent: Intent?) {
        if (intent?.action == ACTION_STATS) requested.value = Page.STATS
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

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    companion object {
        const val ACTION_STATS = "io.github.beesan.lfg.action.STATS"
    }
}
