// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * The tutorial set-up ends on: a screen made to look like a game, read and
 * looked up in as a game is, and one thing to try at a time. Each step is
 * done when it has been seen done, never by a "Next" button, so what is
 * learnt is what works in a game.
 */
object Tutorial {
    enum class Step { OPEN, MOVE, CARD, CLOSE, WHEEL, PICK, TAP, TAP_CARD, DONE }

    /** What has been seen done so far; the counts only go up ([io.github.beesan.lfg.capture.LfgAccessibilityService.Practised]). */
    data class Seen(
        val lookup: Boolean = false, val word: Boolean = false, val moves: Int = 0, val cards: Int = 0,
        val closes: Int = 0, val wheels: Int = 0, val wheelUp: Boolean = false,
    )

    /** With a [controller]: the shortcut, the cursor, a card, closing, the wheel. By touch: a tap and a card. */
    fun steps(controller: Boolean): List<Step> =
        if (controller) listOf(Step.OPEN, Step.MOVE, Step.CARD, Step.CLOSE, Step.WHEEL, Step.PICK, Step.DONE) else listOf(Step.TAP, Step.TAP_CARD, Step.DONE)

    /** Whether [step] has been done: [now] against [then], what had been seen when the step began. */
    fun done(step: Step, then: Seen, now: Seen): Boolean = when (step) {
        Step.OPEN -> now.lookup
        Step.MOVE -> now.moves >= then.moves + MOVES
        Step.CARD, Step.TAP_CARD -> now.cards > then.cards
        Step.CLOSE -> !now.lookup && (then.lookup || now.closes > then.closes)
        Step.WHEEL -> now.wheels > then.wheels
        Step.PICK -> !now.wheelUp
        Step.TAP -> now.word
        Step.DONE -> false
    }

    /** What the step asks for, and one line on what it does. [shortcut] is the buttons' names; [add] and [close] the face buttons'. */
    fun words(step: Step, shortcut: String, add: String, close: String): Pair<String, String> = when (step) {
        Step.OPEN -> "Press $shortcut" to "The dictionary opens on the game's text."
        Step.MOVE -> "Move with the D-pad" to "Each press goes to the next word."
        Step.CARD -> "Press $add" to "This word becomes a flashcard."
        Step.CLOSE -> "Press $close" to "The dictionary closes. The buttons are the game's again."
        Step.WHEEL -> "Hold $shortcut" to "The wheel opens."
        Step.PICK -> "Point with the stick, then let go" to "The wheel does what you point at."
        Step.TAP -> "Tap a word" to "Its meaning shows."
        Step.TAP_CARD -> "Tap +" to "This word becomes a flashcard."
        Step.DONE -> "You are ready" to "Open a game. LFG reads it by itself."
    }

    /** How many times the cursor has to move before the step is done: once could be an accident. */
    const val MOVES = 2
}
