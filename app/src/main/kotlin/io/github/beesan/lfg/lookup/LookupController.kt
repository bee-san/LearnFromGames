// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.lookup

import io.github.beesan.lfg.anki.AnkiBridge
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.anki.CardContent
import io.github.beesan.lfg.anki.CardMaker
import io.github.beesan.lfg.anki.CardResult
import io.github.beesan.lfg.core.CursorPos
import io.github.beesan.lfg.core.Selectable
import io.github.beesan.lfg.core.Selection
import io.github.beesan.lfg.core.TextCursor
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.KanjiEntry
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

/** What a lookup does that can be heard: a step to another word, a card asked for, and the card made or not. */
enum class Heard { STEP, CARD_ASKED, CARD_MADE, CARD_FAILED }

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
    /** Read from an ordinary app rather than a game: its card gets no sentence audio and no moving picture. */
    val fromApp: Boolean = false,
    /** [LineStore.SOURCE_OCR], [LineStore.SOURCE_TREE] or another source, for the row it is saved as. */
    val source: String = LineStore.SOURCE_OCR,
    /** The game's row, whose visual novel's character names are looked in first; null for none. */
    val gameId: String? = null,
    /** Kept as a menu's or a label's text, not the story's: the wheel acts on a line of the story first. */
    val menu: Boolean = false,
)

/** The line the wheel and the panel act on with nothing picked: the story's newest on screen, else any kept line there. */
fun List<LookupLine>.storyLine(): LookupLine? =
    lastOrNull { it.geometry != null && it.lineId != null && !it.menu } ?: lastOrNull { it.geometry != null && it.lineId != null }

data class LookupState(
    val lines: List<LookupLine> = emptyList(),
    /** -1 when nothing is selected. */
    val line: Int = -1,
    /** The word looked up: where the lookup started, and where its match ends. A card marks this in its sentence. */
    val spanStart: Int = 0,
    val spanEnd: Int = 0,
    /**
     * Where the mark drawn from [spanStart] ends: the match in Whole word
     * selection, the one character in Character selection, and the one
     * character while there is no entry.
     */
    val markEnd: Int = 0,
    val entries: List<Entry> = emptyList(),
    /** The entry the controller is on: what A adds and Y says. */
    val entryIndex: Int = 0,
    /** Each entry's card button, in the order of [entries]. */
    val buttons: List<CardButton> = emptyList(),
    val message: String? = null,
    /** The controller drives the app and the game receives nothing. */
    val controllerMode: Boolean = false,
    /** Asked once under the entries: whether the game is this visual novel. */
    val vnQuestion: VnQuestion? = null,
    /** The headword whose kanji the [entries] are, tapped there; null while they are the word's own. */
    val kanjiFrom: String? = null,
) {
    val entry: Entry? get() = entries.getOrNull(entryIndex)
    val button: CardButton get() = buttons.getOrNull(entryIndex) ?: CardButton.ADD
    val selected: Boolean get() = line in lines.indices
    val selectedLine: LookupLine? get() = lines.getOrNull(line)
}

enum class Move { LEFT, RIGHT, UP, DOWN }

/** "Is this the visual novel [title]?" for the game of [gameId], about VNDB's [vnId]. */
data class VnQuestion(val gameId: String, val vnId: String, val title: String)

/**
 * Lookup from a character of a line, by touch or by controller, and the card
 * made from it. One instance serves the game overlays; the Lines screen has
 * its own.
 */
