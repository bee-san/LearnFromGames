// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Every VN here is made up; the titles only echo the cases that shaped the rules.
class VnMatcherTest {
    private fun vn(
        id: String,
        title: String,
        platforms: List<String> = listOf("win"),
        languages: List<String> = listOf("ja"),
        aliases: List<String> = emptyList(),
        titles: List<VnTitle> = emptyList(),
        alttitle: String? = null,
    ) = VnResult(id, title, alttitle, aliases, titles, platforms = platforms, languages = languages)

    @Test
    fun steamWithOneVnLinks() {
        assertEquals(VnMatch.Link("v900", "steam"), VnMatcher.match("Anything", 123, null, listOf("v900", "v900"), emptyList()))
    }

    @Test
    fun steamWithSeveralVnsSuggestsTheOneWhoseTitleMatches() {
        val results = listOf(vn("v902", "Lantern Road"))
        assertEquals(VnMatch.Suggest("v902"), VnMatcher.match("Lantern Road", 123, null, listOf("v901", "v902"), results))
        assertEquals(VnMatch.Suggest("v901"), VnMatcher.match("Elsewhere", 123, null, listOf("v901", "v902"), results))
    }

    @Test
    fun steamVnsWithoutAnAppIdAreIgnored() {
        assertEquals(VnMatch.None, VnMatcher.match("Elsewhere", null, "win", listOf("v900"), emptyList()))
    }

    @Test
    fun exactTitlePlatformAndJapaneseLink() {
        val results = listOf(vn("v903", "Lantern Road: Night", platforms = listOf("swi")), vn("v900", "Lantern Road", platforms = listOf("win", "swi")))
        assertEquals(VnMatch.Link("v900", "title"), VnMatcher.match("LANTERN ROAD", null, "swi", emptyList(), results))
    }

    @Test
    fun aliasesAndTitleEntriesCount() {
        val byAlias = listOf(vn("v900", "Something Else", aliases = listOf("Lantern-Road")))
        assertEquals(VnMatch.Link("v900", "title"), VnMatcher.match("Lantern Road", null, "win", emptyList(), byAlias))
        val byLatin = listOf(vn("v901", "灯籠の道", titles = listOf(VnTitle("灯籠の道", "Tourou no Michi", "ja", true, true))))
        assertEquals(VnMatch.Link("v901", "title"), VnMatcher.match("Tourou no Michi", null, "win", emptyList(), byLatin))
        val byAlt = listOf(vn("v902", "Tourou", alttitle = "灯籠の道"))
        assertEquals(VnMatch.Link("v902", "title"), VnMatcher.match("灯籠の道", null, "win", emptyList(), byAlt))
    }

    /** A non-VN Android game whose name is a VN's: the VN is on Windows only, so it is only offered. */
    @Test
    fun journeyStaysASuggestion() {
        val results = listOf(vn("v910", "Journey", platforms = listOf("win")))
        assertEquals(VnMatch.Suggest("v910"), VnMatcher.match("Journey", null, VnMatcher.platformFor("com.example.journey", ownApp = true), emptyList(), results))
    }

    /** The right platform, but the VN never had a Japanese release. */
    @Test
    fun insideStaysASuggestion() {
        val results = listOf(vn("v911", "Inside", platforms = listOf("swi"), languages = listOf("en")))
        assertEquals(VnMatch.Suggest("v911"), VnMatcher.match("Inside", null, "swi", emptyList(), results))
    }

    /** On Switch the same-named VN is not a Switch game; and with the platform unknown nothing links. */
    @Test
    fun hadesStaysASuggestion() {
        val results = listOf(vn("v912", "Hades", platforms = listOf("win", "and")))
        assertEquals(VnMatch.Suggest("v912"), VnMatcher.match("Hades", null, "swi", emptyList(), results))
        val everywhere = listOf(vn("v912", "Hades", platforms = listOf("win", "swi")))
        assertEquals(VnMatch.Suggest("v912"), VnMatcher.match("Hades", null, VnMatcher.platformFor("com.retroarch.aarch64", false), emptyList(), everywhere))
    }

    @Test
    fun twoPassingVnsAreOnlySuggested() {
        val results = listOf(vn("v920", "Twin Moon"), vn("v921", "Other", aliases = listOf("Twin Moon")))
        assertEquals(VnMatch.Suggest("v920"), VnMatcher.match("Twin Moon", null, "win", emptyList(), results))
    }

    @Test
    fun noExactTitleIsNothing() {
        val results = listOf(vn("v930", "Twin Moon Stories"))
        assertEquals(VnMatch.None, VnMatcher.match("Twin Moon", null, "win", emptyList(), results))
        assertEquals(VnMatch.None, VnMatcher.match("Twin Moon", null, "win", emptyList(), emptyList()))
    }

    @Test
    fun platforms() {
        assertEquals("swi", VnMatcher.platformFor("dev.eden.eden_emulator", false))
        assertEquals("swi", VnMatcher.platformFor("org.citron.citron_emu", false))
        assertEquals("n3d", VnMatcher.platformFor("org.citra.citra_emu", false))
        assertEquals("psp", VnMatcher.platformFor("org.ppsspp.ppssppgold", false))
        assertEquals("wii", VnMatcher.platformFor("org.dolphinemu.dolphinemu", false))
        assertEquals("win", VnMatcher.platformFor("com.winlator", false))
        assertEquals("win", VnMatcher.platformFor("com.droiddeck.launcher", false))
        assertEquals("and", VnMatcher.platformFor("com.example.game", true))
        assertNull(VnMatcher.platformFor("org.scummvm.scummvm", false))
        assertNull(VnMatcher.platformFor("org.renpy.android", false))
        assertNull(VnMatcher.platformFor(null, false))
    }
}
