// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.SetupStep
import io.github.beesan.lfg.core.Shortcut
import io.github.beesan.lfg.dict.DefaultDictionaries
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.ocr.ModelState
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.ocr.ScreenAi
import kotlinx.coroutines.delay

/** What a step of set-up can ask for. Each answer moves on by itself where there is nothing more to say. */
class SetupActions(
    val onLanguage: (Language) -> Unit,
    val onTurnOn: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val onController: (Boolean) -> Unit,
    val onBluetooth: () -> Unit,
    /** The buttons pressed are the shortcut from now on. */
    val onShortcut: (String) -> Unit,
    val onConnectAnki: () -> Unit,
    val onInstallAnki: () -> Unit,
    /** Whether LFG is used outside games too, with its button over other apps. */
    val onEverywhere: (Boolean) -> Unit,
    val onKey: (String) -> Unit,
    val onTestKey: () -> Unit,
    val onKeepDialogue: (Boolean) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onRetry: () -> Unit,
    val onTutorial: () -> Unit,
    /** Set-up ends here, without the tutorial. */
    val onFinish: () -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * Set-up: one question to a screen ([io.github.beesan.lfg.core.SetupFlow]), each with as few words as
 * say it. The downloads run behind every step and are only waited for at the end, if they are not in by then.
 */
@Composable
fun SetupScreen(
    step: SetupStep,
    steps: List<SetupStep>,
    setup: SetupState,
    dictionary: DictState,
    reader: ModelState,
    prefs: Prefs,
    /** The controller the device has or is connected to, by name; null for none. */
    pad: String?,
    /** The buttons pressed for the shortcut on this visit to its step, as [Shortcut] saves them; null before any. */
    pressed: String?,
    keyTest: Pair<Boolean, String>?,
    /** The tutorial is open on the other screen of a two-screen handheld. */
    practising: Boolean,
    actions: SetupActions,
) {
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
            if (step != steps.first()) LfgButton("Back", actions.onBack, compact = true)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val at = steps.indexOf(step)
                steps.forEachIndexed { i, _ ->
                    Box(Modifier.size(if (i == at) 9.dp else 7.dp).clip(CircleShape).background(if (i <= at) Lfg.Fill else Lfg.BorderStrong))
                }
            }
        }
        when (step) {
            SetupStep.LANGUAGE -> LanguageStep(Language.of(prefs.language), actions.onLanguage)
            SetupStep.READING -> ReadingStep(setup, actions)
            SetupStep.CONTROLLER -> Question(
                "Do you play with a controller?",
                pad?.let { "LFG can see $it." } ?: "LFG works with buttons or with touch.",
            ) {
                LfgButton("Yes", { actions.onController(true) }, primary = pad != null, modifier = Modifier.firstFocus())
                LfgButton("No, I use touch", { actions.onController(false) }, primary = pad == null)
            }
            SetupStep.CONNECT -> Question("Connect your controller", "Put it in pairing mode, then pick it in Bluetooth. LFG moves on when it connects.") {
                LfgButton("Open Bluetooth", actions.onBluetooth, primary = true, modifier = Modifier.firstFocus())
                LfgButton("Skip", actions.onNext)
            }
            SetupStep.BUTTON -> ButtonStep(prefs, pressed, actions)
            SetupStep.ANKI -> AnkiStep(setup, actions)
            SetupStep.WHERE -> {
                Heading("Where will you use LFG?")
                Option("Only in games", "LFG spots a game by itself and starts reading.", !prefs.readButton, Modifier.firstFocus()) { actions.onEverywhere(false) }
                Option("Everywhere", "A small LFG button floats over other apps. Tap it to read the screen.", prefs.readButton) { actions.onEverywhere(true) }
            }
            SetupStep.AI -> AiStep(prefs, keyTest, actions)
            SetupStep.IMPROVE -> Question("Help LFG read better?", KEEP_DIALOGUE_ASK) {
                LfgButton("Yes", { actions.onKeepDialogue(true) }, primary = true, modifier = Modifier.firstFocus())
                LfgButton("No", { actions.onKeepDialogue(false) })
            }
            SetupStep.DOWNLOAD -> {
                Heading("Almost ready")
                Body("LFG is getting its dictionary and its screen reader.")
                Downloads(dictionary, reader, prefs, actions, always = true)
            }
            SetupStep.TUTORIAL -> if (practising) {
                Heading("Look at the other screen")
                Body("The practice screen is open there.")
            } else Question(
                "Try it",
                "A practice screen opens. " + (if (prefs.usesController) "Look a word up, make a flashcard and open the wheel." else "Look a word up and make a flashcard."),
            ) {
                LfgButton("Start", actions.onTutorial, primary = true, modifier = Modifier.firstFocus())
                LfgButton("Skip", actions.onFinish)
            }
        }
        if (step != SetupStep.DOWNLOAD && step != SetupStep.LANGUAGE) Downloads(dictionary, reader, prefs, actions, always = false)
    }
}

