// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * A reader's box for each character hugs that character's ink, so small っ
 * sits low and short, し is narrow, and ー is a thin bar: a mark drawn from
 * them looks to one side of the text. As GameSentenceMiner's overlay does,
 * every character but punctuation is given the line's own height (its width,
 * in vertical text), and a narrow full-width character grows towards the next
 * one, so each fills its cell.
 *
 * Two things are not GameSentenceMiner's, which draws no mark of its own
 * around a word. They come from ScreenAI's boxes on the AYN Thor's screens
 * (2026-11-23), where a quarter of the lines lost a tenth of their height:
 *
 *  - the cell runs from the highest letter's top to the lowest letter's
 *    bottom. Its boxes sit a few pixels up or down from one another, so "the
 *    highest top and the tallest height" stopped short of the lowest;
 *  - a full-width letter with nothing to grow towards (the first and last of
 *    a line, one beside punctuation or a wide gap) grows outwards to its
 *    cell's width: ScreenAI's box for る or い at the end of a bold line stops
 *    inside the stroke.
 */
object GlyphBoxes {
    /** The boxes of [chars], characters of [text] in one row, made even. Punctuation keeps its own. */
    fun even(text: String, chars: List<CharBox>, vertical: Boolean): List<CharBox> {
        if (chars.isEmpty()) return chars
        fun symbol(c: CharBox) = text.substring(c.start.coerceAtMost(text.length), c.end.coerceAtMost(text.length))
        val punctuation = chars.map { c -> symbol(c).none { it.isLetterOrDigit() } }
        val letters = chars.indices.filterNot { punctuation[it] }
        if (letters.isEmpty()) return chars
        // The cell across the reading direction: from the highest letter's top to the lowest letter's bottom.
        val start = letters.minOf { across(chars[it].box, vertical).first }
        val end = letters.maxOf { across(chars[it].box, vertical).second }
        val extent = letters.maxOf { across(chars[it].box, vertical).let { (a, b) -> b - a } }
        // Text set on a slope has no one cell: each letter keeps its own middle, as tall as the tallest.
        val sloped = end - start > extent * 3 / 2
        val evened = chars.mapIndexed { i, c ->
            if (punctuation[i]) return@mapIndexed c
            if (!sloped) return@mapIndexed c.copy(box = withAcross(c.box, vertical, start, end))
            val (a, b) = across(c.box, vertical)
            val from = a - (extent - (b - a)) / 2
            c.copy(box = withAcross(c.box, vertical, from, from + extent))
        }
        val wide = chars.map { fullWidth(symbol(it)) }
        // Each narrow full-width letter grows from the reader's own boxes, so growth never compounds.
        return evened.mapIndexed { i, c ->
            if (!wide[i]) return@mapIndexed c
            val (from, to) = along(chars[i].box, vertical)
            val span = to - from
            if (span <= 0 || extent <= span) return@mapIndexed c
            val before = chars.getOrNull(i - 1)
            val after = chars.getOrNull(i + 1)
            val gapBefore = before?.let { from - along(it.box, vertical).second }
            val gapAfter = after?.let { along(it.box, vertical).first - to }
            // A wide gap may part two words or a label from its line: it is not bridged.
            val joinsBefore = before != null && wide[i - 1] && gapBefore!! > 0 && gapBefore <= extent / 2
            val joinsAfter = after != null && wide[i + 1] && gapAfter!! > 0 && gapAfter <= extent / 2
            // Towards the next letter, most of the gap; the letter after takes what is left of it.
            val forward = when {
                joinsAfter -> minOf(gapAfter!! * 4 / 5, extent * 2 / 5, extent - span)
                else -> minOf((extent - span + 1) / 2, gapAfter?.coerceAtLeast(0) ?: Int.MAX_VALUE)
            }
            val back = when {
                joinsBefore -> minOf(gapBefore!! / 5, (extent - span) / 2)
                else -> minOf((extent - span) / 2, gapBefore?.coerceAtLeast(0) ?: Int.MAX_VALUE)
            }
            if (forward <= 0 && back <= 0) c
            else c.copy(box = withAlong(c.box, vertical, (from - back.coerceAtLeast(0)).coerceAtLeast(0), to + forward.coerceAtLeast(0)))
        }
    }

    /** One kanji, kana or ー, not a half-width katakana. */
    private fun fullWidth(symbol: String): Boolean {
        if (symbol.codePointCount(0, symbol.length) != 1) return false
        val c = symbol.codePointAt(0)
        if (c in 0xFF61..0xFF9F) return false
        if (c == 'ー'.code) return true
        return when (Character.UnicodeScript.of(c)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
            else -> false
        }
    }

    private fun across(b: Box, vertical: Boolean) = if (vertical) b.left to b.right else b.top to b.bottom
    private fun along(b: Box, vertical: Boolean) = if (vertical) b.top to b.bottom else b.left to b.right
    private fun withAcross(b: Box, vertical: Boolean, from: Int, to: Int) = if (vertical) b.copy(left = from, right = to) else b.copy(top = from, bottom = to)
    private fun withAlong(b: Box, vertical: Boolean, from: Int, to: Int) = if (vertical) b.copy(top = from, bottom = to) else b.copy(left = from, right = to)
}
