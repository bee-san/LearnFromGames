// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * Where the time goes. Each named step keeps how often it ran, its mean and
 * its worst; [report] gives them as one line and starts again. It costs two
 * clock reads a step, so it stays in.
 */
object Perf {
    class Step {
        var count = 0L
        var totalNs = 0L
        var maxNs = 0L
    }

    val steps = LinkedHashMap<String, Step>()

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, System.nanoTime() - start)
        }
    }

    fun add(name: String, ns: Long) = synchronized(steps) {
        val step = steps.getOrPut(name) { Step() }
        step.count++
        step.totalNs += ns
        if (ns > step.maxNs) step.maxNs = ns
    }

    /** "probe 120x 24.1ms (max 40.2) | read 6x 118.0ms (max 131.5) | …", and the counts start again. */
    fun report(): String = synchronized(steps) {
        val line = steps.entries.joinToString(" | ") { (name, s) ->
            "%s %dx %.1fms (max %.1f)".format(name, s.count, s.totalNs / 1e6 / s.count.coerceAtLeast(1), s.maxNs / 1e6)
        }
        steps.clear()
        line
    }
}
