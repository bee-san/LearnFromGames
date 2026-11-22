// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A character's card as Yomitan structured content, ported from content_builder.rs
 * for a character from one visual novel. Keys are written in the order the Rust
 * source lists them; serde sorts them, and key order is not part of the format.
 */
class CharacterCard(private val settings: DictSettings) {

    /** The comma-joined stats line: sex, age, height, weight, blood type and birthday, whichever are known. */
    fun formatStats(char: Character): String {
        val parts = ArrayList<String>()
        char.sex?.let { sex -> SEX_DISPLAY[sex.lowercase()]?.let(parts::add) }
        char.age?.let { parts += "$it years" }
        char.height?.let { parts += "${it}cm" }
        char.weight?.let { parts += "${it}kg" }
        char.bloodType?.let { parts += "Blood Type $it" }
        char.birthday?.let { birthday ->
            val formatted = formatBirthday(birthday)
            if (formatted.isNotEmpty()) parts += "Birthday: $formatted"
        }
        return parts.joinToString(" • ")
    }

    /** One list item per trait group with something left to show; spoiler traits are left out unless spoilers are on. */
    fun buildTraitsByCategory(char: Character): List<JsonElement> {
        val items = ArrayList<JsonElement>()
        val categories = listOf(
            char.personality to "Personality",
            char.roles to "Role",
            char.engagesIn to "Activities",
            char.subjectOf to "Subject of",
        )
        for ((traits, label) in categories) {
            if (traits.isEmpty()) continue
            val shown = traits.filter { it.name.isNotEmpty() && (settings.showSpoilers || it.spoiler == 0) }.map { it.name }
            if (shown.isNotEmpty()) items += obj("tag" to str("li"), "content" to str("$label: ${shown.joinToString(", ")}"))
        }
        return items
    }

