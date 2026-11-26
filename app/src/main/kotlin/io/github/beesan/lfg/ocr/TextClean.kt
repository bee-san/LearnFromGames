// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.Selectable
import io.github.beesan.lfg.core.TextLine
import java.text.Normalizer

/**
 * What a reader read, made into the text the game drew, for every reader
 * alike. The rules are Rubi's (Rubi_for_vita core/textnorm.h, measured on
 * vita-vn-ocr's game text), each character keeping its box:
 *
 *  - width: full-width ASCII becomes ASCII (！ -> !, Ａ -> A) and half-width
 *    katakana full-width (ｶﾞ -> ガ), so a word is looked up as one form;
 *  - tildes 〜 ∼ become ~, and the dashes — – ‐ ‑ ‒ − ─ ━ the Japanese dash ―;
 *  - ellipses: "..." becomes …, and a run of two or more ・ one … for every
 *    three (at least one); a lone ・, as in a name, stays;
 *  - ッ read for the counter ヶ becomes ヶ before 月 or 年, and before 国, 条 or
 *    所 after a numeral (CLANNAD's 九ヶ月 read as 九ッ月);
 *  - ー or 一 by context: 一 after katakana becomes ー unless a kanji, a
 *    hiragana or ヵ ヶ follows (コ一ヒ一 -> コーヒー, but コーヒー一杯 stays),
 *    and after hiragana when what follows ends a drawn-out sound (だよ一っ);
 *    ー between two kanji becomes 一 (統ー的 -> 統一的);
 *  - a line that is only a text box's border read as text (ーニーーニー…) goes.
 *
 * Two more come from the OCR bench (tools/ocr_bench.py):
 *
 *  - a bracket read as a letter (「 as [ J E Г L, 」 as ] J Г L) at one end
 *    of a line whose other end has its pair, or at both ends around Japanese;
 *  - a line of letters or digits with nothing Japanese in it goes: a menu's
 *    English, a number, scene art. A line of marks alone (……, !?) stays.
 *
 * And one from the AYN Thor (Senren Banka's quick-save prompt, 2026-11-22):
 *
 *  - a piece with no letter or digit in it that is not made of the marks a
 *    line of dialogue is made of alone (……, !?, ――, 「」, ♪) goes: a
 *    checkbox, an icon or the text box's cursor read as a character (□, ▼).
 *
 * And two for games drawn in pixels, as Pokémon is:
 *
 *  - the box's cursor at either end of a line (the ▼ that asks for a button,
 *    the ▶ by a choice) goes, so a blinking cursor is not part of the line;
 *  - a piece made only of the kanji a tiled picture is read as (田, 回, 口,
 *    目, 川: grass, bricks, windows, water) goes.
 *
 * And two for a handheld's pixel font, where kana and kanji are drawn alike:
 *
 *  - script twins: a kanji drawn like a katakana (口 工 二 力 夕 八 卜 才)
 *    becomes it when katakana is on one side and no kanji on the other
 *    (口ケット -> ロケット, 力一ド -> カード, but 人口 and 口を stay); a
 *    hiragana へ べ ぺ り before katakana becomes katakana, and a katakana
 *    ヘ ベ ペ リ before hiragana becomes hiragana (ヘや -> へや);
 *  - spaces from boxes: on a line whose characters sit on one grid, as a
 *    pixel font's do, ScreenAI's spaces between two characters give way to
 *    what the boxes show: one full-width space where at least one cell is
 *    empty, none where there is not.
 */
object TextClean {
    /** One piece of the line: usually one character and its box; a space between words has none. */
    private class Piece(var text: String, var box: Box?)

    /**
     * [line] cleaned, or null when nothing of the game's text is left in it. With [japanese] off the
     * line is in another language: it loses only the box's cursor at its ends, and goes when no letter is in it.
     */
    fun line(line: TextLine, japanese: Boolean = true): TextLine? {
        val pieces = pieces(line)
        cursors(pieces)
        if (!japanese) {
            val text = pieces.joinToString("") { it.text }
            if (text.codePoints().noneMatch(Character::isLetter)) return null
            return rebuilt(line, text, pieces)
        }
        spaces(pieces, line.vertical)
        forms(pieces)
        twins(pieces)
        longVowels(pieces)
        brackets(pieces)
        val text = pieces.joinToString("") { it.text }
        if (border(text) || foreign(text) || stray(text) || tiles(text)) return null
        return rebuilt(line, text, pieces)
    }

