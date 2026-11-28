// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.data.DiaryBook
import io.github.beesan.lfg.data.DiaryPage
import io.github.beesan.lfg.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateStyle
import java.util.Date
import java.util.Locale

/** Dates, a day's telling and what the reader wrote are set in a serif, as on paper; the rest is the app's own. */
private val Paper = FontFamily.Serif
private val OnPicture = Color.White
private val OnPictureDim = Color.White.copy(alpha = 0.88f)
private val PictureShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 8f)

private fun month(month: YearMonth) = "${month.month.getDisplayName(DateStyle.FULL, Locale.ENGLISH)} ${month.year}"
private fun weekday(day: LocalDate) = day.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.ENGLISH)
private fun short(day: LocalDate) = "${day.dayOfMonth} ${day.month.getDisplayName(DateStyle.SHORT, Locale.ENGLISH)}"
private fun days(n: Int) = if (n == 1) "1 day" else "$n days"
private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"

/** L1 and R1 for a page that steps: the day or the month before, and after. */
private fun Modifier.shoulders(onStep: (Int) -> Unit) = onKeyEvent { event ->
    val by = when (event.key) {
        Key.ButtonL1 -> -1
        Key.ButtonR1 -> 1
        else -> return@onKeyEvent false
    }
    if (event.type == KeyEventType.KeyUp) onStep(by)
    true
}

// ── the shelf ────────────────────────────────────────────────────────────────

/** The diary's first page: a book for each game, the one read last first and pulled out a little. */
@Composable
fun DiaryShelfScreen(state: DiaryModel.State, onBook: (String) -> Unit, onCalendar: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    val books = state.books
    var picked by rememberSaveable(books.firstOrNull()?.gameId) { mutableStateOf(books.firstOrNull()?.gameId.orEmpty()) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        PageHeader("Diary") {
            if (books.isNotEmpty()) LfgButton("Calendar", onCalendar)
            LfgButton("Settings", onSettings)
            LfgButton("Back", onBack, key = "B")
        }
        HorizontalDivider(color = Lfg.Border)
        if (books.isEmpty()) {
            Text(
                if (state.loaded) "Nothing here yet. Each day you play a game gets a page, with a picture of it." else "Opening the diary…",
                color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp),
            )
            return
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).horizontalScroll(scroll).firstFocus().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom,
        ) {
            books.forEachIndexed { i, book -> Spine(book, i, book.gameId == picked, { picked = book.gameId }) { onBook(book.gameId) } }
        }
        // The shelf the books stand on.
        Box(Modifier.fillMaxWidth().height(3.dp).background(Lfg.BorderStrong))
        val book = books.firstOrNull { it.gameId == picked } ?: books.first()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(book.title, color = Lfg.Text, fontSize = 20.sp, fontFamily = Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val written = book.pages.sumOf { it.entries.size }
                Text(
                    listOfNotNull(days(book.pages.size), entries(written).takeIf { written > 0 }, "last played ${io.github.beesan.lfg.core.WidgetDay.date(book.last)}").joinToString(" · "),
                    color = Lfg.Dim, fontSize = 14.sp,
                )
            }
            LfgButton("Open", { onBook(book.gameId) }, primary = true)
        }
    }
}

private val SPINES = listOf(0xFF4A2B3A, 0xFF2B3A4A, 0xFF34452E, 0xFF4A3F2B, 0xFF2B4544, 0xFF40304D).map { Color(it) }

/** A book's spine, its title down it; the picked one stands a little proud, with a ribbon. */
@Composable
private fun Spine(book: DiaryBook, index: Int, picked: Boolean, onPicked: () -> Unit, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    // Thicker for a game with more days in it.
    val width = (40 + minOf(book.pages.size, 30)).dp
    Box(
        Modifier.padding(top = 18.dp).offset(y = if (picked) (-12).dp else 0.dp).width(width).fillMaxHeight(if (index % 3 == 1) 0.84f else 0.92f)
            .onFocusChanged { if (it.isFocused) onPicked() }.focusRing(shape).clip(shape).background(SPINES[index % SPINES.size])
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape).clickable(onClick = onOpen),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (picked) Box(Modifier.align(Alignment.TopEnd).padding(end = 7.dp).width(7.dp).height(30.dp).background(Lfg.Accent))
        val japanese = book.title.any { it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF }
        if (japanese) {
            // Upright, a letter a line, as a Japanese book's spine is set.
            val shown = if (book.title.length > 11) book.title.take(10) + "︙" else book.title
            Text(
                shown.replace('ー', '｜').toList().joinToString("\n"), color = OnPicture, fontFamily = Paper, fontSize = 15.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 40.dp),
            )
        } else {
            Text(
                book.title, color = OnPicture, fontFamily = Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 40.dp, bottom = 10.dp).sideways().rotate(90f),
            )
        }
    }
}

