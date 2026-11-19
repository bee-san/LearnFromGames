// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.lookup

import io.github.beesan.lfg.anki.AnkiBridge
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.CardMaker
import io.github.beesan.lfg.anki.CardResult
import io.github.beesan.lfg.core.CursorPos
import io.github.beesan.lfg.core.Segmenter
import io.github.beesan.lfg.core.TextCursor
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.Pronunciation
import io.github.beesan.lfg.dict.WordAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CardButton { ADD, ADDING, IN_ANKI, WAITING }

/** A line that can be looked up in: on screen now (with [geometry]) or from the store (without). */
data class LookupLine(
    val text: String,
    /** Its row in `game_lines`; a card and a lookup are recorded against it. */
    val lineId: String?,
    val framePath: String?,
    val gameName: String,
    val geometry: TextLine? = null,
    /** When the line began to appear on screen, for sentence audio; null for a line from the store. */
    val appearedAtMs: Long? = null,
)

data class LookupState(
    val lines: List<LookupLine> = emptyList(),
    /** -1 when nothing is selected. */
    val line: Int = -1,
    val spanStart: Int = 0,
    val spanEnd: Int = 0,
    val entries: List<Entry> = emptyList(),
    /** The entry the controller is on: what A adds and Y says. */
    val entryIndex: Int = 0,
    /** Each entry's card button, in the order of [entries]. */
    val buttons: List<CardButton> = emptyList(),
    val message: String? = null,
    /** The controller drives the app and the game receives nothing. */
    val controllerMode: Boolean = false,
) {
    val entry: Entry? get() = entries.getOrNull(entryIndex)
    val button: CardButton get() = buttons.getOrNull(entryIndex) ?: CardButton.ADD
    val selected: Boolean get() = line in lines.indices
    val selectedLine: LookupLine? get() = lines.getOrNull(line)
}

enum class Move { WORD_NEXT, WORD_PREVIOUS, LINE_UP, LINE_DOWN, CHARACTER_NEXT, CHARACTER_PREVIOUS }

/**
 * Lookup from a character of a line, by touch or by controller, and the card
 * made from it. One instance serves the game overlays; the Lines screen has
 * its own.
 */