    /** [line] as [pieces] now spell it, each character's box kept with it. */
    private fun rebuilt(line: TextLine, text: String, pieces: List<Piece>): TextLine {
        if (text == line.text) return line
        val chars = ArrayList<CharBox>(pieces.size)
        var at = 0
        for (piece in pieces) {
            piece.box?.let { chars += CharBox(at, at + piece.text.length, it) }
            at += piece.text.length
        }
        return line.copy(text = text, chars = chars)
    }

    private fun pieces(line: TextLine): MutableList<Piece> {
        val text = line.text
        val out = ArrayList<Piece>(text.length)
        fun loose(from: Int, to: Int) {
            var i = from
            while (i < to) {
                val n = Character.charCount(text.codePointAt(i))
                out += Piece(text.substring(i, i + n), null)
                i += n
            }
        }
        var at = 0
        for (c in line.chars.sortedBy { it.start }) {
            if (c.start < at || c.end > text.length) continue
            loose(at, c.start)
            out += Piece(text.substring(c.start, c.end), c.box)
            at = c.end
        }
        loose(at, text.length)
        return out
    }

    /** The box's cursor at either end of [pieces], with the spaces beside it. */
    private fun cursors(pieces: MutableList<Piece>) {
        if (pieces.none { it.text.isNotBlank() && it.text !in CURSORS }) return
        fun word(i: Int) = pieces[i].text.isNotBlank()
        while (true) {
            val first = pieces.indices.first(::word)
            if (pieces[first].text !in CURSORS) break
            val next = (first + 1 until pieces.size).first(::word)
            pieces.subList(0, next).clear()
        }
        while (true) {
            val last = pieces.indices.last(::word)
            if (pieces[last].text !in CURSORS) break
            val before = (0 until last).last(::word)
            pieces.subList(before + 1, pieces.size).clear()
        }
    }

    /**
     * How far a character's centre may sit from its cell's, as a share of a cell: a small kana or a 、 does not
     * fill its cell, so its centre moves. A gap is then 1.65 cells or more, and between 1.35 and 1.65 the line
     * is not taken to be on a grid.
     */
    private const val OFF_GRID = 0.35

    /** ScreenAI's spaces between two characters replaced by what the boxes show, on a line set on one grid. */
    private fun spaces(pieces: MutableList<Piece>, vertical: Boolean) {
        if (pieces.any { p -> p.box != null && (p.text.length != 1 || isLatin(cp(p))) }) return
        fun along(b: Box) = if (vertical) (b.top + b.bottom) / 2.0 else (b.left + b.right) / 2.0
        fun across(b: Box) = if (vertical) (b.left + b.right) / 2.0 else (b.top + b.bottom) / 2.0
        fun thickness(b: Box) = if (vertical) b.width else b.height
        // Two characters one after the other on the same row, with nothing but blanks between them.
        class Step(val from: Int, val to: Int, val distance: Double)
        val steps = ArrayList<Step>()
        var last = -1
        for (i in pieces.indices) {
            val p = pieces[i]
            val box = p.box
            if (box == null) {
                if (p.text.isNotBlank()) last = -1
                continue
            }
            val before = pieces.getOrNull(last)?.box
            if (before != null && kotlin.math.abs(across(box) - across(before)) < thickness(before) / 2.0) {
                steps += Step(last, i, along(box) - along(before))
            }
            last = i
        }
        if (steps.size < 2) return
        val least = steps.minOf { it.distance }
        if (least <= 0) return
        val near = steps.map { it.distance }.filter { it <= least * 1.5 }.sorted()
        val pitch = near[near.size / 2]
        val cells = steps.map { it.distance / pitch }
        if (cells.any { it < 1 - OFF_GRID || kotlin.math.abs(it - kotlin.math.round(it)) > OFF_GRID }) return
        for (k in steps.indices.reversed()) {
            val step = steps[k]
            pieces.subList(step.from + 1, step.to).clear()
            if (kotlin.math.round(cells[k]) >= 2) pieces.add(step.from + 1, Piece("　", null))
        }
    }

    private fun union(a: Box?, b: Box?) = if (a == null) b else if (b == null) a else a.union(b)

