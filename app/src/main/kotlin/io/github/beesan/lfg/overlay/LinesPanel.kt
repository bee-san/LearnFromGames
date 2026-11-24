// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.LookupState
import io.github.beesan.lfg.overlay.OverlayHost.RecapOf
import io.github.beesan.lfg.overlay.OverlayHost.SheetTab
import io.github.beesan.lfg.ui.EntryLink
import io.github.beesan.lfg.ui.EntryList
import io.github.beesan.lfg.ui.EntryScroller
import io.github.beesan.lfg.ui.Lfg
import io.github.beesan.lfg.ui.LfgButton
import io.github.beesan.lfg.ui.LineItem
import io.github.beesan.lfg.ui.LineRowUi
import io.github.beesan.lfg.ui.NoteItem
import java.text.SimpleDateFormat
import java.util.Locale

/** How a piece of text under the lines is coloured: an answer, a hint or what is being done, or what went wrong. */
enum class Tone { TEXT, DIM, ACCENT }

/** What the sheet under the lines shows: a word's entry, a line's translation or explanation, or the game's recap. */
sealed interface PanelSheet {
    data class Entry(val heading: String, val message: String?) : PanelSheet

    /** [again] is the button that asks once more, or null for none; [key] changes for another line or tab, which starts at the top. */
    data class Answer(val tab: SheetTab, val text: String, val tone: Tone, val again: String?, val againEnabled: Boolean, val key: String) : PanelSheet

    data class Recap(val choices: List<RecapChoice>, val text: String, val tone: Tone) : PanelSheet
}

data class RecapChoice(val what: RecapOf, val label: String, val chosen: Boolean, val enabled: Boolean)

/** Everything the lines' screen shows, as [OverlayHost] has it now. */
@Immutable
data class PanelUi(
    val title: String = "LearnFromGames",
    val stats: String = "",
    val rows: List<LineRowUi> = emptyList(),
    /** What shows while there are no rows. */
    val empty: String = "",
    val sheet: PanelSheet? = null,
    val lookup: LookupState = LookupState(),
    /** A box is being drawn: Crop is Cancel. */
    val picking: Boolean = false,
    /** The foot of the screen: the controller's buttons in lookup mode, else a tip. */
    val foot: String = "",
    /** The row the sheet speaks of; while there is one, new lines do not pull the list to its end. */
    val lit: String? = null,
    /** Bumped when a new line arrived and the list goes to its end. */
    val endSignal: Int = 0,
    /** Bumped when [keep] is to be brought into sight, just above the sheet. */
    val keep: String? = null,
    val keepSignal: Int = 0,
)

/** What a touch on the lines' screen does. */
interface PanelActions {
    fun tapCharacter(key: String, index: Int)
    fun touchRow(key: String)
    fun tab(tab: SheetTab)
    fun noteTab()
    fun closeSheet()
    fun again()
    fun recap(what: RecapOf)
    fun crop()
    fun recapButton()
    fun note()
    fun openApp()
    fun entryLink(link: EntryLink, index: Int)
}

/**
 * The lines' screen of a two-screen handheld, laid out as a texthooker page:
 * the game and its session at the top, then this game's lines as they are
 * read, oldest first, the one on screen last and largest, drawn with the same
 * rows as the app's Lines page. Every character can be tapped. A touch beside
 * a line opens the sheet under the lines on it, whose tabs are its translation
 * and explanation, the game's recap, and a note; the lines never move for it,
 * and the recap, about the whole game, takes their place until it is closed.
 * Notes written while playing sit among the lines, at their time. A word
 * picked here has its entry in the sheet.
 */
@Composable
fun LinesPanel(
    ui: PanelUi,
    actions: PanelActions,
    styles: List<EntryHtml.Style>,
    media: (String, String) -> ByteArray?,
    entries: EntryScroller,
) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().background(Lfg.Bg)) {
        // The game and its session, with what the screen can do beside it.
        Column(Modifier.fillMaxWidth().background(Lfg.Surface).padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.title, color = Lfg.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Crop draws the box the game is read in; while it is drawn it is Cancel, the only way out of a box drawn by finger.
                LfgButton(if (ui.picking) "Cancel" else "Crop", actions::crop, compact = true)
                LfgButton("Recap", actions::recapButton, compact = true)
                LfgButton("Note", actions::note, compact = true)
                LfgButton("LFG", actions::openApp, compact = true)
            }
            Text(ui.stats, color = Lfg.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        HorizontalDivider(color = Lfg.Border)

        // The recap has the whole screen below the game's; the lines stay composed, at no height, so they keep their place.
        val fullSheet = ui.sheet is PanelSheet.Recap
        Box(Modifier.fillMaxWidth().then(if (fullSheet) Modifier.height(0.dp) else Modifier.weight(1f))) {
            if (ui.rows.isEmpty()) {
                Text(ui.empty, color = Lfg.Dim, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = 16.dp, top = 18.dp, end = 16.dp))
            } else {
                Lines(ui, actions, clock)
            }
        }

        ui.sheet?.let { sheet ->
            Column(Modifier.fillMaxWidth().weight(if (fullSheet) 1f else SHEET_WEIGHT)) {
                HorizontalDivider(color = Lfg.Border)
                Row(
                    Modifier.fillMaxWidth().background(Lfg.Surface).padding(start = 16.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sheet is PanelSheet.Entry) {
                        Text(sheet.heading, color = Lfg.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    } else {
                        Tabs((sheet as? PanelSheet.Answer)?.tab ?: SheetTab.RECAP, actions, Modifier.weight(1f).padding(end = 8.dp))
                    }
                    LfgButton("Close", actions::closeSheet, compact = true)
                }
                HorizontalDivider(color = Lfg.Border)
                when (sheet) {
                    is PanelSheet.Entry -> {
                        EntryList(ui.lookup, styles, media, actions::entryLink, Modifier.fillMaxWidth().weight(1f), scroller = entries)
                        sheet.message?.let { Text(it, color = Lfg.Dim, fontSize = 12.5.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 6.dp)) }
                    }
                    is PanelSheet.Answer -> {
                        // Another line or tab starts at the top of its answer.
                        val scroll = key(sheet.key) { rememberScrollState() }
                        Answer(sheet.text, sheet.tone, Modifier.weight(1f).verticalScroll(scroll).padding(16.dp, 12.dp))
                        sheet.again?.let { LfgButton(it, actions::again, enabled = sheet.againEnabled, compact = true, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp)) }
                    }
                    is PanelSheet.Recap -> {
                        if (sheet.choices.isNotEmpty()) {
                            Row(Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (choice in sheet.choices) {
                                    LfgButton(choice.label, { actions.recap(choice.what) }, primary = choice.chosen, enabled = choice.enabled || choice.chosen, compact = true)
                                }
                            }
                        }
                        Answer(sheet.text, sheet.tone, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp))
                    }
                }
            }
        }

        HorizontalDivider(color = Lfg.Border)
        Text(ui.foot, color = Lfg.Dim, fontSize = 12.5.sp, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 12.dp, bottom = 6.dp))
    }
}

