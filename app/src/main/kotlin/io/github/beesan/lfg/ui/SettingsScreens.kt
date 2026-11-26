// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.backup.Backup
import io.github.beesan.lfg.core.DialogueBox
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.ReadingGoals
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.PictureKeeper
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.dict.DictionaryRepository
import io.github.beesan.lfg.dict.DictionarySource
import io.github.beesan.lfg.dict.InstalledDictionary
import io.github.beesan.lfg.dict.WordAudio
import io.github.beesan.lfg.games.CocoonLog
import io.github.beesan.lfg.games.Eden
import io.github.beesan.lfg.games.EsdeLog
import io.github.beesan.lfg.games.GameNameSources
import io.github.beesan.lfg.games.RetroArchHistory
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
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padScroll(scroll)) {
        PageHeader(title) {
            actions()
            LfgButton("Back", onBack)
        }
        HorizontalDivider(color = Lfg.Border)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).firstFocus().padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp), content = content,
        )
    }
}

/**
 * A row of the app: a name, a short line of detail, and whatever it does on the
 * right. In a [Group] it is one row of the group's box; alone, a box of its own.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun Card(name: String, detail: String = "", onClick: (() -> Unit)? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val inGroup = LocalInGroup.current
    val shape = if (inGroup) RoundedCornerShape(0.dp) else RoundedCornerShape(18.dp)
    Box(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.focusRing(shape, inside = inGroup) else Modifier)
            .then(if (inGroup) Modifier.background(Lfg.Surface) else Modifier.clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        RowOrStack(
            texts = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (detail.isNotEmpty()) Text(detail, color = Lfg.Dim, fontSize = 13.sp, lineHeight = 18.sp)
                }
            },
            trailing = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { trailing() }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A choice between a few named options, in a row: the current one is filled. */
@Composable
internal fun Choice(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) = Segmented(options, current, onPick)

@Composable
internal fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) = Toggle(on) { onChange(it) }

/** The page last opened from Settings, where the pad's focus goes back to. */
private var lastSetting: Page? = null