    private fun cp(p: Piece?) = if (p == null || p.text.isEmpty()) 0 else p.text.codePointAt(0)

    private fun isDash(c: Int) = c in 0x2010..0x2014 || c == 0x2212 || c == 0x2500 || c == 0x2501

    private fun isCount(c: Int) = c in '0'.code..'9'.code || c in 0xFF10..0xFF19 || c in COUNT

    private val COUNT = "〇一二三四五六七八九十百千万何数幾".map { it.code }.toSet()

    /** Width, tildes, dashes, the counter ヶ and ellipses. */
    private fun forms(pieces: MutableList<Piece>) {
        var i = 0
        while (i < pieces.size) {
            val p = pieces[i]
            val c = cp(p)
            when {
                c in 0xFF01..0xFF5E && p.text.length == 1 -> p.text = (c - 0xFEE0).toChar().toString()
                c in 0xFF61..0xFF9F -> {
                    p.text = Normalizer.normalize(p.text, Normalizer.Form.NFKC)
                    // A half-width ﾞ or ﾟ is its own character: it joins the kana before it.
                    val mark = cp(p)
                    if ((mark == 0x3099 || mark == 0x309A) && i > 0) {
                        val prev = pieces[i - 1]
                        prev.text = Normalizer.normalize(prev.text + p.text, Normalizer.Form.NFC)
                        prev.box = union(prev.box, p.box)
                        pieces.removeAt(i)
                        continue
                    }
                }
                c == 0x301C || c == 0x223C -> p.text = "~"
                isDash(c) -> p.text = "―"
                c == 'ッ'.code -> {
                    val prev = cp(pieces.getOrNull(i - 1))
                    val next = cp(pieces.getOrNull(i + 1))
                    if (next == '月'.code || next == '年'.code ||
                        (isCount(prev) && (next == '国'.code || next == '条'.code || (next == '所'.code && prev != '一'.code)))
                    ) p.text = "ヶ"
                }
            }
            i++
        }
        // Dots, once every piece has its folded form (．is . by now).
        i = 0
        while (i < pieces.size) {
            val c = cp(pieces[i])
            // A run is of single dots only: a box ScreenAI gave to several ("..", or a word with no
            // symbols of its own) is left as it is, and stepping past it always moves on.
            fun single(k: Int) = pieces.getOrNull(k)?.let { cp(it) == c && it.text.length == 1 } == true
            var j = i
            if (c == '.'.code || c == '・'.code) while (single(j)) j++
            val run = j - i
            // Periods three to an ellipsis; middle dots two or more, so a lone one in a name stays.
            val ellipses = when {
                c == '.'.code -> run / 3
                c == '・'.code && run >= 2 -> if (run >= 3) run / 3 else 1
                else -> 0
            }
            if (ellipses == 0) {
                i = maxOf(j, i + 1)
                continue
            }
            fun span(from: Int, to: Int) = pieces.subList(from, to).fold(null as Box?) { box, p -> union(box, p.box) }
            // Periods: three to an ellipsis, any left over stay. Middle dots: shared out among the ellipses.
            val made = if (c == '.'.code) {
                (0 until ellipses).map { e -> Piece("…", span(i + e * 3, i + e * 3 + 3)) } + pieces.subList(i + ellipses * 3, j).toList()
            } else {
                (0 until ellipses).map { e -> Piece("…", span(i + e * run / ellipses, i + (e + 1) * run / ellipses)) }
            }
            pieces.subList(i, j).clear()
            pieces.addAll(i, made)
            i += made.size
        }
    }

