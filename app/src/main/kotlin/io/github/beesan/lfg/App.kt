// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.app.Application
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.anki.AnkiBridge
import io.github.beesan.lfg.anki.CardMaker
import io.github.beesan.lfg.anki.MediaAttacher
import io.github.beesan.lfg.anki.Speech
import io.github.beesan.lfg.data.LfgDatabase
import io.github.beesan.lfg.games.GameFinder
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.dict.DictionaryRepository
import io.github.beesan.lfg.dict.WordAudio
import io.github.beesan.lfg.dict.DictionaryWorker
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.data.PictureKeeper
import io.github.beesan.lfg.widget.StatsWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.beesan.lfg.data.Notes
import android.content.Intent
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.backup.BackupWorker
import io.github.beesan.lfg.ask.Asker
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.ask.Translator
import java.io.File

/** The app's few long-lived parts, shared by the screens and the accessibility service. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { LfgDatabase.open(this) }
    val lines by lazy {
        LineStore(database, language = { settings.prefs.value.learning.tag }) { line ->
            StatsWidgets.requestUpdate(this)
            // "Every line": each new line is translated as it is read, behind the reading. A menu label is not a line.
            val prefs = settings.prefs.value
            if (!line.interfaceText && prefs.translate == Prefs.TRANSLATE_EVERY && prefs.openRouterKey.isNotBlank()) {
                scope.launch {
                    try {
                        translator.translate(line)
                    } catch (_: OpenRouter.Failure) {
                        // The line stays untranslated; "Translate" on it says what went wrong.
                    }
                }
            }
        }
    }
    val translator by lazy { Translator(database, io.github.beesan.lfg.ask.OfflineTranslator()) { settings.prefs.value } }
    val explainer by lazy {
        io.github.beesan.lfg.ask.Explainer(database, { settings.prefs.value }) { lineId -> cards.refreshExplained(lineId) }
            .also { made -> scope.launch(Dispatchers.IO) { made.load() } }
    }
    val diary by lazy { io.github.beesan.lfg.data.Diary(database) }
    val recapper by lazy { Recapper(database, { settings.prefs.value }, diary = diary) }
    val asker by lazy { Asker(database, { settings.prefs.value }) }
    val dictionaries by lazy { DictionaryRepository(this) { Language.of(settings.prefs.value.language) } }
    val wordAudio by lazy { WordAudio(this) }
    val anki by lazy { AnkiBridge(this) }
    val settings by lazy { Settings(this) }
    val dialogueBoxes by lazy { DialogueBoxes(this) }
    val extraBoxes by lazy { ExtraBoxes(this) }
    val wheels by lazy { Wheels(this) }
    val profiles by lazy { Profiles(this, settings, anki, scope) }
    val speech by lazy { Speech(this) { settings.prefs.value.learning } }
    val media by lazy { MediaAttacher(this, scope, anki, database, { settings.prefs.value }, speech) }
    val lineAsker by lazy { io.github.beesan.lfg.ask.LineAsker(database) { settings.prefs.value } }
    val cards by lazy { CardMaker(anki, database, lines, { settings.prefs.value }, media, File(filesDir, "media"), wordAudio) { StatsWidgets.requestUpdate(this) } }
    val models by lazy { ModelStore(this) }
    val pictures by lazy { PictureKeeper(database, filesDir, diary) }
    val notes by lazy { Notes(database, File(filesDir, "notes")) }
    val gameFinder by lazy { GameFinder(this, scope) { settings.prefs.value } }
    val characterNames by lazy { io.github.beesan.lfg.names.CharacterNames(this, database, dictionaries, scope, { settings.prefs.value }) }

    override fun onCreate() {
        super.onCreate()
        // The dictionaries are already downloading when the set-up screen first shows; any that failed are tried again.
        val readerMissing = models.screenAiDir() == null && ScreenAi.available()
        if (dictionaries.state.value is DictState.Missing || dictionaries.defaultsPending() || readerMissing) DictionaryWorker.start(this, wifiOnly = settings.prefs.value.wifiOnly)
        scope.launch(Dispatchers.IO) { models.forgetMeiki() }
        // Android is shown the quick settings tile of each profile there is.
        scope.launch { profiles.syncTiles() }
        // Another language to learn, at set-up or in Settings: its dictionaries are the ones in use, and are fetched if it has none.
        scope.launch {
            settings.prefs.map { it.language }.distinctUntilChanged().drop(1).collect {
                dictionaries.onLanguage()
                if (dictionaries.state.value is DictState.Missing) DictionaryWorker.start(this@App, replace = true, wifiOnly = settings.prefs.value.wifiOnly)
            }
        }
        // With a key, lines get translated: what translating them with no internet needs is fetched while there is some.
        scope.launch {
            settings.prefs.map { listOf(it.aiReady, it.language, it.translateInto, it.wifiOnly) }.distinctUntilChanged().collect { translator.prepareOffline() }
        }
        // The app's own sounds: read once, and as loud as Settings › Appearance says.
        scope.launch(Dispatchers.IO) { io.github.beesan.lfg.ui.GameSounds.load(this@App) }
        scope.launch {
            settings.prefs.map { it.gameSounds to it.soundVolume }.distinctUntilChanged().collect { (on, volume) ->
                io.github.beesan.lfg.ui.GameSounds.enabled = on
                io.github.beesan.lfg.ui.GameSounds.level = volume / 100f
                io.github.beesan.lfg.ui.ButtonSounds.level = volume / 100f
            }
        }
        // The widgets measure against the goals and draw in the chosen colours, so they redraw as soon as one changes.
        scope.launch { settings.prefs.map { listOf(it.goals, it.goalCards, it.colours, it.theme, it.widgetSize) }.distinctUntilChanged().drop(1).collect { StatsWidgets.update(this@App) } }
        // WorkManager keeps the automatic backup across restarts; it changes only when the setting does.
        scope.launch { settings.prefs.map { it.backupEvery }.distinctUntilChanged().drop(1).collect { BackupWorker.schedule(this@App, it) } }
        // The diary's days are written once each is over, while that is on and there is a key to write them with.
        scope.launch {
            settings.prefs.map { it.diaryWrite == Prefs.DIARY_EACH_DAY && it.aiReady }.distinctUntilChanged()
                .collect { on -> io.github.beesan.lfg.data.DiaryWorker.schedule(this@App, on) }
        }
        // The same for the daily look for newer dictionaries, which is also set going on a first start.
        scope.launch { settings.prefs.map { it.updateDictionaries to it.wifiOnly }.distinctUntilChanged().collect { (on, wifi) -> io.github.beesan.lfg.dict.DictionaryUpdateWorker.schedule(this@App, on, wifi) } }
        // And for the daily look for a newer LFG. It is fetched, and waits for a tap to be installed.
        scope.launch { settings.prefs.map { it.updateApp to it.wifiOnly }.distinctUntilChanged().collect { (on, wifi) -> io.github.beesan.lfg.update.UpdateWorker.schedule(this@App, on, wifi) } }
    }

    /** Starts the app again from its first screen: after a restore, which replaced the database under it. */
    fun restart() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        Runtime.getRuntime().exit(0)
    }
}
