// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.platform.LocalContext
import io.github.beesan.lfg.backup.Backup
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.ReadingGoals
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.games.CocoonLog
import io.github.beesan.lfg.games.Eden
import io.github.beesan.lfg.games.EsdeLog
import io.github.beesan.lfg.games.GameNameSources
import io.github.beesan.lfg.games.RetroArchHistory
import io.github.beesan.lfg.data.PictureKeeper
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
internal fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
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
@OptIn(ExperimentalLayoutApi::class)
fun Card(name: String, detail: String = "", onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Narrow, a row of buttons beside the name squeezed it to a letter a line: the buttons go under it.
        if (maxWidth < NARROW - 32.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text(name, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (detail.isNotEmpty()) Text(detail, color = Lfg.Dim, fontSize = 14.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (detail.isNotEmpty()) Text(detail, color = Lfg.Dim, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
    }
}

/** A choice between a few named options: the current one is filled. */
@Composable
internal fun Choice(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    options.forEach { (value, label) -> LfgButton(label, { onPick(value) }, primary = value == current) }
}

@Composable
private fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) = Choice(listOf("on" to "On", "off" to "Off"), if (on) "on" else "off") { onChange(it == "on") }

@Composable
fun SettingsScreen(onOpen: (Page) -> Unit, onBack: () -> Unit) = Page("Settings", onBack) {
    Card("Appearance", "Colours, and light or dark", { onOpen(Page.APPEARANCE) })
    Card("Backup", "Back up, restore, and back up on a schedule", { onOpen(Page.BACKUP) })
    Card("Translation", "Translate and explain lines with your OpenRouter key", { onOpen(Page.TRANSLATION) })
    Card("Dictionaries", "Add, order and remove dictionaries", { onOpen(Page.DICTIONARIES) })
    Card("Anki", "The note type and deck cards go to", { onOpen(Page.ANKI) })
    Card("Controls", "The lookup shortcut, whole words or characters, and where the entry shows", { onOpen(Page.CONTROLS) })
    Card("Text recognition", "ScreenAI, which reads the screen", { onOpen(Page.RECOGNITION) })
    Card("Games and apps", "Rename one, choose when it is read, turn tap strips off", { onOpen(Page.GAMES) })
    Card("Game names", "Which game is open in an emulator: Cocoon, ES-DE, RetroArch, Eden and Steam", { onOpen(Page.GAME_NAMES) })
    Card("Sound and pictures", "Sentence audio, spoken lines and animated pictures", { onOpen(Page.MEDIA) })
    Card("Other apps", "Read browsers, chat and ebooks when you ask", { onOpen(Page.APPS) })
    Card("Practice line", "Try a lookup and a card without a game", { onOpen(Page.PRACTICE) })
    Card("Storage", "What LFG keeps, and how long screens are kept", { onOpen(Page.STORAGE) })
    Card("Help and diagnostics", "A report to send with a bug, or after a first run on a new device", { onOpen(Page.HELP) })
}

@Composable
fun DictionariesScreen(
    state: DictState,
    fetching: DictState?,
    busy: DictionariesState,
    onAdd: () -> Unit,
    onMove: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onSources: () -> Unit,
    wordAudio: WordAudio.Info?,
    onAddAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    /** The starter dictionaries not installed, by name, and the button that fetches them. */
    starterMissing: List<String>,
    onStarter: () -> Unit,
    onBack: () -> Unit,
) = Page("Dictionaries", onBack, actions = { LfgButton(if (busy.busy) "Adding…" else "Add dictionary…", onAdd, primary = true, enabled = !busy.busy) }) {
    val date = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    busy.error?.let { Text(it, color = Lfg.Error, fontSize = 14.sp) }
    busy.progress?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp) }
    // The rest of the starter set, arriving after the first.
    when (fetching) {
        is DictState.Downloading -> Text(
            "Downloading ${fetching.name}… " + if (fetching.total > 0) "${fetching.bytes * 100 / fetching.total}%" else "${fetching.bytes / (1024 * 1024)} MB",
            color = Lfg.Dim, fontSize = 14.sp,
        )
        is DictState.Installing -> Text("Installing ${fetching.name}…", color = Lfg.Dim, fontSize = 14.sp)
        else -> {}
    }
    val list = (state as? DictState.Ready)?.dictionaries ?: emptyList()
    if (list.isEmpty()) Text("No dictionary is installed.", color = Lfg.Dim, fontSize = 15.sp)
    if (starterMissing.isNotEmpty() && fetching == null) {
        Card("Starter set", "Hachidori's five: meanings, names, kanji, frequencies (which put the commoner word first) and grammar. Missing: " + starterMissing.joinToString(", ") + ". About 105 MB in all.") {
            LfgButton("Download", onStarter, primary = true, enabled = !busy.busy)
        }
    }
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
            "non-commercial use only. ScreenAI reads the text: screenai_ocr (AuroraWright, MPL-2.0) running the model files Google publishes for Chromium, " +
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

fun shortcutLabel(prefs: Prefs) = when (prefs.shortcut) {
    Prefs.SHORTCUT_L1_R1 -> "L1 + R1"
    Prefs.SHORTCUT_L2_R2 -> "L2 + R2"
    else -> "L3 + R3"
}

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

/**
 * Backing up and restoring, a spreadsheet of the lines, and automatic
 * backups to a folder, with the lines file for GameSentenceMiner.
 */
@Composable
fun BackupScreen(
    prefs: Prefs,
    onChange: ((Prefs) -> Prefs) -> Unit,
    message: String?,
    pendingRestore: String?,
    onBackUp: () -> Unit,
    onPickRestore: () -> Unit,
    onRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onExport: () -> Unit,
    onChooseFolder: () -> Unit,
    onBack: () -> Unit,
) = Page("Backup", onBack) {
    message?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp) }
    Card("Back up now", "Lines, stats, notes, recaps, waiting cards and settings, in one file") { LfgButton("Back up…", onBackUp, primary = true) }
    if (pendingRestore == null) {
        Card("Restore", "Replaces what is here with a backup. What is here is backed up first.") { LfgButton("Restore…", onPickRestore) }
    } else {
        Card("Restore from $pendingRestore?", "Lines, notes and settings here are replaced, and LFG starts again.") {
            LfgButton("Cancel", onCancelRestore)
            LfgButton("Restore", onRestore, primary = true)
        }
    }
    Card("Export lines", "A spreadsheet of every line: time, game, text and characters") { LfgButton("Export…", onExport) }
    Card(
        "Lines file for GSM",
        if (prefs.gsmFile) "${Backup.LINES_FILE} in the backup folder, written with each automatic backup. GSM’s ExStatic import adds only lines it hasn’t seen."
        else "Keeps every line in a file GameSentenceMiner can import",
    ) {
        Choice(listOf("off" to "Off", "on" to "On"), if (prefs.gsmFile) "on" else "off") { v -> onChange { it.copy(gsmFile = v == "on") } }
    }
    Text("Automatic backup", color = Lfg.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    val last = remember(prefs.backupLast) { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prefs.backupLast)) }
    val status = when {
        prefs.backupEvery == Prefs.BACKUP_OFF -> "Off"
        prefs.backupFolder.isEmpty() -> "Choose a folder first"
        prefs.backupLast == 0L -> "No backup yet: the first is at about 04:00"
        else -> "Last: $last, " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, prefs.backupLastSize)
    }
    Card("How often", status) {
        Choice(
            listOf(Prefs.BACKUP_OFF to "Off", Prefs.BACKUP_DAILY to "Daily", Prefs.BACKUP_WEEKLY to "Weekly"),
            prefs.backupEvery,
        ) { v -> onChange { it.copy(backupEvery = v) } }
    }
    Card("Folder", Backup.folderName(prefs.backupFolder).ifEmpty { "Not chosen. A folder a sync app keeps in the cloud works too." }) {
        LfgButton(if (prefs.backupFolder.isEmpty()) "Choose folder…" else "Change folder…", onChooseFolder)
    }
    Card("Keep", "Older automatic backups are deleted") {
        Choice(listOf("7" to "Last 7", "30" to "Last 30", "0" to "All"), "${prefs.backupKeep}") { v -> onChange { it.copy(backupKeep = v.toInt()) } }
    }
    Card("Pictures", "Pictures of lines and notes") {
        Choice(listOf("in" to "Include", "out" to "Leave out"), if (prefs.backupPictures) "in" else "out") { v -> onChange { it.copy(backupPictures = v == "in") } }
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
    // With a key, every line has Translate and Explain; without one, no line is sent anywhere.
    val every = prefs.translate == Prefs.TRANSLATE_EVERY
    val detail = when {
        !prefs.aiReady -> "Add a key below and each line gets Translate and Explain"
        every -> "Each new line is translated as it is read. Explain is on each line"
        else -> "Translate and Explain are buttons on each line"
    }
    Card("Translate", detail) {
        Choice(
            listOf(Prefs.TRANSLATE_ASK to "When asked", Prefs.TRANSLATE_EVERY to "Every line"),
            if (every) Prefs.TRANSLATE_EVERY else Prefs.TRANSLATE_ASK,
        ) { v -> onChange { it.copy(translate = v) } }
    }
    SettingText("OpenRouter key", "Kept on this device, and never in a backup. Lines you translate or explain, and recaps' lines, go to OpenRouter.", prefs.openRouterKey, secret = true) { v ->
        onChange { it.copy(openRouterKey = v.trim()) }
    }
    SettingText("Model", "Also explains lines and writes recaps", prefs.model) { v -> onChange { it.copy(model = v.trim()) } }
    SettingText("Into", "The language translations, explanations and recaps are in", prefs.translateInto) { v -> onChange { it.copy(translateInto = v) } }
    Card("Lines before", "Sent with the line, so the answer knows who is speaking") {
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
        Choice(listOf(Prefs.SHORTCUT_L3_R3 to "L3 + R3", Prefs.SHORTCUT_L1_R1 to "L1 + R1", Prefs.SHORTCUT_L2_R2 to "L2 + R2"), prefs.shortcut) { v -> onChange { it.copy(shortcut = v) } }
    }
    Card("Add card button", "Which face button adds the card; the other one closes. As Android names them.") {
        Choice(listOf("a" to "A adds, B closes", "b" to "B adds, A closes"), if (prefs.swapConfirm) "b" else "a") { v -> onChange { it.copy(swapConfirm = v == "b") } }
    }
    val word = prefs.selection != Prefs.SELECT_CHARACTER
    Card(
        "Selection",
        if (word) "A lookup marks the whole word it found, and ▶ goes past that word." else "A lookup marks the one character it starts from, and ▶ goes one character.",
    ) {
        Choice(listOf(Prefs.SELECT_WORD to "Whole word", Prefs.SELECT_CHARACTER to "Character"), if (word) Prefs.SELECT_WORD else Prefs.SELECT_CHARACTER) { v ->
            onChange { it.copy(selection = v) }
        }
    }
    Card("Japanese characters only", "The cursor and a tap land only on kana and kanji, never on digits or Latin letters. Punctuation is always passed over.") {
        OnOff(prefs.japaneseOnly) { v -> onChange { it.copy(japaneseOnly = v) } }
    }
    Card("Lines on the other screen", "On a two-screen handheld, the screen the game is not on lists the game's lines as they are read.") {
        OnOff(prefs.secondScreenPanel) { v -> onChange { it.copy(secondScreenPanel = v) } }
    }
    Card("Dictionary entry", "On a two-screen handheld: a popup beside the word over the game, or on the lines' screen. A word picked in the lines always shows there.") {
        Choice(listOf(Prefs.ENTRY_OVER_GAME to "Over the game", Prefs.ENTRY_OTHER_SCREEN to "Other screen"), prefs.entryPlace) { v -> onChange { it.copy(entryPlace = v) } }
    }
    Card("Popup see-through", "How much of the game shows through the popup over it.") {
        Stepper(if (prefs.popupSeeThrough == 0) "Solid" else "${prefs.popupSeeThrough}%", prefs.popupSeeThrough, Prefs.SEE_THROUGH_STEPS) { v ->
            onChange { it.copy(popupSeeThrough = v) }
        }
    }
    Text(
        "In lookup: D-pad ◀ ▶ " + (if (word) "word" else "character") + ", ▲ ▼ row. Left stick: the same four ways, one character at a time, " +
            "faster while held. L1 R1 entry, Y listen to the word, right stick scroll.",
        color = Lfg.Dim, fontSize = 14.sp,
    )
}

