// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Profile
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.ProfileRules
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.dict.DictState

/**
 * Settings › Profiles: the profiles there are, the one in use marked, and one
 * more made by saying which language it is for. Each can be renamed, put in
 * the quick settings and, but for the last, deleted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    dictionary: DictState,
    fetching: DictState?,
    /** What came of asking Android for a quick settings tile, said under the profiles. */
    message: String?,
    onUse: (String) -> Unit,
    onNew: (Language) -> Unit,
    onRename: (String, String) -> Unit,
    onTile: (Profile) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    // The profile being renamed, with the name as typed so far, and the one asked about before it is deleted.
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(adding) { adding = false }
    if (adding) {
        Page("New profile", { adding = false }) {
            Group("Which language?") {
                Plain {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Language.entries.forEach { language ->
                            LanguageChip(language.title, language.ownName, false) {
                                onNew(language)
                                adding = false
                            }
                        }
                    }
                }
            }
            Hint("Starts with the settings you have now")
        }
        return
    }
    Page("Profiles", onBack) {
        val several = profiles.size > 1
        Group("Profiles") { profiles.forEach { profile ->
            val named = profile.name.isNotBlank()
            when (profile.id) {
                renaming -> Card("Name", "Empty: ${profile.language.title}") {
                    NameField(typed, { typed = it.take(ProfileRules.NAME_LENGTH) }, profile.language.title) {
                        onRename(profile.id, typed)
                        renaming = null
                    }
                    LfgButton("Save", {
                        onRename(profile.id, typed)
                        renaming = null
                    }, primary = true)
                }
                removing -> Card("Delete ${profile.shown}?", "Lines, cards and dictionaries stay") {
                    LfgButton("Delete", {
                        onRemove(profile.id)
                        removing = null
                    })
                    LfgButton("Keep", { removing = null }, primary = true)
                }
                else -> Card(profile.shown, if (named) profile.language.title else "") {
                    if (several) LfgButton(if (profile.inUse) "In use" else "Use", { onUse(profile.id) }, primary = profile.inUse)
                    LfgButton("Rename", {
                        typed = profile.name
                        removing = null
                        renaming = profile.id
                    })
                    if (several) {
                        LfgButton("Quick tile", { onTile(profile) })
                        LfgButton("Delete", {
                            renaming = null
                            removing = profile.id
                        })
                    }
                }
            }
        } }
        Hint("Each has its own language and settings · keys and backups are shared")
        if (message != null) Hint(message)
        if (profiles.size < ProfileRules.MAX) LfgButton("New profile", { adding = true }, primary = true)
        // A language new to the device: its dictionary comes as soon as the profile is made.
        when (val state = fetching ?: dictionary) {
            is DictState.Downloading -> Text("Downloading the dictionary… ${if (state.total > 0) state.bytes * 100 / state.total else 0}%", color = Lfg.Dim, fontSize = 14.sp)
            is DictState.Installing -> Text("Setting up the dictionary…", color = Lfg.Dim, fontSize = 14.sp)
            is DictState.Failed -> Text(state.reason, color = Lfg.Dim, fontSize = 14.sp)
            DictState.Missing -> Text("The dictionary downloads when there is internet.", color = Lfg.Dim, fontSize = 14.sp)
            is DictState.Ready -> {}
        }
    }
}

/**
 * Settings › Games and apps: the profile a game changes to as it comes to the
 * front, or none, to leave the one in use as it is. Only with two profiles to
 * choose from, and not for an ordinary app, which is read only when asked.
 */
@Composable
fun GameProfileRow(game: Game, profiles: List<Profile>, linked: String?, onPick: (String?) -> Unit) {
    if (profiles.size < 2 || game.app) return
    // A link to a profile since deleted is none.
    val current = profiles.firstOrNull { it.id == linked }
    HorizontalDivider(color = Lfg.Border)
    Column {
        Text("Profile", color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(if (current == null) "Left as it is" else "Changes to it when played", color = Lfg.Dim, fontSize = 13.sp)
    }
    Choice(listOf("" to "Any") + profiles.map { it.id to it.shown }, current?.id.orEmpty()) { onPick(it.ifEmpty { null }) }
}

/** The one-line field a profile is named in. */
@Composable
internal fun NameField(value: String, onChange: (String) -> Unit, hint: String, onDone: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(Lfg.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(0.5f).heightIn(min = 44.dp).padField(shape, singleLine = true).clip(shape).background(Lfg.Sunken)
            .border(1.dp, Lfg.BorderStrong, shape).padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = Lfg.Dim, fontSize = 16.sp, maxLines = 1)
            inner()
        },
    )
}
