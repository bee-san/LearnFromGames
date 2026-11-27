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

/**
 * A game's other boxes, by the game's id: box 2 onwards, each read when it
 * is picked on the wheel, or all the time beside the dialogue box when it is
 * [DialogueBox.always], as many as are drawn. Kept beside the dialogue
 * boxes, in the settings' own file.
 */
class ExtraBoxes(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _boxes = MutableStateFlow(read())
    val boxes: StateFlow<Map<String, List<DialogueBox>>> = _boxes

    private fun read(): Map<String, List<DialogueBox>> = buildMap {
        for ((key, value) in store.all) {
            if (!key.startsWith(KEY)) continue
            val list = (value as? String)?.split(';')?.mapNotNull(DialogueBox::decode).orEmpty()
            if (list.isNotEmpty()) put(key.removePrefix(KEY), list)
        }
    }

    operator fun get(gameId: String): List<DialogueBox> = _boxes.value[gameId].orEmpty()

    /** The game's box [slot] (2 onwards) is read all the time, or only when picked. */
    fun setAlways(gameId: String, slot: Int, always: Boolean) {
        get(gameId).getOrNull(slot - 2)?.let { set(gameId, slot, it.copy(always = always)) }
    }

    /** The game's box [slot] (2 onwards) is [box]; past the last, it is added; null takes it away. */
    fun set(gameId: String, slot: Int, box: DialogueBox?) {
        val list = get(gameId).toMutableList()
        val at = slot - 2
        when {
            box == null -> if (at in list.indices) list.removeAt(at)
            at in list.indices -> list[at] = box
            else -> list += box
        }
        store.edit().apply { if (list.isEmpty()) remove(KEY + gameId) else putString(KEY + gameId, list.joinToString(";") { it.encode() }) }.apply()
        _boxes.value = if (list.isEmpty()) _boxes.value - gameId else _boxes.value + (gameId to list)
    }

    private companion object {
        const val KEY = "boxes."
    }
}