private fun modelStatus(state: ModelState, ready: String) = when (state) {
    ModelState.Missing -> "Not downloaded"
    is ModelState.Downloading -> "Downloading… ${(state.bytes + 524288) / 1048576} MB of ${(state.total + 524288) / 1048576} MB"
    ModelState.Ready -> ready
    is ModelState.Failed -> state.reason
}

@Composable
fun RecognitionScreen(
    screenAi: ModelState,
    screenAiBuilt: Boolean,
    onDownloadScreenAi: () -> Unit,
    interfaceApart: Boolean,
    onInterfaceApart: (Boolean) -> Unit,
    onBack: () -> Unit,
) = Page("Text recognition", onBack) {
    Card(
        "ScreenAI",
        if (!screenAiBuilt) "Not in this build of the app." else modelStatus(screenAi, "Installed") +
            if (screenAi is ModelState.Missing) ". Its files come from the package Google publishes for Chromium." else "",
    ) {
        if (screenAiBuilt && (screenAi is ModelState.Missing || screenAi is ModelState.Failed)) LfgButton(if (screenAi is ModelState.Failed) "Try again" else "Download", onDownloadScreenAi)
    }
    Text("VOCR watches every picture for new text; ScreenAI then reads it once it has stopped moving.", color = Lfg.Dim, fontSize = 14.sp)
    Card(
        "Leave out menus and labels",
        "Short text that comes as a menu, stays put while the lines change, or keeps coming back is still tappable, " +
            "but stays out of Lines, the stats, recaps and the lines file. Off puts every line back.",
    ) {
        OnOff(interfaceApart, onInterfaceApart)
    }
}

