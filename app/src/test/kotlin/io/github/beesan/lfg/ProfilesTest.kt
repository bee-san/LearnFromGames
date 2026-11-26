// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.backup.Backup
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
}
