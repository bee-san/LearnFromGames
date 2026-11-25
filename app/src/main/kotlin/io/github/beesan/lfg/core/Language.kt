// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * A language to learn from games. The player picks one at set-up; the reader
 * is told which, so it reads every line as that language instead of guessing
 * each line's script first.
 *
 * Only Japanese so far. The dictionaries, the word cursor and the character
 * names are still Japanese throughout; what a language needs of the readers
 * is said here, for the day there is a second.
 */
enum class Language(
    /** The tag saved in the settings and given to ScreenAI. */
    val tag: String,
    /** Its name in English, and in the language itself. */
    val title: String,
    val ownName: String,
    /** What watches the screen for the text changing. */
    val probe: Probe,
) {
    JAPANESE("ja", "Japanese", "日本語", Probe.VOCR);

    enum class Probe {
        /** VOCR, a small reader that knows only Japanese: quick enough to look at every picture. */
        VOCR,

        /** The reader itself, looking twice: for a language VOCR cannot read. */
        READER,
    }

    companion object {
        /** The language with [tag]; null for an empty tag (not chosen yet) or one this build does not know. */
        fun of(tag: String): Language? = entries.firstOrNull { it.tag == tag }
    }
}
