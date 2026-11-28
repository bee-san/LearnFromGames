// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import io.github.beesan.lfg.data.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VnQuestionTest {
    @Test
    fun onlyARealIdIsALinkedVisualNovel() {
        val game = Game(id = "g", packageName = "p", title = "t")
        assertNull(game.linkedVn)
        assertNull(game.copy(vndb = Game.VNDB_NONE).linkedVn)
        assertEquals("v17", game.copy(vndb = "v17").linkedVn)
    }
}
