// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

/**
 * Romaji to hiragana, katakana and hiragana either way, and kanji detection, ported
 * from kana.rs. Text is walked by code point, as Rust's char is one, so kanji past
 * the Basic Multilingual Plane count as one character.
 */
object Kana {
    fun containsKanji(text: String): Boolean = text.codePoints().anyMatch(::isKanji)

    /** CJK ideographs, Extensions A to H, the compatibility ideographs, and 々, which repeats the kanji before it. */
    fun isKanji(c: Int): Boolean =
        c in 0x4E00..0x9FFF ||
            c in 0x3400..0x4DBF ||
            c in 0x20000..0x2A6DF ||
            c in 0x2A700..0x2B73F ||
            c in 0x2B740..0x2B81F ||
            c in 0x2B820..0x2CEAF ||
            c in 0x2CEB0..0x2EBEF ||
            c in 0x30000..0x3134F ||
            c in 0x31350..0x323AF ||
            c in 0xF900..0xFAFF ||
            c in 0x2F800..0x2FA1F ||
            c == 0x3005

    private fun isHiragana(c: Int): Boolean = c in 0x3041..0x3096

    private fun isKatakana(c: Int): Boolean = c in 0x30A1..0x30F6 || c == 0x30FC

    fun containsJapanese(text: String): Boolean = text.codePoints().anyMatch { isKanji(it) || isHiragana(it) || isKatakana(it) }

    /** ァ to ヶ become ぁ to ゖ; everything else, ー included, is kept. */
    fun kataToHira(text: String): String = mapCodePoints(text) { if (it in 0x30A1..0x30F6) it - 0x60 else it }

    fun hiraToKata(text: String): String = mapCodePoints(text) { if (it in 0x3041..0x3096) it + 0x60 else it }

    private inline fun mapCodePoints(text: String, f: (Int) -> Int): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            out.appendCodePoint(f(c))
            i += java.lang.Character.charCount(c)
        }
        return out.toString()
    }

    /** An apostrophe, hyphen or full stop between syllables: "Shin'ichi" is しんいち, not しにち. */
    private fun isSyllableBoundary(c: Int): Boolean =
        c == '\''.code || c == 0x2019 || c == 0x2018 || c == '-'.code || c == '.'.code

    /**
     * Romanised text as hiragana: doubled consonants as っ, n as ん unless a vowel or
     * y follows, boundary marks dropped, spaces kept, other non-letters dropped and
     * letters that make no syllable passed through.
     */
    fun alphabetToKana(input: String): String {
        val chars = input.lowercase().codePoints().toArray()
        val result = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (isSyllableBoundary(c)) {
                i++
                continue
            }
            if (!isAsciiAlphabetic(c) && c != ' '.code) {
                i++
                continue
            }
            if (c == ' '.code) {
                result.append(' ')
                i++
                continue
            }
            if (i + 1 < chars.size && chars[i] == chars[i + 1] && isConsonant(chars[i])) {
                result.append('っ')
                i++
                continue
            }
            if (i + 3 <= chars.size) {
                val kana = lookupRomaji(chars, i, 3)
                if (kana != null) {
                    result.append(kana)
                    i += 3
                    continue
                }
            }
            if (i + 2 <= chars.size) {
                val kana = lookupRomaji(chars, i, 2)
                if (kana != null) {
                    result.append(kana)
                    i += 2
                    continue
                }
            }
            if (chars[i] == 'n'.code) {
                val next = chars.getOrNull(i + 1)
                if (next == null || !isVowelOrY(next)) {
                    result.append('ん')
                    i++
                    continue
                }
            }
            val kana = lookupRomaji(chars, i, 1)
            if (kana != null) result.append(kana) else result.appendCodePoint(chars[i])
            i++
        }
        return result.toString()
    }

    private fun isAsciiAlphabetic(c: Int): Boolean = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    private fun isConsonant(c: Int): Boolean = c in 'a'.code..'z'.code && c.toChar() !in "aeiou"

    private fun isVowelOrY(c: Int): Boolean = c < 0x80 && c.toChar() in "aiueoy"

    private fun lookupRomaji(chars: IntArray, start: Int, length: Int): String? {
        val key = StringBuilder(length)
        for (k in start until start + length) key.appendCodePoint(chars[k])
        return ROMAJI[key.toString()]
    }

    private val ROMAJI: Map<String, String> = hashMapOf(
        // Three letters: Hepburn
        "sha" to "しゃ", "shi" to "し", "shu" to "しゅ", "sho" to "しょ", "she" to "しぇ",
        "chi" to "ち", "tsu" to "つ", "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "che" to "ちぇ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        // Three letters: Nihon-shiki and Kunrei-shiki
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
        // Three letters: foreign sounds
        "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ",
        // Two letters
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "ji" to "じ", "je" to "じぇ",
        "la" to "ら", "li" to "り", "lu" to "る", "le" to "れ", "lo" to "ろ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "wa" to "わ", "wi" to "ゐ", "we" to "ゑ", "wo" to "を",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        // One letter: vowels only; n is handled on its own
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
    )
}
