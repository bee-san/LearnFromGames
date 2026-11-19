// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.app.Application
import io.github.beesan.lfg.anki.AnkiBridge
import io.github.beesan.lfg.anki.CardMaker
import io.github.beesan.lfg.anki.MediaAttacher
import io.github.beesan.lfg.anki.Speech
import io.github.beesan.lfg.data.LfgDatabase
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.dict.DictionaryRepository
import io.github.beesan.lfg.dict.WordAudio
import io.github.beesan.lfg.dict.DictionaryWorker
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.widget.StatsWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.beesan.lfg.data.Notes
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.ask.Translator
import java.io.File

/** The app's few long-lived parts, shared by the screens and the accessibility service. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { LfgDatabase.open(this) }
    val lines by lazy {
        LineStore(database) { line ->
            StatsWidgets.requestUpdate(this)
            // "Every line": each new line is translated as it is read, behind the reading.
            val prefs = settings.prefs.value
            if (prefs.translate == Prefs.TRANSLATE_EVERY && prefs.openRouterKey.isNotBlank()) {
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
    val translator by lazy { Translator(database) { settings.prefs.value } }
    val recapper by lazy { Recapper(database, { settings.prefs.value }) }
    val dictionaries by lazy { DictionaryRepository(this) }
    val wordAudio by lazy { WordAudio(this) }
    val anki by lazy { AnkiBridge(this) }
    val settings by lazy { Settings(this) }
    val media by lazy { MediaAttacher(this, scope, anki, database, { settings.prefs.value }, Speech(this)) }
    val cards by lazy { CardMaker(anki, database, lines, { settings.prefs.value }, media, File(filesDir, "media"), wordAudio) { StatsWidgets.requestUpdate(this) } }
    val models by lazy { ModelStore(this) }
    val notes by lazy { Notes(database, File(filesDir, "notes")) }

    override fun onCreate() {
        super.onCreate()
        // The dictionary is already downloading when the set-up screen first shows.
        if (dictionaries.state.value is DictState.Missing || models.meikiFiles() == null || models.screenAiDir() == null) DictionaryWorker.start(this)
        // The widgets measure against the goals and draw in the chosen colours, so they redraw as soon as one changes.
        scope.launch { settings.prefs.map { listOf(it.goals, it.goalCards, it.colours, it.theme) }.distinctUntilChanged().drop(1).collect { StatsWidgets.update(this@App) } }
    }
}
