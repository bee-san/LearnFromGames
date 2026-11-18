// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * A dictionary entry as HTML, the way Yomitan lays structured content out, so
 * each dictionary's own stylesheet applies: every element carries the class
 * `gloss-sc-<tag>` and its `data` as `data-sc-*` attributes, and each
 * dictionary's block sits in an element with `data-dictionary`, which its
 * rules are scoped to. Pictures are asked for from [MEDIA_SCHEME] URLs, which
 * the view answers from the dictionary's own files.
 */
object EntryHtml {
    const val MEDIA_SCHEME = "lfgdict"

    /** The link the audio button follows; the view plays the word instead of going anywhere. In the popup's list, `/<index>` follows. */
    const val AUDIO_URL = "$MEDIA_SCHEME://audio/play"

    /** The link of an entry's card button in the popup's list, with `/<index>` after it: adds the card, or opens it in AnkiDroid. */
    const val CARD_URL = "$MEDIA_SCHEME://card"

    private val TAGS = setOf(
        "br", "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "span", "div", "ol", "ul", "li", "details", "summary", "a", "img",
    )

    // Yomitan's own list: what a dictionary may set in a style object.
    private val STYLES = setOf(
        "fontStyle", "fontWeight", "fontSize", "color", "background", "backgroundColor", "textDecorationLine",
        "textDecorationStyle", "textDecorationColor", "borderColor", "borderStyle", "borderRadius", "borderWidth",
        "clipPath", "verticalAlign", "textAlign", "textEmphasis", "textShadow", "margin", "marginTop", "marginLeft",
        "marginRight", "marginBottom", "padding", "paddingTop", "paddingLeft", "paddingRight", "paddingBottom",
        "wordBreak", "whiteSpace", "cursor", "listStyleType",
    )

    data class Style(val dictionary: String, val css: String)

