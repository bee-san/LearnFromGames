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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.dict.InstalledDictionary
import io.github.beesan.lfg.names.CharacterNames
import io.github.beesan.lfg.names.VnResult

/** What a game's page needs to show its character names. */
class NamesRowActions(
    val title: (String) -> String?,
    val onSearch: (Game) -> Unit,
    val onAccept: (Game) -> Unit,
    val onDecline: (Game) -> Unit,
    val onBuild: (String) -> Unit,
    val onStop: (String) -> Unit,
    val onRemove: (String) -> Unit,
)

/** A small coloured label: Ready, Building, Is this it?, Not built. */
@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color, ground: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(ground).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * The "Character names" part of a game's card: the visual novel it is linked
 * to and its dictionary, a build under way or failed, a suggestion to take,
 * or the way to choose one by hand.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GameNamesRow(game: Game, installed: InstalledDictionary?, build: CharacterNames.Build?, actions: NamesRowActions) {
    val vn = game.linkedVn
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Character names", color = Lfg.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            when {
                build is CharacterNames.Build.Running -> Badge("Building", Lfg.Accent, Lfg.Soft)
                build is CharacterNames.Build.Failed -> Badge("Not built", Lfg.Error, Lfg.Soft)
                vn != null && installed != null -> Badge("Ready", Lfg.Ok, Lfg.OkSoft)
                vn == null && game.vndb.isEmpty() && game.vndbSuggested.isNotEmpty() -> Badge("Is this it?", Lfg.Warn, Lfg.Soft)
            }
        }
        when {
            vn != null -> {
                val name = actions.title(vn) ?: vn
                val how = when (game.vndbHow) {
                    Game.HOW_STEAM -> "Linked by Steam"
                    Game.HOW_TITLE -> "Linked by title, platform and a Japanese release"
                    Game.HOW_SUGGESTED -> "Linked when you said yes"
                    else -> "Chosen by you"
                }
                Text("$name · VNDB $vn", color = Lfg.Text, fontSize = 14.sp)
                when (build) {
                    is CharacterNames.Build.Running -> {
                        if (build.total > 0) Progress(build.done.toFloat() / build.total)
                        Text(
                            build.stage + (if (build.total > 0) " · ${build.done} of ${build.total}" else "") + ". It carries on while you play.",
                            color = Lfg.Dim, fontSize = 13.sp,
                        )
                    }
                    is CharacterNames.Build.Failed -> Text(build.reason, color = Lfg.Dim, fontSize = 13.sp)
                    null -> Text(
                        how + (installed?.let { " · %,d entries".format(it.terms) } ?: " · not built yet"),
                        color = Lfg.Dim, fontSize = 13.sp,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (build) {
                        is CharacterNames.Build.Running -> LfgButton("Stop", { actions.onStop(vn) })
                        is CharacterNames.Build.Failed -> LfgButton("Try now", { actions.onBuild(vn) }, primary = true)
                        null -> LfgButton(if (installed == null) "Build" else "Update", { actions.onBuild(vn) })
                    }
                    LfgButton("Change…", { actions.onSearch(game) })
                    if (installed != null && build == null) LfgButton("Remove", { actions.onRemove(vn) })
                }
            }
            game.vndb == Game.VNDB_NONE -> {
                Text("None. You said this is not a visual novel.", color = Lfg.Dim, fontSize = 13.sp)
                LfgButton("Search…", { actions.onSearch(game) })
            }
            game.vndbSuggested.isNotEmpty() -> {
                val suggested = game.vndbSuggested
                Text("${actions.title(suggested) ?: suggested} · VNDB $suggested", color = Lfg.Text, fontSize = 14.sp)
                Text("Only the title matched, so LFG did not link it by itself. This game may not be a visual novel.", color = Lfg.Dim, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LfgButton("Use it", { actions.onAccept(game) }, primary = true)
                    LfgButton("Search…", { actions.onSearch(game) })
                    LfgButton("Not a visual novel", { actions.onDecline(game) })
                }
            }
            else -> {
                Text("For visual novels: each character's name looks up as that character.", color = Lfg.Dim, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LfgButton("Search…", { actions.onSearch(game) })
                    LfgButton("Not a visual novel", { actions.onDecline(game) })
                }
            }
        }
    }
}

@Composable
private fun Progress(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Lfg.Border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(Lfg.Accent))
    }
}

