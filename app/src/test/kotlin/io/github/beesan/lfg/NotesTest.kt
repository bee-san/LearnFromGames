package io.github.beesan.lfg

import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.data.Notes
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.dict.Glossary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val note = Note(
        gameId = "g", gameName = "Quest", term = "洞窟", reading = "どうくつ",
        title = "洞窟の鍵", text = "Behind the waterfall.", createdAt = 0,
    )

    @Test
    fun aNoteIsADictionaryOfItsOwnAndNotAMeaning() {
        val mine = Notes.glossary(note)
        assertEquals(Entry.MY_NOTES, mine.dictionary)
        val html = EntryHtml.glossary(mine.glossary, mine.dictionary)
        assertTrue(html.contains("洞窟の鍵"))
        assertTrue(html.contains("Behind the waterfall."))
        assertTrue(html.contains("Quest"))
        // On a card, the dictionary's meanings come; the user's notes do not.
        val entry = Entry(expression = "洞窟", glossaries = listOf(mine, Glossary("JMdict", "[\"cave\"]")))
        assertEquals(listOf("JMdict"), entry.meanings.map { it.dictionary })
    }
}
