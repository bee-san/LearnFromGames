// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VnQuestionTest {
    private val entry = Entry(matched = "放送", expression = "放送", reading = "ほうそう")

    @Test
    fun theQuestionShowsUnderTheEntriesOnlyWhenAsked() {
        val plain = EntryHtml.listBody(listOf(entry)) { false }
        assertFalse(plain.contains("lfg-ask"))
        val asking = EntryHtml.listBody(listOf(entry), askVn = "星屑<ラジオ>") { false }
        assertTrue(asking.contains("Is this the visual novel"))
        assertTrue(asking.contains(EntryHtml.VN_YES_URL + "/0"))
        assertTrue(asking.contains(EntryHtml.VN_NO_URL + "/0"))
        // A title from VNDB is text, never markup.
        assertFalse(asking.contains("<ラジオ>"))
    }

    @Test
    fun onlyARealIdIsALinkedVisualNovel() {
        val game = Game(id = "g", packageName = "p", title = "t")
        assertNull(game.linkedVn)
        assertNull(game.copy(vndb = Game.VNDB_NONE).linkedVn)
        assertEquals("v17", game.copy(vndb = "v17").linkedVn)
    }
}
