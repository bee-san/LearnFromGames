// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

/** A Japanese name split on its first space: [family] and [given] are null when it has none. */
data class JapaneseNameParts(
    val hasSpace: Boolean,
    val original: String,
    val combined: String,
    val family: String?,
    val given: String?,
)

/** A name's readings in hiragana. */
data class NameReadings(val full: String, val family: String, val given: String)

/**
 * Splitting a VNDB character's Japanese name and reading it, ported from the VNDB
 * path of name_parser.rs. VNDB romanises in Western order ("Given Family") and
 * writes the Japanese family first, so the first romanised word reads the family name.
 */
object NameReadingsParser {
    fun splitJapaneseName(nameOriginal: String): JapaneseNameParts {
        val pos = nameOriginal.indexOf(' ')
        if (pos < 0) return JapaneseNameParts(false, nameOriginal, nameOriginal, null, null)
        val family = nameOriginal.substring(0, pos)
        val given = nameOriginal.substring(pos + 1)
        return JapaneseNameParts(true, nameOriginal, family + given, family, given)
    }

    /**
     * Each part of the Japanese name is read from the romanised word in the same
     * position when it has kanji, and from its own kana when it has none.
     */
    fun generateMixedNameReadings(nameOriginal: String, romanizedName: String): NameReadings {
        if (nameOriginal.isEmpty()) return NameReadings("", "", "")
        if (' ' !in nameOriginal) {
            val full = if (Kana.containsKanji(nameOriginal)) Kana.alphabetToKana(romanizedName)
            else Kana.kataToHira(nameOriginal.replace(" ", ""))
            return NameReadings(full, full, full)
        }
        val parts = splitJapaneseName(nameOriginal)
        val familyJp = parts.family.orEmpty()
        val givenJp = parts.given.orEmpty()
        val romanised = romanizedName.split(' ', limit = 2)
        val romFirst = romanised[0]
        val romSecond = romanised.getOrElse(1) { "" }
        val family = if (Kana.containsKanji(familyJp)) Kana.alphabetToKana(romFirst) else Kana.kataToHira(familyJp)
        val given = if (Kana.containsKanji(givenJp)) Kana.alphabetToKana(romSecond) else Kana.kataToHira(givenJp)
        return NameReadings(family + given, family, given)
    }

    /** [generateMixedNameReadings] with whitespace taken out, which a romanised name of several words leaves behind. */
    fun generateNameReadings(nameOriginal: String, romanizedName: String): NameReadings {
        val r = generateMixedNameReadings(nameOriginal, romanizedName)
        return NameReadings(stripWhitespace(r.full), stripWhitespace(r.family), stripWhitespace(r.given))
    }

    private fun stripWhitespace(s: String): String = s.filterNot { it.isWhitespace() }
}
