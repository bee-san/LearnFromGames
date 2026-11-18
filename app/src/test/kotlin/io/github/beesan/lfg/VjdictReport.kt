// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.dict.VjdictImport
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a pass-or-fail test: converts the .vjdict, or the zip holding one, that
 * LFG_VJDICT names, as "Add dictionary…" does, into the Yomitan zip
 * LFG_VJDICT_OUT names, and prints what it found and how long it took. Runs
 * only when LFG_VJDICT is set; the dictionary stays where it is.
 */
class VjdictReport {
    @Test
    fun convert() {
        val input = System.getenv("LFG_VJDICT")?.let(::File)
        assumeTrue("LFG_VJDICT is not set", input != null && input.isFile)
        val output = File(System.getenv("LFG_VJDICT_OUT") ?: File(input!!.parentFile, "converted.zip").path)
        val start = System.nanoTime()
        val found = VjdictImport.find(input!!, input.name, output.absoluteFile.parentFile) ?: error("No .vjdict in $input")
        val extracted = System.nanoTime()
        try {
            val converted = VjdictImport.toYomitan(found.file, found.name, found.attribution, output)
            val done = System.nanoTime()
            println(
                "vjdict report: ${converted.title}: ${converted.entries} entries, ${converted.terms} terms " +
                    "(${converted.aliases} for extra keys), ${converted.frequencies} frequencies, ${converted.skipped} skipped; " +
                    "found in ${(extracted - start) / 1_000_000} ms, converted in ${(done - extracted) / 1_000_000} ms; " +
                    "${found.file.length()} bytes in, ${output.length()} bytes out; attribution: ${found.attribution != null}",
            )
        } finally {
            if (found.temporary) found.file.delete()
        }
    }
}
