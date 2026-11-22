// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.Context
import io.github.beesan.lfg.core.DialogueBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The dialogue box drawn for each game, by the game's id. A game without one
 * is read on its whole screen. They are kept in the settings' own file, so a
 * backup carries them.
 */
class DialogueBoxes(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _boxes = MutableStateFlow(read())
    val boxes: StateFlow<Map<String, DialogueBox>> = _boxes

    private fun read(): Map<String, DialogueBox> = buildMap {
        for ((key, value) in store.all) {
            if (!key.startsWith(KEY)) continue
            (value as? String)?.let(DialogueBox::decode)?.let { put(key.removePrefix(KEY), it) }
        }
    }

    operator fun get(gameId: String): DialogueBox? = _boxes.value[gameId]

    /** This game is read in [box] from now on; with null, on its whole screen again. */
    operator fun set(gameId: String, box: DialogueBox?) {
        store.edit().apply { if (box == null) remove(KEY + gameId) else putString(KEY + gameId, box.encode()) }.apply()
        _boxes.value = if (box == null) _boxes.value - gameId else _boxes.value + (gameId to box)
    }

    private companion object {
        const val KEY = "dialogueBox."
    }
}
