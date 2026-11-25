// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.ocr.ModelState
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.ModelStore
import io.github.beesan.lfg.dict.DefaultDictionaries

@Composable
fun SetupScreen(
    setup: SetupState,
    dictionary: DictState,
    reader: ModelState,
    onTurnOn: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onConnectAnki: () -> Unit,
    onInstallAnki: () -> Unit,
    onRetryDownloads: () -> Unit,
    wifiOnly: Boolean = false,
    onWifiOnly: (Boolean) -> Unit = {},
    /** The language being learnt; null until the question is answered. */
    language: Language? = Language.JAPANESE,
    onLanguage: (Language) -> Unit = {},
    /** Asked once: whether to keep every screen of dialogue for training a better reader. */
    askKeepDialogue: Boolean = false,
    onKeepDialogue: (Boolean) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set up", color = Lfg.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))

        if (language == null) LanguageQuestion(onLanguage) else SetupRow("Language", "Learning ${language.title}", done = true)

        SetupRow(
            name = "Screen reading",
            status = if (setup.serviceOn) "On" else "Off",
            done = setup.serviceOn,
            action = if (setup.serviceOn) null else "Turn on",
            primary = true,
            onAction = onTurnOn,
        )
        if (!setup.serviceOn && setup.installedFromFile) RestrictedSettingsHelp(onOpenAppInfo)

        when {
            setup.ankiBusy -> SetupRow("AnkiDroid", "Connecting…")
            setup.anki == AnkiStatus.NOT_INSTALLED -> SetupRow(
                name = "AnkiDroid",
                status = "Not installed. Cards wait here until it is.",
                action = "Install AnkiDroid",
                onAction = onInstallAnki,
            )
            setup.anki == AnkiStatus.NEEDS_PERMISSION -> SetupRow(
                name = "AnkiDroid",
                status = "Not connected",
                action = "Connect",
                primary = setup.serviceOn,
                onAction = onConnectAnki,
            )
            setup.ankiTarget != null -> SetupRow(
                name = "AnkiDroid",
                status = "Cards go to ${setup.ankiTarget.modelName} in ‘${setup.ankiTarget.deckName}’" +
                    if (setup.ankiTarget.created) ". Note type and deck added to AnkiDroid; they upload at the next sync." else "",
                done = true,
            )
            else -> SetupRow(
                name = "AnkiDroid",
                status = setup.ankiError ?: "Not connected",
                error = setup.ankiError != null,
                action = "Connect",
                onAction = onConnectAnki,
            )
        }

        // One row for both first-run downloads: the dictionaries, then the reader's files.
        val name = "Dictionaries and text recognition"
        val waiting = if (wifiOnly) "Waiting for Wi-Fi…" else "Waiting for a connection…"
        when {
            dictionary is DictState.Failed -> SetupRow(name, dictionary.reason, error = true, action = "Try again", onAction = onRetryDownloads)
            reader is ModelState.Failed -> SetupRow(name, reader.reason, error = true, action = "Try again", onAction = onRetryDownloads)
            dictionary is DictState.Downloading -> SetupRow(
                name = name,
                status = "Downloading ${dictionary.name}… ${mb(dictionary.bytes)} MB of ${mb(dictionary.total)} MB (${percent(dictionary.bytes, dictionary.total)}%)",
                progress = fraction(dictionary.bytes, dictionary.total),
            )
            dictionary is DictState.Installing -> SetupRow(name, "Installing ${dictionary.name}…", progress = 1f)
            dictionary is DictState.Missing -> SetupRow(name, waiting)
            reader is ModelState.Downloading -> SetupRow(
                name = name,
                status = "Downloading text recognition… ${mb(reader.bytes)} MB of ${mb(reader.total)} MB (${percent(reader.bytes, reader.total)}%)",
                progress = fraction(reader.bytes, reader.total),
            )
            reader is ModelState.Missing -> SetupRow(
                name,
                if (ScreenAi.available()) "Dictionaries installed. Text recognition: ${waiting.replaceFirstChar { it.lowercase() }}"
                else "This build of LFG cannot read the screen. Install a release from GitHub.",
                error = !ScreenAi.available(),
            )
            else -> SetupRow(
                name = name,
                status = (dictionary as DictState.Ready).dictionaries.filter { it.vndb == null }.joinToString { it.title } + " and text recognition installed",
                done = true,
            )
        }
        if (dictionary !is DictState.Ready || reader !is ModelState.Ready) {
            val total = (DefaultDictionaries.BYTES + ModelStore.SCREENAI_BYTES) / 1_000_000
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "About $total MB to download once: the dictionaries (${DefaultDictionaries.BYTES / 1_000_000} MB), then " +
                        "ScreenAI's text recognition (${ModelStore.SCREENAI_BYTES / 1_000_000} MB, of which 14 MB is kept).",
                    color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                Choice(listOf("any" to "Any network", "wifi" to "Only on Wi-Fi"), if (wifiOnly) "wifi" else "any") { onWifiOnly(it == "wifi") }
            }
        }
        if (askKeepDialogue) KeepDialogueQuestion(onKeepDialogue)
    }
}