    /**
     * The whole card. [imagePath] is the portrait's path in the zip and [imageDims]
     * its size in pixels; [gameTitle] is shown as "From: …".
     */
    fun buildContent(char: Character, imagePath: String?, imageDims: Pair<Int, Int>?, gameTitle: String): JsonObject {
        val content = ArrayList<JsonElement>()
        if (char.nameOriginal.isNotEmpty()) {
            content += obj(
                "tag" to str("div"),
                "data" to obj("id" to str(ID_NAME)),
                "style" to obj("fontWeight" to str("bold"), "fontSize" to str("1.2em")),
                "content" to str(char.nameOriginal),
            )
        }
        if (char.name.isNotEmpty()) {
            content += obj(
                "tag" to str("div"),
                "data" to obj("id" to str(ID_NAME_ROMAJI)),
                "style" to obj("fontStyle" to str("italic"), "color" to str("#666"), "marginBottom" to str("8px")),
                "content" to str(char.name),
            )
        }
        if (settings.showImage && imagePath != null) {
            val (w, h) = imageDims?.takeIf { it.first > 0 && it.second > 0 }
                ?.let { (w, h) -> (w * MAX_DISPLAY_HEIGHT + h / 2) / h to MAX_DISPLAY_HEIGHT }
                ?: (FALLBACK_DISPLAY_WIDTH to MAX_DISPLAY_HEIGHT)
            content += obj(
                "tag" to str("div"),
                "data" to obj("id" to str(ID_PORTRAIT_CONTAINER)),
                "content" to obj(
                    "tag" to str("img"),
                    "path" to str(imagePath),
                    "width" to JsonPrimitive(w),
                    "height" to JsonPrimitive(h),
                    "sizeUnits" to str("px"),
                    "collapsible" to JsonPrimitive(false),
                    "collapsed" to JsonPrimitive(false),
                    "background" to JsonPrimitive(false),
                ),
            )
        }
        if (gameTitle.isNotEmpty()) {
            content += obj(
                "tag" to str("div"),
                "data" to obj("id" to str(ID_SOURCE)),
                "style" to obj("fontSize" to str("0.9em"), "color" to str("#888"), "marginTop" to str("4px")),
                "content" to str("From: $gameTitle"),
            )
        }
        if (settings.showTag) {
            // A span inside a div, as Yomitan's styles have no inline display and tools that strip HTML keep divs apart.
            content += obj(
                "tag" to str("div"),
                "data" to obj("id" to str(ID_ROLE_CONTAINER)),
                "content" to obj(
                    "tag" to str("span"),
                    "style" to obj(
                        "background" to str(ROLE_COLORS[char.role] ?: "#9E9E9E"),
                        "color" to str("white"),
                        "padding" to str("2px 6px"),
                        "borderRadius" to str("3px"),
                        "fontSize" to str("0.85em"),
                        "marginTop" to str("4px"),
                    ),
                    "data" to obj("id" to str(ID_ROLE)),
                    "content" to str(ROLE_LABELS[char.role] ?: "Unknown"),
                ),
            )
        }
        if (settings.showDescription) {
            val desc = char.description
            if (desc != null && desc.isNotBlank()) {
                val shown = if (settings.showSpoilers) desc else stripSpoilers(desc)
                if (shown.isNotEmpty()) {
                    content += obj(
                        "tag" to str("details"),
                        "content" to JsonArray(
                            listOf(
                                obj("tag" to str("summary"), "content" to str("Description")),
                                obj(
                                    "tag" to str("div"),
                                    "style" to obj("fontSize" to str("0.9em"), "marginTop" to str("4px")),
                                    "content" to parseBbcodeToStructured(parseVndbMarkup(shown)),
                                ),
                            ),
                        ),
                    )
                }
            }
        }
        if (settings.showTraits) {
            val info = ArrayList<JsonElement>()
            val stats = formatStats(char)
            if (stats.isNotEmpty()) info += obj("tag" to str("li"), "style" to obj("fontWeight" to str("bold")), "content" to str(stats))
            info += buildTraitsByCategory(char)
            if (info.isNotEmpty()) {
                content += obj(
                    "tag" to str("details"),
                    "content" to JsonArray(
                        listOf(
                            obj("tag" to str("summary"), "content" to str("Character Information")),
                            obj(
                                "tag" to str("ul"),
                                "style" to obj("marginTop" to str("4px"), "paddingLeft" to str("20px")),
                                "content" to JsonArray(info),
                            ),
                        ),
                    ),
                )
            }
        }
        if (settings.showSeiyuu) {
            val va = char.seiyuu
            if (!va.isNullOrEmpty()) {
                content += obj(
                    "tag" to str("details"),
                    "content" to JsonArray(
                        listOf(
                            obj("tag" to str("summary"), "content" to str("Voiced by")),
                            obj(
                                "tag" to str("div"),
                                "style" to obj("marginTop" to str("4px")),
                                "content" to JsonArray(
                                    listOf(obj("tag" to str("div"), "style" to obj("fontSize" to str("0.9em"), "marginTop" to str("4px")), "content" to str(va))),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        return obj("type" to str("structured-content"), "content" to JsonArray(content))
    }

    companion object {
        const val ID_NAME = "name"
        const val ID_NAME_ROMAJI = "name-romaji"
        const val ID_PORTRAIT_CONTAINER = "portrait-container"
        const val ID_SOURCE = "source"
        const val ID_ROLE_CONTAINER = "role-container"
        const val ID_ROLE = "role"

        /** Portraits are shown this many CSS pixels high. */
        const val MAX_DISPLAY_HEIGHT = 100

        /** The width shown when a portrait's size is unknown. */
        const val FALLBACK_DISPLAY_WIDTH = 67

        val ROLE_COLORS = mapOf("main" to "#4CAF50", "primary" to "#2196F3", "side" to "#FF9800", "appears" to "#9E9E9E")
        val ROLE_LABELS = mapOf("main" to "Protagonist", "primary" to "Main Character", "side" to "Side Character", "appears" to "Minor Role")
        private val MONTH_NAMES = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        private val SEX_DISPLAY = mapOf("m" to "♂ Male", "f" to "♀ Female", "male" to "♂ Male", "female" to "♀ Female")

        private val VNDB_SPOILER = Regex("(?isu)\\[spoiler].*?\\[/spoiler]")
        private val URL = Regex("(?iu)\\[url=[^\\]]+]([^\\[]*)\\[/url]")
        private val QUOTE = Regex("(?isu)\\[quote](.*?)\\[/quote]")
        private val CODE = Regex("(?isu)\\[code](.*?)\\[/code]")
        private val RAW = Regex("(?isu)\\[raw](.*?)\\[/raw]")
        private val UNDERLINE = Regex("(?isu)\\[u](.*?)\\[/u]")
        private val STRIKETHROUGH = Regex("(?isu)\\[s](.*?)\\[/s]")
        private val BBCODE_INNER = Regex("(?isu)\\[(b|i)]([^\\[]*?)\\[/(b|i)]")
        private val PLACEHOLDER = Regex("\u0000NODE(\\d+)\u0000")

        /** VNDB's [spoiler] sections taken out, then the text trimmed. */
        fun stripSpoilers(text: String): String = VNDB_SPOILER.replace(text, "").trim()

        /** VNDB's [url], [quote], [code], [raw], [u] and [s] tags reduced to the text inside them. */
        fun parseVndbMarkup(text: String): String {
            var t = URL.replace(text, "$1")
            t = QUOTE.replace(t, "$1")
            t = CODE.replace(t, "$1")
            t = RAW.replace(t, "$1")
            t = UNDERLINE.replace(t, "$1")
            return STRIKETHROUGH.replace(t, "$1")
        }

        /**
         * [b] and [i] as bold and italic spans: a plain string when there are none,
         * otherwise a node or a list of strings and nodes. The innermost pair is
         * replaced first by a numbered placeholder, until none is left; a [b] closed
         * by [/i] loses both tags and keeps its text.
         */
        fun parseBbcodeToStructured(text: String): JsonElement {
            val nodes = ArrayList<JsonElement>()
            var working = text
            while (true) {
                val m = BBCODE_INNER.find(working) ?: break
                val open = m.groupValues[1].lowercase()
                val close = m.groupValues[3].lowercase()
                val inner = m.groupValues[2]
                val replacement = if (open != close) {
                    inner
                } else {
                    val content = resolvePlaceholders(inner, nodes)
                    nodes += if (open == "b") obj("tag" to str("span"), "style" to obj("fontWeight" to str("bold")), "content" to content)
                    else obj("tag" to str("span"), "style" to obj("fontStyle" to str("italic")), "content" to content)
                    "\u0000NODE${nodes.size - 1}\u0000"
                }
                working = working.substring(0, m.range.first) + replacement + working.substring(m.range.last + 1)
            }
            return resolvePlaceholders(working, nodes)
        }

        private fun resolvePlaceholders(text: String, nodes: List<JsonElement>): JsonElement {
            if (!PLACEHOLDER.containsMatchIn(text)) return str(text)
            val result = ArrayList<JsonElement>()
            var lastEnd = 0
            for (m in PLACEHOLDER.findAll(text)) {
                if (m.range.first > lastEnd) result += str(text.substring(lastEnd, m.range.first))
                m.groupValues[1].toIntOrNull()?.let { nodes.getOrNull(it) }?.let(result::add)
                lastEnd = m.range.last + 1
            }
            if (lastEnd < text.length) result += str(text.substring(lastEnd))
            return if (result.size == 1) result[0] else JsonArray(result)
        }

        /** "September 1" from [9, 1]; empty when the day is missing, "Unknown" for a month out of range. */
        fun formatBirthday(birthday: List<Int>): String {
            if (birthday.size < 2) return ""
            val month = MONTH_NAMES.getOrNull(birthday[0] - 1) ?: "Unknown"
            return "$month ${birthday[1]}"
        }

        /** The note an honorific's entry starts with: the suffix in bold, then what it means. */
        fun honorificBanner(suffixDisplay: String, suffixDescription: String): JsonObject = obj(
            "tag" to str("div"),
            // Yomitan's styles set a border on all four sides at once, so the left bar is a width of "0 0 0 3px".
            "style" to obj(
                "fontSize" to str("0.85em"),
                "color" to str("#4A90D9"),
                "borderColor" to str("#4A90D9"),
                "borderStyle" to str("solid"),
                "borderWidth" to str("0 0 0 3px"),
                "paddingLeft" to str("6px"),
                "marginBottom" to str("6px"),
            ),
            "content" to JsonArray(
                listOf(
                    obj("tag" to str("span"), "style" to obj("fontWeight" to str("bold")), "content" to str(suffixDisplay)),
                    obj("tag" to str("span"), "content" to str(" — $suffixDescription")),
                ),
            ),
        )

        /** [base] with the honorific's banner first; a card without a content list is returned as it is. */
        fun buildHonorificContent(base: JsonElement, suffixDisplay: String, suffixDescription: String): JsonElement {
            val content = (base as? JsonObject)?.get("content") as? JsonArray ?: return base
            return obj("type" to str("structured-content"), "content" to JsonArray(listOf(honorificBanner(suffixDisplay, suffixDescription)) + content))
        }

        /** A Yomitan term: term, reading, definition tags, rules, score, definitions, sequence, term tags. */
        fun createTermEntry(term: String, reading: String, role: String, score: Int, structuredContent: JsonElement): JsonArray = JsonArray(
            listOf(
                str(term), str(reading), str(definitionTags(role)), str(""), JsonPrimitive(score),
                JsonArray(listOf(structuredContent)), JsonPrimitive(0), str(""),
            ),
        )

        fun definitionTags(role: String): String = if (role.isEmpty()) "name" else "name $role"

        private fun str(s: String) = JsonPrimitive(s)

        private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    }
}
