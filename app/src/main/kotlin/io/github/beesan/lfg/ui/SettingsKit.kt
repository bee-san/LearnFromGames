// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser

/*
 * The pieces Settings' pages are built from: rows gathered into groups with a
 * short heading, a switch, a row of choices, and − and + round a value. Each
 * takes the pad's focus and clicks as the app's buttons do.
 */

/** Inside a [Group]: a row then draws no box of its own, and the group draws a line between rows. */
internal val LocalInGroup = compositionLocalOf { false }

/**
 * Rows that belong together, in one rounded box with a line between each,
 * under a short [title]. Put [Card]s, setting fields or [Plain] in it.
 */
@Composable
internal fun Group(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                title.uppercase(), color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 6.dp).semantics { heading() },
            )
        }
        val shape = RoundedCornerShape(18.dp)
        // The rows sit 1 dp apart on the border's colour: that gap is the line between them.
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Lfg.Border).padding(1.dp).clip(RoundedCornerShape(17.dp)),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            CompositionLocalProvider(LocalInGroup provides true) { content() }
        }
    }
}

/** Anything else in a [Group], on the rows' own ground. */
@Composable
internal fun Plain(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Lfg.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

/** A short line under a group: a word on what it does, never a paragraph. */
@Composable
internal fun Hint(text: String, color: Color = Lfg.Dim) =
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 6.dp))

/** On or off, as a switch. */
@Composable
internal fun Toggle(on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val knob by animateDpAsState(if (on) 20.dp else 0.dp, label = "knob")
    Box(
        Modifier.focusRing(shape).size(width = 52.dp, height = 32.dp).clip(shape)
            .background(if (!enabled) Lfg.Soft else if (on) Lfg.Fill else Lfg.BorderStrong)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(4.dp),
    ) {
        Box(
            Modifier.offset(x = knob).size(24.dp).clip(CircleShape)
                .background(if (on) Lfg.OnFill else if (Lfg.palette.dark) Lfg.Text else Lfg.Surface),
        )
    }
}

/** One of a few named options, side by side in a well; the current one is filled. Wraps when narrow. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun Segmented(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    val outer = RoundedCornerShape(14.dp)
    val inner = RoundedCornerShape(11.dp)
    // Not clipped: the pad's ring round an option is drawn a little outside it.
    FlowRow(
        Modifier.background(Lfg.Sunken, outer).border(1.dp, Lfg.Border, outer).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == current
            Box(
                Modifier.focusRing(inner).clip(inner).background(if (on) Lfg.Fill else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(value) })
                    .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Lfg.OnFill else Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** − and + round [label], in a well. */
@Composable
internal fun StepperPill(label: String, onLess: () -> Unit, onMore: () -> Unit) {
    val outer = RoundedCornerShape(14.dp)
    Row(
        Modifier.background(Lfg.Sunken, outer).border(1.dp, Lfg.Border, outer).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepKey("−", "Less", onLess)
        Text(
            label, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.widthIn(min = 84.dp).padding(horizontal = 8.dp),
        )
        StepKey("+", "More", onMore)
    }
}

@Composable
private fun StepKey(text: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.focusRing(shape).size(40.dp).clip(shape).background(Lfg.Surface).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Lfg.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
}

/** A small rounded square in [tone] with an icon in it: strokes on a 24-unit square. */
@Composable
internal fun IconTile(path: String, tone: Color, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tone.copy(alpha = if (Lfg.palette.dark) 0.18f else 0.13f)),
        contentAlignment = Alignment.Center,
    ) { StrokeIcon(path, tone, size * 0.55f) }
}

