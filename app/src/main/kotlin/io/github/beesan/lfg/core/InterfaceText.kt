// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * Tells a game's interface text (menu labels, a HUD's words, name plates)
 * from the lines it is telling. Interface text is still read and tappable, but
 * it is not a line: it stays out of the lines list, the stats, recaps,
 * translations' context and the lines file.
 *
 * A piece is interface text when:
 *
 * - it comes in a crowd: [CROWD] or more pieces arriving on the screen
 *   together, as a menu's do, none of them a line read before. A long piece
 *   is one too then: a setting's description, a save slot. [LineSorter]
 *   tells this, because it knows which pieces have just arrived;
 * - it is short ([SHORT] characters or fewer) and stays: it has stayed on
 *   screen while the text around it changed [STAYS] times, as a HUD label or
 *   a speaker's name plate does;
 * - it is short and comes back: it has been read on [OCCASIONS] separate
 *   occasions, each starting [OCCASION_GAP_MS] or more after the last began,
 *   as a menu opened now and then is.
 *
 * Once a short one is found, every piece of the same text in that game is
 * interface text. A long piece is only ever one in a menu's company, so a
 * sentence of dialogue that was once listed on a save screen is still a line
 * when the game tells it.
 */
class InterfaceText {
    /** How many times each piece on screen has stayed while other text changed. */
    private var stayed: Map<String, Int> = emptyMap()

    /**
     * The pieces of this reading that are interface text because they have
     * stayed. Every reading of a game is offered, an empty one too when the
     * text has gone, so a piece that left and came back does not count as
     * having stayed.
     */
    fun onReading(texts: List<String>): Set<String> {
        val now = texts.toSet()
        val before = stayed.keys
        val changed = before.isNotEmpty() && (now - before).isNotEmpty()
        stayed = now.associateWith { (stayed[it] ?: 0) + if (changed && it in before) 1 else 0 }
        val found = HashSet<String>()
        stayed.forEach { (text, times) -> if (times >= STAYS && short(text)) found += text }
        return found
    }

    /** Forget what was on screen: another game is in front, or the reading starts afresh. */
    fun reset() {
        stayed = emptyMap()
    }

    companion object {
        /** The most characters a piece of interface text has: a menu label, a name. */
        const val SHORT = 12

        /** Pieces arriving together that make a menu. A line, its name plate and a few choices are fewer. */
        const val CROWD = 6

        /** Changes of the text around it that a piece must stay through. */
        const val STAYS = 3

        /** Separate occasions a piece must be read on. */
        const val OCCASIONS = 3

        /** Readings closer together than this are one occasion: a line read again after a menu was opened over it. */
        const val OCCASION_GAP_MS = 10 * 60 * 1000L

        fun short(text: String): Boolean = text.codePointCount(0, text.length) <= SHORT

        /**
         * One kana, kanji, letter or digit with nothing beside it: an icon or
         * the edge of a picture read as a character. It is never a line. A
         * mark alone (…, ！) can be one, and is not this.
         */
        fun lone(text: String): Boolean = text.codePointCount(0, text.length) == 1 && Character.isLetterOrDigit(text.codePointAt(0))

        /**
         * Whether a short piece read at [nowMs] has come back often enough to be
         * interface text, given when it was saved before ([earlierMs], any order).
         */
        fun comesBack(text: String, earlierMs: List<Long>, nowMs: Long): Boolean {
            if (!short(text)) return false
            // An occasion starts with a reading [OCCASION_GAP_MS] or more after the start of the one before.
            var occasions = 0
            var start = Long.MIN_VALUE
            for (at in (earlierMs + nowMs).sorted()) {
                if (start == Long.MIN_VALUE || at - start >= OCCASION_GAP_MS) {
                    occasions++
                    start = at
                }
            }
            return occasions >= OCCASIONS
        }
    }
}
