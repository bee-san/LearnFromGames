// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.CardButton
import io.github.beesan.lfg.lookup.LookupState
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
fun PageHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Text(title, color = Lfg.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/**
 * What opens from the launcher: the lines the app has read, newest first,
 * grouped by day and game. Tapping a line opens it for lookup, so a card can be
 * made after the session. A problem shows as one row at the top with its button.
 */
@Composable
fun LinesScreen(
    lines: List<GameLine>,
    problem: Pair<String, String>?,
    onProblem: () -> Unit,
    onOpenLine: (GameLine) -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onNotes: () -> Unit,
) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(lines.size) { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Lines") {
            LfgButton("Notes", onNotes)
            LfgButton("Stats", onStats)
            LfgButton("Open settings", onSettings)
        }
        if (problem != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(problem.first, color = Lfg.Warn, fontSize = 15.sp, modifier = Modifier.weight(1f))
                LfgButton(problem.second, onProblem, primary = true)
            }
        }
        HorizontalDivider(color = Lfg.Border)
        if (lines.isEmpty()) {
            Text("No lines yet. Open a game. Tap a word, or press L3 + R3.", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            return
        }
        LazyColumn {
            var lastGroup = ""
            for (line in lines) {
                val group = MainViewModel.dayLabel(ReadingStats.dayOf(line.timestamp, zone), today) + " · " + line.gameName
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "g:" + line.id) {
                        Text(group, color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                    }
                }
                item(key = line.id) {
                    Row(Modifier.fillMaxWidth().clickable { onOpenLine(line) }.padding(vertical = 10.dp)) {
                        Text(time.format(Date(line.createdAt)), color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(top = 4.dp))
                        Column {
                            Text(line.lineText, color = Lfg.Text, fontSize = 17.sp, lineHeight = 26.sp)
                            line.translation?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp, lineHeight = 20.sp) }
                            if (line.noteIds.isNotEmpty()) Text("Card", color = Lfg.Dim, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Lfg.Border)
                }
            }
        }
    }
}

/** A sentence whose every character can be tapped, with the looked-up word marked. */
@Composable
fun TappableSentence(sentence: String, spanStart: Int, spanEnd: Int, onTapCharacter: (Int) -> Unit, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = buildAnnotatedString {
        append(sentence)
        if (spanEnd > spanStart && spanEnd <= sentence.length) addStyle(SpanStyle(background = Lfg.Wash, color = Lfg.Accent), spanStart, spanEnd)
    }
    Text(
        text = text,
        color = Lfg.Text,
        fontSize = 24.sp,
        lineHeight = 44.sp,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(sentence) {
            detectTapGestures { position ->
                val result = layout ?: return@detectTapGestures
                // getOffsetForPosition rounds to the nearest caret; the character under the finger is the one whose box holds it.
                val caret = result.getOffsetForPosition(position)
                val index = listOf(caret, caret - 1).firstOrNull {
                    it in sentence.indices && result.getBoundingBox(it).contains(position)
                } ?: return@detectTapGestures
                onTapCharacter(if (Character.isLowSurrogate(sentence[index]) && index > 0) index - 1 else index)
            }
        },
    )
}

fun cardButtonLabel(button: CardButton) = when (button) {
    CardButton.ADD -> "Add card"
    CardButton.ADDING -> "Adding…"
    CardButton.IN_ANKI -> "In Anki"
    CardButton.WAITING -> "Waiting for AnkiDroid"
}

/** One stored line, opened: tap a word, read every entry in one scrolling list, make a card or a note from one. */
@Composable
fun LineLookupScreen(
    state: LookupState,
    styles: List<EntryHtml.Style>,
    media: (String, String) -> ByteArray?,
    onTap: (Int) -> Unit,
    onLink: (EntryLink, Int) -> Unit,
    translation: MainViewModel.TranslationState,
    /** What Settings › Translation says: nothing shows while it is off. */
    translate: String,
    translateContext: Int,
    onTranslate: () -> Unit,
    onBack: () -> Unit,
) {
    val line = state.lines.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        PageHeader(line?.gameName ?: "Line") { LfgButton("Back", onBack) }
        HorizontalDivider(color = Lfg.Border)
        if (line == null) return
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                TappableSentence(line.text, state.spanStart, if (state.selected) state.spanEnd else 0, onTap)
                if (!state.selected) Text("Tap a word.", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                if (translate != Prefs.TRANSLATE_OFF) {
                    HorizontalDivider(color = Lfg.Border, modifier = Modifier.padding(top = 18.dp))
                    Text("Translation", color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    when {
                        translation.text != null -> Text(translation.text, color = Lfg.Text, fontSize = 17.sp, lineHeight = 25.sp)
                        translation.busy -> Text("Translating…", color = Lfg.Dim, fontSize = 15.sp)
                    }
                    translation.error?.let { Text(it, color = Lfg.Warn, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            if (translateContext == 0) "Without the lines before" else "With the $translateContext lines before",
                            color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        LfgButton(if (translation.text == null) "Translate" else "Translate again", onTranslate, enabled = !translation.busy)
                    }
                }
            }
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                if (state.selected) {
                    EntryList(state, styles, media, onLink, Modifier.fillMaxWidth().weight(1f))
                    state.message.takeIf { state.entries.isNotEmpty() }?.let {
                        Text(it, color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
