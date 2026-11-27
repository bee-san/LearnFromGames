// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

object Sentence {
    fun escapeHtml(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("<br>")
            else -> append(c)
        }
    }

    /** The sentence as card HTML with `[start, end)` wrapped in [open] and [close]. */
    fun mark(sentence: String, start: Int, end: Int, open: String = "<b>", close: String = "</b>"): String {
        val s = start.coerceIn(0, sentence.length)
        val e = end.coerceIn(s, sentence.length)
        if (s == e) return escapeHtml(sentence)
        return escapeHtml(sentence.substring(0, s)) + open + escapeHtml(sentence.substring(s, e)) + close +
            escapeHtml(sentence.substring(e))
    }

    /**
     * Anki's plain furigana form, as Yomitan fills it: each run of kanji with its own reading, `食[た]べる`,
     * `取[と]り 引[ひ]き` (a space before a run that follows kana); the whole word with its reading when it
     * cannot be divided; just the word when the reading adds nothing.
     */
    fun furiganaPlain(expression: String, reading: String): String {
        if (reading.isEmpty() || reading == expression) return expression
        val rubies = Furigana.divide(expression, reading)
        if (rubies.isEmpty()) return "$expression[$reading]"
        return buildString {
            var at = 0
            for (ruby in rubies) {
                append(expression, at, ruby.start)
                if (ruby.start > 0) append(' ')
                append(expression, ruby.start, ruby.end).append('[').append(ruby.reading).append(']')
                at = ruby.end
            }
            append(expression, at, expression.length)
        }
    }
}
