// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.lookup.LookupState
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Notes on what happened in games, and on words in them, newest first, by day and game. */
@Composable
fun NotesScreen(notes: List<Note>, onNew: () -> Unit, onBack: () -> Unit) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(notes.size) { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Notes") {
            LfgButton("New note…", onNew, primary = true)
            LfgButton("Back", onBack)
        }
        HorizontalDivider(color = Lfg.Border)
        if (notes.isEmpty()) {
            Text("No notes yet. Press ✎ on a word over a game, or New note…", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            return
        }
        LazyColumn {
            var lastGroup = ""
            for (note in notes) {
                val day = MainViewModel.dayLabel(ReadingStats.dayOf(note.createdAt / 1000.0, zone), today)
                val group = if (note.gameName.isEmpty()) day else "$day · ${note.gameName}"
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "g:${note.id}") {
                        Text(group, color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    }
                }
                item(key = note.id) { NoteRow(note, time.format(Date(note.createdAt))) }
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, time: String) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        note.picturePath?.let { Picture(it, Modifier.width(168.dp).height(95.dp).clip(RoundedCornerShape(6.dp))) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (note.term.isNotEmpty()) {
                    Text(note.term, color = Lfg.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (note.reading.isNotEmpty() && note.reading != note.term) Text(note.reading, color = Lfg.Dim, fontSize = 13.sp)
                } else {
                    Text(note.title.ifEmpty { "Note" }, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text(time, color = Lfg.Dim, fontSize = 13.sp)
            }
            if (note.term.isNotEmpty() && note.title.isNotEmpty()) Text(note.title, color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (note.text.isNotEmpty()) Text(note.text, color = Lfg.Text, fontSize = 15.sp, lineHeight = 22.sp)
            if (note.lineText.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        append("「")
                        val at = if (note.term.isEmpty()) -1 else note.lineText.indexOf(note.term)
                        if (at < 0) {
                            append(note.lineText)
                        } else {
                            append(note.lineText.substring(0, at))
                            pushStyle(SpanStyle(color = Lfg.Accent))
                            append(note.term)
                            pop()
                            append(note.lineText.substring(at + note.term.length))
                        }
                        append("」")
                    },
                    color = Lfg.Dim, fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * A new note: the picture of a line, the line to tap a word in, then the
 * word, a title and the note. The word is the entry the line's lookup is on.
 */
@Composable
fun NoteNewScreen(
    draft: MainViewModel.NoteDraft,
    lookup: LookupState,
    onTap: (Int) -> Unit,
    onLineBefore: () -> Unit,
    onRemovePicture: () -> Unit,
    onClearWord: () -> Unit,
    onSave: (title: String, text: String, inLookups: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var inLookups by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("New note") {
            LfgButton("Cancel", onCancel)
            LfgButton("Save", { onSave(title, text, inLookups) }, primary = true, enabled = text.isNotBlank() || title.isNotBlank())
        }
        HorizontalDivider(color = Lfg.Border)
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                draft.picture?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.line != null) LfgButton("Use the line before", onLineBefore)
                    if (draft.picture != null) LfgButton("Remove picture", onRemovePicture)
                }
                val line = draft.line
                if (line != null) {
                    TappableSentence(line.lineText, lookup.spanStart, if (lookup.selected) lookup.spanEnd else 0, onTap)
                    Text("Tap a word to note it.", color = Lfg.Dim, fontSize = 14.sp)
                } else {
                    Text("No lines yet: this note is on its own.", color = Lfg.Dim, fontSize = 14.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val entry = lookup.entry
                if (entry != null) {
                    Label("Word")
                    val shape = RoundedCornerShape(8.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            if (entry.reading.isNotEmpty() && entry.reading != entry.expression) Text(entry.reading, color = Lfg.Dim, fontSize = 11.sp)
                            Text(entry.expression, color = Lfg.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(entry.senses().firstOrNull().orEmpty(), color = Lfg.Dim, fontSize = 15.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        LfgButton("Clear", onClearWord)
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { inLookups = !inLookups },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = inLookups,
                            onCheckedChange = { inLookups = it },
                            colors = CheckboxDefaults.colors(checkedColor = Lfg.Accent, uncheckedColor = Lfg.Dim, checkmarkColor = Lfg.OnFill),
                        )
                        Text("Show in lookups of this word", color = Lfg.Text, fontSize = 15.sp)
                    }
                }
                Label("Title")
                Field(title, { title = it }, singleLine = true)
                Label("Note")
                Field(text, { text = it }, singleLine = false, modifier = Modifier.heightIn(min = 140.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Lfg.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Field(value: String, onChange: (String) -> Unit, singleLine: Boolean, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(Lfg.Accent),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Lfg.Sunken)
            .border(1.dp, Lfg.BorderStrong, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A picture from a file, decoded near the size it shows at; nothing when the file is gone. */
@Composable
fun Picture(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { decodeSmall(path)?.asImageBitmap() } ?: return
    Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

private fun decodeSmall(path: String, width: Int = 960): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
