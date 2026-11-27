// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.KnownWords
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.KnownWordList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Known words on Settings › Anki: whether they look in all of Anki, and your
 * own list of words you know, for the language being learnt, pasted in and
 * edited here. A word on the list shows as known whatever Anki says, and the
 * list marks words with no AnkiDroid at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnownWordsGroup(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, store: KnownWords, ankiConnected: Boolean) {
    val language = prefs.learning
    val changed by store.changed.collectAsState()
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(language, changed) { count = withContext(Dispatchers.IO) { store.words(language.tag).size } }
    // The list as it is being edited; null when it is not. Not saved with the page: a long list is too big for that.
    var editing by remember(language) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Group("Known words") {
        if (ankiConnected) {
            Card("Look in all of Anki", "Any deck or note type") {
                OnOff(prefs.knownAllAnki) { on -> onChange { it.copy(knownAllAnki = on) } }
            }
        }
        val words = count
        Card(
            "Your known words",
            when {
                words == null -> "…"
                words == 0 -> "Paste words you already know"
                words == 1 -> "1 ${language.title} word"
                else -> "$words ${language.title} words"
            },
        ) {
            if (editing == null) {
                LfgButton("Edit…", { scope.launch { editing = withContext(Dispatchers.IO) { KnownWordList.format(store.words(language.tag)) } } })
            }
        }
        editing?.let { text ->
            Plain {
                val shape = RoundedCornerShape(12.dp)
                BasicTextField(
                    value = text,
                    onValueChange = { editing = it },
                    // Words as they are: the keyboard neither corrects them nor learns them.
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Lfg.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).padField(shape, singleLine = false).clip(shape)
                        .background(Lfg.Sunken).border(1.dp, Lfg.Border, shape).padding(horizontal = 14.dp, vertical = 13.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("One word a line, as a dictionary writes it", color = Lfg.Dim, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LfgButton("Save", {
                        val list = KnownWordList.parse(text)
                        editing = null
                        scope.launch { withContext(Dispatchers.IO) { store.replace(language.tag, list) } }
                    }, primary = true)
                    LfgButton("Cancel", { editing = null })
                    LfgButton("Clear", { editing = "" }, enabled = text.isNotEmpty())
                }
            }
        }
    }
}
