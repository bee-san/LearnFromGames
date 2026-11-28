// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/** A position in the text on screen: a line and a UTF-16 index into it. Not a pointer. */
data class CursorPos(val line: Int, val index: Int) : Comparable<CursorPos> {
    /** Reading order: line by line, then along the line. */
    override fun compareTo(other: CursorPos): Int = if (line != other.line) line.compareTo(other.line) else index.compareTo(other.index)
}

/**
 * How a word is picked and marked, as Rubi for Vita has it (its Selection mode
 * and Japanese characters only, docs/touch-lookup.md).
 */
data class Selection(
    /**
     * Whole word: the mark covers the dictionary's match, inflected ending
     * included, and the D-pad's Right goes past it. Character: the mark is the
     * one character the lookup starts from, and Right goes one character.
     */
    val wholeWord: Boolean = true,
    /** The cursor and a tap land only on kana and kanji; off, on Latin letters and digits too. Never on punctuation. */
    val japaneseOnly: Boolean = false,
    /**
     * The language's words are looked up from their first letter ([Language.wordStart]): a tap or the
     * cursor anywhere in a word takes the whole word, and a word with no entry is still stepped over whole.
     */
    val wordStart: Boolean = false,
)

/** Which characters can hold the cursor: Rubi's `vjo_text_selectable` (core/text_rows.c). */
object Selectable {
    // Rubi's table of Japanese letters (core/utf.c): the iteration marks, kana, kanji and their extensions.
    private val JAPANESE = intArrayOf(
        0x3005, 0x3007, 0x3021, 0x3029, 0x3031, 0x3035, 0x3038, 0x303C, 0x3041, 0x3096, 0x3099, 0x309A, 0x309D, 0x309F,
        0x30A1, 0x30FA, 0x30FC, 0x30FF, 0x31F0, 0x31FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xF900, 0xFA6D, 0xFA70, 0xFAD9,
        0xFF66, 0xFF9F, 0x16FE3, 0x16FE3, 0x16FF2, 0x16FF6, 0x1AFF0, 0x1AFF3, 0x1AFF5, 0x1AFFB, 0x1AFFD, 0x1AFFE,
        0x1B000, 0x1B122, 0x1B132, 0x1B132, 0x1B150, 0x1B152, 0x1B155, 0x1B155, 0x1B164, 0x1B167, 0x20000, 0x2A6DF,
        0x2A700, 0x2B81D, 0x2B820, 0x2CEAD, 0x2CEB0, 0x2EBE0, 0x2EBF0, 0x2EE5D, 0x2F800, 0x2FA1D, 0x30000, 0x3134A,
        0x31350, 0x33479,
    )

    fun isJapaneseLetter(codePoint: Int): Boolean {
        var i = 0
        while (i < JAPANESE.size) {
            if (codePoint < JAPANESE[i]) return false
            if (codePoint <= JAPANESE[i + 1]) return true
            i += 2
        }
        return false
    }

    /**
     * A character that can hold the cursor: a letter or a digit. Punctuation,
     * brackets, symbols and spaces never do, so a step goes from word to word
     * past the 、 and 。 between them (the owner, 2026-11-21: Rubi's own
     * default lands on them, and each was a stop with no entry).
     */
    fun codePoint(codePoint: Int, japaneseOnly: Boolean): Boolean =
        isJapaneseLetter(codePoint) || (!japaneseOnly && Character.isLetterOrDigit(codePoint))