/** Which language to learn: the first thing asked. One answer so far. */
@Composable
private fun LanguageQuestion(onAnswer: (Language) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Which language are you learning?", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("LFG reads your games in this language. Only Japanese for now; more will come.", color = Lfg.Dim, fontSize = 15.sp, lineHeight = 21.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { LfgButton("${it.title} · ${it.ownName}", { onAnswer(it) }, primary = true) }
        }
    }
}

@Composable
private fun KeepDialogueQuestion(onAnswer: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Enable saving full dialogue to improve the app?", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(KEEP_DIALOGUE_ASK, color = Lfg.Dim, fontSize = 15.sp, lineHeight = 21.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LfgButton("Yes", { onAnswer(true) }, primary = true)
            LfgButton("No", { onAnswer(false) })
        }
    }
}

/** The set-up question's words, also on Settings › Help improve LFG. */
internal const val KEEP_DIALOGUE_ASK =
    "Making an OCR program is hard and expensive! You can help me for free by enabling it.\n\n" +
        "LFG will save screenshots from every bit of dialogue you see, that way I can train a better OCR in the future, " +
        "improving the app for free.\n\n" +
        "These screenshots are not sent to me automatically. You have to go into Settings and share them with me."

private fun mb(bytes: Long) = (bytes + 512 * 1024) / (1024 * 1024)
private fun percent(bytes: Long, total: Long) = if (total > 0) bytes * 100 / total else 0
private fun fraction(bytes: Long, total: Long) = if (total > 0) bytes.toFloat() / total else 0f

@Composable
private fun SetupRow(
    name: String,
    status: String,
    done: Boolean = false,
    error: Boolean = false,
    action: String? = null,
    primary: Boolean = false,
    progress: Float? = null,
    onAction: () -> Unit = {},
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (done) Color.Transparent else Lfg.Surface)
            .border(1.dp, Lfg.Border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(status, color = if (error) Lfg.Error else if (done) Lfg.Ok else Lfg.Dim, fontSize = 14.sp)
            if (progress != null) {
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Lfg.Fill,
                    trackColor = Lfg.Sunken,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(16.dp))
            LfgButton(action, onAction, primary = primary)
        }
    }
}

/** Only when Android blocks the switch: an app installed from a file must be allowed restricted settings first. */
@Composable
private fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, Lfg.Border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("If the switch is greyed out", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Android blocks Screen reading for apps installed from a file.", color = Lfg.Dim, fontSize = 14.sp)
        listOf(
            "Tap LFG in the list, then OK",
            "In app info, tap ⋮, then Allow restricted settings",
            "Turn on Screen reading",
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
