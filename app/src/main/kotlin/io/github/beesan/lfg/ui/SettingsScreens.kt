// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.dict.DictionarySource
import io.github.beesan.lfg.ocr.ModelState
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
private fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title) {
            actions()
            LfgButton("Back", onBack)
        }
        HorizontalDivider(color = Lfg.Border)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
        )
    }
}

/** A row of the app: a name, a line of detail, and whatever it does on the right. */
@Composable
fun Card(name: String, detail: String = "", onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, color = Lfg.Dim, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** A choice between a few named options: the current one is filled. */
@Composable
private fun Choice(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    options.forEach { (value, label) -> LfgButton(label, { onPick(value) }, primary = value == current) }
}

@Composable
private fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) = Choice(listOf("on" to "On", "off" to "Off"), if (on) "on" else "off") { onChange(it == "on") }

@Composable
fun SettingsScreen(onOpen: (Page) -> Unit, onBack: () -> Unit) = Page("Settings", onBack) {
    Card("Dictionaries", "Add, order and remove dictionaries", { onOpen(Page.DICTIONARIES) })
    Card("Anki", "The note type and deck cards go to", { onOpen(Page.ANKI) })
    Card("Controls", "The lookup shortcut", { onOpen(Page.CONTROLS) })
    Card("Text recognition", "The engine that reads the game", { onOpen(Page.RECOGNITION) })
    Card("Games", "Rename a game, turn tap strips off for it", { onOpen(Page.GAMES) })
    Card("Sound and pictures", "Sentence audio and animated pictures on cards", { onOpen(Page.MEDIA) })
    Card("Practice line", "Try a lookup and a card without a game", { onOpen(Page.PRACTICE) })
}

@Composable
fun DictionariesScreen(
    state: DictState,
    busy: DictionariesState,
    onAdd: () -> Unit,
    onMove: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onSources: () -> Unit,
    onBack: () -> Unit,
) = Page("Dictionaries", onBack, actions = { LfgButton(if (busy.busy) "Adding…" else "Add dictionary…", onAdd, primary = true, enabled = !busy.busy) }) {
    val date = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    busy.error?.let { Text(it, color = Lfg.Error, fontSize = 14.sp) }
    val list = (state as? DictState.Ready)?.dictionaries ?: emptyList()
    if (list.isEmpty()) Text("No dictionary is installed.", color = Lfg.Dim, fontSize = 15.sp)
    list.forEachIndexed { i, d ->
        Card(d.title, listOf(d.summary, date.format(Date(d.installedAt))).filter { it.isNotEmpty() }.joinToString(" · ")) {
            if (i > 0) LfgButton("Move up", { onMove(d.title, true) })
            if (i < list.size - 1) LfgButton("Move down", { onMove(d.title, false) })
            LfgButton("Remove", { onRemove(d.title) })
        }
    }
    Row {
        Text("Entries show in this order. ", color = Lfg.Dim, fontSize = 14.sp)
        Text("Show sources", color = Lfg.Accent, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onSources))
    }
}

/** Each dictionary's own attribution, on a screen of its own as JMdict's licence asks. */
@Composable
fun SourcesScreen(sources: List<DictionarySource>, onBack: () -> Unit) = Page("Sources", onBack) {
    for (s in sources) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.title, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            listOf(s.author, s.url, s.revision).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · "), color = Lfg.Dim, fontSize = 13.sp) }
            if (s.description.isNotBlank()) Text(s.description, color = Lfg.Text, fontSize = 14.sp)
            Text(s.attribution.ifBlank { "This dictionary states no attribution." }, color = Lfg.Text, fontSize = 14.sp, lineHeight = 20.sp)
        }
        HorizontalDivider(color = Lfg.Border)
    }
    Text("Text recognition", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "VOCR (bee-san/vita-vn-ocr) notices new text. Its code is GPL-3.0-or-later; its weights ship with this app for " +
            "non-commercial use only. meikiocr (rtr46) reads the text: code Apache-2.0, weights LGPL-3.0. ScreenAI, when " +
            "installed, is screenai_ocr (AuroraWright, MPL-2.0) running the model files Google publishes for Chromium, " +
            "which are downloaded to this device and are not part of this app.",
        color = Lfg.Text, fontSize = 14.sp, lineHeight = 20.sp,
    )
    Text("Dictionary engine: hoshidicts (GPL-3.0). Cards: AnkiDroid's API.", color = Lfg.Text, fontSize = 14.sp)
}