/** Lays a line of text out down the page rather than across it; `rotate(90f)` after it turns the letters. */
private fun Modifier.sideways() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2)
    }
}

// ── the month ────────────────────────────────────────────────────────────────

/** A month of a game, or of every game: each day read is its picture, and the side tells the day picked. */
@Composable
fun DiaryMonthScreen(state: DiaryModel.State, today: LocalDate, onMonth: (Int) -> Unit, onOpen: (DiaryPage) -> Unit, onBack: () -> Unit) {
    val month = state.month
    // The day read most on each date, when two games were.
    val pages = remember(state.books, state.gameId, month) { state.pages.filter { YearMonth.from(it.day) == month }.associateBy { it.day } }
    var picked by remember(month, state.gameId, pages.size) { mutableStateOf(pages.keys.maxOrNull()) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padScroll(scroll).shoulders(onMonth)) {
        PageHeader(month(month)) {
            LfgButton(month.minusMonths(1).month.getDisplayName(DateStyle.SHORT, Locale.ENGLISH), { onMonth(-1) }, key = "L1")
            LfgButton(month.plusMonths(1).month.getDisplayName(DateStyle.SHORT, Locale.ENGLISH), { onMonth(1) }, key = "R1", enabled = month < YearMonth.from(today))
            LfgButton("Back", onBack, key = "B")
        }
        HorizontalDivider(color = Lfg.Border)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
            val grid = @Composable { modifier: Modifier -> MonthGrid(month, pages, picked, today, { picked = it }, onOpen, modifier) }
            val side = @Composable { modifier: Modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.title, color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${days(pages.size)} of ${month.lengthOfMonth()}", color = Lfg.Dim, fontSize = 14.sp)
                    val page = picked?.let(pages::get)
                    if (page != null) {
                        Text("${weekday(page.day)} ${page.day.dayOfMonth}", color = Lfg.Text, fontFamily = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        if (state.gameId == null) Text(page.gameName, color = Lfg.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (page.title.isNotBlank()) Text(page.title, color = Lfg.Text, fontFamily = Paper, fontSize = 15.sp)
                        if (page.lines > 0) Text(DiaryModel.stats(page), color = Lfg.Dim, fontSize = 13.sp)
                        if (page.entries.isNotEmpty()) Text(entries(page.entries.size), color = Lfg.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        LfgButton("Open", { onOpen(page) }, primary = true, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text("Nothing was read this month.", color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (maxWidth < NARROW) {
                Column(Modifier.verticalScroll(scroll).firstFocus(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    grid(Modifier.fillMaxWidth().height(300.dp))
                    side(Modifier.fillMaxWidth().padding(bottom = 16.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    grid(Modifier.weight(1f).fillMaxHeight().firstFocus())
                    side(Modifier.width(190.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth, pages: Map<LocalDate, DiaryPage>, picked: LocalDate?, today: LocalDate, onPicked: (LocalDate) -> Unit, onOpen: (DiaryPage) -> Unit, modifier: Modifier,
) {
    val first = month.atDay(1)
    // Weeks start on Monday.
    val lead = first.dayOfWeek.value - 1
    val weeks = (lead + month.lengthOfMonth() + 6) / 7
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (name in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) Text(name, color = Lfg.Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in 0 until 7) {
                    val number = week * 7 + column - lead + 1
                    if (number < 1 || number > month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val day = month.atDay(number)
                    val page = pages[day]
                    val shape = RoundedCornerShape(4.dp)
                    if (page == null) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(shape).background(Lfg.Surface)) {
                            Text("$number", color = if (day == today) Lfg.Accent else Lfg.Dim, fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp, top = 2.dp))
                        }
                        continue
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().onFocusChanged { if (it.isFocused) onPicked(day) }.focusRing(shape, inside = true).clip(shape)
                            .background(Lfg.Soft).then(if (day == picked) Modifier.border(2.dp, Lfg.Accent, shape) else Modifier).clickable { onOpen(page) },
                    ) {
                        page.picture?.let { Picture(it, Modifier.fillMaxSize(), width = 240) }
                        Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                        Text("$number", color = OnPicture, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(shadow = PictureShadow), modifier = Modifier.padding(start = 5.dp, top = 2.dp))
                        if (page.entries.isNotEmpty()) {
                            // How many entries were written that day.
                            Text(
                                "${page.entries.size}", color = Lfg.OnFill, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).background(Lfg.Fill, RoundedCornerShape(7.dp)).padding(horizontal = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── a day ────────────────────────────────────────────────────────────────────

/**
 * A day of a game. Told by the model, it is a spread: the telling on the
 * left, the day's pictures on the right. Untold, it is its picture with the
 * date and what was read on it. Either way, the entries written that day
 * follow, and a new one can be started.
 */
@Composable
fun DiaryDayScreen(
    page: DiaryPage,
    today: LocalDate,
    before: DiaryPage?,
    after: DiaryPage?,
    onStep: (Int) -> Unit,
    onEntry: (Note) -> Unit,
    onWrite: () -> Unit,
    onBack: () -> Unit,
) {
    val left = rememberScrollState()
    val right = rememberScrollState()
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padScroll(right).shoulders(onStep)) {
        PageHeader(page.gameName) {
            if (before != null) LfgButton(short(before.day), { onStep(-1) }, key = "L1")
            if (after != null) LfgButton(short(after.day), { onStep(1) }, key = "R1")
            LfgButton("Back", onBack, key = "B")
        }
        HorizontalDivider(color = Lfg.Border)
        val sub = listOfNotNull(weekday(page.day), "day ${page.number}", "today".takeIf { page.day == today }).joinToString(" · ")
        val stats = if (page.lines > 0) DiaryModel.stats(page) + if (page.day == today) " so far" else "" else ""
        val written = @Composable {
            for (entry in page.entries) EntryText(entry, clock) { onEntry(entry) }
            if (page.entries.isEmpty()) Text("No entry", color = Lfg.Dim, fontSize = 14.sp)
            LfgButton(if (page.entries.isEmpty()) "Write an entry" else "Write another", onWrite)
        }
        val quote = @Composable {
            if (page.lineText.isNotBlank()) {
                Text(io.github.beesan.lfg.core.Sentence.quoted(page.lineText), color = Lfg.Text, fontFamily = Paper, fontSize = 15.sp, lineHeight = 24.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (page.lineAt > 0) Text(clock.format(Date(page.lineAt)), color = Lfg.Dim, fontSize = 13.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp)) {
            when {
                maxWidth < NARROW -> Column(Modifier.verticalScroll(right).firstFocus().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateBlock(page.day, sub, Lfg.Text, Lfg.Dim)
                    if (page.title.isNotBlank()) Text(page.title, color = Lfg.Text, fontFamily = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    page.picture?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) }
                    if (page.summary.isNotBlank()) Text(page.summary, color = Lfg.Text, fontSize = 15.sp, lineHeight = 23.sp)
                    quote()
                    if (stats.isNotEmpty()) Text(stats, color = Lfg.Dim, fontSize = 13.sp)
                    written()
                }
                page.written -> Row(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Lfg.Surface).border(1.dp, Lfg.Border, RoundedCornerShape(8.dp)),
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(left).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DateBlock(page.day, sub, Lfg.Text, Lfg.Dim)
                        if (page.title.isNotBlank()) Text(page.title, color = Lfg.Text, fontFamily = Paper, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text(page.summary, color = Lfg.Text, fontSize = 15.sp, lineHeight = 23.sp)
                        if (stats.isNotEmpty()) Text(stats, color = Lfg.Dim, fontSize = 13.sp)
                    }
                    // The fold between the two pages.
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Lfg.Border))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(right).firstFocus().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        page.picture?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp))) }
                        if (page.more.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (path in page.more) Picture(path, Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp)), width = 480)
                                if (page.more.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        quote()
                        written()
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PostcardBox(page.picture, Modifier.weight(2.6f)) {
                        DateBlock(page.day, sub, OnPicture, OnPictureDim, shadow = true)
                        if (stats.isNotEmpty()) Text(stats, color = OnPictureDim, fontSize = 13.sp, style = TextStyle(shadow = PictureShadow))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(right).firstFocus().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { written() }
                }
            }
        }
    }
}

/** The day large, with the month and year, and the weekday under them. */
@Composable
private fun DateBlock(day: LocalDate, sub: String, colour: Color, dim: Color, shadow: Boolean = false) {
    val style = if (shadow) TextStyle(shadow = PictureShadow) else TextStyle.Default
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${day.dayOfMonth}", color = colour, fontFamily = Paper, fontSize = 52.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, style = style)
        Column {
            Text(month(YearMonth.from(day)), color = colour, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = style)
            Text(sub, color = dim, fontSize = 13.sp, style = style)
        }
    }
}

/** An entry on its day: under a rule, as written, and opened with a press. */
@Composable
private fun EntryText(entry: Note, clock: SimpleDateFormat, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onOpen)) {
        HorizontalDivider(color = Lfg.Border)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title.ifBlank { "Your entry" }, color = if (entry.title.isBlank()) Lfg.Dim else Lfg.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(clock.format(Date(entry.createdAt)), color = Lfg.Dim, fontSize = 13.sp)
        }
        if (entry.text.isNotBlank()) {
            Text(entry.text, color = Lfg.Text, fontFamily = Paper, fontSize = 16.sp, lineHeight = 26.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        }
    }
}

/**
 * A picture with words on it, like a postcard: dark at the top where the
 * words go, away from the game's own text box, a thin white line round it,
 * and "learn from games" faint in the corner.
 */
@Composable
internal fun PostcardBox(picture: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.aspectRatio(16f / 9f).clip(shape).background(Lfg.Surface)) {
        picture?.let { Picture(it, Modifier.fillMaxSize(), width = 1280) }
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.66f)
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.78f), 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent)),
        )
        Box(Modifier.fillMaxSize().padding(8.dp).border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
        Text(Postcard.WATERMARK, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, letterSpacing = 0.4.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 26.dp))
        Column(Modifier.padding(start = 28.dp, top = 24.dp, end = 118.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

// ── an entry ─────────────────────────────────────────────────────────────────

/** An entry, read: its picture with what was written on it, and when and where it was written beside it. */
@Composable
fun DiaryEntryScreen(entry: Note, page: DiaryPage?, onShare: () -> Unit, onEdit: () -> Unit, onBack: () -> Unit) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val day = remember(entry.id, entry.createdAt) { io.github.beesan.lfg.data.Diary.dayOf(entry, java.time.ZoneId.systemDefault()) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padScroll(scroll)) {
        PageHeader("Diary entry") {
            LfgButton("Share…", onShare, primary = true)
            LfgButton("Edit", onEdit)
            LfgButton("Back", onBack, key = "B")
        }
        HorizontalDivider(color = Lfg.Border)
        val card = @Composable { modifier: Modifier ->
            PostcardBox(entry.picturePath?.takeIf { java.io.File(it).isFile } ?: page?.picture, modifier) {
                Text(
                    entry.text.ifBlank { entry.title }, color = OnPicture, fontFamily = Paper, fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = PictureShadow),
                )
            }
        }
        val about = @Composable {
            Text("${day.dayOfMonth}", color = Lfg.Text, fontFamily = Paper, fontSize = 52.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold)
            Text(month(YearMonth.from(day)), color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${weekday(day)}, ${clock.format(Date(entry.createdAt))}", color = Lfg.Dim, fontSize = 13.sp)
            if (entry.gameName.isNotBlank()) Text(entry.gameName, color = Lfg.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            if (entry.title.isNotBlank()) Text(entry.title, color = Lfg.Text, fontFamily = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).firstFocus().padding(top = 14.dp)) {
            if (maxWidth < NARROW) {
                Column(Modifier.verticalScroll(scroll).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    card(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    about()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    card(Modifier.weight(2.6f))
                    Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) { about() }
                }
            }
        }
    }
}

// ── writing one ──────────────────────────────────────────────────────────────

/**
 * An entry being written, as an open book: the moment on the left page (its
 * picture, the line, and a step to the picture before or after), a title and
 * the entry on the right. Share saves it first.
 */
@Composable
fun DiaryWriteScreen(
    draft: DiaryModel.Draft,
    onPicture: (earlier: Boolean) -> Unit,
    onSave: (title: String, text: String) -> Unit,
    onShare: (title: String, text: String) -> Unit,
    onCancel: () -> Unit,
) {
    var title by rememberSaveable(draft.serial) { mutableStateOf(draft.title) }
    var text by rememberSaveable(draft.serial) { mutableStateOf(draft.text) }
    val when_ = remember { SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.ENGLISH) }
    val left = rememberScrollState()
    val right = rememberScrollState()
    val filled = text.isNotBlank() || title.isNotBlank()
    Column(Modifier.fillMaxSize().padScroll(right)) {
        PageHeader("Diary entry") {
            LfgButton("Share…", { onShare(title, text) }, enabled = filled)
            LfgButton("Cancel", onCancel, key = "B")
            LfgButton("Save", { onSave(title, text) }, primary = true, enabled = filled)
        }
        HorizontalDivider(color = Lfg.Border)
        val moment = @Composable {
            draft.picture?.let { Picture(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp))) }
            val line = draft.line
            if (line != null) Text(io.github.beesan.lfg.core.Sentence.quoted(line.lineText), color = Lfg.Text, fontFamily = Paper, fontSize = 16.sp, lineHeight = 25.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(draft.gameName.takeIf { it.isNotBlank() }, when_.format(Date(draft.at.takeIf { it > 0 } ?: System.currentTimeMillis()))).joinToString(" · "),
                color = Lfg.Dim, fontSize = 13.sp,
            )
        }
        val pictures = @Composable {
            if (draft.line != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LfgButton("Earlier picture", { onPicture(true) }, compact = true, modifier = Modifier.heightIn(min = 44.dp))
                    LfgButton("Later picture", { onPicture(false) }, compact = true, modifier = Modifier.heightIn(min = 44.dp))
                }
            }
        }
        val fields = @Composable { entryModifier: Modifier ->
            Label("Title")
            Field(title, { title = it }, singleLine = true, serif = true)
            Label("Entry")
            Field(text, { text = it }, singleLine = false, ruled = true, modifier = entryModifier)
        }
        val shape = RoundedCornerShape(8.dp)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
            if (maxWidth < NARROW) {
                Column(Modifier.verticalScroll(right).firstFocus().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    moment()
                    pictures()
                    fields(Modifier.heightIn(min = 150.dp))
                }
            } else {
                Row(Modifier.fillMaxSize().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape)) {
                    Column(Modifier.weight(0.75f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(left), verticalArrangement = Arrangement.spacedBy(8.dp)) { moment() }
                        pictures()
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Lfg.Border))
                    Column(Modifier.weight(1f).fillMaxHeight().firstFocus().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fields(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

/** A text field; [ruled] draws a line under each line of writing, as a diary's page has. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, singleLine: Boolean, modifier: Modifier = Modifier, serif: Boolean = false, ruled: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    val rule = Lfg.Border
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = Lfg.Text, fontSize = if (serif) 18.sp else 16.sp, lineHeight = 26.sp, fontFamily = if (serif) Paper else null, fontWeight = if (serif) FontWeight.SemiBold else null),
        cursorBrush = SolidColor(Lfg.Accent),
        modifier = modifier.fillMaxWidth().padField(shape, singleLine = singleLine).clip(shape).background(Lfg.Sunken).border(1.dp, Lfg.BorderStrong, shape)
            .padding(horizontal = 12.dp, vertical = if (ruled) 6.dp else 10.dp)
            .then(
                if (!ruled) Modifier else Modifier.drawBehind {
                    val step = 26.sp.toPx()
                    var y = step
                    while (y < size.height) {
                        drawLine(rule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        y += step
                    }
                },
            ),
    )
}

// ── sharing one ──────────────────────────────────────────────────────────────

/** What of an entry goes on the picture that is shared. */
data class ShareChoice(val entry: Boolean = true, val meta: Boolean = true, val line: Boolean = false, val translation: Boolean = false)

/** The words of [entry] that [choice] puts on its picture. */
fun shareWords(entry: Note, sharing: DiaryModel.Sharing, choice: ShareChoice): Postcard.Words {
    val day = io.github.beesan.lfg.data.Diary.dayOf(entry, java.time.ZoneId.systemDefault())
    return Postcard.Words(
        line = if (choice.line) sharing.line?.lineText ?: entry.lineText else "",
        translation = if (choice.translation) sharing.line?.translation.orEmpty() else "",
        entry = if (choice.entry) entry.text.ifBlank { entry.title } else "",
        meta = if (choice.meta) listOfNotNull(entry.gameName.takeIf { it.isNotBlank() }, io.github.beesan.lfg.core.WidgetDay.date(day)).joinToString(" · ") else "",
    )
}

/** An entry about to be shared: the picture as it will go, and what is written on it. */
@Composable
fun DiaryShareScreen(
    entry: Note,
    picture: String?,
    sharing: DiaryModel.Sharing,
    prefs: Prefs,
    onTranslate: () -> Unit,
    onShare: (ShareChoice) -> Unit,
    onBack: () -> Unit,
) {
    var mine by rememberSaveable(entry.id) { mutableStateOf(true) }
    var meta by rememberSaveable(entry.id) { mutableStateOf(true) }
    var line by rememberSaveable(entry.id) { mutableStateOf(false) }
    var translation by rememberSaveable(entry.id) { mutableStateOf(false) }
    val choice = ShareChoice(mine, meta, line, translation)
    val words = shareWords(entry, sharing, choice)
    val source by produceState<Bitmap?>(null, picture) { value = withContext(Dispatchers.IO) { Postcard.decode(picture) } }
    val preview by produceState<Bitmap?>(null, source, words) { value = withContext(Dispatchers.Default) { Postcard.render(source, words) } }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padScroll(scroll)) {
        PageHeader("Share an entry") {
            LfgButton("Share…", { onShare(choice) }, primary = true)
            LfgButton("Back", onBack, key = "B")
        }
        HorizontalDivider(color = Lfg.Border)
        val card = @Composable { modifier: Modifier ->
            Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Lfg.Surface)) {
                preview?.let { Image(it.asImageBitmap(), contentDescription = "The picture as it will be shared", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        val choices = @Composable {
            Text("Written on the picture", color = Lfg.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Tick("My entry", mine) { mine = it }
            Tick("Game and date", meta) { meta = it }
            val hasLine = (sharing.line?.lineText ?: entry.lineText).isNotBlank()
            if (hasLine) Tick("The line", line) { line = it }
            // Only when there is one to write, or a model to ask for it.
            if (hasLine && sharing.line != null && (sharing.line.translation != null || prefs.aiReady)) {
                Tick("Its translation", translation) {
                    translation = it
                    if (it) onTranslate()
                }
                if (sharing.translating) Text("Translating…", color = Lfg.Dim, fontSize = 13.sp)
                sharing.error?.let { Text(it, color = Lfg.Warn, fontSize = 13.sp) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).firstFocus().padding(top = 14.dp)) {
            if (maxWidth < NARROW) {
                Column(Modifier.verticalScroll(scroll).padding(bottom = 20.dp)) {
                    card(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    choices()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    card(Modifier.weight(1.5f))
                    Column(Modifier.weight(1f).verticalScroll(scroll)) { choices() }
                }
            }
        }
    }
}

@Composable
private fun Tick(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRing(RoundedCornerShape(6.dp)).clickable { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Lfg.Accent, uncheckedColor = Lfg.Dim, checkmarkColor = Lfg.OnFill), modifier = Modifier.padding(end = 10.dp))
        Text(label, color = Lfg.Text, fontSize = 15.sp)
    }
}

// ── settings ─────────────────────────────────────────────────────────────────

@Composable
fun DiarySettingsScreen(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit, onBack: () -> Unit) = Page("Diary", onBack) {
    Group("Diary") {
        Card("Write each day", if (prefs.aiReady) "A title and a few lines per day played" else "Needs an OpenRouter key, in Translation") {
            Choice(listOf(Prefs.DIARY_WITH_RECAP to "With a recap", Prefs.DIARY_EACH_DAY to "After each day"), prefs.diaryWrite) { v -> onChange { it.copy(diaryWrite = v) } }
        }
    }
}

// ── pictures ─────────────────────────────────────────────────────────────────

/** A picture from a file, decoded near the size it shows at; nothing when the file is gone. */
@Composable
fun Picture(path: String, modifier: Modifier = Modifier, width: Int = 960) {
    val bitmap = remember(path) { decodeSmall(path, width)?.asImageBitmap() } ?: return
    Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

private fun decodeSmall(path: String, width: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
