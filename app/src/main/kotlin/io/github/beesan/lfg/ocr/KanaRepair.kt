// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.TextLine

/**
 * A character read as its lookalike, put right by the dictionary: a pixel
 * font draws ば like は, ゃ like や and ソ like ン, and ScreenAI reads each
 * character without the word around it.
 *
 * The line is walked word by word, by longest dictionary match as a lookup
 * does. At each word, every character in it and the one after it is tried as
 * each of its lookalikes, one change at a time; a change is kept when it makes
 * the word starting there longer than any other change does (でんしや ->
 * でんしゃ, がつこう -> がっこう). Where both forms are words of the same
 * length (はかり, ばかり), what was read stays.
 *
 * Every lookalike is one UTF-16 unit, as the character it stands in for, so
 * the line's character boxes stay as they are.
 */
object KanaRepair {
    /** Characters drawn alike in a pixel font. A character may be in more than one group (つ: づ, っ). */
    private val GROUPS: List<String> = run {
        val voiced = listOf(
            "かが", "きぎ", "くぐ", "けげ", "こご", "さざ", "しじ", "すず", "せぜ", "そぞ",
            "ただ", "ちぢ", "つづ", "てで", "とど", "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        )
        val small = listOf("あぁ", "いぃ", "うぅ", "えぇ", "おぉ", "つっ", "やゃ", "ゆゅ", "よょ", "わゎ")
        val katakana = (voiced + small).map { group -> group.map { it + 0x60 }.joinToString("") }
        voiced + small + katakana + listOf("シツ", "ソン", "ノソ", "クワ", "ぬめ", "われね", "るろ")
    }

    private val ALIKE: Map<Char, List<Char>> = GROUPS
        .flatMap { group -> group.map { c -> c to group.filter { it != c } } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, others) -> others.flatten().distinct() }

    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー'

    private fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    /**
     * [line] with its lookalikes put right. [match] is how many characters
     * of a text its longest dictionary word covers, 0 for none.
     *
     * A line with a kanji in it is left as read: it is not a handheld's
     * kana-only text, and its kana are usually short words between kanji,
     * where a lookalike making a longer word is more often wrong than right.
     */
    suspend fun line(line: TextLine, match: suspend (String) -> Int): TextLine {
        if (line.text.any(::isKanji)) return line
        val text = StringBuilder(line.text)
        var at = 0
        while (at < text.length) {
            if (!isKana(text[at])) {
                at++
                continue
            }
            var length = match(text.substring(at))
            while (true) {
                var best = length
                var change: Pair<Int, Char>? = null
                for (i in at..minOf(at + length, text.length - 1)) {
                    val read = text[i]
                    for (other in ALIKE[read] ?: continue) {
                        text.setCharAt(i, other)
                        val got = match(text.substring(at))
                        text.setCharAt(i, read)
                        // Only a change inside the word it makes counts, and only one longer than any other.
                        if (got > best && got > i - at) {
                            best = got
                            change = i to other
                        }
                    }
                }
                val (i, other) = change ?: break
                text.setCharAt(i, other)
                length = best
            }
            at += maxOf(length, 1)
        }
        val fixed = text.toString()
        return if (fixed == line.text) line else line.copy(text = fixed)
    }
}