@Composable
fun SettingsScreen(onOpen: (Page) -> Unit, onBack: () -> Unit) = Page("Settings", onBack) {
    val back = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        if (input.inputMode == InputMode.Keyboard && lastSetting != null) runCatching { back.requestFocus() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    SettingsSearchField(query, { query = it })
    // Something typed: the settings it finds, in place of the pages.
    if (query.isNotBlank()) {
        SettingsSearchResults(SettingsIndex.search(query)) {
            lastSetting = it
            onOpen(it)
        }
        return@Page
    }
    @Composable
    fun GroupOf(group: SettingsGroup) = Group(group.title) {
        for (entry in group.entries) {
            SettingsEntryRow(entry, group.tone(), if (entry.page == lastSetting) Modifier.focusRequester(back) else Modifier) {
                lastSetting = entry.page
                onOpen(entry.page)
            }
        }
    }
    // Wide, as on a handheld's top screen, the groups go in two columns.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= NARROW) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) { SETTINGS_GROUPS.take(2).forEach { GroupOf(it) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) { SETTINGS_GROUPS.drop(2).forEach { GroupOf(it) } }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { SETTINGS_GROUPS.forEach { GroupOf(it) } }
        }
    }
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
    /** Removes a visual novel's character names, by its VNDB ID. */
    onRemoveNames: (String) -> Unit,
    wordAudio: WordAudio.Info?,
    onAddAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    /** The starter dictionaries not installed, by name, and the button that fetches them. */
    starterMissing: List<String>,
    onStarter: () -> Unit,
    /** Whether dictionaries are brought up to date by themselves, when they were last asked about (0 for never), and "Check now". */
    updates: Boolean,
    onUpdates: (Boolean) -> Unit,
    updatesCheckedAt: Long,
    onCheckUpdates: () -> Unit,
    /** The title of the dictionary a tapped kanji is looked up in, as Settings holds it (empty for Bee's), and the choice of another. */
    kanjiDictionary: String,
    onKanjiDictionary: (String) -> Unit,
    onBack: () -> Unit,
) = Page("Dictionaries", onBack, actions = { LfgButton(if (busy.busy) "Adding…" else "Add dictionary…", onAdd, primary = true, enabled = !busy.busy) }) {
    val date = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    busy.error?.let { Hint(it, Lfg.Error) }
    busy.progress?.let { Hint(it) }
    // The rest of the starter set, arriving after the first.
    when (fetching) {
        is DictState.Downloading -> Hint(
            "Downloading ${fetching.name}… " + if (fetching.total > 0) "${fetching.bytes * 100 / fetching.total}%" else "${fetching.bytes / (1024 * 1024)} MB",
        )
        is DictState.Installing -> Hint("Installing ${fetching.name}…")
        else -> {}
    }
    val all = (state as? DictState.Ready)?.dictionaries ?: emptyList()
    val list = all.filter { it.vndb == null }
    val names = all.filter { it.vndb != null }
    if (starterMissing.isNotEmpty() && fetching == null) {
        Group("Get started") {
            Card("Starter set", "Missing: " + starterMissing.joinToString(", ") + " · about 105 MB") {
                LfgButton("Download", onStarter, primary = true, enabled = !busy.busy)
            }
        }
    }
    Group("Lookup order") {
        if (list.isEmpty()) Card("No dictionary yet", "Add one, or download the starter set")
        list.forEachIndexed { i, d ->
            Card(d.title, listOf(d.summary, date.format(Date(d.installedAt))).filter { it.isNotEmpty() }.joinToString(" · ")) {
                if (i > 0) LfgButton("▲", { onMove(d.title, true) }, modifier = Modifier.semantics { contentDescription = "Move up" })
                if (i < list.size - 1) LfgButton("▼", { onMove(d.title, false) }, modifier = Modifier.semantics { contentDescription = "Move down" })
                LfgButton("Remove", { onRemove(d.title) })
            }
        }
    }
    Group("Options") {
        val kanjiChoices = list.filter { it.terms > 0 }
        if (kanjiChoices.isNotEmpty()) {
            val kanji = DictionaryRepository.kanjiDictionary(kanjiChoices, kanjiDictionary)
            Card("Kanji", if (kanji == null) "Bee's kanji dictionary is not installed: all are used" else "Tap a kanji for its entry here") {
                Choice(kanjiChoices.map { it.title to it.title }, kanji?.title ?: "") { title ->
                    onKanjiDictionary(if (DictionaryRepository.DEFAULT_KANJI.title.matches(title)) "" else title)
                }
            }
        }
        if (list.isNotEmpty()) {
            Card(
                "Updates",
                (if (updates) "Daily · " else "") + if (updatesCheckedAt > 0) "Checked ${date.format(Date(updatesCheckedAt))}" else "Not checked yet",
            ) {
                Choice(listOf("on" to "Automatic", "off" to "Off"), if (updates) "on" else "off") { onUpdates(it == "on") }
                LfgButton("Check now", onCheckUpdates, enabled = !busy.busy && fetching == null)
            }
        }
        if (wordAudio == null) {
            Card("Word audio", "android.db from Local Audio Server") { LfgButton("Add…", onAddAudio, enabled = !busy.busy) }
        } else {
            Card("Word audio", wordAudio.summary) {
                LfgButton("Replace…", onAddAudio, enabled = !busy.busy)
                LfgButton("Remove", onRemoveAudio, enabled = !busy.busy)
            }
        }
        Card("Sources", "Each dictionary's credits", onClick = onSources)
    }
    if (names.isNotEmpty()) {
        Group("Character names") {
            for (d in names) {
                Card(d.title, listOf(d.summary, date.format(Date(d.installedAt))).filter { it.isNotEmpty() }.joinToString(" · ")) {
                    LfgButton("Remove", { d.vndb?.let(onRemoveNames) })
                }
            }
        }
        Hint("Removing one marks its games as not visual novels")
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
    Group("AnkiDroid") {
        when {
            setup.anki == AnkiStatus.NOT_INSTALLED -> Card("AnkiDroid", "Not installed · cards wait here")
            setup.anki == AnkiStatus.NEEDS_PERMISSION -> Card("AnkiDroid", "Not connected") { LfgButton("Connect", onConnect, primary = true) }
            else -> {
                val t = setup.ankiTarget
                Card("Note type", t?.modelName ?: "Not chosen yet")
                Card("Deck", t?.deckName ?: "Not chosen yet")
                Card("Find again", "Kiku, Lapis or Senren, else LFG's own") {
                    LfgButton(if (setup.ankiBusy) "Looking…" else "Find again", onFindAgain, enabled = !setup.ankiBusy)
                }
            }
        }
    }
    setup.ankiError?.let { Hint(it, Lfg.Error) }
    if (setup.anki != AnkiStatus.NOT_INSTALLED && setup.anki != AnkiStatus.NEEDS_PERMISSION && setup.ankiNeverSynced) {
        Hint("Using AnkiWeb? Sync AnkiDroid once first.", Lfg.Warn)
    }
}

fun shortcutLabel(prefs: Prefs) = io.github.beesan.lfg.core.Shortcut.label(prefs.shortcut)

/**
 * The language being learnt. Changing it changes what the reader reads lines as, whose rules a lookup
 * takes words apart by, and which dictionaries are looked up in: each language keeps its own, and
 * one picked for the first time has its dictionary fetched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageScreen(
    learning: Language,
    dictionary: DictState,
    fetching: DictState?,
    reader: ModelState,
    onPick: (Language) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) = Page("Language", onBack) {
    Group("Learning") {
        Plain {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.entries.forEach { language -> LanguageChip(language.title, language.ownName, language == learning) { onPick(language) } }
            }
        }
    }
    val state = fetching ?: dictionary
    Group("Dictionaries") {
        Card(
            "${learning.title} dictionaries",
            when (state) {
                is DictState.Ready -> state.dictionaries.count { it.vndb == null }.let { if (it == 1) "1 installed" else "$it installed" }
                is DictState.Downloading -> "Downloading… ${if (state.total > 0) state.bytes * 100 / state.total else 0}%"
                is DictState.Installing -> "Setting up…"
                is DictState.Failed -> state.reason
                DictState.Missing -> "Waiting for the internet…"
            },
        ) {
            if (state is DictState.Failed) LfgButton("Try again", onRetry)
        }
    }
    if (reader !is ModelState.Ready) Hint("The screen reader is still downloading")
}

/** The colour scheme, and whether it is light, dark or follows the system. Used by the app, the overlays and the widgets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Appearance", onBack) {
    val dark = Lfg.palette.dark
    Group("Theme") {
        Plain {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val light = Palette.of(prefs.colours, Palette.THEME_LIGHT, systemDark = false)
                val night = Palette.of(prefs.colours, Palette.THEME_DARK, systemDark = true)
                listOf(Triple(Palette.THEME_SYSTEM, "Match system", light to night), Triple(Palette.THEME_LIGHT, "Light", light to light), Triple(Palette.THEME_DARK, "Dark", night to night))
                    .forEach { (value, label, pair) ->
                        ThemePick(label, pair.first, pair.second, prefs.theme == value, Modifier.weight(1f)) { onChange { it.copy(theme = value) } }
                    }
            }
        }
    }
    Group("Colours") {
        Plain {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Palette.SCHEMES.forEach { (value, label) ->
                    val p = Palette.of(value, if (dark) Palette.THEME_DARK else Palette.THEME_LIGHT, systemDark = dark)
                    Swatch(label, Color(p.fill), Color(p.onFill), prefs.colours == value, Modifier.weight(1f)) { onChange { it.copy(colours = value) } }
                }
            }
        }
    }
    Group("Sounds") {
        Card("Button sounds") { OnOff(prefs.buttonSounds) { on -> onChange { it.copy(buttonSounds = on) } } }
        Card("Wheel and lookup sounds", "Tick, thock, chime") {
            OnOff(prefs.gameSounds) { on -> onChange { it.copy(gameSounds = on) } }
        }
        Card("Sound volume") {
            Choice(Prefs.SOUND_VOLUME_STEPS.map { it.toString() }.zip(listOf("Quiet", "Normal", "Loud")), prefs.soundVolume.toString()) { v ->
                onChange { it.copy(soundVolume = v.toInt()) }
                // Heard as it is chosen.
                GameSounds.sample(v.toInt())
            }
        }
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
    message?.let { Hint(it) }
    Group("Now") {
        Card("Back up now", "Everything, in one file") { LfgButton("Back up…", onBackUp, primary = true) }
        if (pendingRestore == null) {
            Card("Restore", "What is here is backed up first") { LfgButton("Restore…", onPickRestore) }
        } else {
            Card("Restore from $pendingRestore?", "Replaces everything here") {
                LfgButton("Cancel", onCancelRestore)
                LfgButton("Restore", onRestore, primary = true)
            }
        }
        Card("Export lines", "A spreadsheet of every line") { LfgButton("Export…", onExport) }
        Card("Lines file for GSM", if (prefs.gsmFile) "${Backup.LINES_FILE}, with each backup" else "For GameSentenceMiner") {
            OnOff(prefs.gsmFile) { v -> onChange { it.copy(gsmFile = v) } }
        }
    }
    val last = remember(prefs.backupLast) { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prefs.backupLast)) }
    val status = when {
        prefs.backupEvery == Prefs.BACKUP_OFF -> "Off"
        prefs.backupFolder.isEmpty() -> "Choose a folder first"
        prefs.backupLast == 0L -> "First one at about 04:00"
        else -> "Last: $last · " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, prefs.backupLastSize)
    }
    Group("Automatic backup") {
        Card("How often", status) {
            Choice(
                listOf(Prefs.BACKUP_OFF to "Off", Prefs.BACKUP_DAILY to "Daily", Prefs.BACKUP_WEEKLY to "Weekly"),
                prefs.backupEvery,
            ) { v -> onChange { it.copy(backupEvery = v) } }
        }
        Card("Folder", Backup.folderName(prefs.backupFolder).ifEmpty { "Not chosen · a synced folder works" }) {
            LfgButton(if (prefs.backupFolder.isEmpty()) "Choose…" else "Change…", onChooseFolder)
        }
        Card("Keep", "Older ones are deleted") {
            Choice(listOf("7" to "Last 7", "30" to "Last 30", "0" to "All"), "${prefs.backupKeep}") { v -> onChange { it.copy(backupKeep = v.toInt()) } }
        }
        Card("Pictures", "Of lines and diary entries") {
            Choice(listOf("in" to "Include", "out" to "Leave out"), if (prefs.backupPictures) "in" else "out") { v -> onChange { it.copy(backupPictures = v == "in") } }
        }
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
    Group("OpenRouter") {
        SettingText("OpenRouter key", "Only on this device · lines you send go to OpenRouter", prefs.openRouterKey, secret = true) { v ->
            onChange { it.copy(openRouterKey = v.trim()) }
        }
        SettingText("Model", "Also writes recaps and answers", prefs.model) { v -> onChange { it.copy(model = v.trim()) } }
        SettingText("Into", "The language answers are in", prefs.translateInto) { v -> onChange { it.copy(translateInto = v) } }
        Plain {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LfgButton("Test the key", onTestKey, primary = true, enabled = prefs.openRouterKey.isNotBlank())
                keyTest?.let { (ok, text) -> Text(text, color = if (ok) Lfg.Ok else Lfg.Warn, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
            }
        }
    }
    Group("Use") {
        Card(
            "Translate",
            when {
                !prefs.aiReady -> "Needs a key"
                every -> "Each new line, as it is read"
                else -> "A button on each line"
            },
        ) {
            Choice(
                listOf(Prefs.TRANSLATE_ASK to "When asked", Prefs.TRANSLATE_EVERY to "Every line"),
                if (every) Prefs.TRANSLATE_EVERY else Prefs.TRANSLATE_ASK,
            ) { v -> onChange { it.copy(translate = v) } }
        }
        Card("Ask", if (prefs.ask) "Questions go to OpenRouter, with web search" else "Off · nothing is sent") {
            OnOff(prefs.ask) { v -> onChange { it.copy(ask = v) } }
        }
        Card("Lines before", "So it knows who is speaking") {
            Stepper(if (prefs.translateContext == 0) "None" else "${prefs.translateContext}", prefs.translateContext, Prefs.CONTEXT_STEPS) { v ->
                onChange { it.copy(translateContext = v) }
            }
        }
    }
}

/** A setting typed in: its name and a few words over the field. */
@Composable
private fun SettingText(name: String, detail: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val inGroup = LocalInGroup.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().then(if (inGroup) Modifier.background(Lfg.Surface) else Modifier.clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotEmpty()) Text(detail, color = Lfg.Dim, fontSize = 13.sp, lineHeight = 18.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            // A key is a password to the keyboard too: it then neither suggests it nor learns it.
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Lfg.Accent),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).padField(RoundedCornerShape(12.dp), singleLine = true).clip(RoundedCornerShape(12.dp))
                .background(Lfg.Sunken).border(1.dp, Lfg.Border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
        )
    }
}