@Composable
internal fun StrokeIcon(path: String, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(settingIconPath(path), tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The icons' paths, parsed once each. Main thread only, as drawing is. */
private val settingIconPaths = HashMap<String, Path>()

private fun settingIconPath(data: String): Path = settingIconPaths.getOrPut(data) { PathParser.createPathFromPathData(data).asComposePath() }

/** A page of Settings as its list shows it: a name, a few words, an icon and its colour. */
internal class SettingsEntry(val page: Page, val name: String, val hint: String, val icon: String)

internal class SettingsGroup(val title: String, val tone: () -> Color, val entries: List<SettingsEntry>)

/** Settings' pages in their groups, in the order the list shows them. */
internal val SETTINGS_GROUPS = listOf(
    SettingsGroup(
        "Learning", { Lfg.Accent },
        listOf(
            SettingsEntry(Page.LANGUAGE, "Language", "What you are learning", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"),
            SettingsEntry(Page.PROFILES, "Profiles", "One setup per language", "M9 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8M2 21c0-4 3-6 7-6s7 2 7 6M16 3.5a4 4 0 0 1 0 7M22 21c0-3-1.5-5-4-5.7"),
            SettingsEntry(Page.DICTIONARIES, "Dictionaries", "Order, add, update", "M3 5c3-1 6-1 9 1c3-2 6-2 9-1v14c-3-1-6-1-9 1c-3-2-6-2-9-1zM12 6v14"),
            SettingsEntry(Page.ANKI, "Anki", "Where cards go", "M4 7h12v13H4zM8 7V4h12v13h-4"),
            SettingsEntry(Page.CHARACTER_NAMES, "Character names", "Casts from VNDB", "M12 12a4 4 0 1 0 0-8a4 4 0 0 0 0 8M5 21a7 7 0 0 1 14 0"),
        ),
    ),
    SettingsGroup(
        "Playing", { Lfg.RingChars },
        listOf(
            SettingsEntry(Page.CONTROLS, "Controls", "Buttons and lookup", "M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6l-.5-.6h-5l-.5.6A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4M7 11v3M5.5 12.5h3M16 11h.01M18 13h.01"),
            SettingsEntry(Page.WHEEL, "Wheel", "Tiles, opening, pointing", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6M12 3v6M12 15v6M3 12h6M15 12h6"),
            SettingsEntry(Page.GAMES, "Games and apps", "Rename, when to read", "M3 5h18v13H3zM10 9l5 2.5-5 2.5zM8 21h8"),
            SettingsEntry(Page.GAME_NAMES, "Game names", "Which game is running", "M3 12V4h8l10 10-8 8zM7.5 8h.01"),
            SettingsEntry(Page.APPS, "Other apps", "Browsers, chat, ebooks", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"),
            SettingsEntry(Page.RECOGNITION, "Text recognition", "Reads the screen", "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 10h8M8 14h5"),
        ),
    ),
    SettingsGroup(
        "Cards and AI", { Lfg.RingCards },
        listOf(
            SettingsEntry(Page.MEDIA, "Sound and pictures", "Audio and images on cards", "M4 5h16v14H4zM4 15l5-5 4 4 3-3 4 4M15.5 9h.01"),
            SettingsEntry(Page.TRANSLATION, "Translation", "Translate, explain, ask", "M4 5h8M8 3v2M6 5c0 4 3 7 6 8M10 5c-1 4-4 7-7 8M13 21l4-9 4 9M14.5 18h5"),
            SettingsEntry(Page.DIARY_SETTINGS, "Diary", "A short entry a day", "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11M9 8h6"),
        ),
    ),
    SettingsGroup(
        "App", { Lfg.Ok },
        listOf(
            SettingsEntry(Page.APPEARANCE, "Appearance", "Theme, colours, sounds", "M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2s-1-2 0-3 2-1 3-1a4 4 0 0 0 4-4c0-4.5-4-8-9-8M7.5 11.5h.01M10 7.5h.01M15 7.5h.01"),
            SettingsEntry(Page.BACKUP, "Backup", "Save and restore", "M7 18a5 5 0 0 1-.5-10A6 6 0 0 1 18 9a4.5 4.5 0 0 1-1 9M12 12v8M9 15l3-3 3 3"),
            SettingsEntry(Page.STORAGE, "Storage", "Space used", "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"),
        ),
    ),
    SettingsGroup(
        "Help", { Lfg.Warn },
        listOf(
            SettingsEntry(Page.PRACTICE, "Tutorial", "Practise without a game", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M10 8.5l5 3.5-5 3.5z"),
            SettingsEntry(Page.DIALOGUE, "Help improve LFG", "Share dialogue screens", "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10"),
            SettingsEntry(Page.HELP, "Help and diagnostics", "A report for bugs", "M3 12h4l2-5 4 10 2-5h6"),
        ),
    ),
)

/** A row of Settings' list: its icon, name and a few words, and an arrow to its page. */
@Composable
internal fun SettingsEntryRow(entry: SettingsEntry, tone: Color, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusRing(RoundedCornerShape(0.dp), inside = true).background(Lfg.Surface).clickable(onClick = onOpen)
            .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(entry.icon, tone)
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.hint, color = Lfg.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StrokeIcon("M9 6l6 6-6 6", Lfg.Dim, 18.dp)
    }
}

/** A language to pick: its own name, and in English under it when that differs. */
@Composable
internal fun LanguageChip(title: String, own: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.focusRing(shape).clip(shape).background(if (on) Lfg.Fill else Lfg.Sunken).border(1.dp, if (on) Lfg.Fill else Lfg.Border, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .widthIn(min = 96.dp).padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(own, color = if (on) Lfg.OnFill else Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (own != title) Text(title, color = if (on) Lfg.OnFill else Lfg.Dim, fontSize = 12.sp, maxLines = 1)
    }
}

/** A button of the pad and what it does, as a small key and a word. */
@Composable
internal fun KeyChip(key: String, does: String) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.background(Lfg.Sunken, shape).border(1.dp, Lfg.Border, shape).heightIn(min = 40.dp).padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(14.dp)).background(Lfg.Fill).padding(horizontal = 9.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(key, color = Lfg.OnFill, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Text(does, color = Lfg.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Light, dark or the system's, drawn as a small picture of the app in [left] and [right]. */
@Composable
internal fun ThemePick(label: String, left: io.github.beesan.lfg.core.Palette, right: io.github.beesan.lfg.core.Palette, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.focusRing(shape).clip(shape).background(Lfg.Surface).border(2.dp, if (on) Lfg.Accent else Lfg.Border, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Lfg.Border, RoundedCornerShape(10.dp))) {
            for (p in listOf(left, right)) {
                Column(
                    Modifier.weight(1f).heightIn(min = 72.dp).background(Color(p.bg)).padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(2) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 14.dp).clip(RoundedCornerShape(4.dp)).background(Color(p.surface)).border(1.dp, Color(p.border), RoundedCornerShape(4.dp)))
                    }
                    Box(Modifier.fillMaxWidth(0.7f).heightIn(min = 10.dp).clip(RoundedCornerShape(5.dp)).background(Color(p.fill)))
                }
            }
        }
        Text(label, color = Lfg.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth())
    }
}

/** A colour scheme to pick: a round swatch of its colour, ticked when chosen, and its name. */
@Composable
internal fun Swatch(label: String, color: Color, onColor: Color, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.focusRing(shape).clip(shape).background(if (on) Lfg.Soft else Color.Transparent)
            .border(2.dp, if (on) Lfg.Accent else Color.Transparent, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            if (on) StrokeIcon("M5 12l5 5 9-10", onColor, 20.dp)
        }
        Text(label, color = Lfg.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A row's name and words, with its controls beside them when they fit in about
 * half the width, and under them when they do not: a switch stays on the right
 * of a narrow screen, where a row of buttons goes underneath.
 */
@Composable
internal fun RowOrStack(texts: @Composable () -> Unit, trailing: @Composable () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.ui.layout.Layout(contents = listOf(texts, trailing), modifier = modifier) { (textPart, trailingPart), constraints ->
        val gap = 14.dp.roundToPx()
        val width = constraints.maxWidth
        val loose = androidx.compose.ui.unit.Constraints(maxWidth = width)
        val trail = trailingPart.map { it.measure(loose) }
        val trailWidth = trail.maxOfOrNull { it.width } ?: 0
        val trailHeight = trail.maxOfOrNull { it.height } ?: 0
        val side = trailWidth == 0 || trailWidth <= width * 0.55f
        if (side) {
            val words = textPart.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = (width - trailWidth - (if (trailWidth > 0) gap else 0)).coerceAtLeast(0))) }
            val wordsHeight = words.maxOfOrNull { it.height } ?: 0
            val height = maxOf(wordsHeight, trailHeight, constraints.minHeight)
            layout(width, height) {
                words.forEach { it.placeRelative(0, (height - wordsHeight) / 2) }
                trail.forEach { it.placeRelative(width - trailWidth, (height - trailHeight) / 2) }
            }
        } else {
            val words = textPart.map { it.measure(loose) }
            val wordsHeight = words.maxOfOrNull { it.height } ?: 0
            val between = 12.dp.roundToPx()
            val height = maxOf(wordsHeight + between + trailHeight, constraints.minHeight)
            layout(width, height) {
                words.forEach { it.placeRelative(0, 0) }
                trail.forEach { it.placeRelative(0, wordsHeight + between) }
            }
        }
    }
}
