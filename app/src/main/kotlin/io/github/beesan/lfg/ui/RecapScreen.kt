// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.data.Game
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The story so far in the game read last, from only what has been read: its
 * arcs, newest first, the last session, and moments to look back at, each
 * with its line's picture.
 */
@Composable
fun RecapScreen(
    state: MainViewModel.RecapState,
    games: List<Game>,
    hasKey: Boolean,
    onWrite: () -> Unit,
    onKey: () -> Unit,
    onBack: () -> Unit,
) {
    val time = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padScroll(scroll)) {
        PageHeader("Recap") {
            if (hasKey) LfgButton(if (state.recap == null) "Write" else "Write again", onWrite, enabled = !state.busy && state.gameId != null)
            LfgButton("Back", onBack)
        }
        HorizontalDivider(color = Lfg.Border)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).firstFocus().padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val game = games.firstOrNull { it.id == state.gameId }
            if (game == null) Text("Nothing has been read yet. Play a game, then come back for its recap.", color = Lfg.Dim, fontSize = 15.sp)
            else Text(game.title, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("From what you have read so far, so nothing ahead is spoiled", color = Lfg.Dim, fontSize = 14.sp)
            if (!hasKey) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A recap is written by the model in Settings › Translation, with your OpenRouter key.", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    LfgButton("Add key…", onKey, primary = true)
                }
            }
            if (state.busy) Text("Writing the recap…", color = Lfg.Dim, fontSize = 15.sp)
            state.error?.let { Text(it, color = Lfg.Warn, fontSize = 14.sp) }
            val recap = state.recap ?: return@Column

            Heading("The story so far")
            // Newest first: where the story is now is what a returning player wants.
            for (arc in recap.arcs.asReversed()) {
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.width(168.dp)) {
                        Text(arc.title, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        val span = if (arc.from == arc.to || arc.to.isBlank()) arc.from else "${arc.from} – ${arc.to}"
                        if (span.isNotBlank()) Text(span, color = Lfg.Dim, fontSize = 13.sp)
                    }
                    Text(arc.summary, color = Lfg.Text, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
                }
            }

            if (recap.last.isNotBlank()) {
                Heading("Last time")
                Text(recap.last, color = Lfg.Text, fontSize = 16.sp, lineHeight = 25.sp)
            }

            if (recap.moments.isNotEmpty()) {
                Heading("Moments")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (moment in recap.moments.take(4)) {
                        val shape = RoundedCornerShape(8.dp)
                        Column(
                            Modifier.weight(1f).clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            moment.picture?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp))) }
                            Text(moment.text, color = Lfg.Text, fontSize = 14.sp, lineHeight = 20.sp)
                            Text(moment.caption, color = Lfg.Dim, fontSize = 13.sp)
                        }
                    }
                    repeat(4 - recap.moments.take(4).size) { Column(Modifier.weight(1f)) {} }
                }
            }

            Text(
                "Written by ${state.model} from %,d lines, ${time.format(Date(state.writtenAt))}".format(state.lineCount),
                color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
