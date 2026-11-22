package io.github.beesan.lfg

import io.github.beesan.lfg.dict.DictionaryUpdates
import io.github.beesan.lfg.dict.ImportOutcome
import io.github.beesan.lfg.dict.InstalledDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryUpdatesTest {
    private val installed = DictionaryUpdates.index(
        """{"title":"JMdict [2026-11-17]","revision":"JMdict.2026-11-17","isUpdatable":true,
           "indexUrl":"https:\/\/example.org\/JMdict.json","downloadUrl":"https://example.org/JMdict.zip","styles":""}""",
    )!!

    private fun remote(revision: String, download: String = "https://example.org/new.zip") =
        DictionaryUpdates.index("""{"revision":"$revision","isUpdatable":true,"indexUrl":"https://example.org/JMdict.json","downloadUrl":"$download"}""")!!

    @Test
    fun aDictionaryIsAskedAboutOnlyWhenItSaysWhere() {
        assertEquals("https://example.org/JMdict.json", installed.indexUrl)
        assertTrue(DictionaryUpdates.asks(installed))
        assertFalse(DictionaryUpdates.asks(DictionaryUpdates.index("""{"title":"Pitch test","revision":"2"}""")!!))
        assertFalse(DictionaryUpdates.asks(installed.copy(updatable = false)))
        assertFalse(DictionaryUpdates.asks(installed.copy(indexUrl = "http://example.org/JMdict.json")))
        assertNull(DictionaryUpdates.index("<html>not found</html>"))
    }

    @Test
    fun anotherRevisionIsFetchedFromWhereThePublisherSays() {
        assertNull(DictionaryUpdates.download(installed, remote("JMdict.2026-11-17")))
        assertEquals("https://example.org/new.zip", DictionaryUpdates.download(installed, remote("JMdict.2026-11-20")))
        // An earlier one too: the publisher took a release back.
        assertEquals("https://example.org/new.zip", DictionaryUpdates.download(installed, remote("JMdict.2026-11-01")))
        // No address of its own, or one that is not HTTPS: the installed dictionary's.
        assertEquals("https://example.org/JMdict.zip", DictionaryUpdates.download(installed, remote("JMdict.2026-11-20", "")))
        assertEquals("https://example.org/JMdict.zip", DictionaryUpdates.download(installed, remote("JMdict.2026-11-20", "http://example.org/x.zip")))
        assertNull(DictionaryUpdates.download(installed, remote("")))
    }

    @Test
    fun aStubDoesNotReplaceADictionary() {
        val old = InstalledDictionary("JMdict [2026-11-17]", "/x", terms = 500_000, installedAt = 0)
        assertTrue(DictionaryUpdates.replaces(old, ImportOutcome(success = true, title = "JMdict [2026-11-20]", terms = 501_000)))
        assertFalse(DictionaryUpdates.replaces(old, ImportOutcome(success = true, title = "JMdict [2026-11-20]", terms = 12)))
        assertFalse(DictionaryUpdates.replaces(old, ImportOutcome(success = false, title = "JMdict [2026-11-20]", terms = 501_000)))
        assertFalse(DictionaryUpdates.replaces(old, ImportOutcome(success = true, title = "", terms = 501_000)))
        val ranks = InstalledDictionary("Jiten", "/y", terms = 0, frequencies = 600_000, installedAt = 0)
        assertTrue(DictionaryUpdates.replaces(ranks, ImportOutcome(success = true, title = "Jiten", frequencies = 610_000)))
        assertFalse(DictionaryUpdates.replaces(ranks, ImportOutcome(success = true, title = "Jiten", frequencies = 0)))
    }
}
