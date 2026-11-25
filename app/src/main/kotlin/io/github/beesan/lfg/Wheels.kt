// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.Context
import io.github.beesan.lfg.core.WheelSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The player's wheels: one shared by every game, and a game's own where it
 * has been given one (Settings › Wheel, "Each game its own"). Kept in the
 * settings' own file, as the boxes are, so a backup carries them.
 */
class Wheels(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _sets = MutableStateFlow(read())

    /** Every wheel kept, by the game's id; the shared one is under [SHARED]. */
    val sets: StateFlow<Map<String, WheelSet>> = _sets

    private fun read(): Map<String, WheelSet> = buildMap {
        for ((key, value) in store.all) {
            if (key != KEY && !key.startsWith("$KEY.")) continue
            val set = (value as? String)?.let(WheelSet::decode) ?: continue
            put(key.removePrefix(KEY).removePrefix("."), set)
        }
    }

    /** The shared wheel. */
    val shared: WheelSet get() = _sets.value[SHARED] ?: WheelSet.DEFAULT

    /** Whether the game has a wheel of its own. */
    fun has(gameId: String): Boolean = gameId.isNotEmpty() && gameId in _sets.value

    /** The wheel a game uses: its own when [perGame] and it has one, else the shared one. */
    fun of(gameId: String?, perGame: Boolean): WheelSet =
        gameId?.takeIf { perGame && it.isNotEmpty() }?.let { _sets.value[it] } ?: shared

    /** [gameId]'s own wheel, or with [SHARED] the shared one, is [set]; null puts it back as it was: the shared wheel for a game, the first wheel for the shared one. */
    fun set(gameId: String, set: WheelSet?) {
        val key = if (gameId.isEmpty()) KEY else "$KEY.$gameId"
        store.edit().apply { if (set == null) remove(key) else putString(key, set.encode()) }.apply()
        _sets.value = if (set == null) _sets.value - gameId else _sets.value + (gameId to set)
    }

    companion object {
        /** The shared wheel's place among the games' ids. */
        const val SHARED = ""
        private const val KEY = "wheel"
    }
}