@Composable
fun AnkiScreen(setup: SetupState, onFindAgain: () -> Unit, onConnect: () -> Unit, onBack: () -> Unit) = Page("Anki", onBack) {
    when {
        setup.anki == AnkiStatus.NOT_INSTALLED -> Card("AnkiDroid", "Not installed. Cards wait here until it is.")
        setup.anki == AnkiStatus.NEEDS_PERMISSION -> Card("AnkiDroid", "Not connected") { LfgButton("Connect", onConnect, primary = true) }
        else -> {
            val t = setup.ankiTarget
            Card("Note type", t?.modelName ?: "Not chosen yet")
            Card("Deck", t?.deckName ?: "Not chosen yet")
            Card(
                "Find again",
                "Looks for a Kiku, Lapis or Senren note type and the deck holding most of its notes; without one, the app uses its own.",
            ) { LfgButton(if (setup.ankiBusy) "Looking…" else "Find again", onFindAgain, enabled = !setup.ankiBusy) }
            setup.ankiError?.let { Text(it, color = Lfg.Error, fontSize = 14.sp) }
            if (setup.ankiNeverSynced) Text("If you use AnkiWeb, sync AnkiDroid once before the first card.", color = Lfg.Warn, fontSize = 14.sp)
        }
    }
}

fun shortcutLabel(prefs: Prefs) = if (prefs.shortcut == Prefs.SHORTCUT_L1_R1) "L1 + R1" else "L3 + R3"

@Composable
fun ControlsScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Controls", onBack) {
    Card("Lookup shortcut", "Starts and stops lookup with the controller. The game sees these buttons too, so pick a pair it does not use.") {
        Choice(listOf(Prefs.SHORTCUT_L3_R3 to "L3 + R3", Prefs.SHORTCUT_L1_R1 to "L1 + R1"), prefs.shortcut) { v -> onChange { it.copy(shortcut = v) } }
    }
    Card("Add card button", "Which face button adds the card; the other one closes. As Android names them.") {
        Choice(listOf("a" to "A adds, B closes", "b" to "B adds, A closes"), if (prefs.swapConfirm) "b" else "a") { v -> onChange { it.copy(swapConfirm = v == "b") } }
    }
    Card("Entry on the other screen", "On a two-screen handheld the entry shows on the screen the game is not on.") {
        OnOff(prefs.secondScreenPanel) { v -> onChange { it.copy(secondScreenPanel = v) } }
    }
    Text("In lookup: D-pad ◀ ▶ word, ▲ ▼ line, left stick one character, L1 R1 entry, right stick scroll.", color = Lfg.Dim, fontSize = 14.sp)
}

private fun modelStatus(state: ModelState, ready: String) = when (state) {
    ModelState.Missing -> "Not downloaded"
    is ModelState.Downloading -> "Downloading… ${(state.bytes + 524288) / 1048576} MB of ${(state.total + 524288) / 1048576} MB"
    ModelState.Ready -> ready
    is ModelState.Failed -> state.reason
}

@Composable
fun RecognitionScreen(
    prefs: Prefs,
    meiki: ModelState,
    screenAi: ModelState,
    screenAiBuilt: Boolean,
    onChange: ((Prefs) -> Prefs) -> Unit,
    onDownloadScreenAi: () -> Unit,
    onBack: () -> Unit,
) = Page("Text recognition", onBack) {
    Card("Engine", "Automatic uses ScreenAI when it is installed and meikiocr until then.") {
        Choice(
            listOf(Prefs.ENGINE_AUTO to "Automatic", Prefs.ENGINE_SCREENAI to "ScreenAI", Prefs.ENGINE_MEIKI to "meikiocr"),
            prefs.engine,
        ) { v -> onChange { it.copy(engine = v) } }
    }
    Card("meikiocr", modelStatus(meiki, "Installed"))
    Card(
        "ScreenAI",
        if (!screenAiBuilt) "Not in this build of the app." else modelStatus(screenAi, "Installed") +
            if (screenAi is ModelState.Missing) ". Its files come from the package Google publishes for Chromium." else "",
    ) {
        if (screenAiBuilt && (screenAi is ModelState.Missing || screenAi is ModelState.Failed)) LfgButton("Download", onDownloadScreenAi)
    }
    Text("VOCR watches every picture for new text; the engine above then reads it once it has stopped moving.", color = Lfg.Dim, fontSize = 14.sp)
}

