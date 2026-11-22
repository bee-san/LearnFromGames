// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * A reader's box for each character hugs that character's ink, so small っ
 * sits low and short, し is narrow, and ー is a thin bar: a mark drawn from
 * them looks to one side of the text. As GameSentenceMiner's overlay does,
 * every character but punctuation is given the line's own height (its width,
 * in vertical text), and a narrow full-width character grows towards the next
 * one, so each fills its cell.
 */
object GlyphBoxes {
    /** The boxes of [chars], characters of [text] in one row, made even. Punctuation keeps its own. */
    fun even(text: String, chars: List<CharBox>, vertical: Boolean): List<CharBox> {
        if (chars.isEmpty()) return chars
        fun symbol(c: CharBox) = text.substring(c.start.coerceAtMost(text.length), c.end.coerceAtMost(text.length))
        val punctuation = chars.map { c -> symbol(c).none { it.isLetterOrDigit() } }
        val letters = chars.indices.filterNot { punctuation[it] }
        if (letters.isEmpty()) return chars
        // The cell across the reading direction: from the highest letter's top, as tall as the tallest letter.
        val start = letters.minOf { across(chars[it].box, vertical).first }
        val extent = letters.maxOf { across(chars[it].box, vertical).let { (a, b) -> b - a } }
        val evened = chars.mapIndexed { i, c ->
            if (punctuation[i]) c else c.copy(box = withAcross(c.box, vertical, start, start + extent))
        }
        // Each full-width letter followed by another grows towards it, from the reader's own boxes so growth never compounds.
        return evened.mapIndexed { i, c ->
            val next = chars.getOrNull(i + 1) ?: return@mapIndexed c
            if (!fullWidth(symbol(chars[i])) || !fullWidth(symbol(next))) return@mapIndexed c
            val (from, to) = along(chars[i].box, vertical)
            val span = to - from
            val gap = along(next.box, vertical).first - to
            // A wide gap may part two words or a label from its line: it is not bridged.
            if (span <= 0 || extent <= span || gap <= 0 || gap > extent / 2) return@mapIndexed c
            val grow = minOf(gap * 4 / 5, extent * 2 / 5, extent - span)
            if (grow <= 0) c else c.copy(box = withAlongEnd(c.box, vertical, to + grow))
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
    private fun withAlongEnd(b: Box, vertical: Boolean, end: Int) = if (vertical) b.copy(bottom = end) else b.copy(right = end)
}
