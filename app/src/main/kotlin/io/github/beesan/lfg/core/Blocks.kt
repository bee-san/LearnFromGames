// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * Turns the rows a reader found into pieces of text as the game shows them.
 * A reader sees a wrapped sentence as two rows; a player reads one sentence,
 * wants it whole on a card, and may tap a word that the wrap cuts in two. Rows
 * that sit under one another like the lines of one text box become one
 * [TextLine]; a speaker's name, a menu's choices and separate paragraphs stay
 * apart.
 */
object Blocks {
    fun group(lines: List<TextLine>): List<TextLine> {
        val candidates = lines.filter { it.text.isNotBlank() }
        val kept = candidates.filter { line -> candidates.none { base -> isFuriganaOf(line, base) } }
        val horizontal = kept.filter { !it.vertical }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val vertical = kept.filter { it.vertical }.sortedWith(compareBy({ -it.box.right }, { it.box.top }))
        val blocks = mutableListOf<MutableList<TextLine>>()
        for (line in horizontal) {
            val home = blocks.lastOrNull { !it[0].vertical && follows(it, line) }
            if (home != null) home += line else blocks += mutableListOf(line)
        }
        for (line in vertical) {
            val home = blocks.lastOrNull { it[0].vertical && followsColumn(it, line) }
            if (home != null) home += line else blocks += mutableListOf(line)
        }
        // Reading order: top to bottom, then left to right, by where each piece starts.
        return blocks.flatMap(::nameApart).map(::join).sortedWith(compareBy({ it.rows[0].top }, { it.rows[0].left }))
    }

    /**
     * A speaker's name set just above the dialogue, at its left edge, joins it
     * as its first row; it is taken off again as a piece of its own, so the
     * cursor starts on the dialogue and a card's sentence does not begin with
     * the name. The test is GameSentenceMiner's overlay's: a short row, not a
     * sentence, much narrower than the dialogue, and set apart from it by a
     * smaller size or a wider gap than the dialogue's own; or a name in 【】,
     * or one over dialogue that opens with 「 when the name has none.
     */
    private fun nameApart(block: MutableList<TextLine>): List<MutableList<TextLine>> {
        if (block.size < 2 || block[0].vertical) return listOf(block)
        val name = block[0]
        val body = block.subList(1, block.size)
        return if (isName(name, body)) listOf(mutableListOf(name), body.toMutableList()) else listOf(block)
    }

    internal fun isName(name: TextLine, body: List<TextLine>): Boolean {
        val text = name.text.trim()
        val symbols = text.codePoints().filter { !Character.isWhitespace(it) }.count()
        if (symbols < 2 || symbols > 16 || text.last() in SENTENCE_END) return false
        val widest = body.maxOf { it.box.width }
        val heights = body.map { it.box.height }.sorted()
        val bodyHeight = heights[heights.size / 2].toFloat()
        if (widest <= 0 || bodyHeight <= 0) return false
        val n = name.box
        if (n.width > 0.65f * widest || n.height > 1.15f * bodyHeight) return false
        if (text.startsWith('【') && text.endsWith('】')) return true
        val opens = body[0].text.trimStart().firstOrNull()
        if (opens != null && opens in QUOTES && text.none { it in QUOTES }) return true
        if (n.height <= 0.9f * bodyHeight) return true
        val gap = body[0].box.top - n.bottom
        if (body.size == 1) return n.width <= 0.35f * widest && gap >= 0.6f * maxOf(n.height.toFloat(), bodyHeight)
        val gaps = body.zipWithNext { a, b -> b.box.top - a.box.bottom }.sorted()
        val usual = gaps[gaps.size / 2]
        return gap >= 0.35f * bodyHeight && gap >= 1.35f * usual
    }

    private const val SENTENCE_END = "。！？!?…‥.,，、：:；;」』）)"
    private const val QUOTES = "「『（(“\""

