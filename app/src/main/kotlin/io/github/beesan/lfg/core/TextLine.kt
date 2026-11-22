// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/** A rectangle in the pixels of the frame it was read from. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top

    fun contains(x: Int, y: Int, slop: Int = 0) =
        x >= left - slop && x < right + slop && y >= top - slop && y < bottom + slop

    fun intersects(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun union(other: Box) = Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    fun moved(dx: Int, dy: Int) = if (dx == 0 && dy == 0) this else Box(left + dx, top + dy, right + dx, bottom + dy)

    /** `left,top,right,bottom`, the form the `box` column stores. */
    fun encode() = "$left,$top,$right,$bottom"

    companion object {
        fun decode(text: String): Box? {
            val parts = text.split(',').mapNotNull { it.trim().toIntOrNull() }
            return if (parts.size == 4) Box(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }
}

/** One character of a line: the UTF-16 range it covers in the line's text, and where it sits. */
data class CharBox(val start: Int, val end: Int, val box: Box)

/**
 * The one record everything is fed from: a piece of text a reader found on a
 * frame. Lookup starts from a character of it, a card quotes it, and the store
 * keeps it.
 *
 * Straight from a reader it is one row of characters. After [Blocks.group] it
 * is a whole piece of text as the game shows it: a sentence wrapped over two
 * rows of a dialogue box is one line here, with a box for each row in [rows],
 * so a word can run from the end of one row into the next.
 */
data class TextLine(
    val text: String,
    val box: Box,
    val chars: List<CharBox>,
    val vertical: Boolean = false,
    /** Each row (or column, for vertical text) of the piece, in reading order. */
    val rows: List<Box> = listOf(box),
) {
    /** Where a point lies across the reading direction: y for horizontal text, x for vertical. */
    private fun across(x: Int, y: Int) = if (vertical) x else y
    private fun along(x: Int, y: Int) = if (vertical) y else x
    private fun Box.acrossRange() = if (vertical) left until right else top until bottom
    private fun Box.alongRange() = if (vertical) top until bottom else left until right

    /** The row a character sits in: the one its centre falls in, else the nearest. */
    fun rowOf(char: CharBox): Int {
        if (rows.size == 1) return 0
        val centre = across((char.box.left + char.box.right) / 2, (char.box.top + char.box.bottom) / 2)
        return rows.indices.minBy { distance(centre, rows[it].acrossRange()) }
    }

    private fun distance(value: Int, range: IntRange) = when {
        value < range.first -> range.first - value
        value > range.last -> value - range.last
        else -> 0
    }

    /**
     * The index in [text] of the character under a touch, or null when the
     * touch is outside the text. Inside a row the nearest character along the
     * reading direction wins, so a touch in the gap between two characters
     * still lands on one.
     */
    fun charIndexAt(x: Int, y: Int, slop: Int = 0): Int? {
        if (chars.isEmpty()) return null
        val row = rows.indices.filter { rows[it].contains(x, y, slop) }
            .minByOrNull { distance(across(x, y), rows[it].acrossRange()) } ?: return null
        val inRow = if (rows.size == 1) chars else chars.filter { rowOf(it) == row }
        val p = along(x, y)
        return inRow.minByOrNull { distance(p, it.box.alongRange()) + if (p > it.box.alongRange().last) 1 else 0 }?.start
    }

    /** The same text somewhere else on the display. */
    fun moved(dx: Int, dy: Int): TextLine = if (dx == 0 && dy == 0) this else copy(
        box = box.moved(dx, dy), chars = chars.map { it.copy(box = it.box.moved(dx, dy)) }, rows = rows.map { it.moved(dx, dy) },
    )

    /**
     * The boxes around the characters covering `[start, end)` of [text], one
     * for each row the span touches: a word split by a line break is marked
     * in both places.
     */
    fun spanBoxes(start: Int, end: Int): List<Box> {
        val hit = chars.filter { it.start < end && it.end > start }
        if (hit.isEmpty()) return emptyList()
        return hit.groupBy(::rowOf).toSortedMap().values.map { row -> row.map { it.box }.reduce(Box::union) }
    }

    /**
     * `[from, to)` of [text] as a line of its own, with the rows it is drawn
     * on: one sentence of a page that holds several. A row it shares with the
     * text before or after it is cut to its own characters.
     */
    fun slice(from: Int, to: Int): TextLine {
        val part = chars.filter { it.start >= from && it.end <= to }
        // No places to cut by: the text alone, where the whole piece is.
        if (part.isEmpty()) return copy(text = text.substring(from, to), chars = emptyList())
        val drawn = part.groupBy(::rowOf).toSortedMap().values.map { row -> row.map { it.box }.reduce(Box::union) }
        return TextLine(
            text.substring(from, to), drawn.reduce(Box::union), part.map { CharBox(it.start - from, it.end - from, it.box) }, vertical, drawn,
        )
    }

    /**
     * The size of its type in pixels: the middle one of its letters' larger
     * sides. 0 when no letter of it has a place.
     */
    val typeSize: Int
        get() {
            val sizes = chars.filter { it.start < text.length && Character.isLetterOrDigit(text.codePointAt(it.start)) }
                .map { maxOf(it.box.width, it.box.height) }.sorted()
            return if (sizes.isEmpty()) 0 else sizes[sizes.size / 2]
        }

    /** One box around the whole span, whatever rows it is on. */
    fun spanBox(start: Int, end: Int): Box? = spanBoxes(start, end).reduceOrNull(Box::union)
}
