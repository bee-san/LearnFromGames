// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import io.github.beesan.lfg.ask.Explainer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.CardButton
import io.github.beesan.lfg.lookup.LookupState
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PageHeader(title: String, actions: @Composable () -> Unit = {}) {
    // A narrow screen (the Thor's lower one is about 470 dp) has no room for the
    // title beside a row of buttons: squeezed, the title went one letter a line.
    // There the buttons go under the title and wrap.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        if (maxWidth < NARROW) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = Lfg.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { actions() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Lfg.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
    }
}

/** Below this width a page stacks what sits side by side on a wide screen. */
internal val NARROW = 640.dp

/**
 * What opens from the launcher: the lines the app has read, newest first,
 * grouped by day and game. Tapping a line opens it for lookup, so a card can be
 * made after the session. A problem shows as one row at the top with its button.
 */
@Composable
fun LinesScreen(
    lines: List<GameLine>,
    problem: Pair<String, String>?,
    onProblem: () -> Unit,
    onOpenLine: (GameLine) -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onDiary: () -> Unit,
    onRecap: () -> Unit,
    onAsk: () -> Unit,
    shortcut: String = "L3 + R3",
    loaded: Boolean = true,
    /** The lines explained, by id: marked, with how many questions were asked of each. */
    explained: Map<String, Explainer.Conversation> = emptyMap(),
) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(lines.size) { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone) }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize().padScroll(list).firstFocus()) {
        PageHeader("Lines") {
            LfgButton("Diary", onDiary)
            LfgButton("Recap", onRecap)
            LfgButton("Ask", onAsk)
            LfgButton("Stats", onStats)
            LfgButton("Settings", onSettings)
        }
        if (problem != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(problem.first, color = Lfg.Warn, fontSize = 15.sp, modifier = Modifier.weight(1f))
                LfgButton(problem.second, onProblem, primary = true)
            }
        }
        HorizontalDivider(color = Lfg.Border)
        if (lines.isEmpty()) {
            // Before the store has answered, an empty list says nothing yet.
            if (loaded) Text("No lines yet. Open a game. Tap a word, or press $shortcut.", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            return
        }
        LazyColumn(state = list) {
            var lastGroup = ""
            for (line in lines) {
                val group = MainViewModel.dayLabel(ReadingStats.dayOf(line.timestamp, zone), today) + " · " + line.gameName
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "g:" + line.id) {
                        Text(group, color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp))
                    }
                }
                item(key = line.id) {
                    LineItem(
                        LineRowUi(
                            line.id, line.createdAt, line.lineText, line.translation, card = line.noteIds.isNotEmpty(),
                            explained = line.id in explained, questions = explained[line.id]?.turns?.size ?: 0,
                        ),
                        time,
                        onClick = { onOpenLine(line) },
                        modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(6.dp), inside = true),
                    )
                    HorizontalDivider(color = Lfg.Border)
                }
            }
        }
    }
}

/**
 * A sentence whose every character can be tapped, with the looked-up word
 * marked. With the pad it takes focus, and left and right move the word:
 * by [onStep] (forward or not) where there is a cursor that knows words,
 * otherwise a character at a time. [onFocused] runs as the pad comes to it.
 */
