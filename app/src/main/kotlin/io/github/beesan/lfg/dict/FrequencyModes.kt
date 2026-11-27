// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.zip.ZipFile

/**
 * Whether a frequency dictionary ranks words (1 is the most common) or counts
 * them (the more the commoner), as its index.json says. Only ranks make a
 * word's average rank; a count read as a rank would make a common word rare.
 * Free of Android, so it is tested on the build machine.
 */
object FrequencyModes {
    const val RANK = "rank-based"
    const val OCCURRENCE = "occurrence-based"

    private val MODE = Regex("\"frequencyMode\"\\s*:\\s*\"([^\"]*)\"")

    /** The mode an index.json declares; null when it declares none. */
    fun declared(index: String): String? = MODE.find(index)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /** [index] declaring ranks in place of counts. */
    fun asRanks(index: String): String = MODE.replace(index) { "\"frequencyMode\":\"$RANK\"" }

    /**
     * Whether [values] are ranks, whatever the dictionary says (Hachidori #684):
     * the smallest is 1, and at most one in a hundred is. In a count, words
     * seen once are many; in a ranking only the top word, with its few
     * readings, is first.
     */
    fun holdRanks(values: Collection<Int>): Boolean {
        if (values.isEmpty() || values.min() != 1) return false
        return values.count { it == 1 } * 100 <= values.size
    }

    /** The first figure of each frequency row in a Yomitan zip's meta banks. */
    fun values(zip: File): List<Int> {
        val out = ArrayList<Int>()
        ZipFile(zip).use { file ->
            for (entry in file.entries()) {
                val name = entry.name.substringAfterLast('/')
                if (!(name.startsWith("term_meta_bank_") || name.startsWith("kanji_meta_bank_")) || !name.endsWith(".json")) continue
                val rows = file.getInputStream(entry).use { DictJson.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)) } as? JsonArray ?: continue
                for (row in rows) {
                    val cells = row as? JsonArray ?: continue
                    if (cells.size < 3 || (cells[1] as? JsonPrimitive)?.content != "freq") continue
                    figure(cells[2])?.let(out::add)
                }
            }
        }
        return out
    }

    /** A row's figure: a number, `{value}`, or `{reading, frequency}` holding either. */
    private fun figure(data: JsonElement): Int? = when (data) {
        is JsonPrimitive -> data.intOrNull ?: data.content.toDoubleOrNull()?.toInt()
        is JsonObject -> data["frequency"]?.let(::figure) ?: data["value"]?.let(::figure)
        else -> null
    }
}
