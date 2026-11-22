// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * Whether a tap on a game's text looks the word up, or is left to the game.
 *
 * Text is everywhere on a game's screen, its own menus and settings included,
 * so with a controller in hand taps are the game's until the lookup shortcut
 * is pressed, and the game's again once it is pressed a second time or the
 * lookup is closed. With no controller there is no shortcut to press, and a
 * tap always looks up. A reading asked for over an ordinary app was itself
 * the asking.
 */
object TapGate {
    const val AUTO = "auto"
    const val ALWAYS = "always"
    const val SHORTCUT = "shortcut"

    fun open(mode: String, hasController: Boolean, lookupMode: Boolean, asked: Boolean): Boolean = when {
        asked || lookupMode -> true
        mode == ALWAYS -> true
        mode == SHORTCUT -> false
        else -> !hasController
    }
}
