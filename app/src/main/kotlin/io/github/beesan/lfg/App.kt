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
import io.github.beesan.lfg.dict.DictionaryWorker
import io.github.beesan.lfg.ocr.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** The app's few long-lived parts, shared by the screens and the accessibility service. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { LfgDatabase.open(this) }
    val lines by lazy { LineStore(database) }
    val dictionaries by lazy { DictionaryRepository(this) }
    val anki by lazy { AnkiBridge(this) }
    val settings by lazy { Settings(this) }
    val media by lazy { MediaAttacher(this, scope, anki, database, { settings.prefs.value }, Speech(this)) }
    val cards by lazy { CardMaker(anki, database, lines, { settings.prefs.value }, media, File(filesDir, "media")) }
    val models by lazy { ModelStore(this) }

    override fun onCreate() {
        super.onCreate()
        // The dictionary is already downloading when the set-up screen first shows.
        if (dictionaries.state.value is DictState.Missing || models.meikiFiles() == null || models.screenAiDir() == null) DictionaryWorker.start(this)
    }
}