@Composable
private fun Heading(text: String) = Text(text, color = Lfg.Text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(text: String, warn: Boolean = false) = Text(text, color = if (warn) Lfg.Warn else Lfg.Dim, fontSize = 17.sp, lineHeight = 24.sp)

/** A step that is a heading, a sentence or two, and its buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Question(title: String, body: String, buttons: @Composable () -> Unit) {
    Heading(title)
    Body(body)
    Spacer(Modifier.height(2.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
}

/** One of a few answers that each need a sentence: the whole card is the button. */
@Composable
private fun Option(name: String, detail: String, chosen: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .background(Lfg.Surface)
            .border(if (chosen) 2.dp else 1.dp, if (chosen) Lfg.Fill else Lfg.Border, shape)
            .clickable(onClick = onPick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(name, color = Lfg.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Lfg.Dim, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

/** The first question: the best-known languages, and the rest behind "More". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageStep(chosen: Language?, onPick: (Language) -> Unit) {
    var all by rememberSaveable { mutableStateOf(chosen != null && chosen.ordinal >= Language.COMMON) }
    Heading("Which language are you learning?")
    Body("LFG reads your games in it and looks its words up.")
    Spacer(Modifier.height(2.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Language.entries.take(if (all) Language.entries.size else Language.COMMON).forEachIndexed { i, language ->
            LfgButton(
                if (language.ownName == language.title) language.title else "${language.title} · ${language.ownName}",
                { onPick(language) }, primary = language == chosen, modifier = if (i == 0) Modifier.firstFocus() else Modifier,
            )
        }
        if (!all) LfgButton("More…", { all = true })
    }
}

@Composable
private fun ReadingStep(setup: SetupState, actions: SetupActions) {
    // On when the step opened: come back to with Back, so it waits to be left rather than going on by itself.
    val onAlready = remember { setup.serviceOn }
    if (setup.serviceOn) {
        // Back from Android's page with the switch on: nothing to press.
        if (!onAlready) LaunchedEffect(Unit) {
            delay(DONE_MS)
            actions.onNext()
        }
        Heading("Screen reading is on")
        Body("LFG can read your games now.")
        if (onAlready) LfgButton("Next", actions.onNext, primary = true, modifier = Modifier.firstFocus())
        return
    }
    Question("Let LFG read your screen", "LFG needs this to see the words in your games. What it reads stays on your device unless you ask for a translation.") {
        LfgButton("Turn on", actions.onTurnOn, primary = true, modifier = Modifier.firstFocus())
    }
    Body("On Android's page: tap LFG, then turn the switch on.")
    if (setup.installedFromFile) RestrictedSettingsHelp(actions.onOpenAppInfo)
}

/** Which buttons open LFG: whatever is pressed now, shown at once, and kept on "Use". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonStep(prefs: Prefs, pressed: String?, actions: SetupActions) {
    val picked = pressed ?: prefs.shortcut
    val (first, second) = Shortcut.keys(picked)
    Heading("Pick your LFG button")
    Body("Press the button that should open LFG. Two buttons together work too.")
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(Shortcut.label(picked), color = Lfg.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (first == second) "While you play, the game no longer gets this button." else "The game gets these too, so pick two it does not use together.",
            color = Lfg.Dim, fontSize = 15.sp, lineHeight = 21.sp,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LfgButton("Use ${Shortcut.label(picked)}", { actions.onShortcut(picked) }, primary = true, modifier = Modifier.firstFocus())
    }
}

@Composable
private fun AnkiStep(setup: SetupState, actions: SetupActions) {
    val target = setup.ankiTarget
    when {
        setup.ankiBusy -> {
            Heading("Flashcards")
            Body("Setting up AnkiDroid…")
        }
        setup.anki == AnkiStatus.NOT_INSTALLED -> Question(
            "Flashcards",
            "LFG makes flashcards in AnkiDroid, which is not installed. Cards you make wait in LFG until it is.",
        ) {
            LfgButton("Get AnkiDroid", actions.onInstallAnki, modifier = Modifier.firstFocus())
            LfgButton("Skip", actions.onNext, primary = true)
        }
        setup.anki == AnkiStatus.READY && target != null -> Question(
            "AnkiDroid is ready",
            "Your flashcards go to ‘${target.deckName}’." +
                (if (target.created) " LFG added that deck and its card type for you." else "") +
                (if (setup.ankiNeverSynced) " If you use AnkiWeb, sync AnkiDroid once before your first card." else ""),
        ) {
            LfgButton("Next", actions.onNext, primary = true, modifier = Modifier.firstFocus())
        }
        else -> {
            Question("Make flashcards in AnkiDroid?", "LFG sets up a deck for you. One press makes a card from any word.") {
                LfgButton("Connect AnkiDroid", actions.onConnectAnki, primary = true, modifier = Modifier.firstFocus())
                LfgButton("Skip", actions.onNext)
            }
            setup.ankiError?.let { Body(it, warn = true) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiStep(prefs: Prefs, keyTest: Pair<Boolean, String>?, actions: SetupActions) {
    Heading("Have an OpenRouter key?")
    Body("With one, LFG can translate and explain lines. No key? Skip this and add one later in Settings.")
    BasicTextField(
        value = prefs.openRouterKey,
        onValueChange = actions.onKey,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // A password to the keyboard too: it then neither suggests the key nor learns it.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp),
        cursorBrush = SolidColor(Lfg.Accent),
        decorationBox = { field ->
            Box {
                if (prefs.openRouterKey.isEmpty()) Text("Paste your key here", color = Lfg.Dim, fontSize = 16.sp)
                field()
            }
        },
        modifier = Modifier.fillMaxWidth().padField(RoundedCornerShape(8.dp), singleLine = true).clip(RoundedCornerShape(8.dp)).background(Lfg.Sunken)
            .border(1.dp, Lfg.Border, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
    )
    // The key works: nothing more to ask.
    val works = keyTest?.first == true && keyTest.second.startsWith("Works")
    // Tested before the step opened (come back to with Back): it waits to be left.
    val worked = remember { works }
    if (works && !worked) LaunchedEffect(Unit) {
        delay(DONE_MS)
        actions.onNext()
    }
    keyTest?.let { (ok, said) -> Body(if (works) "Your key works." else said, warn = !ok) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (prefs.openRouterKey.isNotBlank()) LfgButton("Check my key", actions.onTestKey, primary = true)
        LfgButton(if (prefs.openRouterKey.isBlank()) "Skip" else "Next", actions.onNext, primary = prefs.openRouterKey.isBlank(), modifier = Modifier.firstFocus())
    }
}

/** The set-up question's words, also on Settings › Help improve LFG. */
internal const val KEEP_DIALOGUE_ASK =
    "LFG can keep a screenshot of each line of dialogue you see, so I can teach it to read better. " +
        "Nothing is sent by itself: you choose when to share them, in Settings."

/**
 * How the first downloads are going: under every step while they run, in one line, so set-up never
 * looks stuck at its end. With [always] it is the step itself, and says so when they are in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Downloads(dictionary: DictState, reader: ModelState, prefs: Prefs, actions: SetupActions, always: Boolean) {
    val failed = (dictionary as? DictState.Failed)?.reason ?: (reader as? ModelState.Failed)?.reason
    val done = dictionary is DictState.Ready && reader is ModelState.Ready
    if (done && !always) return
    val waiting = if (prefs.wifiOnly) "Waiting for Wi-Fi…" else "Waiting for the internet…"
    val (said, progress) = when {
        failed != null -> failed to null
        dictionary is DictState.Downloading -> "Getting the dictionary… ${percent(dictionary.bytes, dictionary.total)}%" to fraction(dictionary.bytes, dictionary.total)
        dictionary is DictState.Installing -> "Setting up the dictionary…" to 1f
        dictionary is DictState.Missing -> waiting to null
        reader is ModelState.Downloading -> "Getting the screen reader… ${percent(reader.bytes, reader.total)}%" to fraction(reader.bytes, reader.total)
        !ScreenAi.available() -> "This build of LFG cannot read the screen. Install a release from GitHub." to null
        reader is ModelState.Missing -> waiting to null
        else -> "Everything is downloaded." to null
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = if (always) 0.dp else 10.dp).clip(shape).border(1.dp, Lfg.Border, shape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(said, color = if (failed != null || !ScreenAi.available()) Lfg.Error else if (done) Lfg.Ok else Lfg.Dim, fontSize = 15.sp)
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Lfg.Fill, trackColor = Lfg.Sunken)
        }
        if (failed != null) LfgButton("Try again", actions.onRetry, primary = always)
        if (always && !done) {
            val total = (DefaultDictionaries.bytes(prefs.learning) + ModelStore.SCREENAI_BYTES) / 1_000_000
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("About $total MB, once.", color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Choice(listOf("any" to "Any network", "wifi" to "Only on Wi-Fi"), if (prefs.wifiOnly) "wifi" else "any") { actions.onWifiOnly(it == "wifi") }
            }
        }
        if (always && done) {
            LaunchedEffect(Unit) {
                delay(DONE_MS)
                actions.onNext()
            }
        }
    }
}

private fun percent(bytes: Long, total: Long) = if (total > 0) bytes * 100 / total else 0
private fun fraction(bytes: Long, total: Long) = if (total > 0) bytes.toFloat() / total else 0f

/** Only when Android blocks the switch: an app installed from a file must be allowed restricted settings first. */
@Composable
private fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, Lfg.Border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Is the switch greyed out?", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "Tap LFG in the list, then OK",
            "Open app info, tap ⋮, then Allow restricted settings",
            "Come back and turn the switch on",
        ).forEachIndexed { i, step ->
            Row {
                Box(Modifier.width(24.dp)) { Text("${i + 1}", color = Lfg.Dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Text(step, color = Lfg.Text, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        LfgButton("Open app info", onOpenAppInfo)
    }
}

/** How long a step that finished by itself shows that it has, before the next. */
private const val DONE_MS = 900L