@Composable
fun ControlsScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Controls", onBack) {
    Group("Lookup button") {
        Card("Lookup shortcut", "Kept from the game while you play") {
            Choice(
                listOf(Prefs.SHORTCUT_L3 to "L3", Prefs.SHORTCUT_R3 to "R3", Prefs.SHORTCUT_SELECT to "Select", Prefs.SHORTCUT_START to "Start"),
                prefs.shortcut,
            ) { v -> onChange { it.copy(shortcut = v) } }
        }
        Card("Or two buttons together", "The game gets these too") {
            Choice(
                listOf(Prefs.SHORTCUT_L3_R3 to "L3 + R3", Prefs.SHORTCUT_L1_R1 to "L1 + R1", Prefs.SHORTCUT_L2_R2 to "L2 + R2", Prefs.SHORTCUT_SELECT_START to "Select + Start"),
                prefs.shortcut,
            ) { v -> onChange { it.copy(shortcut = v) } }
        }
    }
    val word = prefs.selection != Prefs.SELECT_CHARACTER
    Group("In lookup") {
        Card("Stick and D-pad", if (prefs.stickWords) "Stick: words · D-pad: characters" else "D-pad: words · stick: characters") {
            Choice(listOf("pad" to "D-pad by word", "stick" to "Stick by word"), if (prefs.stickWords) "stick" else "pad") { v -> onChange { it.copy(stickWords = v == "stick") } }
        }
        Card(
            "Tap a word",
            when (prefs.tapLookup) {
                Prefs.TAP_ALWAYS -> "A tap always looks up"
                Prefs.TAP_SHORTCUT -> "After ${shortcutLabel(prefs)}"
                else -> "After ${shortcutLabel(prefs)} with a controller"
            },
        ) {
            Choice(listOf(Prefs.TAP_AUTO to "Automatic", Prefs.TAP_SHORTCUT to "After shortcut", Prefs.TAP_ALWAYS to "Always"), prefs.tapLookup) { v -> onChange { it.copy(tapLookup = v) } }
        }
        Card("Add card button", "The other one closes") {
            Choice(listOf("a" to "A adds", "b" to "B adds"), if (prefs.swapConfirm) "b" else "a") { v -> onChange { it.copy(swapConfirm = v == "b") } }
        }
        Card("Selection", if (word) "▶ goes a word" else "▶ goes a character") {
            Choice(listOf(Prefs.SELECT_WORD to "Whole word", Prefs.SELECT_CHARACTER to "Character"), if (word) Prefs.SELECT_WORD else Prefs.SELECT_CHARACTER) { v ->
                onChange { it.copy(selection = v) }
            }
        }
        if (prefs.learning.japanese) Card("Japanese characters only", "Skips digits and Latin letters") {
            OnOff(prefs.japaneseOnly) { v -> onChange { it.copy(japaneseOnly = v) } }
        }
    }
    Group("Two screens") {
        Card("Lines on the other screen", "As they are read") {
            OnOff(prefs.secondScreenPanel) { v -> onChange { it.copy(secondScreenPanel = v) } }
        }
        Card("LFG on the second screen", "Leaves the first to the game") {
            OnOff(prefs.appOnSecondScreen) { v -> onChange { it.copy(appOnSecondScreen = v) } }
        }
        Card("Dictionary entry", "Where it opens") {
            Choice(listOf(Prefs.ENTRY_OVER_GAME to "Over the game", Prefs.ENTRY_OTHER_SCREEN to "Other screen"), prefs.entryPlace) { v -> onChange { it.copy(entryPlace = v) } }
        }
        Card("Popup see-through", "How much game shows through") {
            Stepper(if (prefs.popupSeeThrough == 0) "Solid" else "${prefs.popupSeeThrough}%", prefs.popupSeeThrough, Prefs.SEE_THROUGH_STEPS) { v ->
                onChange { it.copy(popupSeeThrough = v) }
            }
        }
    }
    Group("Buttons in lookup") {
        Plain {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyChip("◀ ▶", if (word) "Word" else "Character")
                KeyChip("▲ ▼", "Row")
                KeyChip("L stick", "Character")
                KeyChip("L1 R1", "Entry")
                KeyChip("Y", "Listen")
                KeyChip("R stick", "Scroll")
            }
        }
    }
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
    Group("Reader") {
        Card(
            "ScreenAI",
            if (!screenAiBuilt) "Not in this build" else modelStatus(screenAi, "Installed") +
                if (screenAi is ModelState.Missing) " · from Google's Chromium files" else "",
        ) {
            if (screenAiBuilt && (screenAi is ModelState.Missing || screenAi is ModelState.Failed)) LfgButton(if (screenAi is ModelState.Failed) "Try again" else "Download", onDownloadScreenAi)
        }
        Card("Leave out menus and labels", "Still tappable · kept out of Lines and stats") {
            OnOff(interfaceApart, onInterfaceApart)
        }
    }
    Hint("VOCR spots new text, then ScreenAI reads it")
}