@Composable
fun TappableSentence(
    sentence: String,
    spanStart: Int,
    spanEnd: Int,
    onTapCharacter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onStep: ((Boolean) -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    val step = { forward: Boolean ->
        if (onStep != null) {
            onStep(forward)
        } else if (sentence.isNotEmpty()) {
            val selected = spanEnd > spanStart
            var next = when {
                !selected -> 0
                forward -> spanStart + Character.charCount(sentence.codePointAt(spanStart))
                else -> spanStart - 1
            }
            if (next in sentence.indices && Character.isLowSurrogate(sentence[next]) && next > 0) next--
            if (next in sentence.indices) onTapCharacter(next)
        }
    }
    TapText(
        sentence, spanStart, spanEnd, onTapCharacter,
        fontSize = 24.sp,
        lineHeight = 44.sp,
        modifier = modifier.focusRing(RoundedCornerShape(6.dp), inside = true)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> step(true)
                    Key.DirectionLeft -> step(false)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A sentence whose every character can be tapped, with the characters `[spanStart, spanEnd)` marked. */
@Composable
fun TapText(
    sentence: String,
    spanStart: Int,
    spanEnd: Int,
    onTapCharacter: (Int) -> Unit,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val onTap by rememberUpdatedState(onTapCharacter)
    val text = buildAnnotatedString {
        append(sentence)
        if (spanEnd > spanStart && spanEnd <= sentence.length) addStyle(SpanStyle(background = Lfg.Wash, color = Lfg.Accent), spanStart, spanEnd)
    }
    Text(
        text = text,
        color = Lfg.Text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(sentence) {
            detectTapGestures { position ->
                val result = layout ?: return@detectTapGestures
                // getOffsetForPosition rounds to the nearest caret; the character under the finger is the one whose box holds it.
                val caret = result.getOffsetForPosition(position)
                val index = listOf(caret, caret - 1).firstOrNull {
                    it in sentence.indices && result.getBoundingBox(it).contains(position)
                } ?: return@detectTapGestures
                onTap(if (Character.isLowSurrogate(sentence[index]) && index > 0) index - 1 else index)
            }
        },
    )
}

/**
 * A row of a list of lines, as the app's Lines page and the lines' screen over
 * a game both show it: a line of the game, or a note written among them.
 */
@Immutable
data class LineRowUi(
    val key: String,
    val time: Long,
    val text: String,
    val translation: String? = null,
    /** A card was made from it. */
    val card: Boolean = false,
    val explained: Boolean = false,
    /** Questions asked about it since it was explained. */
    val questions: Int = 0,
    /** On the game's screen now: larger, and marked at its edge. */
    val current: Boolean = false,
    /** What the sheet under the lines speaks of: outlined. */
    val lit: Boolean = false,
    /** The looked-up word, `[markStart, markEnd)`; an empty range marks nothing. */
    val markStart: Int = 0,
    val markEnd: Int = 0,
    val note: Note? = null,
)

/**
 * A line of a list: its time, its text, its translation under it and what was
 * made of it. With [onTapCharacter] every character can be tapped, and a touch
 * beside the text is [onClick]; without, the whole row is [onClick].
 */
@Composable
fun LineItem(
    row: LineRowUi,
    clock: SimpleDateFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTapCharacter: ((Int) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(4.dp)
    val ground = when {
        row.lit -> Modifier.background(Lfg.Surface, shape).border(1.5.dp, Lfg.Accent, shape)
        row.current -> Modifier.background(Lfg.Surface)
        else -> Modifier
    }
    Row(modifier.then(ground).clickable(onClick = onClick).height(IntrinsicSize.Min).padding(end = 16.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (row.current || row.lit) Lfg.Accent else Color.Transparent))
        Text(
            clock.format(Date(row.time)), color = if (row.lit) Lfg.Accent else Lfg.Dim, fontSize = 12.sp,
            modifier = Modifier.width(58.dp).padding(start = 11.dp, top = 13.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            val size = if (row.current) 21.sp else 17.sp
            val height = if (row.current) 32.sp else 26.sp
            if (onTapCharacter != null) TapText(row.text, row.markStart, row.markEnd, onTapCharacter, size, height)
            else Text(row.text, color = Lfg.Text, fontSize = size, lineHeight = height)
            row.translation?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Lfg.Dim, fontSize = 14.5.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 5.dp))
            }
            val tags = listOfNotNull("CARD".takeIf { row.card }, "EXPLAINED".takeIf { row.explained }?.let { it + questionsTag(row.questions) }).joinToString("   ")
            if (tags.isNotEmpty()) {
                Text(tags, color = Lfg.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun questionsTag(count: Int) = when (count) {
    0 -> ""
    1 -> " · 1 QUESTION"
    else -> " · $count QUESTIONS"
}

/**
 * A line's explanation, the questions asked about it since with their answers, three more to ask
 * with a tap, and a box for one's own. "Explain again" asks first when questions would go with it.
 */
@Composable
private fun Explanation(state: MainViewModel.ExplainState, onExplain: () -> Unit, onAsk: (String) -> Unit, askNow: Boolean) {
    val talk = state.talk
    var confirm by remember(talk?.explanation) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (confirm) "Start over? The questions asked go with this explanation." else "Explanation",
            color = if (confirm) Lfg.Accent else Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        if (confirm) {
            LfgButton("Start over", { confirm = false; onExplain() })
            LfgButton("Keep", { confirm = false }, primary = true)
        } else {
            LfgButton(
                if (talk == null) "Explain" else "Explain again",
                { if (talk != null && talk.turns.isNotEmpty()) confirm = true else onExplain() },
                enabled = !state.busy,
            )
        }
    }
    when {
        state.busy && talk == null -> Text("Explaining…", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        talk != null -> Text(talk.explanation, color = Lfg.Text, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp))
    }
    if (state.busy && talk != null) Text("Explaining…", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
    state.error?.let { Text(it, color = Lfg.Warn, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
    if (talk != null && !state.busy) Talk(talk, state, onAsk, askNow)
}

/** What was asked about an explained line, the questions to ask next, and the box for one's own. */
@Composable
private fun Talk(talk: Explainer.Conversation, state: MainViewModel.ExplainState, onAsk: (String) -> Unit, askNow: Boolean) {
    val waiting = state.asking != null && state.askError == null
    val ui = TalkUi(talk.turns, state.asking, state.askError, if (waiting || talk.full) emptyList() else talk.suggestions, talk.full)
    if (ui.turns.isNotEmpty() || ui.asking != null || ui.full) TalkTurns(ui, Modifier.padding(top = 12.dp))
    if (!talk.full) {
        TalkChips(ui.suggestions, !waiting, onAsk, Modifier.padding(top = 12.dp))
        var question by rememberSaveable(talk.explanation) { mutableStateOf("") }
        // The question stays in the box until its answer is kept, so one that failed can be sent again.
        LaunchedEffect(talk.turns.size) { if (talk.turns.lastOrNull()?.question == question.trim()) question = "" }
        val send = { if (!waiting && question.isNotBlank()) onAsk(question) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(askNow) { if (askNow) runCatching { focus.requestFocus() } }
        // A question asked, and then its answer, push the box down the page: it is kept in sight.
        // A line opened with questions already asked starts at its top, as any line does.
        val inSight = remember { BringIntoViewRequester() }
        val said = talk.turns.size to state.asking
        var seen by remember(talk.explanation) { mutableStateOf(said) }
        LaunchedEffect(said) {
            if (said != seen) inSight.bringIntoView()
            seen = said
        }
        Row(Modifier.padding(top = 10.dp).bringIntoViewRequester(inSight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = question,
                onValueChange = { question = it.take(Explainer.QUESTION_LENGTH) },
                textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Lfg.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { field ->
                    if (question.isEmpty()) Text("Ask about this line…", color = Lfg.Dim, fontSize = 16.sp)
                    field()
                },
                modifier = Modifier.weight(1f).focusRequester(focus).clip(RoundedCornerShape(6.dp)).background(Lfg.Sunken)
                    .border(1.dp, Lfg.BorderStrong, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            LfgButton("Ask", send, primary = true, enabled = !waiting && question.isNotBlank())
        }
    }
}

/** A note written while playing, among the lines at its time. */
@Composable
fun NoteItem(note: Note, clock: SimpleDateFormat, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 3.dp, top = 6.dp, end = 16.dp, bottom = 6.dp)) {
        Text(clock.format(Date(note.createdAt)), color = Lfg.Dim, fontSize = 12.sp, modifier = Modifier.width(58.dp).padding(start = 11.dp, top = 11.dp))
        Column(
            Modifier.weight(1f).background(Lfg.Surface, RoundedCornerShape(10.dp)).border(1.dp, Lfg.Border, RoundedCornerShape(10.dp))
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
        ) {
            Text(
                if (note.term.isEmpty()) "DIARY ENTRY" else "NOTE  ·  ${note.term}",
                color = Lfg.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp,
            )
            Text(note.text, color = Lfg.Text, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** What the pad does on a stored line's page. */
class LinePad(
    val onFocused: () -> Unit,
    val onStep: (Boolean) -> Unit,
    val onCard: () -> Unit,
    val onAudio: () -> Unit,
    val onNote: () -> Unit,
    val onEntry: (Boolean) -> Unit,
)

fun cardButtonLabel(button: CardButton) = when (button) {
    CardButton.ADD -> "Add card"
    CardButton.ADDING -> "Adding…"
    CardButton.IN_ANKI -> "In Anki"
    CardButton.WAITING -> "Waiting for AnkiDroid"
}

/** One stored line, opened: tap a word, read every entry in one scrolling list, make a card or a note from one. */
@Composable
fun LineLookupScreen(
    state: LookupState,
    styles: List<EntryHtml.Style>,
    media: (String, String) -> ByteArray?,
    onTap: (Int) -> Unit,
    onLink: (EntryLink, Int) -> Unit,
    translation: MainViewModel.TranslationState,
    explanation: MainViewModel.ExplainState,
    /** There is an OpenRouter key: Translate and Explain show. Without one nothing is offered. */
    ai: Boolean,
    translateContext: Int,
    onTranslate: () -> Unit,
    onExplain: () -> Unit,
    /** A question about the line, asked after its explanation. */
    onAsk: (String) -> Unit,
    /** Opened from over a game to write a question: the box for it has the keyboard at once. */
    askNow: Boolean = false,
    onBack: () -> Unit,
    /** The pad, as over a game: a cursor over the line, a card, the word said, a note, and the entries in turn. */
    pad: LinePad? = null,
) {
    val line = state.lines.firstOrNull()
    val scroll = rememberScrollState()
    val entries = remember { EntryScroller() }
    Column(
        Modifier.fillMaxSize().padScroll(scroll).onKeyEvent { event ->
            if (pad == null) return@onKeyEvent false
            val down = event.type == KeyEventType.KeyDown
            when (event.key) {
                Key.ButtonL1 -> if (down) pad.onEntry(false)
                Key.ButtonR1 -> if (down) pad.onEntry(true)
                Key.ButtonY -> if (down) pad.onAudio()
                // The triggers page through the entries, as the right stick does over a game.
                Key.ButtonL2 -> if (down) entries.page(false)
                Key.ButtonR2 -> if (down) entries.page(true)
                // On release, as over a game: the note's fields must not get this key's up.
                Key.ButtonX -> if (!down) pad.onNote()
                else -> return@onKeyEvent false
            }
            true
        },
    ) {
        PageHeader(line?.gameName ?: "Line") { LfgButton("Back", onBack) }
        HorizontalDivider(color = Lfg.Border)
        if (line == null) return
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // An explanation runs long: the column scrolls.
            Column(Modifier.weight(1f).verticalScroll(scroll).firstFocus().padding(vertical = 6.dp)) {
                TappableSentence(
                    line.text, state.spanStart, if (state.selected) state.markEnd else 0, onTap,
                    // A, on the line: the card of the entry shown.
                    modifier = Modifier.onKeyEvent { event ->
                        if (pad == null || event.key != Key.ButtonA) return@onKeyEvent false
                        if (event.type == KeyEventType.KeyDown && state.selected) pad.onCard()
                        true
                    },
                    onStep = pad?.onStep,
                    onFocused = { pad?.onFocused?.invoke() },
                )
                if (!state.selected) Text("Tap a word.", color = Lfg.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                if (ai) {
                    HorizontalDivider(color = Lfg.Border, modifier = Modifier.padding(top = 18.dp))
                    Text("Translation", color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    when {
                        translation.text != null -> Text(translation.text, color = Lfg.Text, fontSize = 17.sp, lineHeight = 25.sp)
                        translation.busy -> Text("Translating…", color = Lfg.Dim, fontSize = 15.sp)
                    }
                    translation.error?.let { Text(it, color = Lfg.Warn, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            if (translateContext == 0) "Without the lines before" else "With the $translateContext lines before",
                            color = Lfg.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        LfgButton(if (translation.text == null) "Translate" else "Translate again", onTranslate, enabled = !translation.busy)
                    }
                    HorizontalDivider(color = Lfg.Border, modifier = Modifier.padding(top = 14.dp))
                    Explanation(explanation, onExplain, onAsk, askNow)
                }
            }
            // Opened to ask about the line, the conversation has the page's whole width until a word is tapped.
            if (state.selected || !askNow) Column(Modifier.weight(1.2f).fillMaxHeight().padding(top = 6.dp)) {
                if (state.selected) {
                    EntryList(state, styles, media, onLink, Modifier.fillMaxWidth().weight(1f), scroller = entries)
                    state.message.takeIf { state.entries.isNotEmpty() }?.let {
                        Text(it, color = Lfg.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