    /** The whole page for a WebView: the app's look, then each dictionary's own, then the entry. */
    fun page(entry: Entry?, styles: List<Style>, message: String? = null, audio: Boolean = false): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
        append(BASE_CSS)
        for (style in styles) append(scope(style.css, style.dictionary))
        append("</style></head><body>")
        if (entry == null) {
            append("<p class=\"lfg-message\">").append(escape(message ?: "")).append("</p>")
        } else {
            append(body(entry, audio))
        }
        append("</body></html>")
    }

    /**
     * Every entry of a lookup, one under the other, for the popup over a game:
     * Hachidori's and Yomitan's popup keep all the entries in one scrolling
     * list rather than paging through them. Each entry carries its own Add card
     * button ([CARD_URL]) and audio button. The buttons' states and the entry
     * the controller is on are set afterwards by the page's one script,
     * `lfgState`, so pressing a button does not reload the page and lose the
     * scroll.
     */
    fun list(entries: List<Entry>, styles: List<Style>, audio: (Entry) -> Boolean): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
        append(BASE_CSS)
        for (style in styles) append(scope(style.css, style.dictionary))
        append("</style><script>").append(STATE_SCRIPT).append("</script></head><body>")
        entries.forEachIndexed { i, entry ->
            append("<section class=\"lfg-entry\" id=\"e").append(i).append("\">")
            append(body(entry, audio(entry), index = i))
            append("</section>")
        }
        append("</body></html>")
    }

    /**
     * The entry alone: headword with its reading as furigana, the pitch graph,
     * the audio button (and, in the popup's list, the card button), frequencies
     * and accents, the inflection path, then each dictionary's glossaries.
     * [index] is the entry's place in the popup's list, or null in a page of
     * one entry.
     */
    fun body(entry: Entry, audio: Boolean = false, index: Int? = null): String = buildString {
        val accents = Pronunciation.accents(entry)
        append("<div class=\"lfg-head\"><span class=\"lfg-term\" lang=\"ja\">").append(furigana(entry.expression, entry.reading)).append("</span>")
        accents.firstOrNull()?.let { append(graph(it)) }
        append("<span class=\"lfg-actions\">")
        if (audio) {
            append("<a class=\"lfg-button lfg-audio\" href=\"").append(AUDIO_URL).append(if (index != null) "/$index" else "")
            append("\" aria-label=\"Listen\" title=\"Listen\">").append(SPEAKER).append("</a>")
        }
        if (index != null) {
            append("<a class=\"lfg-button lfg-card\" id=\"c").append(index).append("\" href=\"").append(CARD_URL).append('/').append(index)
            append("\" data-state=\"add\" aria-label=\"Add card\" title=\"Add card\">").append(CARD_ICONS).append("</a>")
        }
        append("</span></div>")
        if (entry.frequencies.isNotEmpty() || accents.isNotEmpty()) {
            append("<div class=\"lfg-meta\">")
            for (a in accents) {
                append("<span class=\"lfg-pitch-tag\" data-pitch=\"").append(a.kind.name.lowercase()).append("\">")
                append(a.kind.label).append(" <b>").append(a.position).append("</b></span>")
            }
            for (f in entry.frequencies) {
                val values = f.values.map { it.display.ifEmpty { it.value.toString() } }.distinct()
                if (values.isEmpty()) continue
                append("<span class=\"lfg-freq\"><span class=\"lfg-freq-name\">").append(escape(Pronunciation.shortName(f.dictionary)))
                append("</span><span class=\"lfg-freq-value\">").append(escape(values.joinToString(" · "))).append("</span></span>")
            }
            append("</div>")
        }
        if (entry.path.isNotEmpty()) append("<div class=\"lfg-path\">").append(escape(entry.path)).append("</div>")
        for ((dictionary, glossaries) in entry.glossaries.groupBy { it.dictionary }) {
            append("<div class=\"lfg-dict\" data-dictionary=\"").append(escape(dictionary)).append("\">")
            append("<div class=\"lfg-source\">").append(escape(dictionary)).append("</div>")
            append("<ol class=\"lfg-senses\">")
            for (g in glossaries) {
                append("<li>")
                val tags = g.definitionTags.split(' ').filter { it.isNotBlank() && it.toIntOrNull() == null }
                if (tags.isNotEmpty()) {
                    append("<span class=\"lfg-tags\">")
                    tags.forEach { append("<span class=\"lfg-tag\">").append(escape(it)).append("</span>") }
                    append("</span>")
                }
                append(glossary(g.glossary, dictionary))
                append("</li>")
            }
            append("</ol></div>")
        }
    }

    /** One glossary (a JSON array of definitions) as HTML. */
    fun glossary(json: String, dictionary: String): String {
        val root = try {
            DictJson.parseToJsonElement(json)
        } catch (_: Exception) {
            return escape(json)
        }
        val items = if (root is JsonArray) root.toList() else listOf(root)
        val plain = mutableListOf<String>()
        val rich = StringBuilder()
        for (item in items) {
            when {
                item is JsonPrimitive -> plain += item.content
                item is JsonObject && item.text("type") == "text" -> plain += item.text("text")
                item is JsonObject && item.text("type") == "image" -> image(item, dictionary, rich)
                item is JsonObject && item.text("type") == "structured-content" -> item["content"]?.let { node(it, dictionary, rich) }
                else -> node(item, dictionary, rich)
            }
        }
        val out = StringBuilder()
        if (plain.size == 1) out.append("<span class=\"gloss-text\">").append(escape(plain[0])).append("</span>")
        if (plain.size > 1) {
            out.append("<ul class=\"gloss-list\">")
            plain.forEach { out.append("<li>").append(escape(it)).append("</li>") }
            out.append("</ul>")
        }
        return out.append(rich).toString()
    }

    private fun node(node: JsonElement, dictionary: String, out: StringBuilder) {
        when (node) {
            is JsonPrimitive -> out.append(escape(node.content))
            is JsonArray -> node.forEach { node(it, dictionary, out) }
            is JsonObject -> {
                val tag = node.text("tag")
                if (tag !in TAGS) {
                    // Unknown elements keep their text and lose their markup.
                    node["content"]?.let { node(it, dictionary, out) }
                    return
                }
                when (tag) {
                    "br" -> out.append("<br>")
                    "img" -> image(node, dictionary, out)
                    else -> {
                        out.append('<').append(tag).append(" class=\"gloss-sc-").append(tag).append('"')
                        attributes(node, out)
                        if (tag == "a") {
                            // Links inside dictionaries point at other entries; they are not followed here.
                            out.append(" data-href=\"").append(escape(node.text("href"))).append('"')
                        }
                        out.append('>')
                        node["content"]?.let { node(it, dictionary, out) }
                        out.append("</").append(tag).append('>')
                    }
                }
            }
        }
    }

    private fun attributes(node: JsonObject, out: StringBuilder) {
        (node["data"] as? JsonObject)?.forEach { (key, value) ->
            val name = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (name.isNotEmpty() && value is JsonPrimitive) out.append(" data-sc-").append(kebab(name)).append("=\"").append(escape(value.content)).append('"')
        }
        (node["style"] as? JsonObject)?.let { style ->
            val css = style.entries.filter { it.key in STYLES }.mapNotNull { (key, value) ->
                val text = when (value) {
                    is JsonPrimitive -> if (value.isString) value.content else value.content + if (key in UNITLESS) "" else "em"
                    is JsonArray -> value.filterIsInstance<JsonPrimitive>().joinToString(" ") { it.content }
                    else -> return@mapNotNull null
                }
                // A value may not close the declaration or open something else.
                if (text.any { it in ";{}<>\"" }) null else "${kebab(key)}:$text"
            }
            if (css.isNotEmpty()) out.append(" style=\"").append(css.joinToString(";")).append('"')
        }
        for (name in listOf("lang", "title")) node.text(name).takeIf { it.isNotEmpty() }?.let { out.append(' ').append(name).append("=\"").append(escape(it)).append('"') }
        for ((key, attr) in listOf("colSpan" to "colspan", "rowSpan" to "rowspan")) {
            node.text(key).toIntOrNull()?.let { out.append(' ').append(attr).append("=\"").append(it).append('"') }
        }
        if (node.text("tag") == "details" && node.text("open") == "true") out.append(" open")
    }

    private fun image(node: JsonObject, dictionary: String, out: StringBuilder) {
        val path = node.text("path")
        if (path.isEmpty()) return
        val units = if (node.text("sizeUnits") == "em") "em" else "px"
        out.append("<img class=\"gloss-image\" src=\"").append(mediaUrl(dictionary, path)).append('"')
        val size = listOfNotNull(
            node.text("width").toDoubleOrNull()?.let { "width:$it$units" },
            node.text("height").toDoubleOrNull()?.let { "height:$it$units" },
            node.text("verticalAlign").takeIf { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '-' } }?.let { "vertical-align:$it" },
        )
        if (size.isNotEmpty()) out.append(" style=\"").append(size.joinToString(";")).append('"')
        val alt = node.text("alt").ifEmpty { node.text("title") }
        out.append(" alt=\"").append(escape(alt)).append("\">")
    }

    /**
     * The headword with its reading as furigana. Kana the word and its reading
     * share at either end stay bare, so 新しい / あたらしい reads 新[あたら]しい;
     * a word whose kana do not line up carries the whole reading.
     */
    fun furigana(expression: String, reading: String): String {
        if (reading.isEmpty() || same(expression, reading)) return escape(expression)
        var head = 0
        while (head < expression.length && head < reading.length && isKana(expression[head]) && hiragana(expression[head]) == hiragana(reading[head])) head++
        var tail = 0
        while (tail < expression.length - head && tail < reading.length - head &&
            isKana(expression[expression.length - 1 - tail]) &&
            hiragana(expression[expression.length - 1 - tail]) == hiragana(reading[reading.length - 1 - tail])
        ) tail++
        val word = expression.substring(head, expression.length - tail)
        val over = reading.substring(head, reading.length - tail)
        if (word.isEmpty() || over.isEmpty()) return "<ruby>" + escape(expression) + "<rt>" + escape(reading) + "</rt></ruby>"
        return escape(expression.substring(0, head)) + "<ruby>" + escape(word) + "<rt>" + escape(over) + "</rt></ruby>" +
            escape(expression.substring(expression.length - tail))
    }

    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヶ' || c == 'ー'
    private fun hiragana(c: Char) = if (c in 'ァ'..'ヶ') c - 0x60 else c
    private fun same(a: String, b: String) = a.length == b.length && a.indices.all { hiragana(a[it]) == hiragana(b[it]) }

    /**
     * An accent as a graph, after Yomitan's pitch-accent graph: a dot for each
     * mora, high or low, joined by a line, and a hollow dot for the particle
     * after the word.
     */
    fun graph(accent: Pronunciation.Accent): String = buildString {
        val step = 14
        val points = accent.high.mapIndexed { i, high -> (6 + step * i) to (if (high) 5 else 19) }
        val width = 6 + step * (points.size - 1) + 6
        append("<svg class=\"lfg-graph\" data-pitch=\"").append(accent.kind.name.lowercase()).append("\" role=\"img\" aria-label=\"")
        append(accent.kind.label).append(' ').append(accent.position).append("\" width=\"").append(width).append("\" height=\"24\" viewBox=\"0 0 ")
        append(width).append(" 24\"><polyline points=\"").append(points.joinToString(" ") { "${it.first},${it.second}" }).append("\"/>")
        points.forEachIndexed { i, (x, y) ->
            append("<circle cx=\"").append(x).append("\" cy=\"").append(y).append("\" r=\"3.5\"")
            if (i == points.lastIndex) append(" class=\"lfg-particle\"")
            append("/>")
        }
        append("</svg>")
    }

    fun mediaUrl(dictionary: String, path: String): String =
        "$MEDIA_SCHEME://media/" + URLEncoder.encode(dictionary, "UTF-8").replace("+", "%20") + "/" +
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * A dictionary's stylesheet, each rule limited to that dictionary's block.
     * Rules inside `@media` and the like are scoped too; other at-rules
     * (`@font-face`, `@import`) are dropped.
     */
    fun scope(css: String, dictionary: String): String {
        val prefix = "[data-dictionary=\"" + dictionary.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"
        val text = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace("</", "<\\/")
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) break
            val selector = text.substring(i, open).trim()
            val close = matching(text, open)
            if (close < 0) break
            val block = text.substring(open + 1, close)
            when {
                selector.startsWith("@media") || selector.startsWith("@supports") ->
                    out.append(selector).append('{').append(scope(block, dictionary)).append('}')
                selector.startsWith("@") -> Unit
                selector.isNotEmpty() -> out.append(selector.split(',').joinToString(",") { "$prefix ${it.trim()}" }).append('{').append(block).append('}')
            }
            i = close + 1
        }
        return out.toString()
    }

    private fun matching(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++ else if (text[i] == '}' && --depth == 0) return i
        }
        return -1
    }

    private fun kebab(name: String) = buildString {
        for (c in name) if (c.isUpperCase()) append('-').append(c.lowercaseChar()) else append(c)
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content ?: ""

    private val UNITLESS = setOf("fontWeight")

    private const val SPEAKER = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9v6h4l5 4V5L8 9H4zm12.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z"/></svg>"""

    // Stroke icons for the card button's four states; the page's CSS shows the one for its `data-state`.
    private const val CARD_ICONS = """<svg class="lfg-i-add" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>""" +
        """<svg class="lfg-i-adding" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4a8 8 0 1 0 8 8"/></svg>""" +
        """<svg class="lfg-i-in-anki" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>""" +
        """<svg class="lfg-i-waiting" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/></svg>"""

    /**
     * The popup's one script. `lfgState(states, current, scroll)` sets each
     * card button's state and name, marks the entry the controller is on, and
     * scrolls to it when asked. Dictionary content never carries script:
     * [glossary] keeps only [TAGS] and data, style, lang and title attributes.
     */
    private const val STATE_SCRIPT = "var LFG_NAMES={'add':'Add card','adding':'Adding…','in-anki':'Open in Anki','waiting':'Waiting for AnkiDroid'};" +
        "function lfgState(s,c,go){for(var i=0;i<s.length;i++){var b=document.getElementById('c'+i);" +
        "if(b){b.dataset.state=s[i];b.setAttribute('aria-label',LFG_NAMES[s[i]]);b.title=LFG_NAMES[s[i]];}" +
        "var e=document.getElementById('e'+i);if(e){if(i==c)e.setAttribute('data-current','');else e.removeAttribute('data-current');}}" +
        "if(go){var t=document.getElementById('e'+c);if(t)t.scrollIntoView();}}"

    /** The app's own look for an entry: Hachidori's default theme. */
    const val BASE_CSS = """
html{background:#22232c;color:#e8e5eb;font:16px/1.5 system-ui,sans-serif;-webkit-text-size-adjust:100%}
body{margin:0;padding:12px 16px 16px;overflow-wrap:anywhere}
:lang(ja){font-family:"Noto Sans CJK JP","Noto Sans JP",sans-serif}
.lfg-head{display:flex;align-items:flex-end;column-gap:.75em}
.lfg-term{font-size:1.75em;font-weight:650;line-height:1.2}
.lfg-term rt{font-size:.45em;font-weight:400;color:#aaa8b2}
.lfg-graph{flex:none;margin-bottom:.3em;color:#59b2ff}
.lfg-graph[data-pitch="atamadaka"]{color:#ff8686}
.lfg-graph[data-pitch="nakadaka"]{color:#ff9b54}
.lfg-graph[data-pitch="odaka"]{color:#67e47d}
.lfg-graph[data-pitch="kifuku"]{color:#bc99f6}
.lfg-graph polyline{fill:none;stroke:currentColor;stroke-width:2}
.lfg-graph circle{fill:currentColor}
.lfg-graph circle.lfg-particle{fill:#22232c;stroke:currentColor;stroke-width:2}
.lfg-actions{display:flex;gap:4px;margin-left:auto;align-self:center}
.lfg-button{display:inline-flex;width:44px;height:44px;border-radius:22px;align-items:center;justify-content:center;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
.lfg-audio{background:#2f303b;color:#e8e5eb}
.lfg-audio svg{width:20px;height:20px;fill:currentColor}
.lfg-card{border:1.5px solid #b1a5ee;background:#353247;color:#b1a5ee}
.lfg-card svg{display:none;width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round}
.lfg-card[data-state="add"] .lfg-i-add,.lfg-card[data-state="adding"] .lfg-i-adding,.lfg-card[data-state="in-anki"] .lfg-i-in-anki,.lfg-card[data-state="waiting"] .lfg-i-waiting{display:block}
.lfg-card[data-state="adding"]{border-style:dashed}
.lfg-card[data-state="in-anki"]{border-color:#8bb79b;background:#2b3530;color:#8bb79b}
.lfg-card[data-state="waiting"]{border-color:#454653;background:#2b2b36;color:#aaa8b2}
.lfg-entry+.lfg-entry{border-top:2px solid #747586;margin-top:14px;padding-top:12px}
.lfg-entry[data-current] .lfg-term{color:#b1a5ee}
.lfg-meta{display:flex;flex-wrap:wrap;gap:4px;margin:.35em 0 .15em}
.lfg-pitch-tag,.lfg-freq{display:inline-flex;font-size:.72em;line-height:1.5;border-radius:4px;overflow:hidden;white-space:nowrap}
.lfg-pitch-tag{padding:0 .45em;color:#22232c;background:#59b2ff}
.lfg-pitch-tag b{margin-left:.3em}
.lfg-pitch-tag[data-pitch="atamadaka"]{background:#ff8686}
.lfg-pitch-tag[data-pitch="nakadaka"]{background:#ff9b54}
.lfg-pitch-tag[data-pitch="odaka"]{background:#67e47d}
.lfg-pitch-tag[data-pitch="kifuku"]{background:#bc99f6}
.lfg-freq-name{padding:0 .45em;background:#347d35;color:#fff}
.lfg-freq-value{padding:0 .45em;background:#2f303b;color:#e8e5eb}
.lfg-path{color:#aaa8b2;font-size:.8em}
.lfg-senses{margin:.4em 0 0;padding-left:1.4em}
.lfg-senses>li{margin:.15em 0}
.lfg-tags{margin-right:.4em}
.lfg-tag{display:inline-block;font-size:.7em;color:#aaa8b2;border:1px solid #454653;border-radius:3px;padding:0 .3em;margin-right:.25em;vertical-align:middle}
.lfg-dict{border-top:1px solid #454653;margin-top:.5em;padding-top:.5em}
.lfg-source{display:inline-block;font-size:.72em;font-weight:600;line-height:1.5;color:#b1a5ee;background:#353247;border-radius:4px;padding:0 .45em}
.lfg-message{color:#aaa8b2}
.gloss-text,.gloss-list>li{white-space:pre-line}
.gloss-list,.gloss-sc-ul,.gloss-sc-ol{margin:0;padding-left:1.3em}
ul[data-sc-content="glossary"]{display:inline;padding:0}
ul[data-sc-content="glossary"]>li{display:inline}
ul[data-sc-content="glossary"]>li:not(:last-child)::after{content:"; "}
.gloss-sc-table{border-collapse:collapse;margin:.3em 0}
.gloss-sc-td,.gloss-sc-th{border:1px solid #454653;padding:.15em .45em}
.gloss-sc-a{color:#b1a5ee;text-decoration:underline}
.gloss-image{max-width:100%;height:auto}
"""
}