@Composable
fun GamesScreen(
    games: List<Game>, ownPackage: String, onUpdate: (Game) -> Unit, onBack: () -> Unit,
    /** A game's box 1 (its dialogue box) and its others, by its id; its last picture, to show them on; and what draws and takes one away. */
    boxes: Map<String, DialogueBox> = emptyMap(), extraBoxes: Map<String, List<DialogueBox>> = emptyMap(),
    picture: suspend (gameId: String) -> String? = { null },
    onDrawBox: (Game, Int) -> Unit = { _, _ -> }, onRemoveBox: (Game, Int) -> Unit = { _, _ -> },
    names: List<InstalledDictionary> = emptyList(),
    builds: Map<String, io.github.beesan.lfg.names.CharacterNames.Build> = emptyMap(),
    namesActions: NamesRowActions? = null,
) = Page("Games and apps", onBack) {
    val list = games.filter { it.packageName != ownPackage }
    if (list.isEmpty()) Hint("Nothing read yet · games show here once played")
    val apps = list.filter { it.detected.isEmpty() }.associate { it.packageName to it.title }
    for (game in list) {
        var title by remember(game.id) { mutableStateOf(game.title) }
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Lfg.Accent),
                    modifier = Modifier.weight(1f).padField(RoundedCornerShape(12.dp), singleLine = true).clip(RoundedCornerShape(12.dp)).background(Lfg.Sunken).padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
                LfgButton("Rename", { onUpdate(game.copy(title = title.trim())) }, enabled = title.trim().isNotEmpty() && title.trim() != game.title)
            }
            if (game.detected.isEmpty()) {
                Text(game.packageName, color = Lfg.Dim, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (game.app) "Read this app (not a game)" else "Read this app", color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (game.reading) {
                                Game.READ_ON_DEMAND -> "Only when you ask"
                                Game.READ_ON -> "Whenever it is in front"
                                Game.READ_OFF -> "Never"
                                else -> "When fullscreen, or when you ask"
                            },
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
            // An ordinary app is read when asked, a region at a time: only what is read by itself has boxes.
            if (!game.app) {
                HorizontalDivider(color = Lfg.Border)
                GameBoxes(game, boxes[game.id], extraBoxes[game.id].orEmpty(), picture, onDrawBox, onRemoveBox)
            }
            // Ordinary apps have no cast to name.
            if (namesActions != null && !game.app) {
                HorizontalDivider(color = Lfg.Border)
                val vn = game.linkedVn
                GameNamesRow(game, vn?.let { id -> names.firstOrNull { it.vndb == id } }, vn?.let { builds[it] }, namesActions)
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
    Hint("Which game is running inside an emulator")
    val folders = listOf(
        Triple(CocoonLog.SOURCE, "Cocoon", "Folder with launch_debug.log"),
        Triple(EsdeLog.SOURCE, "ES-DE", "Folder with logs/es_log.txt"),
        Triple(RetroArchHistory.SOURCE, "RetroArch", "Folder with playlists"),
        Triple(Eden.SOURCE, "Eden", "Pick Eden in the side menu"),
    )
    Group("Launchers") {
        for ((source, name, help) in folders) {
            val chosen = GameNameSources.folder(prefs, source).isNotEmpty()
            Card(name, if (chosen) status[source] ?: "Looking…" else help) {
                if (chosen) LfgButton("Forget", { onForget(source) })
                LfgButton(if (chosen) "Change…" else "Choose…", { onChoose(source) }, primary = !chosen)
            }
        }
    }
    Group("Steam") {
        SettingText("Steam profile", "SteamID64, profile link or name", prefs.steamAccount) { v ->
            onChange { it.copy(steamAccount = v.trim()) }
        }
        SettingText("Web API key", "steamcommunity.com/dev/apikey · only on this device", prefs.steamKey, secret = true) { v ->
            onChange { it.copy(steamKey = v.trim()) }
        }
        Plain {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LfgButton("Ask Steam", onTestSteam, primary = true, enabled = prefs.steamKey.isNotBlank() && prefs.steamAccount.isNotBlank())
                steamTest?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
            }
        }
    }
    Hint("For DroidDeck, GameNative and Winlator · game details must be public")
}

@Composable
fun MediaScreen(prefs: Prefs, recording: Boolean, onChange: ((Prefs) -> Prefs) -> Unit, onStartRecording: () -> Unit, onBack: () -> Unit) = Page("Sound and pictures", onBack) {
    Group("Audio") {
        Card("Sentence audio", "The game's voice · Android asks once a session") {
            OnOff(prefs.sentenceAudio) { v -> onChange { it.copy(sentenceAudio = v) } }
        }
        if (prefs.sentenceAudio) {
            Card("This session", if (recording) "Listening to game sound" else "Not listening yet") {
                if (!recording) LfgButton("Start", onStartRecording, primary = true)
            }
        }
        Card("Spoken line", "Android's voice, when the game has none") {
            OnOff(prefs.spokenLines) { v -> onChange { it.copy(spokenLines = v) } }
        }
    }
    Group("Pictures") {
        Card("Animated picture", "A moving picture, not a still") {
            OnOff(prefs.animatedPicture) { v -> onChange { it.copy(animatedPicture = v) } }
        }
        Card("Trim black bars", "Cut to the game") {
            OnOff(prefs.trimBars) { v -> onChange { it.copy(trimBars = v) } }
        }
    }
}

/** Ordinary apps: how a reading is asked for, and what is kept from it. */
@Composable
fun AppsScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Other apps", onBack) {
    Hint("LFG reads an app only when you ask")
    Group("Ways to ask") {
        Plain {
            listOf(
                "Select text › LFG",
                "Quick settings › Read screen or Read region",
                "The lookup shortcut",
                "The LFG button below",
            ).forEach { Text("•  $it", color = Lfg.Text, fontSize = 15.sp) }
        }
    }
    Group("Other apps") {
        Card("LFG button", "Tap: read · hold: region · drag: move") {
            OnOff(prefs.readButton) { v -> onChange { it.copy(readButton = v) } }
        }
        Card("Keep lines from apps", "Off keeps chats private · passwords never read") {
            OnOff(prefs.keepAppLines) { v -> onChange { it.copy(keepAppLines = v) } }
        }
        Card("Apps in reading time", "Count towards goals and widgets") {
            OnOff(prefs.appsInReadingTime) { v -> onChange { it.copy(appsInReadingTime = v) } }
        }
    }
    Hint("Cards from apps get no audio or moving picture")
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
    Group("Each day") {
        Card("Time per day", "As Stats counts it") {
            Stepper(if (prefs.goalMinutes == 0) "Off" else goalTime(prefs.goalMinutes), prefs.goalMinutes, ReadingGoals.MINUTE_STEPS) { v ->
                onChange { it.copy(goalMinutes = v) }
            }
        }
        Card("Characters per day", "No punctuation or spaces") {
            Stepper(if (prefs.goalChars == 0) "Off" else "%,d".format(prefs.goalChars), prefs.goalChars, ReadingGoals.CHAR_STEPS) { v ->
                onChange { it.copy(goalChars = v) }
            }
        }
        Card("Cards per day", "Made, added or waiting") {
            Stepper(if (prefs.goalCards == 0) "Off" else "${prefs.goalCards}", prefs.goalCards, ReadingGoals.CARD_STEPS) { v ->
                onChange { it.copy(goalCards = v) }
            }
        }
    }
    Group("Widget") {
        Card("Today widget", "Auto follows its size") {
            Choice(listOf("auto" to "Auto", "small" to "Small", "wide" to "Wide", "large" to "Large"), prefs.widgetSize) { v ->
                onChange { it.copy(widgetSize = v) }
            }
        }
    }
    Hint("A day runs from 4:00 to 4:00")
}