    private fun isHira(c: Int) = c in 0x3041..0x3096 || c == 0x309D || c == 0x309E
    private fun isKata(c: Int) = c in 0x30A1..0x30FA || c == 'ー'.code || c == 0x30FD || c == 0x30FE
    private fun isKanji(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF || c == '々'.code || c == '〆'.code

    /** ぁぃぅぇぉっゃゅょゎ and their katakana; not the counters ヵ ヶ. */
    private fun isSmall(code: Int): Boolean {
        val c = if (code in 0x30A1..0x30F6) code - 0x60 else code
        return c - 0x3040 in SMALL
    }

    private val SMALL = setOf(0x01, 0x03, 0x05, 0x07, 0x09, 0x23, 0x43, 0x45, 0x47, 0x4E)

    /** What may follow a sound drawn out after a hiragana; not the end of a line, which is often mid-word (もう一 / 度). */
    private fun endsElongation(c: Int) =
        isSmall(c) || c == 'ー'.code || c == '~'.code || c == '!'.code || c == '?'.code || c == '…'.code || c == '‥'.code ||
            c == '」'.code || c == '』'.code

    private val KANJI_TWINS = mapOf('口' to "ロ", '工' to "エ", '二' to "ニ", '力' to "カ", '夕' to "タ", '八' to "ハ", '卜' to "ト", '才' to "オ")
        .mapKeys { it.key.code }
    private val KANA_TWINS = mapOf('へ' to 'ヘ', 'べ' to 'ベ', 'ぺ' to 'ペ', 'り' to 'リ').entries
        .flatMap { (h, k) -> listOf(h.code to k.toString(), k.code to h.toString()) }.toMap()

    /** Katakana a word is spelt with: not the counters ヵ ヶ. */
    private fun isKataWord(c: Int) = isKata(c) && c != 'ヵ'.code && c != 'ヶ'.code

    /** A kanji drawn like a katakana, or a hiragana like a katakana, made the script its neighbours are in. */
    private fun twins(pieces: MutableList<Piece>) {
        for (i in pieces.indices) {
            val p = pieces[i]
            if (p.text.length != 1) continue
            val c = cp(p)
            KANJI_TWINS[c]?.let { kana ->
                // The nearest neighbour that is not a twin itself; to the right 一 is passed over too, as it
                // may be a long vowel read as a kanji (力一ド).
                fun near(step: Int): Int {
                    var k = i + step
                    while (k in pieces.indices) {
                        val n = cp(pieces[k])
                        if (n !in KANJI_TWINS && !(step > 0 && n == '一'.code)) return n
                        k += step
                    }
                    return 0
                }
                val prev = near(-1)
                val next = near(1)
                if ((isKataWord(prev) && !isKanji(next)) || (isKataWord(next) && !isKanji(prev))) p.text = kana
            }
            KANA_TWINS[c]?.let { other ->
                val prev = cp(pieces.getOrNull(i - 1))
                val next = cp(pieces.getOrNull(i + 1))
                if (isHira(c) && isKataWord(next) && !isHira(prev)) p.text = other
                if (isKata(c) && isHira(next) && !isKataWord(prev)) p.text = other
            }
        }
    }

    private fun longVowels(pieces: MutableList<Piece>) {
        var prev = 0
        var i = 0
        while (i < pieces.size) {
            val c = cp(pieces[i])
            if (c == '一'.code && (isKata(prev) || isHira(prev))) {
                var j = i
                while (j < pieces.size && (cp(pieces[j]) == '一'.code || cp(pieces[j]) == 'ー'.code)) j++
                val next = cp(pieces.getOrNull(j))
                val vowel = if (isKata(prev)) {
                    !isKanji(next) && !(isHira(next) && !isSmall(next)) && next != 'ヵ'.code && next != 'ヶ'.code
                } else {
                    endsElongation(next)
                }
                if (vowel) {
                    while (i < pieces.size && cp(pieces[i]) == '一'.code) pieces[i++].text = "ー"
                    prev = 'ー'.code
                    continue
                }
            } else if (c == 'ー'.code && isKanji(prev) && prev != '一'.code) {
                val next = cp(pieces.getOrNull(i + 1))
                if (isKanji(next) && next != '一'.code) pieces[i].text = "一"
            }
            prev = cp(pieces[i])
            i++
        }
    }

    private val OPEN_LOOKALIKES = setOf("[", "J", "E", "Г", "Γ", "L", "|", "I")
    private val CLOSE_LOOKALIKES = setOf("]", "J", "Г", "Γ", "L", "|", "I")

    /** What 「 is read as that no Japanese line begins with otherwise: the Cyrillic and the Greek letter, and a square bracket. */
    private val OPEN_ALONE = setOf("Г", "Γ", "[")

    private fun isLatin(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    /**
     * A bracket read as a letter at one end of a line whose other end has its
     * pair: 「…Г -> 「…」, [ふーん…」 -> 「ふーん…」, and both at once when
     * Japanese is between them (E……Г -> 「……」). A letter that is part of a
     * Latin word ("Jun", "OK") is that word.
     *
     * A line's other end is often on another row, or was not read. So Г, Γ or
     * [ before Japanese opens speech by itself (Γ……! -> 「……!), and ] after
     * Japanese closes it (……] -> ……」), unless the line has the square
     * bracket's own pair in it.
     */
    private fun brackets(pieces: MutableList<Piece>) {
        val first = pieces.indexOfFirst { it.text.isNotBlank() }
        val last = pieces.indexOfLast { it.text.isNotBlank() }
        if (first < 0 || last <= first) return
        val opens = pieces[first].text in OPEN_LOOKALIKES && !isLatin(cp(pieces[first + 1]))
        val closes = pieces[last].text in CLOSE_LOOKALIKES && !isLatin(cp(pieces[last - 1]))
        val start = pieces[first].text
        val end = pieces[last].text
        when {
            opens && (end == "」" || end == "』") -> pieces[first].text = if (end == "」") "「" else "『"
            closes && (start == "「" || start == "『") -> pieces[last].text = if (start == "「") "」" else "』"
            opens && closes && pieces.subList(first + 1, last).any { japanese(cp(it)) } -> {
                pieces[first].text = "「"
                pieces[last].text = "」"
            }
            start in OPEN_ALONE && japanese(cp(pieces[first + 1])) && pieces.none { it.text == "]" } -> pieces[first].text = "「"
            end == "]" && japanese(cp(pieces[last - 1])) && pieces.none { it.text == "[" } -> pieces[last].text = "」"
        }
    }

    private val BORDER = "ーニ一二―-~・"

    /** Rubi's border rule: three or more of ー ニ 一 二 ― - ~ ・ and nothing else, two kinds at least, ー or ニ among them. */
    private fun border(text: String): Boolean {
        val t = text.filterNot { it.isWhitespace() }
        return t.length >= 3 && t.all { it in BORDER } && t.toSet().size >= 2 && (t.contains('ー') || t.contains('ニ'))
    }

    /** Kana, kanji, or a mark only Japanese text uses (…, 「」, 。, ―, ~). */
    private fun japanese(c: Int) = isHira(c) || isKata(c) || isKanji(c) || c in 0x3000..0x303F ||
        c == '…'.code || c == '‥'.code || c == '―'.code || c == '・'.code || c == '~'.code

    /** The marks a line of dialogue can be made of with no word in it: ……, !?, ――, ♪. Brackets are told by their kind. */
    private const val MARKS = "…‥―ー—–-！？!?♪～~・。、.,♡♥☆★\"'"

    private fun bracket(c: Int) = when (Character.getType(c).toByte()) {
        Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION -> true
        else -> false
    }

    /** No letter or digit, and not the marks of a wordless line either: a checkbox, an icon, a cursor read as a character. */
    private fun stray(text: String): Boolean {
        val points = text.codePoints().toArray()
        if (points.any { Character.isLetterOrDigit(it) || Selectable.isJapaneseLetter(it) }) return false
        return !points.all { Character.isWhitespace(it) || Character.isSpaceChar(it) || (it <= 0xFFFF && it.toChar() in MARKS) || bracket(it) }
    }

    /** The marks a game puts by its text to ask for a button or show a choice. */
    private val CURSORS = setOf("▼", "▽", "▾", "▿", "⏷", "▶", "▷", "▸", "►")

    /** [text] without the cursor marks at either end of it. */
    fun uncursored(text: String): String = text.trim().let { t ->
        var from = 0
        var to = t.length
        while (from < to && (t[from].isWhitespace() || t[from].toString() in CURSORS)) from++
        while (to > from && (t[to - 1].isWhitespace() || t[to - 1].toString() in CURSORS)) to--
        t.substring(from, to)
    }

    /** Kanji whose shapes are a tiled picture's: a grid, a box, bars, as grass, bricks, windows and water are read. */
    private const val TILES = "口囗田回日曰目皿冊凹凸工王川井亘旦"

    /** Only kanji a tiled picture is read as, with nothing else beside them. */
    fun tiles(text: String): Boolean {
        val t = text.filterNot { it.isWhitespace() }
        return t.isNotEmpty() && t.all { it in TILES }
    }

    /** Letters or digits and nothing Japanese: a menu's English, a number, scene art. */
    private fun foreign(text: String): Boolean = text.any { it.isLetterOrDigit() } && text.none { japanese(it.code) }
}