class LookupController(
    private val scope: CoroutineScope,
    /** Entries for the text, in the game of the given id (null for none). */
    private val lookup: suspend (String, String?) -> List<Entry>,
    private val cards: CardMaker,
    private val anki: AnkiBridge,
    private val store: LineStore,
    private val audio: WordAudio? = null,
    /** Adds to a lookup's entries before they show: the user's notes on each word. */
    private val decorate: suspend (List<Entry>) -> List<Entry> = { it },
    /**
     * Gives a line not kept in the store a row and a picture, the moment a
     * card is made from it: lines from apps are kept only when asked, and a
     * card needs the line it quotes.
     */
    private val prepare: suspend (LookupLine) -> LookupLine = { it },
    /** Whole word or Character, and whether only Japanese characters can be picked: Settings › Controls. */
    private val selection: () -> Selection = { Selection() },
    /**
     * The question to ask once under a game's entries, if its visual novel is
     * uncertain; marks it asked. Null for most games, and always for none.
     */
    private val vnQuestion: suspend (String) -> VnQuestion? = { null },
    /** The answer to a [VnQuestion]. */
    private val vnAnswer: suspend (VnQuestion, Boolean) -> Unit = { _, _ -> },
    /** The entries for one kanji in the kanji dictionary (Settings › Dictionaries). */
    private val lookupKanji: suspend (String) -> List<Entry> = { emptyList() },
    /**
     * The card button was pressed on a word that is in Anki already. True when that is all there is to do:
     * in the tutorial, where opening AnkiDroid at the card would take the player out of what they are trying.
     */
    private val alreadyInAnki: () -> Boolean = { false },
    /** Something happened that has a sound over a game ([Heard]): only what the player did, never a line arriving by itself. */
    private val heard: (Heard) -> Unit = {},
) {
    private val _state = MutableStateFlow(LookupState())
    val state: StateFlow<LookupState> = _state

    /** What became of each word carded from each line, so its button reads the same on return. */
    private val done = HashMap<Pair<String?, String>, CardButton>()
    private var cursor: TextCursor? = null
    private var selecting: Job? = null
    private var resting: Job? = null

    /**
     * A line read earlier that is being looked into beside the ones on screen
     * ([lookIn]). It is the last of the state's lines and has no place on
     * screen; the lines on screen keep theirs, and their tap strips.
     */
    private var away: LookupLine? = null

    /** The word's lookup as it was when one of its kanji was tapped, to go back to. */
    private var word: LookupState? = null

    private fun onScreen(state: LookupState) = if (away != null) state.lines.dropLast(1) else state.lines

    /**
     * New text on screen, which is read while a word is looked up too. A word
     * whose line is still there stays chosen; one whose line has gone is
     * replaced by the first word of the new text, so the entry follows the game.
     */
    fun setLines(lines: List<LookupLine>) {
        val before = _state.value
        val shown = onScreen(before)
        if (lines.map { it.text } == shown.map { it.text } && lines.map { it.lineId } == shown.map { it.lineId }) {
            // The same lines read again (their boxes may have shifted by a pixel): keep the selection.
            _state.update { it.copy(lines = lines + listOfNotNull(away)) }
            return
        }
        val picked = before.selectedLine
        if (picked != null) {
            // The word's line as it was up to the word's end, with other lines changed around it or more
            // of itself typed out after it; or the earlier line looked into, which is not on screen at all.
            val still = if (before.line >= shown.size) lines.size else lines.indexOfFirst {
                it.lineId != null && it.lineId == picked.lineId && before.spanEnd <= it.text.length &&
                    it.text.regionMatches(0, picked.text, 0, before.spanEnd)
            }
            if (still >= 0) {
                val next = lines + listOfNotNull(away)
                _state.update { it.copy(lines = next, line = still) }
                if (cursor != null) cursor = cursorOver(next)
                return
            }
        }
        selecting?.cancel()
        resting?.cancel()
        cursor = null
        away = null
        word = null
        _state.value = LookupState(lines = lines, controllerMode = before.controllerMode)
        if (before.controllerMode) enterControllerMode()
        // A word chosen by touch is followed by the new text's first word too: the entry was up, and stays up.
        else if (picked != null) cursorOver(lines).first()?.let { lookUp(it.line, it.index) }
    }

    /**
     * Looks a word up in [line], which was read earlier, from [index]. The
     * lines on screen stay as they are; if [line] is one of them, the word is
     * picked there.
     */
    fun lookIn(line: LookupLine, index: Int) {
        val state = _state.value
        val shown = onScreen(state)
        val here = shown.indexOfFirst { it.lineId != null && it.lineId == line.lineId }
        if (here >= 0) {
            select(here, index)
            return
        }
        if (away?.lineId != line.lineId || away?.text != line.text) {
            selecting?.cancel()
            resting?.cancel()
            away = line
            _state.value = LookupState(lines = shown + line, controllerMode = state.controllerMode)
            if (state.controllerMode) cursor = cursorOver(_state.value.lines)
        }
        select(shown.size, index)
    }

    /** Looks up the longest word starting at [index] of line [line]: a tap, or the cursor put there. */
    fun select(line: Int, index: Int) {
        val target = _state.value.lines.getOrNull(line) ?: return
        if (!Selectable.at(target.text, index, selection().japaneseOnly)) return
        cursor?.forget()
        resting?.cancel()
        lookUp(line, index)
    }

    private fun lookUp(line: Int, at: Int) {
        val target = _state.value.lines.getOrNull(line) ?: return
        if (at !in target.text.indices) return
        // A language looked up by the word: wherever in the word the tap or the cursor is, its first letter.
        val byWord = selection().wordStart
        val index = if (byWord) Selectable.wordStart(target.text, at) else at
        selecting?.cancel()
        word = null
        selecting = scope.launch {
            val entries = decorate(io.github.beesan.lfg.core.Perf.measure("lookup") { lookup(target.text.substring(index), target.gameId) })
            val first = entries.firstOrNull()
            val character = (index + Character.charCount(target.text.codePointAt(index))).coerceAtMost(target.text.length)
            // A word no dictionary has is still one word: marked whole, and stepped over whole.
            val unknown = if (byWord) Selectable.wordEnd(target.text, index).coerceAtLeast(character) else character
            val end = if (first == null) unknown else (index + first.matched.length).coerceAtMost(target.text.length)
            _state.update {
                if (it.lines.getOrNull(line)?.text != target.text) return@update it
                it.copy(
                    line = line, spanStart = index, spanEnd = end, markEnd = if (selection().wholeWord) end else character,
                    entries = entries, entryIndex = 0,
                    buttons = entries.map { e -> done[target.lineId to e.expression] ?: CardButton.ADD },
                    message = if (first == null) "No entry" else null,
                    vnQuestion = null, kanjiFrom = null,
                )
            }
            if (first == null) return@launch
            target.gameId?.let { gameId ->
                val question = withContext(Dispatchers.IO) { vnQuestion(gameId) }
                if (question != null) _state.update { if (it.entries === entries) it.copy(vnQuestion = question) else it }
            }
            target.lineId?.let { store.recordLookup(it, first.expression, first.reading) }
            // Every entry shows its own button, so each is checked; the first is looked at first.
            for (entry in entries.distinctBy { it.expression }) checkDuplicate(target, entry)
        }
    }

    /**
     * A kanji of a headword was tapped: its entry in the kanji dictionary
     * takes the word's place, with a way back ([backToWord]). Its card button
     * mines the kanji.
     */
    fun lookUpKanji(codePoint: Int) {
        if (!Character.isValidCodePoint(codePoint)) return
        val kanji = String(Character.toChars(codePoint))
        val before = _state.value
        val line = before.selectedLine ?: return
        selecting?.cancel()
        selecting = scope.launch {
            val entries = lookupKanji(kanji)
            val shown = _state.value
            if (shown.line != before.line || shown.spanStart != before.spanStart || shown.selectedLine?.text != line.text) return@launch
            if (entries.isEmpty()) {
                _state.update { it.copy(message = "$kanji is not in the kanji dictionary.") }
                return@launch
            }
            val from = word ?: shown.also { word = it }
            _state.update {
                it.copy(
                    entries = entries, entryIndex = 0,
                    buttons = entries.map { e -> done[line.lineId to e.expression] ?: CardButton.ADD },
                    message = null, vnQuestion = null,
                    kanjiFrom = from.entries.firstOrNull { kanji in it.expression }?.expression ?: from.entry?.expression ?: kanji,
                )
            }
            for (entry in entries.distinctBy { it.expression }) checkDuplicate(line, entry)
        }
    }

    /** Back from a kanji's entry to the word's. */
    fun backToWord() {
        val back = word ?: return
        word = null
        selecting?.cancel()
        val lineId = _state.value.selectedLine?.lineId
        _state.update {
            it.copy(
                entries = back.entries, entryIndex = back.entryIndex,
                buttons = back.entries.mapIndexed { i, e -> done[lineId to e.expression] ?: back.buttons.getOrElse(i) { CardButton.ADD } },
                message = null, vnQuestion = null, kanjiFrom = null,
            )
        }
    }

    /** Yes or No to the visual novel asked about under the entries. */
    fun answerVn(yes: Boolean) {
        val question = _state.value.vnQuestion ?: return
        _state.update { it.copy(vnQuestion = null) }
        scope.launch { withContext(Dispatchers.IO) { vnAnswer(question, yes) } }
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
        val shown = state.selectedLine ?: return
        when (state.buttons.getOrNull(index) ?: CardButton.ADD) {
            CardButton.IN_ANKI -> {
                if (alreadyInAnki()) {
                    _state.update { it.copy(message = "This word is already in your Anki deck.") }
                    return
                }
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
        heard(Heard.CARD_ASKED)
        _state.update { it.copy(message = null) }
        scope.launch {
            val line = if (shown.lineId != null) shown else prepare(shown).also { ready ->
                // The line has its row now: a second card from it goes to the same one.
                _state.update { s -> s.copy(lines = s.lines.map { if (it === shown) ready else it }) }
            }
            val lineId = line.lineId ?: run {
                heard(Heard.CARD_FAILED)
                setButtons(entry.expression, CardButton.ADD, message = "The line could not be saved.")
                return@launch
            }
            val kanji = state.kanjiFrom != null
            val content = CardContent(
                expression = entry.expression,
                reading = if (kanji) KanjiEntry.reading(entry) else entry.reading,
                sentence = line.text,
                spanStart = state.spanStart, spanEnd = state.spanEnd,
                senses = if (kanji) KanjiEntry.senses(entry) else entry.senses(),
                pitchPositions = Pronunciation.accents(entry).map { it.position },
                frequencyRank = Pronunciation.frequencyRank(entry),
                gameName = line.gameName, picturePath = line.framePath, lineStartMs = line.appearedAtMs.takeUnless { line.fromApp },
                fromApp = line.fromApp, kanji = kanji,
            )
            val result = cards.add(lineId, content)
            val key = lineId to entry.expression
            when (result) {
                is CardResult.Added, CardResult.Duplicate -> done[key] = CardButton.IN_ANKI
                is CardResult.Waiting -> done[key] = CardButton.WAITING
                is CardResult.Failed -> Unit
            }
            // Heard whichever word is shown by now: the card was made, or was not.
            when (result) {
                is CardResult.Added, is CardResult.Waiting -> heard(Heard.CARD_MADE)
                is CardResult.Failed -> heard(Heard.CARD_FAILED)
                CardResult.Duplicate -> Unit
            }
            // The reply may arrive after another word was chosen; that word's buttons are left alone.
            if (_state.value.selectedLine?.lineId != lineId) return@launch
            when (result) {
                is CardResult.Added -> setButtons(entry.expression, CardButton.IN_ANKI, message = "Card added")
                CardResult.Duplicate -> setButtons(entry.expression, CardButton.IN_ANKI)
                is CardResult.Waiting -> setButtons(entry.expression, CardButton.WAITING, message = result.reason + " The card is kept here until it is.")
                is CardResult.Failed -> setButtons(entry.expression, CardButton.ADD, message = result.reason)
            }
        }
    }

    /** Drops the selection; the lines stay. */
    fun clear() {
        selecting?.cancel()
        resting?.cancel()
        word = null
        _state.update {
            // The earlier line looked into goes with its word: the lines are the screen's again.
            it.copy(
                lines = onScreen(it), line = -1, spanStart = 0, spanEnd = 0, markEnd = 0,
                entries = emptyList(), entryIndex = 0, message = null, buttons = emptyList(), kanjiFrom = null,
            )
        }
        if (away != null) {
            away = null
            if (cursor != null) cursor = cursorOver(_state.value.lines)
        }
    }

    private fun cursorOver(lines: List<LookupLine>) = TextCursor(
        lines.map { it.geometry ?: TextLine(it.text, io.github.beesan.lfg.core.Box(0, 0, 0, 0), emptyList()) },
        selection().japaneseOnly,
    )

    /**
     * The shortcut was pressed. The cursor starts on the first character of
     * the dialogue, with the word there already looked up.
     */
    fun enterControllerMode() {
        _state.update { it.copy(controllerMode = true) }
        val built = cursorOver(_state.value.lines)
        cursor = built
        // A word already chosen by touch stays chosen.
        if (!_state.value.selected) built.first()?.let { lookUp(it.line, it.index) }
    }

    /**
     * The pad on one of the app's own pages: a cursor over the lines, as in
     * controller mode, without the game's controller mode. A word already
     * chosen stays; otherwise the first is looked up.
     */
    fun startCursor() {
        if (cursor != null) return
        val built = cursorOver(_state.value.lines)
        cursor = built
        if (!_state.value.selected) built.first()?.let { lookUp(it.line, it.index) }
    }

    fun exitControllerMode() {
        cursor = null
        _state.update { it.copy(controllerMode = false) }
        clear()
    }

    /**
     * A step of the cursor, by Rubi for Vita's rules ([TextCursor]). The
     * D-pad's is looked up at once. The [stick]'s goes a character at a time
     * and is looked up once the cursor has rested [STICK_REST_MS], so a held
     * stick crosses a line without a lookup at each character.
     */
    fun move(move: Move, stick: Boolean = false) {
        val cursor = cursor ?: return
        val state = _state.value
        if (!state.selected) {
            cursor.first()?.let {
                heard(Heard.STEP)
                lookUp(it.line, it.index)
            }
            return
        }
        val from = CursorPos(state.line, state.spanStart)
        // Right goes past the word shown: in Whole word selection, once that word's lookup is in.
        val matchedEnd = if (selection().wholeWord && (state.entries.isNotEmpty() || selection().wordStart)) state.spanEnd else 0
        val to = when (move) {
            Move.RIGHT -> if (stick) cursor.stickRight(from) else cursor.right(from, matchedEnd)
            Move.LEFT -> if (stick) cursor.stickLeft(from) else cursor.left(from)
            Move.UP -> cursor.up(from)
            Move.DOWN -> cursor.down(from)
        }
        if (to == from) return
        heard(Heard.STEP)
        resting?.cancel()
        if (!stick) {
            lookUp(to.line, to.index)
            return
        }
        // Show the cursor at once; look the word up when the stick has rested.
        val text = state.lines[to.line].text
        val character = to.index + Character.charCount(text.codePointAt(to.index))
        selecting?.cancel()
        word = null
        _state.update {
            it.copy(
                line = to.line, spanStart = to.index, spanEnd = character, markEnd = character,
                entries = emptyList(), entryIndex = 0, message = null, buttons = emptyList(), kanjiFrom = null,
            )
        }
        resting = scope.launch {
            delay(STICK_REST_MS)
            lookUp(to.line, to.index)
        }
    }

    companion object {
        const val STICK_REST_MS = 200L
    }
}
