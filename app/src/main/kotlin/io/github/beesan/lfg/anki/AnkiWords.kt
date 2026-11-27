// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import java.security.MessageDigest

/**
 * How a word is found in every note of Anki, of any note type, for Known
 * words: what is said to AnkiDroid, and how what comes back is read. No
 * Android in it, so tests can run it.
 *
 * Two ways, the first cheap. A note's first field is kept with a checksum
 * that Anki indexes (`notes.csum`, the first 4 bytes of the SHA-1 of the field
 * without its HTML), and AnkiDroid's `notes_v2` takes a query on it straight
 * to the database: every word of a screen is one query. A word not found so
 * is then looked for in every field of every note with one search for all of
 * them, `"*:re:…"`, which Anki runs as `regexp_fields(?, n.flds)` over each
 * note: slower, as it reads them all, so only for what the first missed.
 */
object AnkiWords {
    private const val FIELDS = '\u001f'

    /** Anki's `field_checksum` of a field with no HTML: the SHA-1's first 4 bytes as a number. */
    fun checksum(text: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return (0 until 4).fold(0L) { sum, i -> (sum shl 8) or (digest[i].toLong() and 0xFF) }
    }

    /** The SQL `notes_v2` is asked: notes whose first field may be one of [words]; [firstField] then says which. */
    fun byChecksum(words: Collection<String>): String = "csum in (${words.map(::checksum).distinct().joinToString(",")})"

    private val style = Regex("(?is)<style.*?>.*?</style>|<script.*?>.*?</script>")
    private val tag = Regex("<[^>]*>")
    private val furigana = Regex("\\[[^\\]]*\\]")
    private val spaces = Regex("\\s+")
    private val entities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'")
    private val entity = Regex("&(?:nbsp|amp|lt|gt|quot|#39);")

    /**
     * A field as the word it holds: without HTML, without readings in brackets
     * (日本[にほん] is 日本, 見[み]る is 見る) and without spaces round it.
     */
    fun plain(field: String): String {
        val text = field.replace(style, "").replace(tag, "").replace(entity) { entities.getValue(it.value) }
        return text.replace(furigana, "").replace(spaces, " ").trim()
    }

    /** The first field of a note, from its fields as AnkiDroid gives them. */
    fun firstField(fields: String): String = plain(fields.substringBefore(FIELDS))

    /** Each field of a note, as [plain] makes it. */
    fun fields(fields: String): List<String> = fields.split(FIELDS).map(::plain)

    /** Characters Rust's regex gives a meaning to, as its `regex::escape` escapes them. */
    private const val META = "\\.+*?()|[]{}^$#&-~"

    /** Room round a field's word: spaces, HTML tags and non-breaking spaces. */
    private const val AROUND = "(?:\\s|&nbsp;|<[^>]*>)*"

    /** A reading in brackets, which may follow any character of a word written with furigana. */
    private const val READING = "(?:\\[[^\\]]*\\])?"

    /**
     * The search, in Anki's own syntax, for the notes that have a field holding
     * just one of [words]: `*` is every field of every note type, `re:` a
     * regular expression over the field as it is kept, HTML and all. Anki adds
     * `(?i)`, so capitals do not matter. The quotes keep its spaces in one term;
     * inside them only `"` needs a backslash, which Anki takes off again.
     */
    fun anyField(words: Collection<String>): String {
        val alternatives = words.joinToString("|") { word ->
            buildString {
                for (c in word) {
                    if (c in META) append('\\')
                    append(c)
                    append(READING)
                }
            }
        }
        val re = "^$AROUND(?:$alternatives)$AROUND$"
        return "\"*:re:${re.replace("\"", "\\\"")}\""
    }

    /** The search for those of [noteIds] with a card of at least [days] days' interval: the mature ones. */
    fun mature(noteIds: Collection<Long>, days: Int): String = "nid:${noteIds.joinToString(",")} prop:ivl>=$days"
}
