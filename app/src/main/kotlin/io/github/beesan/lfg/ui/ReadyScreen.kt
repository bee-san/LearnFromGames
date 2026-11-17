// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.lookup.CardButton

/** Set-up ends on a real lookup of a practice sentence, as Hachidori's does. */
@Composable
fun ReadyScreen(
    setup: SetupState,
    dictionaryTitles: String,
    practice: PracticeState,
    shortcut: String,
    onTapCharacter: (Int) -> Unit,
    onAddCard: () -> Unit,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Ready") { LfgButton("Done", onDone, primary = true) }
        val cards = setup.ankiTarget?.let { "Cards go to ${it.modelName} in ‘${it.deckName}’" } ?: "Cards wait for AnkiDroid"
        Text("Screen reading on · $cards · $dictionaryTitles installed", color = Lfg.Dim, fontSize = 14.sp)
        if (setup.ankiTarget?.created == true) {
            Text("Note type and deck added to AnkiDroid; they upload at the next sync.", color = Lfg.Dim, fontSize = 14.sp)
        }
        if (setup.ankiNeverSynced && setup.ankiTarget != null) {
            Text("If you use AnkiWeb, sync AnkiDroid once before the first card.", color = Lfg.Warn, fontSize = 14.sp)
        }

        val shape = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TappableSentence(practice.sentence, practice.spanStart, practice.spanEnd, onTapCharacter, Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                if (practice.entry == null) {
                    Text("Tap a word.", color = Lfg.Dim, fontSize = 15.sp)
                } else {
                    EntryView(practice.entry)
                    Spacer(Modifier.height(12.dp))
                    LfgButton(cardButtonLabel(practice.button), onAddCard, primary = true, enabled = practice.button == CardButton.ADD)
                    practice.message?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
        // The only instruction the app ever gives.
        Text("Open a game. Tap a word, or press $shortcut.", color = Lfg.Text, fontSize = 16.sp)
    }
}

/** An entry as plain text, as the popup over a game shows it. */
@Composable
fun EntryView(entry: Entry) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(entry.expression, color = Lfg.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        if (entry.reading != entry.expression) Text(entry.reading, color = Lfg.Dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
    if (entry.path.isNotEmpty()) Text(entry.path, color = Lfg.Dim, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    entry.senses().take(6).forEachIndexed { i, sense ->
        Row {
            Text("${i + 1}.", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.width(24.dp))
            Text(sense, color = Lfg.Text, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
    Text(entry.sources.joinToString { it.substringBefore(" [") }, color = Lfg.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}
