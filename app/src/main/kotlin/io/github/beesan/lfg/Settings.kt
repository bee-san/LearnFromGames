// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.Context
import io.github.beesan.lfg.core.ReadingGoals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few choices the app has. Every one has a default, so none is ever asked for. */
data class Prefs(
    /** [SHORTCUT_L3_R3] or [SHORTCUT_L1_R1]. */
    val shortcut: String = SHORTCUT_L3_R3,
    /** Swap which face button adds a card and which closes, for pads printed the other way round. */
    val swapConfirm: Boolean = false,
    /** [ENGINE_AUTO], [ENGINE_SCREENAI] or [ENGINE_MEIKI]. */
    val engine: String = ENGINE_AUTO,
    /** On a two-screen handheld, show the entry on the screen the game is not on. */
    val secondScreenPanel: Boolean = true,
    /** Sentence audio from a rolling buffer of game sound. Needs screen recording consent each session. */
    val sentenceAudio: Boolean = false,
    /** Android's voice reads the line onto a card the game gave no voice. */
    val spokenLines: Boolean = true,
    /** An animated picture of the moments before the card was made, instead of a still. */
    val animatedPicture: Boolean = false,
    /** Daily reading goals on the stats screen; 0 is none. */
    val goalMinutes: Int = 0,
    val goalChars: Int = 0,
) {
    val goals: ReadingGoals get() = ReadingGoals(goalMinutes, goalChars)

    companion object {
        const val SHORTCUT_L3_R3 = "l3r3"
        const val SHORTCUT_L1_R1 = "l1r1"
        const val ENGINE_AUTO = "auto"
        const val ENGINE_SCREENAI = "screenai"
        const val ENGINE_MEIKI = "meiki"
    }
}

class Settings(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = _prefs

    private fun read() = Prefs().let { d ->
        Prefs(
            shortcut = store.getString("shortcut", d.shortcut) ?: d.shortcut,
            swapConfirm = store.getBoolean("swapConfirm", d.swapConfirm),
            engine = store.getString("engine", d.engine) ?: d.engine,
            secondScreenPanel = store.getBoolean("secondScreenPanel", d.secondScreenPanel),
            sentenceAudio = store.getBoolean("sentenceAudio", d.sentenceAudio),
            spokenLines = store.getBoolean("spokenLines", d.spokenLines),
            animatedPicture = store.getBoolean("animatedPicture", d.animatedPicture),
            goalMinutes = store.getInt("goalMinutes", d.goalMinutes),
            goalChars = store.getInt("goalChars", d.goalChars),
        )
    }

    fun update(change: (Prefs) -> Prefs) {
        val next = change(_prefs.value)
        store.edit()
            .putString("shortcut", next.shortcut)
            .putBoolean("swapConfirm", next.swapConfirm)
            .putString("engine", next.engine)
            .putBoolean("secondScreenPanel", next.secondScreenPanel)
            .putBoolean("sentenceAudio", next.sentenceAudio)
            .putBoolean("spokenLines", next.spokenLines)
            .putBoolean("animatedPicture", next.animatedPicture)
            .putInt("goalMinutes", next.goalMinutes)
            .putInt("goalChars", next.goalChars)
            .apply()
        _prefs.value = next
    }
}
