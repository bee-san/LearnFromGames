// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.beesan.lfg.App
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.LookupController
import io.github.beesan.lfg.lookup.LookupLine
import io.github.beesan.lfg.lookup.LookupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "LFG" in Android's text-selection menu, in any app where text can be
 * selected. The selection is looked up with no overlay and no picture of the
 * screen; a card made from it quotes the selection as its sentence.
 */
class SelectionActivity : ComponentActivity() {
    private val app get() = application as App
    private val styles = MutableStateFlow<List<EntryHtml.Style>>(emptyList())

    private lateinit var controller: LookupController

    /** The app the text was selected in, as Android names the caller; this app when it cannot say. */
    private val source: String by lazy { referrer?.host?.takeIf { it.isNotBlank() && it != packageName } ?: packageName }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = selected(intent)
        if (text.isEmpty()) {
            finish()
            return
        }
        controller = LookupController(
            lifecycleScope, { text, _ -> app.dictionaries.lookup(text) }, app.cards, app.anki, app.lines, app.wordAudio, { app.notes.withNotes(it) },
            prepare = ::saveLine,
            selection = { app.settings.prefs.value.picking },
            lookupKanji = { app.dictionaries.lookupKanji(it, app.settings.prefs.value.kanjiDictionary) },
        )
        val title = titleOf(source)
        controller.setLines(listOf(LookupLine(text, null, null, title, fromApp = true, source = LineStore.SOURCE_SELECTION)))
        // The first character that can be picked: a selection may open with a bracket or a space.
        controller.select(0, text.indices.firstOrNull { io.github.beesan.lfg.core.Selectable.at(text, it, app.settings.prefs.value.japaneseOnly) } ?: 0)
        lifecycleScope.launch { styles.value = withContext(Dispatchers.IO) { app.dictionaries.styles() } }

        setContent {
            val prefs by app.settings.prefs.collectAsStateWithLifecycle()
            val state by controller.state.collectAsStateWithLifecycle()
            val loaded by styles.collectAsStateWithLifecycle()
            val palette = Palette.of(prefs.colours, prefs.theme, isSystemInDarkTheme())
            if (Lfg.palette != palette) Lfg.palette = palette
            SideEffect { window.setBackgroundDrawable(ColorDrawable(palette.bg)) }
            Selection(text, title, state, loaded)
        }
    }

    /** The selection, from a menu tap or a share. */
    private fun selected(intent: Intent): String = (
        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: ""
        ).toString().trim().take(MAX_LENGTH)

    private fun titleOf(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    /** A card needs its line: the selection is saved under the app it came from when one is made, and not before. */
    private suspend fun saveLine(line: LookupLine): LookupLine = withContext(Dispatchers.IO) {
        val game = app.lines.gameFor(source, line.gameName, app = true)
        line.copy(lineId = app.lines.saveLine(game, line.text, LineStore.SOURCE_SELECTION).id)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Selection(text: String, from: String, state: LookupState, styles: List<EntryHtml.Style>) {
        Column(
            Modifier.fillMaxSize().background(Lfg.Bg).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From $from", color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                LfgButton("Close", { finish() })
            }
            // Each character can be tapped, to look up a word further into the selection.
            FlowRow(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                var i = 0
                while (i < text.length) {
                    val index = i
                    val width = Character.charCount(text.codePointAt(i))
                    val inWord = state.selected && index >= state.spanStart && index < state.markEnd
                    Text(
                        text.substring(index, index + width),
                        color = if (inWord) Lfg.Accent else Lfg.Text,
                        fontSize = 24.sp,
                        fontWeight = if (inWord) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(if (inWord) Lfg.Wash else Lfg.Bg)
                            .clickable { controller.select(0, index) }.padding(horizontal = 1.dp, vertical = 2.dp),
                    )
                    i += width
                }
            }
            if (state.entries.isEmpty()) {
                Text(state.message ?: "Looking up…", color = Lfg.Dim, fontSize = 15.sp)
            } else {
                EntryList(
                    state, styles, { dictionary, path -> app.dictionaries.mediaBlocking(dictionary, path) },
                    { link, index ->
                        when (link) {
                            EntryLink.CARD -> controller.addCard(index)
                            EntryLink.AUDIO -> controller.playAudio(index)
                            EntryLink.KANJI -> controller.lookUpKanji(index)
                            EntryLink.BACK -> controller.backToWord()
                            // Not shown here: a note belongs to a game's line.
                            EntryLink.NOTE -> Unit
                        }
                    },
                    Modifier.fillMaxWidth().weight(1f),
                    notes = false,
                )
                state.message?.let { Text(it, color = Lfg.Dim, fontSize = 13.sp) }
            }
        }
    }

    private companion object {
        /** A whole page selected is not a sentence; the start of it is looked up and quoted. */
        const val MAX_LENGTH = 400
    }
}
