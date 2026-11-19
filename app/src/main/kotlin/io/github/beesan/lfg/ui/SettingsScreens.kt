// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.ReadingGoals
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.dict.DictionarySource
import io.github.beesan.lfg.dict.WordAudio
import io.github.beesan.lfg.ocr.ModelState
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    Card("Appearance", "Colours, and light or dark", { onOpen(Page.APPEARANCE) })
    Card("Translation", "Translate lines with your OpenRouter key", { onOpen(Page.TRANSLATION) })
    Card("Dictionaries", "Add, order and remove dictionaries", { onOpen(Page.DICTIONARIES) })
    Card("Anki", "The note type and deck cards go to", { onOpen(Page.ANKI) })
    Card("Controls", "The lookup shortcut", { onOpen(Page.CONTROLS) })
    Card("Text recognition", "The engine that reads the game", { onOpen(Page.RECOGNITION) })
    Card("Games", "Rename a game, turn tap strips off for it", { onOpen(Page.GAMES) })
    Card("Sound and pictures", "Sentence audio, spoken lines and animated pictures", { onOpen(Page.MEDIA) })
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
    wordAudio: WordAudio.Info?,
    onAddAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    onBack: () -> Unit,
) = Page("Dictionaries", onBack, actions = { LfgButton(if (busy.busy) "Adding…" else "Add dictionary…", onAdd, primary = true, enabled = !busy.busy) }) {
    val date = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    busy.error?.let { Text(it, color = Lfg.Error, fontSize = 14.sp) }
    busy.progress?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp) }
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
    HorizontalDivider(color = Lfg.Border)
    if (wordAudio == null) {
        Card(
            "Word audio",
            "Words said by people, from the android.db that the Local Audio Server add-on for Anki makes on a computer. " +
                "Copy it to this device and add it; LFG keeps its own copy, so the one you picked can be deleted after. Entries get a " +
                "play button, Y says the word in a game, and cards get it in their ExpressionAudio field.",
        ) { LfgButton("Add android.db…", onAddAudio, enabled = !busy.busy) }
    } else {
        Card("Word audio", wordAudio.summary + " · from local-audio-yomichan") {
            LfgButton("Replace…", onAddAudio, enabled = !busy.busy)
            LfgButton("Remove", onRemoveAudio, enabled = !busy.busy)
        }
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

/** The colour scheme, and whether it is light, dark or follows the system. Used by the app, the overlays and the widgets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Appearance", onBack) {
    Text("Theme", color = Lfg.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            listOf(Palette.THEME_SYSTEM to "Match system", Palette.THEME_LIGHT to "Light", Palette.THEME_DARK to "Dark"),
            prefs.theme,
        ) { v -> onChange { it.copy(theme = v) } }
    }
    Text("Colours", color = Lfg.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(Palette.SCHEMES, prefs.colours) { v -> onChange { it.copy(colours = v) } }
    }
}

/** Translating lines, and writing recaps, with an OpenRouter key: when, which model, into what, with how much before. */
@Composable
fun TranslationScreen(
    prefs: Prefs,
    onChange: ((Prefs) -> Prefs) -> Unit,
    keyTest: Pair<Boolean, String>?,
    onTestKey: () -> Unit,
    onBack: () -> Unit,
) = Page("Translation", onBack) {
    val detail = when (prefs.translate) {
        Prefs.TRANSLATE_ASK -> "A Translate button on each line"
        Prefs.TRANSLATE_EVERY -> "Each new line, as it is read"
        else -> "No line is sent anywhere"
    }
    Card("Translate", detail) {
        Choice(
            listOf(Prefs.TRANSLATE_OFF to "Off", Prefs.TRANSLATE_ASK to "When asked", Prefs.TRANSLATE_EVERY to "Every line"),
            prefs.translate,
        ) { v -> onChange { it.copy(translate = v) } }
    }
    SettingText("OpenRouter key", "Kept on this device, and never in a backup. Lines, and recaps' lines, go to OpenRouter.", prefs.openRouterKey, secret = true) { v ->
        onChange { it.copy(openRouterKey = v.trim()) }
    }
    SettingText("Model", "Also writes recaps", prefs.model) { v -> onChange { it.copy(model = v.trim()) } }
    SettingText("Into", "The language translations and recaps are in", prefs.translateInto) { v -> onChange { it.copy(translateInto = v) } }
    Card("Lines before", "Sent with the line, so the translation knows who is speaking") {
        Stepper(if (prefs.translateContext == 0) "None" else "${prefs.translateContext}", prefs.translateContext, Prefs.CONTEXT_STEPS) { v ->
            onChange { it.copy(translateContext = v) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LfgButton("Test the key", onTestKey, primary = true, enabled = prefs.openRouterKey.isNotBlank())
        keyTest?.let { (ok, text) -> Text(text, color = if (ok) Lfg.Ok else Lfg.Warn, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
    }
}

/** A setting typed in: its name and a line of detail over the field. */
@Composable
private fun SettingText(name: String, detail: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Lfg.Dim, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Lfg.Accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Lfg.Sunken).padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

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
    Text("In lookup: D-pad ◀ ▶ word, ▲ ▼ line, left stick one character, L1 R1 entry, Y listen to the word, right stick scroll.", color = Lfg.Dim, fontSize = 14.sp)
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
    Card("Sentence audio", "The voice in the game’s sound between a line and the next, added to the card. Android asks for screen recording each session.") {
        OnOff(prefs.sentenceAudio) { v -> onChange { it.copy(sentenceAudio = v) } }
    }
    if (prefs.sentenceAudio) {
        Card("This session", if (recording) "Listening to game sound" else "Not listening yet") {
            if (!recording) LfgButton("Start", onStartRecording, primary = true)
        }
    }
    Card("Spoken line", "Android’s voice reads the line onto cards with no voice from the game.") {
        OnOff(prefs.spokenLines) { v -> onChange { it.copy(spokenLines = v) } }
    }
    Card("Animated picture", "The moments around the line as a short moving picture, instead of a still.") {
        OnOff(prefs.animatedPicture) { v -> onChange { it.copy(animatedPicture = v) } }
    }
}

private fun duration(seconds: Double): String {
    val minutes = (seconds / 60).toLong()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** A goal's time: "45 min", "2 h", "1 h 30 min". */
private fun goalTime(minutes: Int) = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** The daily goals the stats screen measures against. */
@Composable
fun GoalsScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Reading goals", onBack) {
    Card("Hours per day", "Reading time, as Stats counts it.") {
        Stepper(if (prefs.goalMinutes == 0) "Off" else goalTime(prefs.goalMinutes), prefs.goalMinutes, ReadingGoals.MINUTE_STEPS) { v ->
            onChange { it.copy(goalMinutes = v) }
        }
    }
    Card("Characters per day", "Characters read, as Stats counts them: no punctuation or spaces.") {
        Stepper(if (prefs.goalChars == 0) "Off" else "%,d".format(prefs.goalChars), prefs.goalChars, ReadingGoals.CHAR_STEPS) { v ->
            onChange { it.copy(goalChars = v) }
        }
    }
    Card("Cards per day", "Cards made, added or waiting for AnkiDroid. A third ring on the Today widget.") {
        Stepper(if (prefs.goalCards == 0) "Off" else "${prefs.goalCards}", prefs.goalCards, ReadingGoals.CARD_STEPS) { v ->
            onChange { it.copy(goalCards = v) }
        }
    }
    Text(
        "A day runs from 4:00 to 4:00, so reading after midnight counts towards the day before. Stats shows today against each goal, " +
            "the days that met it, and a line on the chart of each day.",
        color = Lfg.Dim, fontSize = 14.sp, lineHeight = 20.sp,
    )
}

/** − and + through [steps], the current value between them. Both stay enabled, so the pad never loses its place at an end. */
@Composable
private fun Stepper(label: String, value: Int, steps: List<Int>, onPick: (Int) -> Unit) {
    LfgButton("−", { onPick(ReadingGoals.step(steps, value, up = false)) })
    Text(
        label, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
        modifier = Modifier.widthIn(min = 112.dp),
    )
    LfgButton("+", { onPick(ReadingGoals.step(steps, value, up = true)) })
}

/** Reading time, characters, speed and streak over a period, the daily goals, and a bar a day or a month. */
@Composable
fun StatsScreen(stats: StatsState, goals: ReadingGoals, onGoals: () -> Unit, onBack: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.DAYS_30) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var picked by remember(period) { mutableIntStateOf(-1) }
    Page("Stats", onBack, actions = {
        Choice(StatsPeriod.entries.map { it.name to it.label }, period.name) { period = StatsPeriod.valueOf(it) }
    }) {
        val today = remember { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, ZoneId.systemDefault()) }
        if (!stats.loaded) return@Page
        if (stats.days.isEmpty()) {
            Text("Nothing has been read yet.", color = Lfg.Dim, fontSize = 15.sp)
            HorizontalDivider(color = Lfg.Border)
            Goals(goals, null, 0, onGoals)
            return@Page
        }
        val view = remember(stats, period) { period.view(stats.days, today) }
        val progress = remember(stats, goals, view) { goals.progress(stats.days, view.start, today) }
        val t = view.totals
        val before = view.before
        Text(view.range, color = Lfg.Dim, fontSize = 14.sp)
        HorizontalDivider(color = Lfg.Border)
        Row(Modifier.fillMaxWidth()) {
            Tile(
                "Reading time", durationParts(t.seconds),
                when {
                    before != null -> StatsPeriod.change(t.seconds, before.seconds, view.beforeName)
                    t.seconds > 0 -> duration(t.seconds / view.dayCount) + " a day on average"
                    else -> ""
                },
            )
            Tile("Characters", listOf("%,d".format(t.chars) to ""), if (t.lines == 1) "1 line" else "%,d lines".format(t.lines))
            Tile(
                "Speed", listOf((if (t.charsPerHour == 0) "–" else "%,d".format(t.charsPerHour)) to "an hour"),
                if (before != null && t.charsPerHour > 0) StatsPeriod.change(t.charsPerHour.toDouble(), before.charsPerHour.toDouble(), view.beforeName) else "",
            )
            Tile("Streak", listOf("${stats.streaks.current}" to if (stats.streaks.current == 1) "day" else "days"), "Longest ${stats.streaks.longest}")
        }
        HorizontalDivider(color = Lfg.Border)
        Goals(goals, progress, view.dayCount, onGoals)
        HorizontalDivider(color = Lfg.Border)

        val value = { b: StatsPeriod.Bar -> if (showTime) b.seconds / 3600 else b.chars.toDouble() }
        val goal = when {
            !period.byDay -> null
            showTime -> if (goals.minutes > 0) goals.minutes / 60.0 else null
            else -> if (goals.chars > 0) goals.chars.toDouble() else null
        }
        val amount = { b: StatsPeriod.Bar -> if (showTime) duration(b.seconds) else "%,d characters".format(b.chars) }
        val top = view.bars.maxBy(value)
        val readout = when {
            picked in view.bars.indices -> view.bars[picked].let { b -> if (b.chars == 0) "${b.name} · Nothing read" else "${b.name} · ${amount(b)}" }
            top.chars == 0 -> "Nothing read"
            else -> "Read on ${view.daysRead} of ${view.dayCount} ${if (view.dayCount == 1) "day" else "days"} · Most: ${amount(top)}, ${top.name}"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(if (period.byDay) "Each day" else "Each month", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(readout, color = Lfg.Dim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
            Choice(listOf("chars" to "Characters", "time" to "Time"), if (showTime) "time" else "chars") { showTime = it == "time" }
        }
        BarChart(view.bars.map(value), view.bars.map { it.axis }, showTime, goal, picked, view.bars.map { "${it.name} · ${amount(it)}" }) {
            picked = if (picked == it) -1 else it
        }
    }
}

/**
 * The daily goals: what today still needs, then a tile for each goal set with
 * today against it and the period's days that met it, and the run of days
 * that met them all. [progress] is null when nothing has been read.
 */
@Composable
private fun Goals(goals: ReadingGoals, progress: ReadingGoals.Progress?, dayCount: Int, onGoals: () -> Unit) {
    val chars = { n: Int -> "%,d characters".format(n) }
    val readout = when {
        !goals.any -> "None set"
        progress == null -> listOfNotNull(
            goalTime(goals.minutes).takeIf { goals.minutes > 0 },
            chars(goals.chars).takeIf { goals.chars > 0 },
        ).joinToString(" and ") + " a day"
        else -> {
            val t = progress.today
            val left = listOfNotNull(
                goalTime(ceil((goals.minutes * 60 - t.seconds) / 60).toInt()).takeIf { goals.minutes > 0 && !goals.timeMet(t) },
                chars(goals.chars - t.chars).takeIf { goals.chars > 0 && !goals.charsMet(t) },
            )
            when {
                left.isNotEmpty() -> left.joinToString(" and ") + " to go today"
                goals.minutes > 0 && goals.chars > 0 -> "Both met today"
                else -> "Met today"
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Text("Daily goals", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(readout, color = Lfg.Dim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        LfgButton(if (goals.any) "Change goals" else "Set goals", onGoals)
    }
    if (!goals.any || progress == null) return
    val t = progress.today
    val met = { n: Int -> "Met on $n of $dayCount ${if (dayCount == 1) "day" else "days"}" }
    Row(Modifier.fillMaxWidth()) {
        if (goals.minutes > 0) {
            val parts = durationParts(t.seconds)
            Tile(
                "Hours per day", parts.dropLast(1) + parts.last().let { (n, unit) -> n to "$unit of ${goalTime(goals.minutes)}" },
                met(progress.timeDays), (t.seconds / (goals.minutes * 60)).toFloat(), goals.timeMet(t),
            )
        }
        if (goals.chars > 0) {
            Tile(
                "Characters per day", listOf("%,d".format(t.chars) to "of " + "%,d".format(goals.chars)),
                met(progress.charDays), t.chars.toFloat() / goals.chars, goals.charsMet(t),
            )
        }
        val s = progress.streaks
        Tile("Goal streak", listOf("${s.current}" to if (s.current == 1) "day" else "days"), "Longest ${s.longest}")
        // Empty columns keep the tiles under the four above.
        repeat(if (goals.minutes > 0 && goals.chars > 0) 1 else 2) { Spacer(Modifier.weight(1f)) }
    }
}

/** A figure with its units smaller beside it, under its name; with [progress], a bar of it, full in green once [done]. */
@Composable
private fun RowScope.Tile(label: String, parts: List<Pair<String, String>>, sub: String, progress: Float? = null, done: Boolean = false) {
    Column(Modifier.weight(1f).padding(top = 14.dp, bottom = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            buildAnnotatedString {
                parts.forEachIndexed { i, (n, unit) ->
                    append(n)
                    if (unit.isNotEmpty()) {
                        withStyle(SpanStyle(fontSize = 16.sp, color = Lfg.Dim, fontWeight = FontWeight.Normal)) {
                            append(if (i < parts.size - 1) " $unit " else " $unit")
                        }
                    }
                }
            },
            color = Lfg.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
        if (progress != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Lfg.Soft)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(if (done) Lfg.Ok else Lfg.Accent))
            }
        }
        if (sub.isNotEmpty()) Text(sub, color = Lfg.Dim, fontSize = 13.sp)
    }
}

/**
 * Bars on round grid lines, with their labels underneath. A bar is picked
 * with a tap or the pad, which puts its day in the line above. A [goal], in
 * the bars' units, is a dashed line across them.
 */
@Composable
private fun BarChart(values: List<Double>, axis: List<String>, hours: Boolean, goal: Double?, picked: Int, names: List<String>, onPick: (Int) -> Unit) {
    val (step, lines) = gridFor(maxOf(values.maxOrNull() ?: 0.0, goal ?: 0.0), hours)
    val height = 200.dp
    val label = { v: Double ->
        when {
            hours && v < 1 -> "${(v * 60).roundToInt()} min"
            hours -> if (v % 1.0 == 0.0) "${v.toInt()} h" else "%.1f h".format(v)
            v >= 1000 -> (v / 1000).let { if (it % 1.0 == 0.0) "${it.toInt()}k" else "%.1fk".format(it) }
            else -> v.roundToInt().toString()
        }
    }
    val top = step * lines
    val goalY = goal?.let { height * (1 - (it / top).toFloat()) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Box(Modifier.width(48.dp).height(height)) {
            for (k in 1..lines) {
                val y = height * (1 - k.toFloat() / lines)
                // The goal's label takes the place of a grid label it would overlap.
                if (goalY != null && abs((y - goalY).value) < 14) continue
                Text(
                    label(step * k), color = Lfg.Dim, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).offset(y = y - 7.dp),
                )
            }
            if (goalY != null) {
                Text(
                    "Goal", color = Lfg.Ok, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).offset(y = goalY - 7.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Box(
                Modifier.fillMaxWidth().height(height).drawWithContent {
                    drawContent()
                    if (goal != null) {
                        val y = size.height * (1 - (goal / top).toFloat())
                        drawLine(
                            Lfg.Ok, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    for (k in 1..lines) {
                        val y = size.height * (1 - k.toFloat() / lines)
                        drawLine(Lfg.Soft, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                    drawLine(Lfg.BorderStrong, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                }
                Row(Modifier.matchParentSize()) {
                    values.forEachIndexed { i, v ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clickable { onPick(i) }.semantics { contentDescription = names[i] },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            val fraction = if (top > 0) (v / top).toFloat().coerceIn(0f, 1f) else 0f
                            Box(
                                Modifier.fillMaxWidth(0.7f).widthIn(max = 18.dp).fillMaxHeight(fraction)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(if (picked < 0 || picked == i) Lfg.Accent else Lfg.Soft),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                axis.forEach { a ->
                    Box(Modifier.weight(1f)) {
                        Text(
                            a, color = Lfg.Dim, fontSize = 12.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

/** A round step and how many lines of it reach the highest bar: up to four. */
private fun gridFor(max: Double, hours: Boolean): Pair<Double, Int> {
    val steps = if (hours) {
        listOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1000.0)
    } else {
        generateSequence(1.0) { it * 10 }.take(10).flatMap { sequenceOf(it, it * 2, it * 5) }.toList()
    }
    val step = steps.firstOrNull { ceil(max / it) <= 4 } ?: steps.last()
    return step to ceil(max / step).toInt().coerceAtLeast(1)
}

/** "23 h 40 min" as numbers and their units. */
private fun durationParts(seconds: Double): List<Pair<String, String>> {
    val minutes = (seconds / 60).toLong()
    return if (minutes >= 60) listOf("${minutes / 60}" to "h", "${minutes % 60}" to "min") else listOf("$minutes" to "min")
}

enum class Page { HOME, LINE, NOTES, NOTE_NEW, RECAP, STATS, GOALS, SETTINGS, APPEARANCE, TRANSLATION, DICTIONARIES, SOURCES, ANKI, CONTROLS, RECOGNITION, GAMES, MEDIA, PRACTICE }
