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

    /** Anki's plain furigana form, `不思議[ふしぎ]`; just the word when the reading adds nothing. */
    fun furiganaPlain(expression: String, reading: String): String =
        if (reading.isEmpty() || reading == expression) expression else "$expression[$reading]"
}
