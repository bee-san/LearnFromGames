package io.github.beesan.lfg

import io.github.beesan.lfg.core.SettleDetector
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Not a pass-or-fail test: what reading a line as soon as it ends a sentence saves, and what it costs.
 * Runs only when LFG_PAGES names a file of pages, one to a line, their rows parted by tabs.
 *
 * Each page follows the one before it and types itself out at several speeds, with and without a
 * rest after a full stop, while the screen is looked at every 350 ms from a random moment. Rows too
 * short to be the story's (a name plate) are there from the start. For the detector without the release
 * and with it: how long the whole page waited to be read, how many readings a page took, and how
 * many pages were read before they were whole.
 */
class SettleBench {
    private class Tally {
        var pages = 0
        var waitMs = 0L
        var reads = 0
        var partial = 0
        var atOnce = 0
    }

    private fun story(row: String) = row.length >= 8 || row.lastOrNull()?.let { it in "。！？!?」』" } == true

    /** When each character of the page's story is on screen, from 0. */
    private fun times(rows: List<String>, perChar: Int, rest: Int): List<IntArray> {
        var t = 0
        return rows.map { row ->
            if (!story(row)) IntArray(row.length) else IntArray(row.length) { i ->
                t += perChar
                val at = t
                if (row[i] in "。！？!?、" && i < row.length - 1) t += if (row[i] == '、') rest / 2 else rest
                at
            }
        }
    }

    private fun signature(rows: List<String>, times: List<IntArray>, t: Int) =
        rows.indices.map { r -> rows[r].take(times[r].count { it <= t }) }.filter { it.isNotEmpty() }.joinToString("\n")

    @Test
    fun report() {
        val file = System.getenv("LFG_PAGES")?.let(::File)
        assumeTrue("LFG_PAGES is not set", file != null && file.isFile)
        val pages = file!!.readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val out = StringBuilder("ms/char rest | without the release | with it\n")
        for ((perChar, rest) in listOf(0 to 0, 20 to 0, 35 to 0, 35 to 250, 60 to 0, 60 to 250)) {
            val tallies = listOf(false, true).map { early ->
                val tally = Tally()
                val d = SettleDetector(atSentenceEnd = early)
                var clock = 0L
                val phase = Random(1)
                for (rows in pages) {
                    val times = times(rows, perChar, rest)
                    val done = times.maxOf { it.maxOrNull() ?: 0 }
                    // The page starts somewhere between two looks.
                    val start = clock + phase.nextInt(350)
                    var firstWhole = true
                    var settled = false
                    var look = clock
                    while (!settled) {
                        look += 350
                        if (look < start) continue
                        val t = (look - start).toInt()
                        val whole = t >= done
                        if (d.offer(signature(rows, times, t), look)) {
                            tally.reads++
                            if (whole) {
                                settled = true
                                tally.waitMs += t - done
                                if (firstWhole) tally.atOnce++
                            } else tally.partial++
                        }
                        if (whole) firstWhole = false
                        if (t > done + 10_000) settled = true
                    }
                    tally.pages++
                    // The player reads for a while; the looks go on.
                    clock = look + 350 * 4
                    d.offer(signature(rows, times, done), clock)
                }
                tally
            }
            out.append("%2d %3d".format(perChar, rest))
            for (t in tallies) out.append(" | wait %4d reads %.2f part %4.1f%%".format(t.waitMs / t.pages, t.reads.toDouble() / t.pages, 100.0 * t.partial / t.pages))
            out.append("\n")
        }
        out.append("${pages.size} pages\n")
        println(out)
        System.getenv("LFG_SETTLE_OUT")?.let { File(it).writeText(out.toString()) }
    }
}