@Composable
fun GamesScreen(games: List<Game>, ownPackage: String, onUpdate: (Game) -> Unit, onBack: () -> Unit) = Page("Games and apps", onBack) {
    val list = games.filter { it.packageName != ownPackage }
    if (list.isEmpty()) Text("Nothing has been read yet. A game is the app in front when it is fullscreen; other apps are listed once you ask for one to be read.", color = Lfg.Dim, fontSize = 15.sp)
    val apps = list.filter { it.detected.isEmpty() }.associate { it.packageName to it.title }
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
            if (game.detected.isEmpty()) {
                Text(game.packageName, color = Lfg.Dim, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (game.app) "Read this app (not a game)" else "Read this app", color = Lfg.Text, fontSize = 15.sp)
                        Text(
                            "Automatic reads it while it is fullscreen, and otherwise when you ask. On demand only when you ask. Always reads it whenever it is in front; Never leaves it alone.",
                            color = Lfg.Dim, fontSize = 13.sp,
                        )
                    }
                }
                Choice(
                    listOf(
                        "${Game.READ_AUTO}" to "Automatic", "${Game.READ_ON_DEMAND}" to "On demand",
                        "${Game.READ_ON}" to "Always", "${Game.READ_OFF}" to "Never",
                    ),
                    "${game.reading}",
                ) { onUpdate(game.copy(reading = it.toInt())) }
            } else {
                Text("In ${apps[game.packageName] ?: game.packageName}, named by ${game.namedBy}", color = Lfg.Dim, fontSize = 13.sp)
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

/** Where the game inside an emulator gets its name: front-ends' logs, RetroArch's history, Eden, and Steam. */
@Composable
fun GameNamesScreen(
    prefs: Prefs,
    status: Map<String, String>,
    steamTest: String?,
    onCheck: () -> Unit,
    onChoose: (String) -> Unit,
    onForget: (String) -> Unit,
    onChange: ((Prefs) -> Prefs) -> Unit,
    onTestSteam: () -> Unit,
    onBack: () -> Unit,
) = Page("Game names", onBack) {
    LaunchedEffect(Unit) { onCheck() }
    Text(
        "An emulator runs many games, and Android only says which app is in front. Choose what you launch games from, " +
            "and lines, stats and recaps go to the game itself instead of to RetroArch or Eden.",
        color = Lfg.Dim, fontSize = 14.sp,
    )
    val folders = listOf(
        Triple(CocoonLog.SOURCE, "Cocoon", "The folder with Cocoon's files, which has launch_debug.log in it or one folder down."),
        Triple(EsdeLog.SOURCE, "ES-DE", "The ES-DE folder, with logs/es_log.txt in it."),
        Triple(RetroArchHistory.SOURCE, "RetroArch", "The RetroArch folder, with playlists in it. For games started from RetroArch itself."),
        Triple(Eden.SOURCE, "Eden", "In the file picker's side menu, choose Eden. For Switch games started from Eden itself."),
    )
    for ((source, name, help) in folders) {
        val chosen = GameNameSources.folder(prefs, source).isNotEmpty()
        Card(name, if (chosen) status[source] ?: "Looking…" else help) {
            if (chosen) LfgButton("Forget", { onForget(source) })
            LfgButton(if (chosen) "Change…" else "Choose…", { onChoose(source) }, primary = !chosen)
        }
    }
    Text("Steam", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "For DroidDeck, GameNative and Winlator: Steam is asked what you are playing every 30 seconds while one is in front. " +
            "Your profile's game details must be public, or the key must be your own account's.",
        color = Lfg.Dim, fontSize = 14.sp,
    )
    SettingText("Steam profile", "Your SteamID64, profile link, or custom profile name", prefs.steamAccount) { v ->
        onChange { it.copy(steamAccount = v.trim()) }
    }
    SettingText("Web API key", "From steamcommunity.com/dev/apikey. Kept on this device, and never in a backup.", prefs.steamKey, secret = true) { v ->
        onChange { it.copy(steamKey = v.trim()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LfgButton("Ask Steam", onTestSteam, primary = true, enabled = prefs.steamKey.isNotBlank() && prefs.steamAccount.isNotBlank())
        steamTest?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
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

/** Ordinary apps: how a reading is asked for, and what is kept from it. */
@Composable
fun AppsScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Other apps", onBack) {
    Text(
        "LFG leaves an app alone while its status bar shows, so its scrolling and taps stay its own. Ask, and it reads what is on " +
            "screen: the app's own text where it shares it, which is exact and instant, and text recognition for pictures, video and anything drawn.",
        color = Lfg.Dim, fontSize = 14.sp,
    )
    Text("Ways to ask", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "• Select text in any app and choose LFG in the menu.\n" +
            "• Add the Read screen and Read region tiles to quick settings. Read region can keep reading a box, for subtitles.\n" +
            "• Press the lookup shortcut on the controller.\n" +
            "• Turn on the LFG button below.\n" +
            "Then tap a word. A tap anywhere else, or Back, takes the strips away.",
        color = Lfg.Text, fontSize = 14.sp,
    )
    Card("LFG button", "A small button over other apps. Tap to read the screen, hold to drag out a region, drag to move it.") {
        OnOff(prefs.readButton) { v -> onChange { it.copy(readButton = v) } }
    }
    Card(
        "Keep lines from apps",
        "Off: chats and mail can be private, so a lookup leaves nothing behind but the line a card is made from. " +
            "A region kept reading is always stored, as a game's lines are. Password fields are never read.",
    ) {
        OnOff(prefs.keepAppLines) { v -> onChange { it.copy(keepAppLines = v) } }
    }
    Card("Apps in reading time", "Lines kept from apps count towards reading time, the goals and the widgets, beside games.") {
        OnOff(prefs.appsInReadingTime) { v -> onChange { it.copy(appsInReadingTime = v) } }
    }
    Text("Cards from apps get no sentence audio and no moving picture: an app's sound is not its text's voice.", color = Lfg.Dim, fontSize = 14.sp)
}

internal fun duration(seconds: Double): String {
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
    Card("Time per day", "Reading time, as Stats counts it.") {
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
    Card("Today widget", "Auto follows the widget's size; some launchers never pass it on, so the layout can be chosen here.") {
        Choice(listOf("auto" to "Auto", "small" to "Small", "wide" to "Wide", "large" to "Large"), prefs.widgetSize) { v ->
            onChange { it.copy(widgetSize = v) }
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

/**
 * Reading time, characters, speed and streak over a period, today's sessions,
 * each game, the daily goals, and a bar a day or a month. A game opens its own
 * page, over the same period.
 */
@Composable
fun StatsScreen(
    stats: StatsState,
    games: List<Game>,
    goals: ReadingGoals,
    onGoals: () -> Unit,
    onGame: (String, StatsPeriod) -> Unit,
    onBack: () -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.DAYS_30) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var picked by remember(period) { mutableIntStateOf(-1) }
    Page("Stats", onBack, actions = {
        Choice(StatsPeriod.entries.map { it.name to it.label }, period.name) { period = StatsPeriod.valueOf(it) }
    }) {
        val today = remember { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, ZoneId.systemDefault()) }
        if (!stats.loaded) return@Page
        val byId = remember(games) { games.associateBy { it.id } }
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
        SessionBanner(stats, byId, today, rememberNow())
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
        GamesList(stats, byId, period, today) { onGame(it, period) }
        HorizontalDivider(color = Lfg.Border, modifier = Modifier.padding(top = 8.dp))
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
                "Time per day", parts.dropLast(1) + parts.last().let { (n, unit) -> n to "$unit of ${goalTime(goals.minutes)}" },
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
internal fun RowScope.Tile(label: String, parts: List<Pair<String, String>>, sub: String, progress: Float? = null, done: Boolean = false) {
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
            // Two lines: on a narrow screen the unit ("an hour") moves under the number instead of being cut off.
            color = Lfg.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
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
internal fun BarChart(values: List<Double>, axis: List<String>, hours: Boolean, goal: Double?, picked: Int, names: List<String>, onPick: (Int) -> Unit) {
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
internal fun durationParts(seconds: Double): List<Pair<String, String>> {
    val minutes = (seconds / 60).toLong()
    return if (minutes >= 60) listOf("${minutes / 60}" to "h", "${minutes % 60}" to "min") else listOf("$minutes" to "min")
}

enum class Page { STORAGE, HELP, HOME, LINE, NOTES, NOTE_NEW, RECAP, STATS, GAME_STATS, GOALS, SETTINGS, APPEARANCE, TRANSLATION, BACKUP, DICTIONARIES, SOURCES, ANKI, CONTROLS, RECOGNITION, GAMES, GAME_NAMES, MEDIA, APPS, PRACTICE }

@Composable
fun StorageScreen(
    usage: PictureKeeper.Usage?,
    keepDays: Int,
    message: String?,
    onKeepDays: (Int) -> Unit,
    onCleanUp: () -> Unit,
    onBack: () -> Unit,
) = Page("Storage", onBack) {
    fun mb(bytes: Long) = if (bytes < 1_048_576) "${(bytes + 1023) / 1024} KB" else "${(bytes + 524_288) / 1_048_576} MB"
    if (usage == null) {
        Text("Measuring…", color = Lfg.Dim, fontSize = 15.sp)
    } else {
        Card("Screens", "${mb(usage.screens)} · ${usage.screenCount} pictures, ${usage.keptScreens} kept for good")
        Card("Line pictures", "${mb(usage.crops)} · each line cut from its screen, kept for good")
        Card("Cards and notes", "${mb(usage.cards + usage.notes)} · pictures and sound made for cards, and notes' own copies")
        Card("Dictionaries", mb(usage.dictionaries))
        Card("Text recognition", mb(usage.reader))
        Card("Lines and stats", mb(usage.database))
        Text("${mb(usage.total)} in all", color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = Lfg.Border)
    Card(
        "Keep screens",
        "The whole screen saved with each line. A line, its text and its own picture are never deleted, and neither is " +
            "a screen a card was made from or a recap chose as a moment.",
    ) {
        Choice(listOf("7" to "A week", "14" to "Two weeks", "30" to "A month", "90" to "Three months", "0" to "Always"), keepDays.toString()) {
            onKeepDays(it.toInt())
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LfgButton("Clean up now", onCleanUp)
        message?.let { Text("  $it", color = Lfg.Dim, fontSize = 14.sp) }
    }
}

@Composable
fun HelpScreen(report: String?, onCopy: () -> Unit, onShare: () -> Unit, onBack: () -> Unit) = Page("Help and diagnostics", onBack) {
    Text(
        "If something goes wrong, or after a first run on a new handheld, send this report with what happened " +
            "(github.com/bee-san/LearnFromGames/issues). It has the device, what LFG has installed, how much it keeps, and " +
            "LFG's own recent log with its timings. It has no key and none of the text LFG read. Read it before you send it.",
        color = Lfg.Text, fontSize = 15.sp, lineHeight = 22.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LfgButton("Copy", onCopy, primary = true, enabled = report != null)
        LfgButton("Share…", onShare, enabled = report != null)
    }
    Text(
        report ?: "Gathering…",
        color = Lfg.Dim, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Lfg.Surface).padding(10.dp),
    )
}