/** The lines and the notes among them. New text pulls the list to its end, unless the sheet is about a line or the list was scrolled back. */
@Composable
private fun Lines(ui: PanelUi, actions: PanelActions, clock: SimpleDateFormat) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (ui.rows.size - 1).coerceAtLeast(0))
    // Whether the list was at its end when last scrolled by hand.
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { if (list.isScrollInProgress) !list.canScrollForward else null }.collect { if (it != null) stick = it }
    }
    val last = ui.rows.lastIndex
    // A new line, or another on screen now (larger): at the end, the list stays there.
    LaunchedEffect(ui.rows.lastOrNull()?.key, ui.rows.indexOfLast { it.current }) {
        if (ui.lit == null && stick) list.scrollToItem(last)
    }
    LaunchedEffect(ui.endSignal) {
        if (ui.endSignal > 0) {
            stick = true
            list.scrollToItem(last)
        }
    }
    LaunchedEffect(ui.keepSignal) {
        if (ui.keepSignal == 0) return@LaunchedEffect
        // After the sheet has taken its room.
        withFrameNanos { }
        val index = ui.rows.indexOfFirst { it.key == ui.keep }.takeIf { it >= 0 } ?: return@LaunchedEffect
        var info = list.layoutInfo
        var seen = info.visibleItemsInfo.firstOrNull { it.index == index }
        val out = seen == null || seen.offset < info.viewportStartOffset || seen.offset + seen.size > info.viewportEndOffset
        if (!out) return@LaunchedEffect
        if (seen == null) {
            list.scrollToItem(index)
            info = list.layoutInfo
            seen = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        }
        // Just above the sheet: its bottom on the list's, unless that would hide its top.
        val below = seen.offset + seen.size - info.viewportEndOffset
        val above = seen.offset - info.viewportStartOffset
        list.scrollBy(below.coerceAtMost(above).toFloat())
    }
    LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 10.dp)) {
        items(ui.rows, key = { it.key }) { row ->
            val note = row.note
            if (note != null) {
                NoteItem(note, clock)
            } else {
                LineItem(
                    row, clock,
                    onClick = { actions.touchRow(row.key) },
                    modifier = Modifier.fillMaxWidth(),
                    onTapCharacter = { actions.tapCharacter(row.key, it) },
                )
            }
        }
    }
}

/** The sheet's tabs: the line's translation and explanation, the game's recap, and a note. */
@Composable
private fun Tabs(open: SheetTab, actions: PanelActions, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.background(Lfg.Sunken, shape).border(1.dp, Lfg.Border, shape).padding(3.dp)) {
        for (tab in SheetTab.entries) Tab(tab.label, tab == open) { actions.tab(tab) }
        Tab("Note", false, actions::noteTab)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(label: String, chosen: Boolean, onPress: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Text(
        label,
        color = if (chosen) Lfg.OnFill else Lfg.Dim,
        fontSize = 13.sp,
        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.weight(1f).background(if (chosen) Lfg.Fill else Color.Transparent, shape).clickable(onClick = onPress)
            .defaultMinSize(minHeight = 36.dp).padding(vertical = 10.dp),
    )
}

@Composable
private fun ColumnScope.Answer(text: String, tone: Tone, modifier: Modifier) {
    Text(
        text,
        color = when (tone) {
            Tone.TEXT -> Lfg.Text
            Tone.DIM -> Lfg.Dim
            Tone.ACCENT -> Lfg.Accent
        },
        fontSize = 15.5.sp,
        lineHeight = 23.sp,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The sheet under the lines takes this much to the lines' 1. */
private const val SHEET_WEIGHT = 1.35f

/**
 * A Compose view in a window drawn over other apps: such a window has no
 * activity to give it a lifecycle, so it is given its own, always resumed.
 * [onDown] hears every touch that starts on it.
 */
internal class OverlayComposeView(
    context: Context,
    private val onDown: () -> Unit,
    private val content: @Composable () -> Unit,
) : AbstractComposeView(context) {
    private val owner = object : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    }

    init {
        owner.saved.performRestore(null)
        owner.registry.currentState = Lifecycle.State.RESUMED
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    @Composable
    override fun Content() = content()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onDown()
        return super.dispatchTouchEvent(event)
    }
}
