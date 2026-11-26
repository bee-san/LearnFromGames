// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.ui.Page
import io.github.beesan.lfg.ui.SettingsIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun names(query: String) = SettingsIndex.search(query).map { it.name }

    /** The pages Settings can open with nothing chosen first. */
    private val open = setOf(
        Page.APPEARANCE, Page.BACKUP, Page.TRANSLATION, Page.DIARY_SETTINGS, Page.DICTIONARIES, Page.ANKI, Page.CONTROLS, Page.WHEEL,
        Page.RECOGNITION, Page.GAMES, Page.GAME_NAMES, Page.CHARACTER_NAMES, Page.MEDIA, Page.APPS, Page.PRACTICE, Page.STORAGE,
        Page.DIALOGUE, Page.HELP, Page.GOALS, Page.LANGUAGE,
    )

    @Test
    fun `nothing typed finds nothing`() {
        assertTrue(SettingsIndex.search("").isEmpty())
        assertTrue(SettingsIndex.search("  \t ").isEmpty())
    }

    @Test
    fun `dark finds the theme`() {
        val theme = SettingsIndex.search("dark").first { it.name == "Theme" }
        assertEquals(Page.APPEARANCE, theme.page)
        assertEquals("Appearance", theme.pageName)
    }

    @Test
    fun `key finds the OpenRouter key`() {
        val key = SettingsIndex.search("key").first { it.name == "OpenRouter key" }
        assertEquals(Page.TRANSLATION, key.page)
    }

    @Test
    fun `capitals and accents do not matter`() {
        assertTrue(SettingsIndex.search("key").isNotEmpty())
        assertEquals(SettingsIndex.search("key"), SettingsIndex.search("KEY"))
        assertEquals(SettingsIndex.search("theme"), SettingsIndex.search(" Thème "))
    }

    @Test
    fun `every word typed must be found`() {
        assertTrue("OpenRouter key" in names("openrouter key"))
        assertTrue("OpenRouter key" in names("key   openrouter"))
        assertTrue(names("openrouter zebra").isEmpty())
        // Each word alone finds both keys; Steam with it leaves one.
        assertTrue("Web API key" in names("steam key"))
        assertFalse("OpenRouter key" in names("steam key"))
    }

    @Test
    fun `a name that starts with what was typed comes first, then a name with it, then the rest`() {
        val typed = "card"
        val found = names(typed)
        val rank = found.map { name ->
            when {
                name.lowercase().startsWith(typed) -> 0
                typed in name.lowercase() -> 1
                else -> 2
            }
        }
        assertEquals(rank.sorted(), rank)
        assertTrue(found.indexOf("Cards per day") < found.indexOf("Add card button"))
        // Trim black bars says "card" only in its detail.
        assertTrue(found.indexOf("Add card button") < found.indexOf("Trim black bars"))
        assertEquals("Cards per day", found.first())
    }

    @Test
    fun `hits of the same rank stay in the index's order`() {
        val found = SettingsIndex.search("wheel").filter { it.name.startsWith("Wheel") }
        assertEquals(SettingsIndex.ALL.filter { it.name.startsWith("Wheel") }, found)
    }

    @Test
    fun `every hit opens a page Settings can open by itself`() {
        for (hit in SettingsIndex.ALL) assertTrue("${hit.name} is on ${hit.page}", hit.page in open)
        // And every such page can be found.
        assertEquals(open, SettingsIndex.ALL.map { it.page }.toSet())
    }

    @Test
    fun `a page has one title, and no two settings of the same name`() {
        for ((page, hits) in SettingsIndex.ALL.groupBy { it.page }) {
            assertEquals("$page", 1, hits.map { it.pageName }.toSet().size)
            assertEquals("$page", hits.size, hits.map { it.name }.toSet().size)
            for (hit in hits) assertTrue("$page", hit.name.isNotBlank() && hit.detail.isNotBlank())
        }
    }

    @Test
    fun `what people call a setting finds it`() {
        assertTrue("Anki" in names("flashcards"))
        assertTrue("Text recognition" in names("ocr"))
        assertTrue("Spoken line" in names("tts"))
        assertTrue("Export lines" in names("csv"))
        assertTrue("Lookup shortcut" in names("hotkey"))
        assertTrue("Language" in names("korean"))
        assertTrue("Button sounds" in names("mute"))
        assertTrue("Tutorial" in names("practice"))
    }
}
