// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.backup.Backup
import io.github.beesan.lfg.core.GameArrivals
import io.github.beesan.lfg.core.KeptProfile
import io.github.beesan.lfg.core.ProfileRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test
    fun `a profile goes by its name, else by its language, numbered from the second`() {
        assertEquals(
            listOf("Japanese", "Spanish", "Docked", "Spanish 2", "Japanese 2", "Spanish 3"),
            ProfileRules.shown(listOf("" to "Japanese", "" to "Spanish", "Docked" to "Spanish", "" to "Spanish", " " to "Japanese", "" to "Spanish")),
        )
    }

    @Test
    fun `a name is trimmed and kept short`() {
        assertEquals("Docked", ProfileRules.named("  Docked \n"))
        assertEquals(ProfileRules.NAME_LENGTH, ProfileRules.named("x".repeat(80)).length)
        assertEquals("", ProfileRules.named("   "))
        // An emoji at the limit is kept whole or left out, never cut in half.
        val smile = "😀"
        assertEquals("x".repeat(ProfileRules.NAME_LENGTH - 1) + smile, ProfileRules.named("x".repeat(ProfileRules.NAME_LENGTH - 1) + smile + smile))
        assertEquals("x".repeat(ProfileRules.NAME_LENGTH), ProfileRules.named("x".repeat(ProfileRules.NAME_LENGTH) + smile))
        assertEquals(smile.repeat(ProfileRules.NAME_LENGTH), ProfileRules.fitted(smile.repeat(30)))
    }

    @Test
    fun `a new profile takes the first free tile and an id none has had`() {
        assertEquals(KeptProfile("2", 2), ProfileRules.added(listOf(ProfileRules.FIRST)))
        // The second was deleted: its tile is free again, and the id goes on from the highest.
        assertEquals(KeptProfile("4", 2), ProfileRules.added(listOf(KeptProfile("1", 1), KeptProfile("3", 3))))
    }

    @Test
    fun `there are no more profiles than tiles`() {
        var all = listOf(ProfileRules.FIRST)
        while (true) all = all + (ProfileRules.added(all) ?: break)
        assertEquals(ProfileRules.MAX, all.size)
        assertEquals((1..ProfileRules.MAX).toList(), all.map { it.slot })
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun `next goes round, and nowhere with one profile`() {
        val all = listOf(KeptProfile("1", 1), KeptProfile("2", 2), KeptProfile("5", 3))
        assertEquals("2", ProfileRules.after(all, "1")?.id)
        assertEquals("5", ProfileRules.after(all, "2")?.id)
        assertEquals("1", ProfileRules.after(all, "5")?.id)
        assertNull(ProfileRules.after(listOf(ProfileRules.FIRST), "1"))
    }

    @Test
    fun `the list comes back as it was kept`() {
        val all = listOf(KeptProfile("1", 1), KeptProfile("2", 4, "Docked \"TV\""), KeptProfile("7", 2))
        assertEquals(all, ProfileRules.decode(ProfileRules.encode(all)))
    }

    @Test
    fun `a list that is not one is none, and a broken profile is left out`() {
        assertNull(ProfileRules.decode(null))
        assertNull(ProfileRules.decode(""))
        assertNull(ProfileRules.decode("{}"))
        assertNull(ProfileRules.decode("[]"))
        val text = """[{"id":"1","slot":1,"name":""},{"id":"1","slot":2},{"id":"3","slot":1},{"id":"4","slot":9},{"slot":3},{"id":"5","slot":3,"name":"Five"}]"""
        assertEquals(listOf(KeptProfile("1", 1), KeptProfile("5", 3, "Five")), ProfileRules.decode(text))
    }

    @Test
    fun `changing profile keeps what every profile shares, and nothing else`() {
        val inUse = Prefs(
            setupDone = true, setupStep = "AI", keepDialogue = true, keepDialogueAsked = true,
            openRouterKey = "key", steamKey = "steam", steamAccount = "me",
            backupFolder = "folder", backupEvery = Prefs.BACKUP_DAILY, backupKeep = 3, backupPictures = false, backupLast = 5, backupLastSize = 6, gsmFile = true,
            cocoonFolder = "c", esdeFolder = "e", retroArchFolder = "r", edenFolder = "d",
            keepScreensDays = 30, wifiOnly = true, updateDictionaries = false, widgetSize = "wide",
            language = "ja", translate = Prefs.TRANSLATE_EVERY, shortcut = Prefs.SHORTCUT_SELECT, buttonSounds = false,
        )
        val kept = Prefs(language = "es", translateInto = "French", stickWords = true, wheelSize = 120, colours = "teal", goalMinutes = 15, usesController = true)
        val now = kept.withShared(inUse)
        // The profile's own.
        assertEquals("es", now.language)
        assertEquals("French", now.translateInto)
        assertTrue(now.stickWords)
        assertEquals(120, now.wheelSize)
        assertEquals("teal", now.colours)
        assertEquals(15, now.goalMinutes)
        assertTrue(now.usesController)
        assertEquals(Prefs.TRANSLATE_OFF, now.translate)
        assertEquals(Prefs.SHORTCUT_L3_R3, now.shortcut)
        assertTrue(now.buttonSounds)
        // Shared: with those put back as the profile had them, it is the profile untouched, so nothing else was carried over.
        assertEquals(inUse.copy(language = "es", translateInto = "French", stickWords = true, wheelSize = 120, colours = "teal", goalMinutes = 15, usesController = true, translate = Prefs.TRANSLATE_OFF, shortcut = Prefs.SHORTCUT_L3_R3, buttonSounds = true), now)
        assertEquals(kept, now.withShared(kept))
    }

    @Test
    fun `a backup carries the profiles and no other file`() {
        assertTrue(Backup.profiles("profiles.xml"))
        assertTrue(Backup.profiles("profile-2.xml"))
        assertTrue(Backup.profiles("profile-12.xml"))
        assertFalse(Backup.profiles("settings.xml"))
        assertFalse(Backup.profiles("secrets.xml"))
        assertFalse(Backup.profiles("profile-.xml"))
        assertFalse(Backup.profiles("profile-2.xml.bak"))
        assertFalse(Backup.profiles("../profile-2.xml"))
    }

    private val three = listOf(KeptProfile("1", 1), KeptProfile("2", 2), KeptProfile("5", 3))

    @Test
    fun `a game changes to its profile, and leaves the one in use when it has none`() {
        val links = mapOf("vn" to "2", "rpg" to "1")
        assertEquals("2", ProfileRules.forGame(links, "vn", null, three, "1"))
        assertNull(ProfileRules.forGame(links, "vn", null, three, "2"))
        assertNull(ProfileRules.forGame(links, "other", null, three, "1"))
        assertEquals("1", ProfileRules.forGame(links, "rpg", null, three, "5"))
    }

    @Test
    fun `a game named in an emulator has its own profile, else the emulator's`() {
        val links = mapOf("retroarch" to "5", "named" to "2")
        assertEquals("2", ProfileRules.forGame(links, "named", "retroarch", three, "1"))
        assertEquals("5", ProfileRules.forGame(links, "unnamed", "retroarch", three, "1"))
        assertNull(ProfileRules.forGame(links, "unnamed", "other", three, "1"))
    }

    @Test
    fun `a deleted profile, or only one, changes nothing`() {
        assertNull(ProfileRules.forGame(mapOf("vn" to "9"), "vn", null, three, "1"))
        // Its own is gone: the emulator's still counts.
        assertEquals("5", ProfileRules.forGame(mapOf("vn" to "9", "host" to "5"), "vn", "host", three, "1"))
        assertNull(ProfileRules.forGame(mapOf("vn" to "1"), "vn", null, listOf(ProfileRules.FIRST), "2"))
    }

    @Test
    fun `games' profiles are read from beside the list, and nothing else is`() {
        val kept = mapOf("list" to "[]", "active" to "2", "anki.2" to "deck", "game.abc-1" to "2", "game." to "1", "game.x" to "", "game.y" to 3)
        assertEquals(mapOf("abc-1" to "2"), ProfileRules.links(kept))
    }

    @Test
    fun `a game counts as come once, until another has come`() {
        val arrivals = GameArrivals()
        assertFalse(arrivals.arrived(null))
        assertTrue(arrivals.arrived("a"))
        assertFalse(arrivals.arrived("a"))
        // Gone behind LFG's pages and back: still the same game, and a profile picked by hand meanwhile stays.
        assertFalse(arrivals.arrived(null))
        assertFalse(arrivals.arrived("a"))
        assertTrue(arrivals.arrived("b"))
        assertTrue(arrivals.arrived("a"))
    }

    @Test
    fun `a game named in an emulator losing its name is not a new game`() {
        val arrivals = GameArrivals()
        assertTrue(arrivals.arrived("retroarch", "retroarch"))
        assertTrue(arrivals.arrived("retroarch:pokemon", "retroarch"))
        // The name is lost for a look, then found: neither changes the profile again.
        assertFalse(arrivals.arrived("retroarch", "retroarch"))
        assertFalse(arrivals.arrived("retroarch:pokemon", "retroarch"))
        // Another game named in it is a change.
        assertTrue(arrivals.arrived("retroarch:zelda", "retroarch"))
        // Another app, then the emulator again: that is a change.
        assertTrue(arrivals.arrived("eden", "eden"))
        assertTrue(arrivals.arrived("retroarch", "retroarch"))
    }

    @Test
    fun `a game given a profile takes it up as it next comes, though it was the last played`() {
        val arrivals = GameArrivals()
        assertTrue(arrivals.arrived("a", "a"))
        assertFalse(arrivals.arrived("a", "a"))
        arrivals.forget()
        assertTrue(arrivals.arrived("a", "a"))
    }
}