    /** A character a word of a spaced language is spelt with: a letter, a digit, or a mark on one (an accent, a vowel point). */
    private fun inWord(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Where the word holding the character at [index] of [text] starts: for a language looked up from a word's first letter. */
    fun wordStart(text: String, index: Int): Int {
        var at = index.coerceIn(0, text.length)
        while (at > 0) {
            val before = text.codePointBefore(at)
            if (!inWord(before)) break
            at -= Character.charCount(before)
        }
        return at
    }

    /** Where the word starting at or holding [index] of [text] ends. */
    fun wordEnd(text: String, index: Int): Int {
        var at = index.coerceIn(0, text.length)
        while (at < text.length) {
            val here = text.codePointAt(at)
            if (!inWord(here)) break
            at += Character.charCount(here)
        }
        return at
    }

    /** Whether the character starting at [index] of [text] can hold the cursor. */
    fun at(text: String, index: Int, japaneseOnly: Boolean): Boolean =
        index in text.indices && !Character.isLowSurrogate(text[index]) && codePoint(text.codePointAt(index), japaneseOnly)
}

/**
 * The controller's cursor, ported from Rubi for Vita (`vjo_text_row_key`,
 * core/text_rows.c). It moves over the characters that can hold it, in reading
 * order across every row and line, and stops at the text's first and last.
 *
 * - The D-pad's Right goes past the word shown ([right]'s `matchedEnd`), or
 *   one character when there is none or in Character mode, and remembers where
 *   it started. Its Left retraces up to [HISTORY] of those; with none to
 *   retrace it steps one character back.
 * - The left stick goes one character either way, whatever word is shown. Its
 *   right is remembered as any Right; its left never retraces one.
 * - Up and Down go to the nearest row with a character, to the one nearest the
 *   column they started from, which repeated presses keep across a short row.
 * - A text on a single row goes round its ends for the D-pad, never the stick.
 *
 * Every move returns the same position when it cannot go further.
 */
class TextCursor(private val lines: List<TextLine>, private val japaneseOnly: Boolean = false) {
    /** A character that can hold the cursor, the row it is drawn on, and where it lies along that row. */
    private class Cell(val pos: CursorPos, val row: Int, val from: Int, val to: Int)

