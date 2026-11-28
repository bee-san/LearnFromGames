// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * Decides when the text on screen is worth reading properly: it has changed
 * since the last line that was read, and it has stopped changing. Dialogue that
 * is still typing itself out keeps changing, so it is read once, at the end.
 *
 * The signature is whatever the cheap first pass produces for a frame (VOCR's
 * text, or a hash of the pixels until VOCR is in). An empty signature means no
 * text on screen.
 *
 * The first pass is not steady over a moving background: a frame apart it may
 * read a symbol where it read none, or one character for another. So two
 * signatures count as the same text when only a few characters in a hundred
 * differ, and text that never holds still at all is read anyway after
 * [restlessMs], rather than never.
 *
 * Text that appears all at once and ends where a sentence does ([ended]) is
 * not waited for: it is taken as whole on the first picture of it. Text seen
 * growing is being typed out and waits to hold still, as before.
 * [atSentenceEnd] turns that off. After PlayTranslate's sentence boundary
 * release; measured by SettleBench.
 *
 * Text taken on its first picture may have been caught fading in or sliding
 * into place. If the next pictures read it a little differently, it is read
 * once more when it holds still ([confirming]), so the line keeps the clear
 * reading. Text that ended a sentence but was still waiting, because it had
 * been seen typing, and is gone before it held still, is [missed]: the picture
 * before is read for it.
 */
class SettleDetector(private val stillMs: Long = 300, private val restlessMs: Long = 6_000, private val atSentenceEnd: Boolean = true) {
    /** A single full stop "." ends a sentence too: in a language other than Japanese, where 「……」 is not typed a dot at a time. */
    @Volatile
    var dotEnds = false

    private var candidate = ""
    private var candidateSince = 0L
    private var lastSettled = ""
    private var showing = ""
    private var restlessSince = 0L

    /**
     * When the text now on screen first began to differ from what was there
     * before: the moment a new line started to appear, which is when its
     * voice starts. Typing out does not move it.
     */
    var changeStartedMs = 0L
        private set

    /** Since when the text last settled has looked as it does. */
    val stillSinceMs: Long get() = candidateSince

    /** Whether the text last settled never held still and was read for having changed too long. */
    var restless = false
        private set

    /** Whether text that is not the text last settled is on screen, waiting to hold still. */
    val pending: Boolean get() = candidate.isNotEmpty() && !same(candidate, lastSettled)

    /** Whether the last settled text was taken on its first picture and has not been seen the same again. */
    private var provisional = false

    /** Whether the text last settled is the text taken on its first picture, read again now that it holds still. */
    var confirming = false
        private set

    /** Set by [offer]: the text before this signature ended a sentence and went before it could be read. */
    var missed = false
        private set

    /** Set by [settleNext]: the next new text settles as soon as it is seen. */
    private var eager = false

    /** The next new text is taken as settled at once, for text asked for that is already showing. */
    fun settleNext() {
        eager = true
    }

    /** Returns true once per new, settled signature. */
    fun offer(signature: String, nowMs: Long): Boolean {
        missed = false
        if (provisional) {
            if (signature == lastSettled || signature.isEmpty() || !same(signature, lastSettled)) {
                provisional = false
            } else {
                // Read a little differently from the first picture: read again once that holds still.
                if (signature != candidate) {
                    candidate = signature
                    candidateSince = nowMs
                    return false
                }
                if (nowMs - candidateSince < stillMs) return false
                provisional = false
                return settle(signature, confirming = true)
            }
        }
        if (eager && signature.isNotEmpty() && !same(signature, lastSettled)) {
            changeStartedMs = nowMs
            candidate = signature
            candidateSince = nowMs
            return settle(signature)
        }
        if (!same(signature, candidate)) {
            // A line typing itself out grows from the one before it; anything else is a new line.
            val growing = candidate.isNotEmpty() && signature.startsWith(candidate)
            // Seen typing, it waited to hold still even though it had ended a sentence; and now it is gone.
            if (atSentenceEnd && !growing && candidate.isNotEmpty() && !same(candidate, lastSettled) && ended(candidate, lastSettled, dotEnds)) {
                missed = true
                lastSettled = candidate
                showing = candidate
            }
            if (!growing || candidate == showing) changeStartedMs = nowMs
            candidate = signature
            candidateSince = nowMs
            if (signature.isEmpty() || same(signature, lastSettled)) {
                restlessSince = 0L
                return false
            }
            // Whole on the first picture of it. Text growing from the look before is being typed, and a
            // rest after a full stop in the middle of a page looks just like its end: that waits, as ever.
            if (atSentenceEnd && !growing && ended(signature, lastSettled, dotEnds)) return settle(signature, provisional = true)
            if (restlessSince == 0L) restlessSince = nowMs
            // Still changing after all this time: it is not typing out, it is a screen that never holds still.
            return nowMs - restlessSince >= restlessMs && settle(signature, restless = true)
        }
        if (signature.isEmpty() || same(signature, lastSettled)) {
            restlessSince = 0L
            return false
        }
        if (nowMs - candidateSince < stillMs) return false
        return settle(signature)
    }