class LookupController(
    private val scope: CoroutineScope,
    private val lookup: suspend (String) -> List<Entry>,
    private val cards: CardMaker,
    private val anki: AnkiBridge,
    private val store: LineStore,
    private val audio: WordAudio? = null,
    /** Adds to a lookup's entries before they show: the user's notes on each word. */
    private val decorate: suspend (List<Entry>) -> List<Entry> = { it },
) {
    private val _state = MutableStateFlow(LookupState())
    val state: StateFlow<LookupState> = _state

    /** What became of each word carded from each line, so its button reads the same on return. */
    private val done = HashMap<Pair<String?, String>, CardButton>()
    private var cursor: TextCursor? = null
    private var selecting: Job? = null
    private var resting: Job? = null

    /** New text on screen. The selection belongs to the old text, so it goes. */
    fun setLines(lines: List<LookupLine>) {
        if (lines.map { it.text } == _state.value.lines.map { it.text } && lines.map { it.lineId } == _state.value.lines.map { it.lineId }) {
            // The same lines read again (their boxes may have shifted by a pixel): keep the selection.
            _state.update { it.copy(lines = lines) }
            return
        }
        val controller = _state.value.controllerMode
        selecting?.cancel()
        cursor = null
        _state.value = LookupState(lines = lines, controllerMode = controller)
        if (controller) enterControllerMode()
    }

    /** Looks up the longest word starting at [index] of line [line]. */
    fun select(line: Int, index: Int) {
        val target = _state.value.lines.getOrNull(line) ?: return
        if (index !in target.text.indices) return
        selecting?.cancel()
        selecting = scope.launch {
            val entries = decorate(io.github.beesan.lfg.core.Perf.measure("lookup") { lookup(target.text.substring(index)) })
            val first = entries.firstOrNull()
            val length = first?.matched?.length ?: Character.charCount(target.text.codePointAt(index))
            val end = (index + length).coerceAtMost(target.text.length)
            _state.update {
                if (it.lines.getOrNull(line)?.text != target.text) return@update it
                it.copy(
                    line = line, spanStart = index, spanEnd = end, entries = entries, entryIndex = 0,
                    buttons = entries.map { e -> done[target.lineId to e.expression] ?: CardButton.ADD },
                    message = if (first == null) "No entry" else null,
                )
            }
            if (first == null) return@launch
            target.lineId?.let { store.recordLookup(it, first.expression, first.reading) }
            // Every entry shows its own button, so each is checked; the first is looked at first.
            for (entry in entries.distinctBy { it.expression }) checkDuplicate(target, entry)
        }
    }

    private suspend fun checkDuplicate(line: LookupLine, entry: Entry) {
        if (done.containsKey(line.lineId to entry.expression)) return
        val duplicate = withContext(Dispatchers.IO) {
            try {
                anki.status() == AnkiStatus.READY && anki.target?.let { anki.isDuplicate(it, entry.expression) } == true
            } catch (_: Exception) {
                false
            }
        }
        if (!duplicate) return
        setButtons(entry.expression, CardButton.IN_ANKI, onlyFrom = CardButton.ADD)
    }

    /** Sets the button of each shown entry with this headword; the same word's entries share a card. */
    private fun setButtons(expression: String, button: CardButton, onlyFrom: CardButton? = null, message: String? = null) = _state.update { s ->
        if (s.entries.none { it.expression == expression }) return@update s
        s.copy(
            buttons = s.entries.mapIndexed { i, e ->
                val now = s.buttons.getOrNull(i) ?: CardButton.ADD
                if (e.expression == expression && (onlyFrom == null || now == onlyFrom)) button else now
            },
            message = message ?: s.message,
        )
    }

    /** Says a shown word, the controller's by default, from the word audio when it is installed. */
    fun playAudio(index: Int = _state.value.entryIndex) {
        _state.value.entries.getOrNull(index)?.let { audio?.play(it) }
    }

    /** Makes the entry at [index] the one A adds and a note is on. */
    fun selectEntry(index: Int) = _state.update { if (index in it.entries.indices) it.copy(entryIndex = index) else it }

    fun nextEntry() = stepEntry(+1)
    fun previousEntry() = stepEntry(-1)

    private fun stepEntry(step: Int) = _state.update {
        val index = (it.entryIndex + step).coerceIn(0, (it.entries.size - 1).coerceAtLeast(0))
        if (index == it.entryIndex) return@update it
        it.copy(entryIndex = index, message = null)
    }

    /**
     * The card button of a shown entry, the controller's by default: adds the
     * card, or opens it in AnkiDroid when it is there already.
     */
    fun addCard(index: Int = _state.value.entryIndex) {
        val state = _state.value
        val entry = state.entries.getOrNull(index) ?: return
        val line = state.selectedLine ?: return
        val lineId = line.lineId ?: return
        when (state.buttons.getOrNull(index) ?: CardButton.ADD) {
            CardButton.IN_ANKI -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        anki.target?.let { anki.noteId(it, entry.expression) }?.let { anki.openNote(it) }
                    } catch (_: Exception) {
                        // AnkiDroid is gone, refused, or too old for the link; the card is still there.
                    }
                }
                return
            }
            CardButton.ADD -> Unit
            else -> return
        }
        setButtons(entry.expression, CardButton.ADDING)
        _state.update { it.copy(message = null) }
        scope.launch {
            val content = CardContent(
                expression = entry.expression, reading = entry.reading, sentence = line.text,
                spanStart = state.spanStart, spanEnd = state.spanEnd, senses = entry.senses(),
                pitchPositions = Pronunciation.accents(entry).map { it.position },
                frequencyRank = Pronunciation.frequencyRank(entry),
                gameName = line.gameName, picturePath = line.framePath, lineStartMs = line.appearedAtMs,
            )
            val result = cards.add(lineId, content)
            val key = lineId to entry.expression
            when (result) {
                is CardResult.Added, CardResult.Duplicate -> done[key] = CardButton.IN_ANKI
                is CardResult.Waiting -> done[key] = CardButton.WAITING
                is CardResult.Failed -> Unit
            }
            // The reply may arrive after another word was chosen; that word's buttons are left alone.
            if (_state.value.selectedLine?.lineId != lineId) return@launch
            when (result) {
                is CardResult.Added -> setButtons(entry.expression, CardButton.IN_ANKI, message = "Card added")
                CardResult.Duplicate -> setButtons(entry.expression, CardButton.IN_ANKI)
                is CardResult.Waiting -> setButtons(entry.expression, CardButton.WAITING, message = result.reason)
                is CardResult.Failed -> setButtons(entry.expression, CardButton.ADD, message = result.reason)
            }
        }
    }

    /** Drops the selection; the lines stay. */
    fun clear() {
        selecting?.cancel()
        resting?.cancel()
        _state.update { it.copy(line = -1, spanStart = 0, spanEnd = 0, entries = emptyList(), entryIndex = 0, message = null, buttons = emptyList()) }
    }

    /**
     * The shortcut was pressed. The cursor starts on the first word with that
     * word already looked up.
     */
    fun enterControllerMode() {
        _state.update { it.copy(controllerMode = true) }
        scope.launch {
            val lines = _state.value.lines
            val geometry = lines.map { it.geometry ?: TextLine(it.text, io.github.beesan.lfg.core.Box(0, 0, 0, 0), emptyList()) }
            val starts = lines.map { line ->
                io.github.beesan.lfg.core.Perf.measure("segment") { Segmenter.wordStarts(line.text) { rest -> lookup(rest).firstOrNull()?.matched?.length ?: 0 } }
            }
            if (_state.value.lines !== lines || !_state.value.controllerMode) return@launch
            val built = TextCursor(geometry, starts)
            cursor = built
            val current = _state.value
            // A word already chosen by touch stays chosen.
            if (!current.selected) built.first()?.let { select(it.line, it.index) }
        }
    }

    fun exitControllerMode() {
        cursor = null
        _state.update { it.copy(controllerMode = false) }
        clear()
    }

    fun move(move: Move) {
        val cursor = cursor ?: return
        val state = _state.value
        if (!state.selected) {
            cursor.first()?.let { select(it.line, it.index) }
            return
        }
        val from = CursorPos(state.line, state.spanStart)
        val to = when (move) {
            Move.WORD_NEXT -> cursor.nextWord(from)
            Move.WORD_PREVIOUS -> cursor.previousWord(from)
            Move.LINE_UP -> cursor.lineUp(from)
            Move.LINE_DOWN -> cursor.lineDown(from)
            Move.CHARACTER_NEXT -> cursor.nextCharacter(from)
            Move.CHARACTER_PREVIOUS -> cursor.previousCharacter(from)
        }
        if (to == from) return
        resting?.cancel()
        if (move == Move.CHARACTER_NEXT || move == Move.CHARACTER_PREVIOUS) {
            // Show the cursor at once; look the word up when the stick has rested.
            val text = state.lines[to.line].text
            _state.update {
                it.copy(
                    line = to.line, spanStart = to.index, spanEnd = to.index + Character.charCount(text.codePointAt(to.index)),
                    entries = emptyList(), entryIndex = 0, message = null, buttons = emptyList(),
                )
            }
            resting = scope.launch {
                delay(STICK_REST_MS)
                select(to.line, to.index)
            }
        } else {
            select(to.line, to.index)
        }
    }

    companion object {
        const val STICK_REST_MS = 200L
    }
}
