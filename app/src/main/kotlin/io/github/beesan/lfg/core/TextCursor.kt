// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/** A position in the text on screen: a line and a UTF-16 index into it. Not a pointer. */
data class CursorPos(val line: Int, val index: Int)

/**
 * Splits a line the way longest-match lookup would: from each position the
 * dictionary's longest word is one step. No tokeniser is shipped; the engine
 * already answers "how long is the word starting here".
 */
object Segmenter {
    /** Kana, kanji, letters and digits start words; punctuation, brackets and spaces do not. */
    fun isWordStart(text: String, index: Int): Boolean = Character.isLetterOrDigit(text.codePointAt(index))

    /**
     * Word start indices of [text]. [matchLength] returns how many UTF-16
     * units the longest dictionary word at the start of its argument covers, 0
     * for none; an unknown character is a one-character word.
     */
    suspend fun wordStarts(text: String, matchLength: suspend (String) -> Int): List<Int> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val charLength = Character.charCount(text.codePointAt(i))
            if (!isWordStart(text, i)) {
                i += charLength
                continue
            }
            starts += i
            i += matchLength(text.substring(i)).coerceIn(charLength, text.length - i)
        }
        return starts
    }
}

/**
 * The controller's cursor, after Rubi for Vita: word steps on the D-pad,
 * character steps on the stick, lines up and down keeping the column, and ends
 * that stop instead of wrapping. Every move returns the same position when it
 * cannot go further.
 */
class TextCursor(private val lines: List<TextLine>, private val wordStarts: List<List<Int>>) {
    init {
        require(lines.size == wordStarts.size)
    }

    /**
     * Where lookup mode starts: the first word of the longest line. On a
     * dialogue screen that is the dialogue, not the speaker's name above it.
     */
    fun first(): CursorPos? {
        val line = lines.indices.filter { wordStarts[it].isNotEmpty() }.maxByOrNull { lines[it].text.length } ?: return null
        return CursorPos(line, wordStarts[line].first())
    }

    fun nextWord(pos: CursorPos): CursorPos =
        wordStarts[pos.line].firstOrNull { it > pos.index }?.let { CursorPos(pos.line, it) } ?: pos

    fun previousWord(pos: CursorPos): CursorPos =
        wordStarts[pos.line].lastOrNull { it < pos.index }?.let { CursorPos(pos.line, it) } ?: pos

    fun nextCharacter(pos: CursorPos): CursorPos {
        val text = lines[pos.line].text
        var i = pos.index
        while (true) {
            i += Character.charCount(text.codePointAt(i))
            if (i >= text.length) return pos
            if (Segmenter.isWordStart(text, i)) return CursorPos(pos.line, i)
        }
    }

    fun previousCharacter(pos: CursorPos): CursorPos {
        val text = lines[pos.line].text
        var i = pos.index
        while (i > 0) {
            i -= Character.charCount(text.codePointBefore(i))
            if (Segmenter.isWordStart(text, i)) return CursorPos(pos.line, i)
        }
        return pos
    }

    fun lineDown(pos: CursorPos): CursorPos = moveRow(pos, +1)
    fun lineUp(pos: CursorPos): CursorPos = moveRow(pos, -1)

    /** A row of text on screen: which line it belongs to, and the word starts that sit in it. */
    private class Row(val line: Int, val starts: List<Int>)

    /** Every row with a word in it, in reading order: a wrapped sentence is one line of several rows. */
    private val rows: List<Row> = lines.indices.flatMap { l ->
        val line = lines[l]
        if (line.rows.size <= 1 || line.chars.isEmpty()) {
            listOf(Row(l, wordStarts[l]))
        } else {
            val rowOfStart = wordStarts[l].groupBy { start -> line.chars.firstOrNull { start >= it.start && start < it.end }?.let(line::rowOf) ?: 0 }
            line.rows.indices.map { r -> Row(l, rowOfStart[r] ?: emptyList()) }
        }
    }.filter { it.starts.isNotEmpty() }

    /** The next row up or down that has a word, landing on the word nearest the column the cursor is in. */
    private fun moveRow(pos: CursorPos, step: Int): CursorPos {
        val here = rows.indexOfFirst { it.line == pos.line && it.starts.any { s -> s >= pos.index } && it.starts.first() <= pos.index }
            .takeIf { it >= 0 } ?: rows.indexOfLast { it.line == pos.line && it.starts.first() <= pos.index }
        if (here < 0) return pos
        val target = rows.getOrNull(here + step) ?: return pos
        val column = column(lines[pos.line], pos.index) ?: return CursorPos(target.line, target.starts.first())
        val best = target.starts.minBy { start ->
            column(lines[target.line], start)?.let { kotlin.math.abs(it - column) } ?: Int.MAX_VALUE
        }
        return CursorPos(target.line, best)
    }

    /** The centre of a character across the reading direction's own axis: x for horizontal text, y for vertical. */
    private fun column(line: TextLine, index: Int): Int? {
        val box = line.chars.firstOrNull { index >= it.start && index < it.end }?.box ?: return null
        return if (line.vertical) (box.top + box.bottom) / 2 else (box.left + box.right) / 2
    }
}

/**
 * Held-button repeat, with Rubi's timings: the first repeat after 350 ms, then
 * one every 100 ms.
 */
class KeyRepeat(private val firstMs: Long = 350, private val nextMs: Long = 100) {
    private var held: Int? = null
    private var nextAt = 0L

    /** A direction was pressed (or is still held). Returns true when a step should happen now. */
    fun press(direction: Int, nowMs: Long): Boolean {
        if (held != direction) {
            held = direction
            nextAt = nowMs + firstMs
            return true
        }
        if (nowMs >= nextAt) {
            nextAt = nowMs + nextMs
            return true
        }
        return false
    }

    fun release() {
        held = null
    }

    val heldDirection: Int? get() = held

    /** When the next repeat is due, for scheduling a timer. */
    val nextRepeatAt: Long get() = nextAt
}
