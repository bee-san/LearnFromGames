// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.ask.Asker
import io.github.beesan.lfg.data.Game
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A question about the game being played, answered with a web search, and
 * the game's last questions and answers under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    state: MainViewModel.AskState,
    games: List<Game>,
    on: Boolean,
    hasKey: Boolean,
    onGame: (String) -> Unit,
    onAsk: (String, Boolean) -> Unit,
    onTurnOn: () -> Unit,
    onKey: () -> Unit,
    onBack: () -> Unit,
) {
    val time = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val uri = LocalUriHandler.current
    var question by rememberSaveable { mutableStateOf("") }
    var withScreen by rememberSaveable { mutableStateOf(false) }
    val ready = on && hasKey && state.gameId != null
    // The question stays in the box until its answer is kept, so a failed one can be sent again.
    var asked by remember { mutableStateOf<String?>(null) }
    val send = {
        if (ready && !state.busy && question.isNotBlank()) {
            asked = question.trim()
            onAsk(question, withScreen)
        }
    }
    LaunchedEffect(state.busy, state.history.firstOrNull()?.id) {
        if (!state.busy && asked != null) {
            if (state.history.firstOrNull()?.question == asked) question = ""
            asked = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Ask") { LfgButton("Back", onBack) }
        HorizontalDivider(color = Lfg.Border)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (games.isEmpty()) Text("Nothing has been read yet. Play a game, then come back to ask about it.", color = Lfg.Dim, fontSize = 15.sp)
            if (games.size == 1) Text(games.single().title, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (games.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    games.forEach { game -> LfgButton(game.title, { onGame(game.id) }, primary = game.id == state.gameId) }
                }
            }
            when {
                !on -> Notice(
                    "Ask sends your question, the game’s name and its last ${Asker.LINES} lines to OpenRouter, which searches the web for an answer.",
                    "Turn on", onTurnOn,
                )
                !hasKey -> Notice("Questions are answered by the model in Settings › Translation, with your OpenRouter key.", "Add key…", onKey)
                else -> Text("Answers stop where you have read to, so nothing ahead is spoiled", color = Lfg.Dim, fontSize = 14.sp)
            }
            if (ready) {
                BasicTextField(
                    value = question,
                    onValueChange = { question = it },
                    textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(Lfg.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { field ->
                        if (question.isEmpty()) Text("How do I get this item?", color = Lfg.Dim, fontSize = 16.sp)
                        field()
                    },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Lfg.Sunken)
                        .border(1.dp, Lfg.BorderStrong, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(
                        checked = withScreen,
                        onCheckedChange = { withScreen = it },
                        colors = CheckboxDefaults.colors(checkedColor = Lfg.Accent, uncheckedColor = Lfg.Dim, checkmarkColor = Lfg.OnFill),
                    )
                    Text("Send the screen too (the model must see pictures)", color = Lfg.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    LfgButton("Ask", send, primary = true, enabled = !state.busy && question.isNotBlank())
                }
            }
            if (state.busy) Text("Searching and answering…", color = Lfg.Dim, fontSize = 15.sp)
            state.error?.let { Text(it, color = Lfg.Warn, fontSize = 14.sp) }

            for (row in state.history) {
                val shape = RoundedCornerShape(8.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(row.question, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(row.answer, color = Lfg.Text, fontSize = 15.sp, lineHeight = 22.sp)
                    for (source in Asker.sources(row)) {
                        val host = runCatching { URI(source.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
                        Text(
                            listOf(source.title, host).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { source.url },
                            color = Lfg.Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { runCatching { uri.openUri(source.url) } },
                        )
                    }
                    val screen = if (row.withScreen) " · with the screen" else ""
                    Text("${row.model}$screen · ${time.format(Date(row.createdAt))}", color = Lfg.Dim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        LfgButton(action, onClick, primary = true)
    }
}
