// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * One piece of text an app gave through accessibility: a `TextView`, a run of
 * text in a WebView or Chrome page. [chars] is where each UTF-16 character of
 * [text] is drawn, in display pixels, as Android reports it; null where the
 * app gave no positions, and a null in it for a character that is not drawn
 * (scrolled away, or a line break).
 */
data class TreeNode(
    val text: String,
    /** The part of the node on screen. */
    val bounds: Box,
    val chars: List<Box?>? = null,
    /** A password field, or a node marked as holding sensitive data: never read. */
    val sensitive: Boolean = false,
)

/**
 * Text from the accessibility tree, as the same [TextLine]s a reader makes
 * from pixels. Where an app draws real text this is exact and costs no model;
 * where it does not, the tree is empty there and OCR fills in.
 */
object TreeText {
    /**
     * The lines of [nodes] that show on [screen]. Each paragraph of a node is
     * one line, its wrapped rows kept as [TextLine.rows], so a sentence that
     * wraps is one sentence as it is from a game's dialogue box.
     */
    fun lines(nodes: List<TreeNode>, screen: Box): List<TextLine> {
        val out = ArrayList<TextLine>()
        val seen = HashSet<Pair<String, Box>>()
        for (node in nodes) {
            if (node.sensitive || node.text.isBlank() || node.bounds.width <= 0 || node.bounds.height <= 0) continue
            if (!node.bounds.intersects(screen)) continue
            val boxes = node.chars?.takeIf { it.size == node.text.length && it.any { b -> b != null } } ?: estimate(node)
            var start = 0
            while (start <= node.text.length) {
                val end = node.text.indexOf('\n', start).let { if (it < 0) node.text.length else it }
                paragraph(node, boxes, start, end, screen)?.let { if (seen.add(it.text to it.box)) out += it }
                start = end + 1
            }
        }
        return out.sortedWith(compareBy({ it.rows[0].top }, { it.rows[0].left }))
    }

    private fun paragraph(node: TreeNode, boxes: List<Box?>, start: Int, end: Int, screen: Box): TextLine? {
        val text = node.text.substring(start, end).trimEnd('\r')
        if (text.isBlank()) return null
        val chars = ArrayList<CharBox>()
        var i = start
        while (i < start + text.length) {
            val width = if (Character.isHighSurrogate(node.text[i]) && i + 1 < end) 2 else 1
            val box = boxes.getOrNull(i)
            // Drawn, and on screen inside the node: a character scrolled out of its view has a box but is not seen.
            if (box != null && box.width > 0 && box.height > 0 && box.intersects(node.bounds) && box.intersects(screen) &&
                !node.text[i].isWhitespace()
            ) {
                chars += CharBox(i - start, i - start + width, box)
            }
            i += width
        }
        if (chars.isEmpty()) return null
        val vertical = isVertical(chars)
        val rows = rows(chars, vertical)
        return TextLine(text, rows.reduce(Box::union), chars, vertical, rows)
    }

    /** Whether characters run down columns: most of them sit under the one before, not beside it. */
    private fun isVertical(chars: List<CharBox>): Boolean {
        if (chars.size < 3) return false
        var down = 0
        var across = 0
        for (k in 1 until chars.size) {
            val a = chars[k - 1].box
            val b = chars[k].box
            val overlapX = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (overlapX > 0 && b.top >= a.bottom - a.height / 3) down++
            if (overlapY > 0 && b.left >= a.right - a.width / 3) across++
        }
        return down > across
    }

    /** The rows (or columns) the characters wrap into, in reading order. */
    private fun rows(chars: List<CharBox>, vertical: Boolean): List<Box> {
        val rows = ArrayList<Box>()
        var row = chars[0].box
        for (k in 1 until chars.size) {
            val prev = chars[k - 1].box
            val b = chars[k].box
            val wrapped = if (vertical) {
                b.right <= prev.left + prev.width / 4 || b.top < prev.top - prev.width / 2
            } else {
                b.top >= prev.bottom - prev.height / 4 || b.left < prev.left - prev.height / 2
            }
            if (wrapped) {
                rows += row
                row = b
            } else {
                row = row.union(b)
            }
        }
        rows += row
        return rows
    }

    /**
     * Places for the characters of a node that gave none: square characters
     * filling its box row by row. Close enough to land a tap on the right
     * word in an ordinary line of text; a line [lines] makes from it reads
     * whole and exactly, only its places are guessed.
     */
    fun estimate(node: TreeNode): List<Box?> {
        val b = node.bounds
        val text = node.text
        val count = text.count { it != '\n' && !Character.isLowSurrogate(it) }.coerceAtLeast(1)
        // One row if the text fits the box in square characters of its height; else rows of the size that fills it.
        val size = if (count.toLong() * b.height <= b.width * 13L / 10) b.height else
            kotlin.math.sqrt(b.width.toDouble() * b.height / count).toInt().coerceAtLeast(1)
        val perRow = (b.width / size).coerceAtLeast(1)
        val step = if (count <= perRow) b.width / count.coerceAtLeast(1) else size
        val out = ArrayList<Box?>(text.length)
        var row = 0
        var col = 0
        for (c in text) {
            when {
                c == '\n' -> {
                    out += null
                    // A line break starts the next row.
                    row++
                    col = 0
                }
                Character.isLowSurrogate(c) -> out += out.lastOrNull()
                else -> {
                    if (col == perRow) {
                        row++
                        col = 0
                    }
                    val top = b.top + row * size
                    out += Box(b.left + col * step, top, b.left + (col + 1) * step, top + size)
                    col++
                }
            }
        }
        return out
    }

    /**
     * Where the tree and OCR both found text, the tree's is exact and wins:
     * an OCR line mostly under a tree line goes. OCR keeps what the tree does
     * not have (pictures in a page, text drawn on a canvas, video frames), and
     * nothing read from over a password field is kept.
     */
    fun preferTree(tree: List<TextLine>, ocr: List<TextLine>, sensitive: List<Box> = emptyList()): List<TextLine> {
        val treeRows = tree.flatMap { it.rows }
        val kept = ocr.filter { line ->
            if (sensitive.any { it.intersects(line.box) }) return@filter false
            val area = line.box.width.toLong() * line.box.height
            val covered = treeRows.sumOf { overlap(it, line.box) }
            area <= 0 || covered * 5 < area * 2
        }
        return (tree + kept).sortedWith(compareBy({ it.rows[0].top }, { it.rows[0].left }))
    }

    /** The lines mostly inside [region]: what a dragged box asks to have read. */
    fun within(lines: List<TextLine>, region: Box): List<TextLine> = lines.filter { line ->
        val area = line.box.width.toLong() * line.box.height
        area > 0 && overlap(line.box, region) * 2 >= area
    }

    private fun overlap(a: Box, b: Box): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0 && h > 0) w.toLong() * h else 0
    }
}
