// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

/**
 * Pitch accent and frequency, worked out the way Yomitan does
 * (japanese.js: getPitchCategory, getDownstepPositions; and
 * dictionary-data-util.js for the frequency average).
 */
object Pronunciation {
    enum class Kind(val label: String) { HEIBAN("平板"), ATAMADAKA("頭高"), NAKADAKA("中高"), ODAKA("尾高"), KIFUKU("起伏") }

    /** One accent of a reading: the pitch of each mora, and of the particle after it. */
    data class Accent(val position: Int, val kind: Kind, val morae: List<String>, val high: List<Boolean>)

    private const val SMALL = "ゃゅょぁぃぅぇぉゎゕゖャュョァィゥェォヮヵヶ"
    private val VERB_OR_ADJECTIVE = setOf("v1", "v5", "vk", "vs", "vz", "adj-i")

    /** The reading cut into morae: a small kana belongs to the one before it. */
    fun morae(reading: String): List<String> {
        val out = ArrayList<String>()
        for (c in reading) {
            if (c in SMALL && out.isNotEmpty()) out[out.size - 1] = out.last() + c else out += c.toString()
        }
        return out
    }

    /** Where the pitch drops: the mora after which it falls, or 0 for none. */
    fun position(value: PitchValue): Int {
        val pattern = value.pattern.uppercase()
        if (pattern.isEmpty()) return value.position
        for (i in 1 until pattern.length) if (pattern[i - 1] == 'H' && pattern[i] == 'L') return i
        return 0
    }

    fun kind(position: Int, morae: Int, rules: String): Kind = when {
        position == 0 -> Kind.HEIBAN
        rules.split(' ').any { it in VERB_OR_ADJECTIVE } -> Kind.KIFUKU
        position == 1 -> Kind.ATAMADAKA
        position >= morae -> Kind.ODAKA
        else -> Kind.NAKADAKA
    }

    /** Each different accent the pitch dictionaries give, in their order. */
    fun accents(entry: Entry): List<Accent> {
        val morae = morae(entry.reading.ifEmpty { entry.expression })
        if (morae.isEmpty()) return emptyList()
        return entry.pitches.flatMap { it.values }.map { position(it) }.filter { it in 0..morae.size }.distinct().map { p ->
            // Heiban rises after the first mora and stays up; otherwise the first mora is low unless the drop comes after it.
            val high = (0..morae.size).map { i -> if (p == 0) i > 0 else if (p == 1) i == 0 else i in 1 until p }
            Accent(p, kind(p, morae.size, entry.rules), morae, high)
        }
    }

    /** Yomitan's average: the floored harmonic mean of each ranking dictionary's first value. */
    fun frequencyRank(entry: Entry): Int? {
        val firsts = entry.frequencies.filter { !it.occurrence }.mapNotNull { f -> f.values.firstOrNull()?.value?.takeIf { it > 0 } }
        if (firsts.isEmpty()) return null
        return (firsts.size / firsts.sumOf { 1.0 / it }).toInt()
    }

    /** Accents and frequencies on one line, for views that have no room for tags: `中高 2 · JPDB 1234`. */
    fun summary(entry: Entry): String = (
        accents(entry).map { "${it.kind.label} ${it.position}" } +
            entry.frequencies.mapNotNull { f ->
                f.values.firstOrNull()?.let { v -> shortName(f.dictionary) + " " + v.display.ifEmpty { v.value.toString() } }
            }
        ).joinToString(" · ")

    /** A frequency dictionary's name without its version and date: `JPDB_v2.2_Frequency_Kana` reads `JPDB`. */
    fun shortName(dictionary: String): String {
        val words = dictionary.split(' ', '_').filter { it.isNotEmpty() }
        val kept = words.takeWhile { w -> !(w[0].isDigit() || w[0] == '(' || w[0] == '[' || Regex("[vV]\\d.*").matches(w)) }
        return kept.joinToString(" ").ifEmpty { dictionary }
    }
}