    private fun settle(signature: String, restless: Boolean = false, provisional: Boolean = false, confirming: Boolean = false): Boolean {
        this.restless = restless
        this.provisional = provisional
        this.confirming = confirming
        eager = false
        lastSettled = signature
        showing = signature
        restlessSince = 0L
        return true
    }

    fun reset() {
        candidate = ""
        candidateSince = 0L
        lastSettled = ""
        showing = ""
        restlessSince = 0L
        changeStartedMs = 0L
        eager = false
        provisional = false
        confirming = false
        missed = false
    }

    companion object {
        private const val ENDS = "。．！？!?‼⁉⁈⁇"
        private const val CLOSES = "」』）)】\"”»"
        private const val OPENS = "「『（(【"

        /**
         * Whether the text new since [before] ends where a sentence does: the last row of [signature]
         * that [before] had not ends in a full stop, a question or an exclamation mark, with or without
         * the bracket that closes speech after it; or is speech opened and closed, as 「そうだね」 is.
         * Dots do not count: 「……」 is typed a dot at a time. Rows that were there before are left out,
         * so a help line under the story that ends in a full stop does not make every look a whole line.
         */
        fun ended(signature: String, before: String, dotEnds: Boolean = false): Boolean {
            val old = before.split('\n')
            val row = signature.split('\n').lastOrNull { it.isNotBlank() && it !in old }?.trimEnd() ?: return false
            val text = row.trimEnd { it in CLOSES }
            if (text.isEmpty()) return false
            if (text.last() in ENDS) return true
            if (dotEnds && text.last() == '.' && text.getOrNull(text.length - 2) != '.') return true
            // Speech with no full stop: closed by the bracket that the new rows opened with.
            if (text.length == row.length) return false
            val first = signature.split('\n').firstOrNull { it.isNotBlank() && it !in old }?.trimStart() ?: return false
            val opened = OPENS.indexOf(first.first())
            return opened >= 0 && CLOSES.indexOf(row.last()).let { it == opened || (opened == 3 && it == 2) || (opened == 2 && it == 3) } &&
                signature.count { it == first.first() } == signature.count { it == row.last() }
        }

        /**
         * Whether two signatures are the same text, give or take the first pass's noise: at most one
         * character in twenty added or lost, a character changed counting as two. Short text allows
         * none, so a one-word change counts. The characters must be in the same order, so one name
         * for another of the same letters is a change; and so is any digit, as 10 for 20.
         */
        fun same(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.isEmpty() || b.isEmpty()) return false
            val allowed = minOf(16, maxOf(a.length, b.length) / 20)
            if (allowed == 0 || kotlin.math.abs(a.length - b.length) > allowed) return false
            if (a.filter(::digit) != b.filter(::digit)) return false
            // The same letters moved about is a new name, not noise.
            if (a.length == b.length && a.toList().sorted() == b.toList().sorted()) return false
            return a.length + b.length - 2 * common(a, b) <= allowed
        }

        private fun digit(c: Char) = c in '0'..'9' || c in '０'..'９'

        /** The length of the longest run of characters both have, in the same order. */
        private fun common(a: String, b: String): Int {
            var above = IntArray(b.length + 1)
            var row = IntArray(b.length + 1)
            for (i in a.indices) {
                for (j in b.indices) row[j + 1] = if (a[i] == b[j]) above[j] + 1 else maxOf(above[j + 1], row[j])
                val swap = above
                above = row
                row = swap
            }
            return above[b.length]
        }
    }
}