    /**
     * Whether [small] is furigana for [base]: kana in much smaller type,
     * sitting tight above (or, for vertical text, to the right of) kanji of
     * the row it annotates. It is reading aid, not text of the game: left in, it
     * would be a line of its own in the store and a row of its own on screen to
     * tap by mistake.
     *
     * A speaker's name above a line is also small kana, so the limits are
     * drawn from what the readers measured: furigana came out at 0.33 to 0.41
     * of its row's height and 0.25 to 0.29 of that height above it; names at
     * 0.46 and up, and twice as far away. A name that is set as small and as
     * close as furigana, over a kanji, is still taken for furigana.
     */
    fun isFuriganaOf(small: TextLine, base: TextLine): Boolean {
        if (small === base || small.vertical != base.vertical) return false
        if (!small.text.all { isKana(it) || it.isWhitespace() }) return false
        val s = small.box
        val b = base.box
        val beside = if (!base.vertical) {
            val h = b.height.toFloat()
            s.height <= 0.45f * h && s.bottom >= b.top - 0.4f * h && s.bottom <= b.top + 0.25f * h &&
                s.left >= b.left - 0.5f * h && s.right <= b.right + 0.5f * h
        } else {
            val w = b.width.toFloat()
            s.width <= 0.45f * w && s.left >= b.right - 0.25f * w && s.left <= b.right + 0.4f * w &&
                s.top >= b.top - 0.5f * w && s.bottom <= b.bottom + 0.5f * w
        }
        if (!beside) return false
        // Furigana is over kanji. What it sits over is found by position along the row.
        val under = base.chars.filter {
            if (base.vertical) it.box.top < s.bottom && s.top < it.box.bottom else it.box.left < s.right && s.left < it.box.right
        }
        // A reader that gives no box per character cannot say; the geometry then decides alone.
        return base.chars.isEmpty() || under.any { c -> base.text.substring(c.start, c.end).any(::isKanji) }
    }

    private fun isKana(c: Char) = c in '\u3041'..'\u309F' || c in '\u30A0'..'\u30FF' || c == '\u30FC'
    private fun isKanji(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c == '々' || c.isSurrogate()

    /** Whether [line] is the next row of the block: same size of type, just below, starting at the same edge. */
    private fun follows(block: List<TextLine>, line: TextLine): Boolean {
        val last = block.last().box
        val h = last.height.toFloat()
        if (h <= 0 || line.box.height <= 0) return false
        val ratio = line.box.height / h
        if (ratio < 0.75f || ratio > 1.33f) return false
        val gap = line.box.top - last.bottom
        if (gap < -0.35f * h || gap > 0.9f * h) return false
        // The edge the block's rows start at. A paragraph's first row may be indented by a character.
        val edge = if (block.size > 1) block[1].box.left else last.left
        val indent = if (block.size > 1) 0.6f * h else 1.4f * h
        return line.box.left >= edge - indent && line.box.left <= edge + 0.6f * h
    }

    /** The same for vertical text: the next column to the left, starting at the same height. */
    private fun followsColumn(block: List<TextLine>, line: TextLine): Boolean {
        val last = block.last().box
        val w = last.width.toFloat()
        if (w <= 0 || line.box.width <= 0) return false
        val ratio = line.box.width / w
        if (ratio < 0.75f || ratio > 1.33f) return false
        val gap = last.left - line.box.right
        if (gap < -0.35f * w || gap > 1.3f * w) return false
        return kotlin.math.abs(line.box.top - block[0].box.top) <= 0.8f * w
    }

    private fun join(rows: List<TextLine>): TextLine {
        if (rows.size == 1) return rows[0]
        val text = StringBuilder()
        val chars = ArrayList<CharBox>()
        for (row in rows) {
            val offset = text.length
            row.chars.mapTo(chars) { CharBox(it.start + offset, it.end + offset, it.box) }
            text.append(row.text)
        }
        return TextLine(text.toString(), rows.map { it.box }.reduce(Box::union), chars, rows[0].vertical, rows.map { it.box })
    }
}
