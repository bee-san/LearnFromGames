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
 */
class SettleDetector(private val stillMs: Long = 300, private val restlessMs: Long = 6_000) {
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

    /** Returns true once per new, settled signature. */
    fun offer(signature: String, nowMs: Long): Boolean {
        if (!same(signature, candidate)) {
            // A line typing itself out grows from the one before it; anything else is a new line.
            if (!(candidate.isNotEmpty() && signature.startsWith(candidate)) || candidate == showing) changeStartedMs = nowMs
            candidate = signature
            candidateSince = nowMs
            if (signature.isEmpty() || same(signature, lastSettled)) {
                restlessSince = 0L
                return false
            }
            if (restlessSince == 0L) restlessSince = nowMs
            // Still changing after all this time: it is not typing out, it is a screen that never holds still.
            return nowMs - restlessSince >= restlessMs && settle(signature)
        }
        if (signature.isEmpty() || same(signature, lastSettled)) {
            restlessSince = 0L
            return false
        }
        if (nowMs - candidateSince < stillMs) return false
        return settle(signature)
    }

    private fun settle(signature: String): Boolean {
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
    }

    companion object {
        /**
         * Whether two signatures are the same text, give or take the first pass's noise: at most one
         * character in twenty added, lost or changed. Short text allows none, so a one-word change counts.
         */
        fun same(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.isEmpty() || b.isEmpty()) return false
            val allowed = minOf(16, maxOf(a.length, b.length) / 20)
            if (allowed == 0 || kotlin.math.abs(a.length - b.length) > allowed) return false
            val counts = HashMap<Char, Int>()
            for (c in a) counts[c] = (counts[c] ?: 0) + 1
            for (c in b) counts[c] = (counts[c] ?: 0) - 1
            return counts.values.sumOf { kotlin.math.abs(it) } <= allowed
        }
    }
}