/** − and + through [steps], the current value between them. Both stay enabled, so the pad never loses its place at an end. */
@Composable
internal fun Stepper(label: String, value: Int, steps: List<Int>, onPick: (Int) -> Unit) = StepperPill(
    label,
    onLess = { onPick(ReadingGoals.step(steps, value, up = false)) },
    onMore = { onPick(ReadingGoals.step(steps, value, up = true)) },
)

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
                            Modifier.weight(1f).fillMaxHeight().focusRing(RoundedCornerShape(4.dp))
                                // The pad picks the bar it is on, as a tap does.
                                .onFocusChanged { if (it.isFocused && picked != i) onPick(i) }.clickable { onPick(i) }.semantics { contentDescription = names[i] },
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

enum class Page { STORAGE, HELP, DIALOGUE, HOME, LINES, LINE, DIARY, DIARY_MONTH, DIARY_DAY, DIARY_ENTRY, DIARY_WRITE, DIARY_SHARE, DIARY_SETTINGS, RECAP, ASK, STATS, GAME_STATS, GOALS, SETTINGS, APPEARANCE, TRANSLATION, BACKUP, DICTIONARIES, SOURCES, ANKI, CONTROLS, RECOGNITION, GAMES, GAME_NAMES, CHARACTER_NAMES, VN_SEARCH, MEDIA, APPS, PRACTICE, BOX_DRAW, WHEEL, WHEEL_EDIT, SEARCH, LANGUAGE, PROFILES }

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
        Hint("Measuring…")
    } else {
        Group("Space used") {
            Card("Screens", "${mb(usage.screens)} · ${usage.screenCount} pictures, ${usage.keptScreens} kept")
            Card("Line pictures", mb(usage.crops))
            Card("Cards and notes", mb(usage.cards + usage.notes))
            Card("Dictionaries", mb(usage.dictionaries))
            Card("Text recognition", mb(usage.reader))
            Card("Lines and stats", mb(usage.database))
            Card("In all") { Text(mb(usage.total), color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
    Group("Screens") {
        Card("Keep screens", "Lines and card pictures are always kept") {
            Choice(listOf("7" to "Week", "14" to "2 weeks", "30" to "Month", "90" to "3 months", "0" to "Always"), keepDays.toString()) {
                onKeepDays(it.toInt())
            }
        }
        Card("Clean up now", message ?: "") { LfgButton("Clean up", onCleanUp) }
    }
}

@Composable
fun DialogueScreen(
    keep: Boolean,
    state: MainViewModel.DialogueState,
    onKeep: (Boolean) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onDiscord: () -> Unit,
    onBack: () -> Unit,
) = Page("Help improve LFG", onBack) {
    Hint("Saved screens help train a better reader")
    val kept = state.kept
    fun mb(bytes: Long) = if (bytes < 1_048_576) "${(bytes + 1023) / 1024} KB" else "${(bytes + 524_288) / 1_048_576} MB"
    Group("Dialogue") {
        Card("Save full dialogue", "Games only · each screen and its text") {
            OnOff(keep, onKeep)
        }
        Card("Saved so far", if (kept == null) "Counting…" else "${kept.screens} screen${if (kept.screens == 1) "" else "s"} · ${mb(kept.bytes)}")
    }
    Group("Send") {
        Plain {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LfgButton(if (state.busy) "Zipping…" else "Send…", onSend, primary = true, enabled = !state.busy && (kept?.screens ?: 0) > 0)
                LfgButton("Join the Discord", onDiscord)
                LfgButton("Delete all", onClear, enabled = !state.busy && (kept?.screens ?: 0) > 0)
            }
        }
    }
    Hint("Mails a zip to ${io.github.beesan.lfg.capture.DialogueSamples.EMAIL} · look first · over 25 MB? Use Discord")
    state.message?.let { Hint(it) }
}

@Composable
fun HelpScreen(report: String?, onCopy: () -> Unit, onShare: () -> Unit, onBack: () -> Unit) = Page("Help and diagnostics", onBack) {
    Group("Report") {
        Card("Diagnostics", "No keys, none of the text LFG read") {
            LfgButton("Copy", onCopy, primary = true, enabled = report != null)
            LfgButton("Share…", onShare, enabled = report != null)
        }
    }
    Hint("Send it with what happened: github.com/bee-san/LearnFromGames/issues")
    Text(
        report ?: "Gathering…",
        color = Lfg.Dim, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Lfg.Surface).border(1.dp, Lfg.Border, RoundedCornerShape(14.dp)).padding(12.dp),
    )
}
