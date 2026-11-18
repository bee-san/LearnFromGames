// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Yomitan glossaries as plain text, which is all stage 1 shows. A glossary is
 * a JSON array whose items are strings, `{type:"text"}`, `{type:"image"}` or
 * `{type:"structured-content"}` trees. Full rendering (images, tables, each
 * dictionary's own styling) is stage 3.
 */
object GlossaryText {
    /** One string per sense. Unparseable input comes back as itself rather than vanishing. */
    fun senses(glossaryJson: String): List<String> {
        if (glossaryJson.isBlank()) return emptyList()
        val root = try {
            DictJson.parseToJsonElement(glossaryJson)
        } catch (_: Exception) {
            return listOf(glossaryJson.trim())
        }
        val items = if (root is JsonArray) root.toList() else listOf(root)
        val plain = mutableListOf<String>()
        val structured = mutableListOf<String>()
        for (item in items) {
            when {
                item is JsonPrimitive -> plain += item.content
                item is JsonObject && item.type() == "text" -> plain += item.string("text")
                item is JsonObject && item.type() == "structured-content" ->
                    item["content"]?.let { structured += structuredSenses(it) }
                item is JsonObject && item.type() == "image" -> Unit
                // A term-bank row can also be a bare structured-content tree.
                else -> structured += structuredSenses(item)
            }
        }
        val out = mutableListOf<String>()
        // JMdict-style plain glossaries list synonyms of one sense: join them. A
        // plain string's own line breaks stay (Rubi's kanji lines follow the definition).
        plain.map(::tidyLines).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { out += it.joinToString("; ") }
        out += structured
        return out
    }

    /**
     * A numbered list is a list of senses (Jitendex puts a whole entry in one
     * row). A bulleted list is the glosses of one sense (JMdict puts each sense
     * in a row of its own), so its items are joined.
     */
    private fun structuredSenses(node: JsonElement): List<String> {
        // A row that is only a table is reference material (JMdict's table of written forms), not a meaning.
        if (onlyTable(node)) return emptyList()
        val senseList = findTag(node, "ol")
        val source = senseList?.let { listItems(it) }?.takeIf { it.isNotEmpty() } ?: listOf(node)
        return source.map { tidy(buildString { flatten(it, this) }) }.filter { it.isNotEmpty() }
    }

    private fun onlyTable(node: JsonElement): Boolean = when (node) {
        is JsonArray -> node.size == 1 && onlyTable(node[0])
        is JsonObject -> node.string("tag") == "table" ||
            (node.string("tag") in WRAPPERS && node["content"]?.let(::onlyTable) == true)
        else -> false
    }

    private fun findTag(node: JsonElement, tag: String): JsonObject? = when (node) {
        is JsonArray -> node.firstNotNullOfOrNull { findTag(it, tag) }
        is JsonObject -> when {
            skipped(node) -> null
            node.string("tag") == tag -> node
            else -> node["content"]?.let { findTag(it, tag) }
        }
        else -> null
    }

    private fun listItems(list: JsonObject): List<JsonElement> = when (val content = list["content"]) {
        null -> emptyList()
        is JsonArray -> content.toList()
        else -> listOf(content)
    }

    private fun flatten(node: JsonElement, out: StringBuilder) {
        when (node) {
            is JsonPrimitive -> out.append(node.content)
            is JsonArray -> node.forEach { flatten(it, out) }
            is JsonObject -> {
                if (skipped(node)) return
                when (val tag = node.string("tag")) {
                    "br" -> out.append(' ')
                    "img" -> Unit
                    // Furigana would otherwise run into the word it annotates.
                    "rt", "rp" -> Unit
                    "ul", "ol" -> {
                        val items = listItems(node).map { tidy(buildString { flatten(it, this) }) }.filter { it.isNotEmpty() }
                        if (items.isNotEmpty()) {
                            if (out.isNotEmpty()) out.append(' ')
                            out.append(items.joinToString("; ")).append(' ')
                        }
                    }
                    else -> {
                        val block = tag in BLOCKS
                        if (block && out.isNotEmpty()) out.append(' ')
                        node["content"]?.let { flatten(it, out) }
                        if (block) out.append(' ')
                    }
                }
            }
        }
    }

    /** Parts of an entry that are not its meaning: example sentences, attribution, cross-reference chrome. */
    private fun skipped(node: JsonObject): Boolean {
        val content = (node["data"] as? JsonObject)?.get("content")?.let { (it as? JsonPrimitive)?.content } ?: return false
        return content in SKIPPED_DATA
    }

    private fun tidy(text: String) = text.replace(WHITESPACE, " ").trim()

    private fun tidyLines(text: String) = text.lines().map(::tidy).filter { it.isNotEmpty() }.joinToString("\n")

    private fun JsonObject.type() = string("type")
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.jsonPrimitive?.content ?: ""

    private val WHITESPACE = Regex("\\s+")
    private val WRAPPERS = setOf("div", "span", "")
    private val BLOCKS = setOf("div", "li", "table", "tr", "td", "th", "details", "summary")
    private val SKIPPED_DATA = setOf(
        "examples", "example-sentence", "attribution", "forms", "xref", "antonyms", "references", "refGlosses", "notes",
    )
}
