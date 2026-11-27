// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.LookupState

/**
 * A word looked up by typing it: what is typed is looked up from its first
 * character as it is written, and a tap on another character of it looks up
 * from there, as in a line.
 */
@Composable
fun SearchScreen(
    state: LookupState,
    styles: List<EntryHtml.Style>,
    media: (String, String) -> ByteArray?,
    onSearch: (String) -> Unit,
    onTap: (Int) -> Unit,
    onLink: (EntryLink, Int) -> Unit,
    onBack: () -> Unit,
    /** Counts each time Search is opened afresh (from the wheel): the field is emptied and takes the keyboard again. */
    fresh: Int = 0,
) {
    var typed by rememberSaveable(fresh) { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(6.dp)
    LaunchedEffect(fresh) { runCatching { field.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Search") { LfgButton("Back", onBack) }
        HorizontalDivider(color = Lfg.Border)
        BasicTextField(
            value = typed,
            onValueChange = {
                typed = it
                onSearch(it)
            },
            singleLine = true,
            textStyle = TextStyle(color = Lfg.Text, fontSize = 20.sp),
            cursorBrush = SolidColor(Lfg.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(field).clip(shape).background(Lfg.Sunken).border(1.dp, Lfg.BorderStrong, shape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (typed.isEmpty()) Text("A word", color = Lfg.Dim, fontSize = 20.sp)
                inner()
            },
        )
        val line = state.lines.firstOrNull()?.takeIf { typed.isNotBlank() } ?: return
        // More than one word typed: the others are tapped, as in a line.
        if (line.text.length > state.markEnd - state.spanStart || !state.selected) {
            TappableSentence(line.text, state.spanStart, if (state.selected) state.markEnd else 0, onTap, modifier = Modifier.padding(top = 10.dp))
        }
        when {
            state.selected && state.entries.isNotEmpty() -> EntryList(state, styles, media, onLink, Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
            else -> (state.message ?: "Nothing found yet.").let { Text(it, color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}