/** Choosing a game's visual novel on VNDB by hand. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun VnSearchScreen(
    game: Game?,
    search: MainViewModel.VnSearch,
    onSearch: (String) -> Unit,
    onPick: (VnResult) -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
) = Page("Character names", onBack) {
    var query by remember(search.gameId) { mutableStateOf(game?.title.orEmpty()) }
    game?.let { Text("For ${it.title}", color = Lfg.Dim, fontSize = 14.sp) }
    Text("Visual novel on VNDB", color = Lfg.Text, fontSize = 15.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Lfg.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.weight(1f).padField(RoundedCornerShape(5.dp), singleLine = true).clip(RoundedCornerShape(5.dp)).background(Lfg.Sunken).border(1.dp, Lfg.BorderStrong, RoundedCornerShape(5.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        LfgButton(if (search.searching) "Searching…" else "Search", { onSearch(query) }, primary = true, enabled = !search.searching && query.isNotBlank())
    }
    Text("A title, a VNDB link, or an ID such as v17.", color = Lfg.Dim, fontSize = 13.sp)
    search.error?.let { Text(it, color = Lfg.Error, fontSize = 14.sp) }
    if (search.searched && search.results.isEmpty() && search.error == null) Text("Nothing on VNDB by that name.", color = Lfg.Dim, fontSize = 14.sp)
    val linked = game?.linkedVn
    for (result in search.results) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.fillMaxWidth().focusRing(shape).clip(shape).background(Lfg.Surface).border(1.dp, if (result.id == linked) Lfg.Accent else Lfg.Border, shape)
                .clickable { onPick(result) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(result.alttitle ?: result.title, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (result.id == linked) Badge("Linked", Lfg.OnFill, Lfg.Fill)
            }
            Text(
                listOfNotNull(result.title.takeIf { result.alttitle != null }, result.year?.toString(), result.id).joinToString(" · "),
                color = Lfg.Dim, fontSize = 13.sp,
            )
            val facts = listOfNotNull(
                "Japanese".takeIf { "ja" in result.languages },
                result.platforms.takeIf { it.isNotEmpty() }?.joinToString(", ") { PLATFORMS[it] ?: it },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Lfg.Dim, fontSize = 13.sp)
        }
    }
    Text(
        "LFG never changes a visual novel you chose. Choosing one builds its names on this device from VNDB.",
        color = Lfg.Dim, fontSize = 13.sp,
    )
    if (game != null && game.vndb != Game.VNDB_NONE) LfgButton("Not a visual novel", onDecline)
}

/** Settings › Character names. */
@Composable
fun CharacterNamesScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Character names", onBack) {
    Text(
        "For visual novels. LFG finds the game on VNDB and builds a dictionary of its cast on this device. Only that game's lookups use it.",
        color = Lfg.Dim, fontSize = 14.sp,
    )
    Card(
        "Find visual novels by themselves",
        "Links a game when its Steam ID matches, or its title, platform and a Japanese release all do. Anything less is asked once, in a lookup, and kept on the game's page.",
    ) { Switch(prefs.namesAuto) { v -> onChange { it.copy(namesAuto = v) } } }
    Card("Names with honorifics", "七瀬さん, 湊くん and about 270 more endings look up as one word. Most of a dictionary's size.") {
        Switch(prefs.namesHonorifics) { v -> onChange { it.copy(namesHonorifics = v) } }
    }
    Card("Show spoilers", "Descriptions and traits VNDB marks as spoilers.") {
        Switch(prefs.namesSpoilers) { v -> onChange { it.copy(namesSpoilers = v) } }
    }
    Text(
        "Changing honorifics or spoilers builds each game's names again the next time it is played. Downloads follow the Wi-Fi only choice of the first set-up.",
        color = Lfg.Dim, fontSize = 13.sp,
    )
}

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) =
    Choice(listOf("on" to "On", "off" to "Off"), if (on) "on" else "off") { onChange(it == "on") }

/** VNDB's platform codes, as their names. */
private val PLATFORMS = mapOf(
    "win" to "Windows", "and" to "Android", "ios" to "iOS", "swi" to "Switch", "psp" to "PSP", "psv" to "Vita", "ps2" to "PS2",
    "ps1" to "PS1", "ps3" to "PS3", "ps4" to "PS4", "ps5" to "PS5", "nds" to "DS", "n3d" to "3DS", "wii" to "Wii", "wiu" to "Wii U",
    "lin" to "Linux", "mac" to "Mac", "web" to "Web", "sw2" to "Switch 2", "xb1" to "Xbox", "xb3" to "Xbox 360",
    "xbo" to "Xbox One", "xxs" to "Xbox Series", "drc" to "Dreamcast", "sat" to "Saturn", "pce" to "PC Engine", "sfc" to "SNES",
    "nes" to "NES", "n64" to "N64", "gba" to "GBA", "gbc" to "GBC", "dos" to "DOS", "p98" to "PC-98", "p88" to "PC-88",
    "x68" to "X68000", "fmt" to "FM Towns", "msx" to "MSX", "mob" to "Other mobile", "dvd" to "DVD player", "bdp" to "Blu-ray player",
    "oth" to "Other",
)
