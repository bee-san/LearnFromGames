// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/** A reading over a stretch of a line's kanji: the UTF-16 range it is over, in the line's text. */
data class Ruby(val start: Int, val end: Int, val reading: String)

/** How well Anki knows a word: its card is mature, it has a card still being learnt, or it has none. */
enum class Knowing { MATURE, LEARNING, NEW }

/** A word of a line, marked by its Anki card. */
data class WordMark(val start: Int, val end: Int, val knowing: Knowing)

/** What is drawn over one line of a game's text, named by the line's text: Furigana and Known words on the wheel. */
data class LineMarks(val text: String, val rubies: List<Ruby> = emptyList(), val words: List<WordMark> = emptyList())

/** Readings for the kanji of a word, from the dictionary's reading of the whole word. */
object Furigana {
    fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆'
    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー'
    private fun hiragana(text: String) = buildString { for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }

    /**
     * The readings over the kanji of a dictionary form: 取り引き read とりひき
     * gives と over 取 and ひ over 引. Kana in the word says where the reading
     * divides; a word it cannot be divided by has its whole reading over all
     * of its kanji.
     */
    fun divide(expression: String, reading: String): List<Ruby> {
        if (expression.none(::isKanji) || reading.isBlank()) return emptyList()
        val read = hiragana(reading)
        // Runs of kana and runs of everything else, in order.
        val runs = ArrayList<IntRange>()
        var from = 0
        for (i in 1..expression.length) {
            if (i == expression.length || isKana(expression[i]) != isKana(expression[from])) {
                runs += from until i
                from = i
            }
        }
        val pattern = runs.joinToString("", "^", "$") { run ->
            val piece = expression.substring(run.first, run.last + 1)
            if (isKana(piece[0])) Regex.escape(hiragana(piece)) else "(.+?)"
        }
        val match = Regex(pattern).find(read)
        val whole = listOf(Ruby(expression.indexOfFirst(::isKanji), expression.indexOfLast(::isKanji) + 1, read))
        match ?: return if (runs.size == 1) whole else emptyList()
        var group = 0
        return runs.filter { !isKana(expression[it.first]) }.map { run -> Ruby(run.first, run.last + 1, match.groupValues[++group]) }
    }

    /**
     * The readings over [surface], a word as a line writes it (見た), from its
     * dictionary form (見る) and that form's reading (みる): those of the
     * kanji the two share from their start, which an inflection leaves alone.
     */
    fun of(surface: String, expression: String, reading: String): List<Ruby> {
        val rubies = divide(expression, reading)
        return rubies.mapNotNull { ruby ->
            if (ruby.end > surface.length || !surface.regionMatches(0, expression, 0, ruby.end)) return@mapNotNull null
            // 来る is read by what follows it: 来ない is こない, 来た is きた.
            if (expression.endsWith("来る") && ruby.end == expression.length - 1 && ruby.reading.endsWith("く")) {
                val next = surface.getOrNull(ruby.end) ?: return@mapNotNull null
                val said = KURU[next] ?: return@mapNotNull null
                return@mapNotNull ruby.copy(reading = ruby.reading.dropLast(1) + said)
            }
            ruby
        }
    }

    private val KURU = mapOf(
        'な' to 'こ', 'ら' to 'こ', 'よ' to 'こ', 'い' to 'こ', 'さ' to 'こ', 'ず' to 'こ',
        'て' to 'き', 'た' to 'き', 'ま' to 'き', 'ち' to 'き', 'そ' to 'き',
        'る' to 'く', 'れ' to 'く',
    )

    /** Whether a word is worth marking by its Anki card: not a particle or an ending, which are short and all hiragana. */
    fun worthKnowing(surface: String): Boolean = surface.any(::isKanji) || surface.length >= 3 || (surface.length == 2 && surface.all { it in 'ァ'..'ヺ' || it == 'ー' })

    /** Whether lookup can start at this character: kana or kanji. */
    fun japanese(c: Char) = isKanji(c) || isKana(c)
}