@Composable
fun GamesScreen(games: List<Game>, ownPackage: String, onUpdate: (Game) -> Unit, onBack: () -> Unit) = Page("Games", onBack) {
    val list = games.filter { it.packageName != ownPackage }
    if (list.isEmpty()) Text("No game has been read yet. A game is the app in front when it is fullscreen.", color = Lfg.Dim, fontSize = 15.sp)
    for (game in list) {
        var title by remember(game.id) { mutableStateOf(game.title) }
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Lfg.Accent),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Lfg.Sunken).padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                LfgButton("Rename", { onUpdate(game.copy(title = title.trim())) }, enabled = title.trim().isNotEmpty() && title.trim() != game.title)
            }
            Text(game.packageName, color = Lfg.Dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Read this app", color = Lfg.Text, fontSize = 15.sp)
                    Text("Automatic reads it while it is fullscreen.", color = Lfg.Dim, fontSize = 13.sp)
                }
                Choice(
                    listOf("${Game.READ_AUTO}" to "Automatic", "${Game.READ_ON}" to "Always", "${Game.READ_OFF}" to "Never"),
                    "${game.reading}",
                ) { onUpdate(game.copy(reading = it.toInt())) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Tap strips", color = Lfg.Text, fontSize = 15.sp)
                    Text("Off for touch-driven games: taps then always reach the game.", color = Lfg.Dim, fontSize = 13.sp)
                }
                OnOff(game.tapStrips) { onUpdate(game.copy(tapStrips = it)) }
            }
        }
    }
}

@Composable
fun MediaScreen(prefs: Prefs, recording: Boolean, onChange: ((Prefs) -> Prefs) -> Unit, onStartRecording: () -> Unit, onBack: () -> Unit) = Page("Sound and pictures", onBack) {
    Card("Sentence audio", "The game's sound from the moment a line appears until the next, added to the card. Android asks for screen recording each session.") {
        OnOff(prefs.sentenceAudio) { v -> onChange { it.copy(sentenceAudio = v) } }
    }
    if (prefs.sentenceAudio) {
        Card("This session", if (recording) "Listening to game sound" else "Not listening yet") {
            if (!recording) LfgButton("Start", onStartRecording, primary = true)
        }
    }
    Card("Animated picture", "The moments around the line as a short moving picture, instead of a still.") {
        OnOff(prefs.animatedPicture) { v -> onChange { it.copy(animatedPicture = v) } }
    }
}

private fun duration(seconds: Double): String {
    val minutes = (seconds / 60).toLong()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** Screens over the line table: characters, lines and time per day and per game, speed, streaks. */
@Composable
fun StatsScreen(stats: StatsState, onBack: () -> Unit) = Page("Stats", onBack) {
    if (stats.days.isEmpty()) {
        Text("Nothing has been read yet.", color = Lfg.Dim, fontSize = 15.sp)
        return@Page
    }
    val today = remember { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, ZoneId.systemDefault()) }
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Figure("%,d".format(stats.chars), "characters")
        Figure("%,d".format(stats.lines), "lines")
        Figure(duration(stats.seconds), "reading")
        Figure(if (stats.seconds < 1) "–" else "%,d".format((stats.chars / stats.seconds * 3600).toInt()), "characters an hour")
        Figure("${stats.streaks.current}", if (stats.streaks.current == 1) "day in a row" else "days in a row")
        Figure("${stats.streaks.longest}", "longest run")
    }
    Table("By day", stats.days.take(60).map { d ->
        listOf(MainViewModel.dayLabel(d.day, today), "%,d".format(d.chars), "%,d".format(d.lines), duration(d.seconds), if (d.charsPerHour == 0) "–" else "%,d".format(d.charsPerHour))
    })
    Table("By game", stats.games.map { (title, g) ->
        listOf(title, "%,d".format(g.chars), "%,d".format(g.lines), duration(g.seconds), if (g.charsPerHour == 0) "–" else "%,d".format(g.charsPerHour))
    })
    Text(
        "Reading time counts the gap after each line, capped at 2.5 times what the line should take at that session's own pace and never more than 5 minutes. The day turns over at 04:00.",
        color = Lfg.Dim, fontSize = 13.sp, lineHeight = 18.sp,
    )
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, color = Lfg.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Lfg.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun Table(title: String, rows: List<List<String>>) {
    Column {
        Text(title, color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        TableRow(listOf("", "Characters", "Lines", "Time", "Per hour"), header = true)
        rows.forEach { TableRow(it, header = false) }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                color = if (header) Lfg.Dim else Lfg.Text,
                fontSize = if (header) 13.sp else 15.sp,
                fontWeight = if (i == 0 && !header) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.weight(if (i == 0) 2f else 1f),
            )
        }
    }
    HorizontalDivider(color = if (header) Lfg.BorderStrong else Lfg.Border)
}

enum class Page { HOME, LINE, STATS, SETTINGS, DICTIONARIES, SOURCES, ANKI, CONTROLS, RECOGNITION, GAMES, MEDIA, PRACTICE }
