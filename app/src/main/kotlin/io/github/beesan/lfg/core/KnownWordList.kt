// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.util.Locale

/**
 * Your own list of known words, as pasted in Settings › Anki: made into words,
 * and asked whether a word of a line is on it. A word on it is known whatever
 * Anki says. No Android in it, so tests can run it.
 */
object KnownWordList {
    /** What sets pasted words apart: a new line, spaces (an ideographic one too), commas and semicolons, Japanese ones too. */
    private val apart = Regex("[\\s\\u3000,，、;；]+")

    /** The words of pasted text, one a line or apart by spaces or commas: each once, in the order given. */
    fun parse(text: String): List<String> = text.split(apart).filter { it.isNotEmpty() }.distinctBy(::key)

    /** The words as the list's page shows them: one a line. */
    fun format(words: Collection<String>): String = words.joinToString("\n")

    /** The form a word is matched by: capitals do not matter. */
    fun key(word: String): String = word.lowercase(Locale.ROOT)

    /**
     * Whether a word of a line is on the list, whose words are [list] as [key]
     * makes them: by its dictionary form, as the marks are worked out on, or,
     * when the line writes it in kana alone, by its reading, so ありがとう on the
     * list is known where the dictionary's form is 有難う; or as the line writes it, so ネコ on the list is known
     * where the dictionary gives 猫, ねこ.
     */
    fun knows(list: Set<String>, expression: String, reading: String, surface: String): Boolean =
        key(expression) in list || (surface.none(Furigana::isKanji) && ((reading.isNotEmpty() && key(reading) in list) || key(surface) in list))
}