    /** Every such character in reading order. Rows count across lines: a wrapped sentence is one line of several rows. */
    private val cells: List<Cell> = buildList {
        var firstRow = 0
        lines.forEachIndexed { l, line ->
            val text = line.text
            // The box each character is in: its own, or its word's when the reader gave only the word one.
            val boxes = arrayOfNulls<CharBox>(text.length)
            for (c in line.chars) for (k in c.start.coerceAtLeast(0) until c.end.coerceAtMost(text.length)) if (boxes[k] == null) boxes[k] = c
            val placed = line.chars.isNotEmpty()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Selectable.codePoint(cp, japaneseOnly)) {
                    val box = boxes[i]
                    if (box == null) {
                        // No place on screen (a line from the store): one row, a column per character. A character a
                        // placed line has no box for (scrolled out of sight) is no stop: nothing could be marked there.
                        if (!placed) add(Cell(CursorPos(l, i), firstRow, i * 2, i * 2 + 2))
                    } else {
                        val along = if (line.vertical) box.box.top to box.box.bottom else box.box.left to box.box.right
                        add(Cell(CursorPos(l, i), firstRow + line.rowOf(box), along.first, along.second))
                    }
                }
                i += Character.charCount(cp)
            }
            firstRow += maxOf(1, line.rows.size)
        }
    }

    /** Where Right started, oldest first, since the last tap, row change or new text. */
    private val history = ArrayDeque<CursorPos>()

    /** Up and Down's column, kept while the cursor stays where they left it. */
    private var goal: Pair<CursorPos, Int>? = null

    /** Takes over [from]'s Rights and column: the same lines, only more of them typed out. */
    fun carry(from: TextCursor) {
        history.clear()
        history.addAll(from.history)
        goal = from.goal
    }

    /**
     * Where lookup mode starts: the first character of the longest line. On a
     * dialogue screen that is the dialogue, not the speaker's name above it.
     */
    fun first(): CursorPos? {
        val line = cells.map { it.pos.line }.distinct().maxByOrNull { lines[it].text.length } ?: return null
        return cells.first { it.pos.line == line }.pos
    }

    /** A tap, or anything else that put the cursor somewhere by itself: nothing to retrace, no column kept. */
    fun forget() {
        history.clear()
        goal = null
    }

    /**
     * The D-pad's Right from [pos]. [matchedEnd] is where the word shown at
     * [pos] ends in its line, or 0 for none (no entry yet, or Character mode).
     */
    fun right(pos: CursorPos, matchedEnd: Int = 0): CursorPos = forward(pos, matchedEnd, wrap = true)

    /** The D-pad's Left: back to where the last Right started, else one character back. */
    fun left(pos: CursorPos): CursorPos {
        goal = null
        val last = history.lastOrNull()
        if (last != null && last != pos) {
            history.removeLast()
            return last
        }
        val to = step(pos, pos, -1, wrap = true)
        if (to != pos) history.clear()
        return to
    }

    /** The left stick's right: the next character, never past the word shown. */
    fun stickRight(pos: CursorPos, matchedEnd: Int = 0): CursorPos = forward(pos, matchedEnd, wrap = false)

    /** The left stick's left: the character before, never a Right retraced. */
    fun stickLeft(pos: CursorPos): CursorPos {
        goal = null
        val to = step(pos, pos, -1, wrap = false)
        // The newest Right, if it started there or after, is done with; the ones before it stay for the D-pad.
        if (to != pos && history.isNotEmpty() && history.last() >= to) history.removeLast()
        return to
    }

    fun up(pos: CursorPos): CursorPos = vertical(pos, -1)
    fun down(pos: CursorPos): CursorPos = vertical(pos, +1)

    private fun forward(pos: CursorPos, matchedEnd: Int, wrap: Boolean): CursorPos {
        goal = null
        val text = lines.getOrNull(pos.line)?.text ?: return pos
        val past = matchedEnd > pos.index && matchedEnd <= text.length
        val from = if (past) CursorPos(pos.line, matchedEnd) else CursorPos(pos.line, pos.index + 1)
        val to = step(pos, from, +1, wrap)
        if (to != pos) {
            if (history.size == HISTORY) history.removeFirst()
            history.addLast(pos)
        }
        return to
    }

    /**
     * Forward: the first character at or after [from]. Back: the last one
     * before [cursor]. Past the text's end that way the cursor stays, unless
     * [wrap] and every character is on one row: then it goes round.
     */
    private fun step(cursor: CursorPos, from: CursorPos, direction: Int, wrap: Boolean): CursorPos {
        val to = if (direction > 0) cells.firstOrNull { it.pos >= from } else cells.lastOrNull { it.pos < cursor }
        if (to != null) return to.pos
        if (!wrap || cells.isEmpty() || cells.first().row != cells.last().row) return cursor
        return (if (direction > 0) cells.first() else cells.last()).pos
    }

    /** The nearest row above or below with a character, and on it the one nearest the column. */
    private fun vertical(pos: CursorPos, direction: Int): CursorPos {
        val here = cells.firstOrNull { it.pos == pos } ?: cells.lastOrNull { it.pos < pos } ?: cells.firstOrNull() ?: return pos
        // The column kept across presses, also when the lookup took the cursor back to its word's first letter on the same row.
        val column = goal?.takeIf { (at, _) ->
            at == pos || (at.line == pos.line && pos.index < at.index && lines.getOrNull(at.line)?.text?.let { Selectable.wordStart(it, at.index) } == pos.index)
        }?.second ?: ((here.from + here.to) / 2)
        val row = (if (direction < 0) cells.lastOrNull { it.row < here.row } else cells.firstOrNull { it.row > here.row })?.row ?: return pos
        // Inside a cell is no distance; else the distance to its nearer edge. The earlier wins a tie.
        val best = cells.filter { it.row == row }.minBy { if (column < it.from) it.from - column else if (column > it.to) column - it.to else 0 }
        goal = best.pos to column
        history.clear()
        return best.pos
    }

    companion object {
        /** How many Rights the D-pad's Left retraces. */
        const val HISTORY = 16
    }
}

/**
 * Held-button repeat, with Rubi's timings: the first repeat after 350 ms, then
 * one every 100 ms. With [fasterByMs], each repeat comes that much sooner than
 * the one before, from [firstMs] down to [nextMs].
 */
class KeyRepeat(private val firstMs: Long = 350, private val nextMs: Long = 100, private val fasterByMs: Long = 0) {
    private var held: Int? = null
    private var nextAt = 0L
    private var gap = firstMs

    /** A direction was pressed (or is still held). Returns true when a step should happen now. */
    fun press(direction: Int, nowMs: Long): Boolean {
        if (held != direction) {
            held = direction
            gap = firstMs
            nextAt = nowMs + gap
            return true
        }
        if (nowMs >= nextAt) {
            gap = if (fasterByMs > 0) maxOf(nextMs, gap - fasterByMs) else nextMs
            nextAt = nowMs + gap
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

    companion object {
        /**
         * The left stick's, as Rubi's: the next step after a sixth of a second,
         * then each 12.5 ms sooner, down to one every 50 ms (20 a second).
         */
        fun stick() = KeyRepeat(firstMs = 167, nextMs = 50, fasterByMs = 13)
    }
}
