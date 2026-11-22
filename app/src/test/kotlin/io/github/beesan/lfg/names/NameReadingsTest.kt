package io.github.beesan.lfg.names

import io.github.beesan.lfg.names.NameReadingsParser.generateMixedNameReadings
import io.github.beesan.lfg.names.NameReadingsParser.generateNameReadings
import io.github.beesan.lfg.names.NameReadingsParser.splitJapaneseName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VNDB tests of name_parser.rs, with made-up names. */
class NameReadingsTest {
    @Test
    fun splitsOnTheFirstSpace() {
        val parts = splitJapaneseName("森川 遥香")
        assertTrue(parts.hasSpace)
        assertEquals("森川", parts.family)
        assertEquals("遥香", parts.given)
        assertEquals("森川遥香", parts.combined)
        assertEquals("森川 遥香", parts.original)

        val three = splitJapaneseName("A B C")
        assertEquals("A", three.family)
        assertEquals("B C", three.given)

        val blank = splitJapaneseName(" ")
        assertTrue(blank.hasSpace)
        assertEquals("", blank.family)
        assertEquals("", blank.given)
    }

    @Test
    fun aNameWithoutASpaceIsNotSplit() {
        val parts = splitJapaneseName("single")
        assertFalse(parts.hasSpace)
        assertNull(parts.family)
        assertNull(parts.given)
        assertEquals("single", parts.combined)
        assertEquals("", splitJapaneseName("").combined)
        val dotted = splitJapaneseName("ミルフィ・ローゼン")
        assertFalse("A middle dot is not a space", dotted.hasSpace)
        assertEquals("ミルフィ・ローゼン", dotted.combined)
        val unsplit = splitJapaneseName("森川遥香")
        assertEquals("森川遥香", unsplit.original)
        assertEquals("森川遥香", unsplit.combined)
    }

    @Test
    fun singleWordNames() {
        assertEquals(NameReadings("", "", ""), generateMixedNameReadings("", ""))
        assertEquals(Kana.alphabetToKana("kan"), generateMixedNameReadings("漢", "Kan").full)
        assertEquals("あいう", generateMixedNameReadings("あいう", "unused").full)
        assertEquals("あいう", generateMixedNameReadings("アイウ", "unused").full)
        assertEquals("", generateMixedNameReadings("漢字", "").full)
        assertEquals("せいばー", generateNameReadings("セイバー", "Saber").full)
    }

    @Test
    fun eachPartIsReadFromTheRomajiInTheSamePlace() {
        val both = generateMixedNameReadings("漢 字", "Given Family")
        assertEquals(Kana.alphabetToKana("given"), both.family)
        assertEquals(Kana.alphabetToKana("family"), both.given)

        val mixed = generateMixedNameReadings("漢 かな", "Romaji Unused")
        assertEquals(Kana.alphabetToKana("romaji"), mixed.family)
        assertEquals("かな", mixed.given)

        val kana = generateMixedNameReadings("あい うえ", "Unused Unused2")
        assertEquals("あい", kana.family)
        assertEquals("うえ", kana.given)
        assertEquals("あいうえ", kana.full)

        val oneWord = generateMixedNameReadings("漢 字", "SingleWord")
        assertEquals(Kana.alphabetToKana("singleword"), oneWord.family)
        assertEquals("", oneWord.given)

        val doubleSpace = generateMixedNameReadings("漢 字", "Given  Family")
        assertEquals(Kana.alphabetToKana("given"), doubleSpace.family)
        assertEquals(Kana.alphabetToKana(" family"), doubleSpace.given)
    }

    @Test
    fun vndbOrderReadsTheFirstWordAsTheFamilyName() {
        val r = generateNameReadings("森川 遥香", "Morikawa Haruka")
        assertEquals("もりかわ", r.family)
        assertEquals("はるか", r.given)
        assertEquals("もりかわはるか", r.full)

        // The positional mapping holds even when the romaji is the other way round.
        val swapped = generateMixedNameReadings("森川 遥香", "Haruka Morikawa")
        assertEquals("はるか", swapped.family)
        assertEquals("もりかわ", swapped.given)

        val kanaGiven = generateNameReadings("千草 おとは", "Chigusa Otoha")
        assertEquals("ちぐさ", kanaGiven.family)
        assertEquals("おとは", kanaGiven.given)
    }

    @Test
    fun apostrophesSeparateN() {
        val r = generateMixedNameReadings("工藤 新一", "Shin'ichi Kudou")
        assertEquals("しんいち", r.family)
        assertEquals("くどう", r.given)
        val v = generateNameReadings("鳴海 新一", "Narumi Shin'ichi")
        assertEquals("なるみ", v.family)
        assertEquals("しんいち", v.given)
    }

    @Test
    fun readingsHaveNoWhitespace() {
        val r = generateNameReadings("ミナの母", "Mina no Haha")
        assertFalse(r.full, ' ' in r.full)
        assertFalse(' ' in r.family)
        assertFalse(' ' in r.given)
        val single = generateNameReadings("田所の母", "Tadokoro no Haha")
        assertEquals("たどころのはは", single.full)
    }

    @Test
    fun honorificsAreThereInOrder() {
        assertTrue(HONORIFIC_SUFFIXES.size > 200)
        val suffixes = HONORIFIC_SUFFIXES.map { it.suffix }
        for (s in listOf("さん", "君", "くん", "ちゃん", "ちょ", "ちょる", "様", "先生", "先輩")) assertTrue("Missing $s", s in suffixes)
        for (h in HONORIFIC_SUFFIXES) {
            assertTrue(h.suffix.isNotEmpty())
            assertTrue(h.reading.isNotEmpty())
            assertTrue("${h.suffix} has no description", h.description.isNotEmpty())
        }
        val triples = HONORIFIC_SUFFIXES.map { "${it.suffix}:${it.reading}:${it.description}" }
        assertEquals("Duplicate honorific triple", triples.size, triples.toSet().size)
        assertEquals("さん", HONORIFIC_SUFFIXES.first().suffix)
        assertEquals("Kana form of 様 (duplicate)", HONORIFIC_SUFFIXES.last().description)
        // 殿 is listed as どの before てん, so どの is the one a name gets.
        assertEquals("どの", HONORIFIC_SUFFIXES.first { it.suffix == "殿" }.reading)
    }
}
