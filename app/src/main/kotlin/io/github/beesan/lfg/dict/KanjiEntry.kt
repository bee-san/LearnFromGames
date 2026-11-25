// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A kanji's entry as a card holds it. Bee's Ultimate Kanji Dictionary marks
 * its parts with `data.beeRole`: the keyword, the meanings and a chip for each
 * reading, while its examples and charts are left off the card. Any other
 * dictionary's entry gives its senses as a word's would, and no reading.
 */
object KanjiEntry {
    /**
     * The kanji's commonest readings, the chips at the top of its entry, joined by 、;
     * the full on and kun lists further down are left out.
     * An entry with none of its own has the kanji itself as its reading, as the engine gives it.
     */
    fun reading(entry: Entry): String {
        val chips = entry.meanings.flatMap { g -> roles(g.glossary, "reading-chips", whole = true).firstOrNull()?.let { roles(it, "reading-chip") }.orEmpty() }.distinct()
        return when {
            chips.isNotEmpty() -> chips.joinToString("、")
            entry.reading == entry.expression -> ""
            else -> entry.reading
        }
    }

    /** The keyword, then the meanings; or the entry's senses, from a dictionary that does not mark them. */
    fun senses(entry: Entry): List<String> {
        val marked = entry.meanings.flatMap { roles(it.glossary, "hero-keyword") + roles(it.glossary, "meaning") }.distinct()
        return marked.ifEmpty { entry.senses() }
    }

    /** The text of every node of [glossaryJson] whose `data.beeRole` is [role]; with [whole], each node itself as JSON. */
    internal fun roles(glossaryJson: String, role: String, whole: Boolean = false): List<String> {
        val root = try {
            DictJson.parseToJsonElement(glossaryJson)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonArray -> node.forEach(::walk)
                is JsonObject -> {
                    val data = node["data"] as? JsonObject
                    if ((data?.get("beeRole") as? JsonPrimitive)?.content == role) {
                        if (whole) out += node.toString() else text(node).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    } else {
                        node["content"]?.let(::walk)
                    }
                }
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    /** A node's text, its furigana left out. */
    private fun text(node: JsonElement): String = when (node) {
        is JsonPrimitive -> if (node.isString) node.content else ""
        is JsonArray -> node.joinToString("") { text(it) }
        is JsonObject -> if ((node["tag"] as? JsonPrimitive)?.content == "rt") "" else node["content"]?.let(::text) ?: ""
    }
}
