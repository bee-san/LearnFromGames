// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Rubi's dictionaries through "Add dictionary…": a picked .vjdict file, or a zip
 * holding one, becomes a Yomitan zip that hoshidicts imports like any other.
 * Nothing here is Android's, so it runs in the JVM tests too.
 */
object VjdictImport {
    /** A .vjdict found in what was picked. [temporary] files are copies to delete afterwards. */
    class Found(val file: File, val name: String, val attribution: String?, val temporary: Boolean)

    class Converted(val title: String, val entries: Int, val terms: Int, val frequencies: Int, val aliases: Int, val skipped: Int)

    /**
     * The .vjdict in [picked]: the file itself, or a `*.vjdict` entry of a zip that
     * has no index.json, streamed out into [cacheDir]. Of several (Rubi's release
     * zip also holds its names dictionary), [preferred] wins. Its attribution is
     * the `<name>-attribution.txt` beside it, as Rubi ships it, or an
     * ATTRIBUTION.md. Null for anything else, which is then imported as a
     * Yomitan zip, as before.
     */
    fun find(picked: File, pickedName: String, cacheDir: File, preferred: String = RubiDictionarySource.ENTRY): Found? {
        val head = ByteArray(8)
        val n = picked.inputStream().use { it.readNBytes(head, 0, 8) }
        if (n == 8 && Vjdict.magicVersion(head) != 0) return Found(picked, pickedName, null, temporary = false)
        if (n < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return null
        val zip = try {
            ZipFile(picked)
        } catch (_: ZipException) {
            return null
        }
        zip.use {
            if (zip.getEntry("index.json") != null) return null
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val vjdict = entries.filter { it.name.endsWith(".vjdict", ignoreCase = true) }
                .sortedByDescending { it.name.substringAfterLast('/').equals(preferred, ignoreCase = true) }
                .firstOrNull { entry ->
                    val magic = ByteArray(8)
                    zip.getInputStream(entry).use { it.readNBytes(magic, 0, 8) } == 8 && Vjdict.magicVersion(magic) != 0
                } ?: return null
            val own = vjdict.name.substringAfterLast('/').substringBeforeLast('.') + "-attribution.txt"
            val attribution = (listOf(own) + ATTRIBUTION_FILES).firstNotNullOfOrNull { wanted ->
                entries.firstOrNull { it.name.substringAfterLast('/').equals(wanted, ignoreCase = true) }
            }?.let { entry ->
                zip.getInputStream(entry).use { String(it.readNBytes(MAX_ATTRIBUTION), Charsets.UTF_8) }.trim().takeIf { it.isNotEmpty() }
            }
            val out = File(cacheDir, "vjdict-${System.nanoTime()}.vjdict")
            try {
                zip.getInputStream(vjdict).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            } catch (e: Exception) {
                out.delete()
                throw e
            }
            return Found(out, vjdict.name.substringAfterLast('/'), attribution, temporary = true)
        }
    }

    /**
     * Writes [vjdict] to [zip] as a Yomitan format-3 dictionary: a term per entry, in
     * Rubi's order (see [Vjdict.Index]), and in version 3 a frequency per ranked
     * entry. [name] is the file's, the title when the file has none. [progress]
     * hears 0..100. Throws [VjdictException] for a file it cannot read.
     */
    fun toYomitan(vjdict: File, name: String, attribution: String?, zip: File, progress: (Int) -> Unit = {}): Converted {
        val revision = "vjdict-" + sha256(vjdict).take(12)
        Vjdict(vjdict).use { dict ->
            // A title is the dictionary's folder name to hoshidicts, which refuses slashes.
            val title = plain(dict.title.ifEmpty { name.substringBeforeLast('.').trim().ifEmpty { "Rubi dictionary" } }).replace('/', '／')
            val index = dict.index { done, total -> progress((done * INDEX_SHARE / total.coerceAtLeast(1)).toInt()) }
            var terms = 0
            var frequencies = 0
            var aliases = 0
            var skipped = 0
            BufferedOutputStream(zip.outputStream(), 1 shl 16).use { stream ->
                val out = ZipWriter(stream)
                out.add("index.json", indexJson(dict, title, revision, attribution))
                val bank = Json()
                val meta = Json()
                var banks = 0
                var metaBanks = 0
                var bankTerms = 0
                var bankMeta = 0
                fun flushBank() {
                    if (bankTerms == 0) return
                    bank.raw(']')
                    out.add("term_bank_${++banks}.json", bank.bytes, bank.size)
                    bank.clear()
                    bankTerms = 0
                }
                fun flushMeta() {
                    if (bankMeta == 0) return
                    meta.raw(']')
                    out.add("term_meta_bank_${++metaBanks}.json", meta.bytes, meta.size)
                    meta.clear()
                    bankMeta = 0
                }
                fun term(expression: String, reading: String, rules: String, score: Int, definition: ByteArray, sequence: Int) {
                    bank.raw(if (bankTerms++ == 0) '[' else ',')
                    bank.raw('[').string(expression).raw(',').string(reading).raw(",\"\",").string(rules).raw(',').number(score)
                    bank.raw(",[").utf8(definition).raw("],").number(sequence).raw(",\"\"]")
                    terms++
                    if (bankTerms >= BANK_TERMS || bank.size >= BANK_BYTES) flushBank()
                }
                // Entries that share a spelling and reading share a rank: it is listed once.
                val listed = HashSet64(index.entries.size + index.aliases.size)
                fun frequency(expression: String, reading: String, rank: Int, borrowed: Boolean) {
                    if (!listed.add(hash("$expression\u0000$reading\u0000$rank\u0000$borrowed"))) return
                    meta.raw(if (bankMeta++ == 0) '[' else ',')
                    meta.raw('[').string(expression).raw(",\"freq\",{\"reading\":").string(reading).raw(",\"frequency\":")
                    // Rubi ranks an entry's own rank above any rank lent from its kana form, so lent ones sort after.
                    if (borrowed) meta.raw("{\"value\":").number(rank + BORROWED_AFTER).raw(",\"displayValue\":").string("$rank㋕").raw('}')
                    else meta.number(rank)
                    meta.raw("}]")
                    frequencies++
                    if (bankMeta >= BANK_TERMS) flushMeta()
                }
                val entries = index.entries
                for (i in entries.indices) {
                    val e = dict.entry(entries[i])
                    val spelling = e.spelling.ifEmpty { e.reading }
                    if (spelling.isEmpty()) {
                        skipped++
                        continue
                    }
                    val reading = e.reading.ifEmpty { spelling }
                    val rules = ruleNames(e.rules)
                    // Earlier in Rubi's order is earlier in a lookup, after frequency.
                    val score = -i
                    term(spelling, reading, rules, score, e.definition, i + 1)
                    if (e.rank > 0) frequency(spelling, reading, e.rank, e.borrowed)
                    for (alias in index.aliases[entries[i] ushr 24].orEmpty()) {
                        term(alias, alias, rules, score, e.definition, i + 1)
                        if (e.rank > 0) frequency(alias, alias, e.rank, e.borrowed)
                        aliases++
                    }
                    if (i % 4096 == 0) progress(INDEX_SHARE + ((100 - INDEX_SHARE) * i.toLong() / entries.size).toInt())
                }
                flushBank()
                flushMeta()
                out.finish()
            }
            progress(100)
            return Converted(title, index.entries.size, terms, frequencies, aliases, skipped)
        }
    }

    /** Rubi's rule bits as the rule names hoshidicts' deinflector reads. iru (bit 6) has no match there and is left out. */
    fun ruleNames(bits: Int): String = HOSHIDICTS_RULES.indices.filter { bits and (1 shl it) != 0 }.joinToString(" ") { HOSHIDICTS_RULES[it] }

    private fun indexJson(dict: Vjdict, title: String, revision: String, attribution: String?): ByteArray {
        val description = buildString {
            append("Converted on this device from a Rubi .vjdict file (format ${dict.version}).")
            if (dict.frequencyList.isNotEmpty()) append(" Frequency ranks: ${plain(dict.frequencyList)}.")
        }
        val json = Json().raw("{\"title\":").string(title).raw(",\"format\":3,\"revision\":").string(revision)
            .raw(",\"description\":").string(description)
            .raw(",\"attribution\":").string(attribution?.let(::reflow) ?: DEFAULT_ATTRIBUTION)
        if (dict.version == 3) json.raw(",\"frequencyMode\":\"rank-based\"")
        json.raw('}')
        return json.bytes.copyOf(json.size)
    }

    /** The title and description are written with nothing to escape: the title is also a folder's name. */
    private fun plain(text: String): String = text.replace('"', '\'').replace('\\', '/').replace('\t', ' ')

    /**
     * Text wrapped at a fixed width, as Rubi's attribution is, as paragraphs: each
     * paragraph's lines joined by spaces, a list item ("- ") on a line of its own,
     * a blank line between paragraphs, and no Markdown heading marks.
     */
    fun reflow(text: String): String = text.replace("\r\n", "\n").trim().split(Regex("""\n[ \t]*\n""")).mapNotNull { paragraph ->
        val out = StringBuilder()
        for (line in paragraph.lines()) {
            val t = line.trim().trimStart('#', '>').trim()
            if (t.isEmpty()) continue
            if (out.isNotEmpty()) out.append(if (t.startsWith("- ") || t.startsWith("* ")) '\n' else ' ')
            out.append(t)
        }
        out.takeIf { it.isNotEmpty() }?.toString()
    }.joinToString("\n\n")

    /** The file's SHA-256 in lower-case hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** FNV-1a over the text's UTF-16 units, then murmur3's finaliser. */
    private fun hash(text: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in text) h = (h xor c.code.toLong()) * 0x100000001b3L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        return h xor (h ushr 33)
    }

    /** A set of 64-bit hashes, open addressing, at most half full. */
    private class HashSet64(expected: Int) {
        private val slots = LongArray(Integer.highestOneBit(maxOf(expected, 8) * 2) * 2)

        /** False when [hash] was already in. */
        fun add(hash: Long): Boolean {
            val h = if (hash == 0L) 1L else hash // 0 marks an empty slot
            var i = (h xor (h ushr 32)).toInt() and (slots.size - 1)
            while (true) {
                if (slots[i] == 0L) {
                    slots[i] = h
                    return true
                }
                if (slots[i] == h) return false
                i = (i + 1) and (slots.size - 1)
            }
        }
    }

    /** JSON text built straight into UTF-8 bytes; a bank of 10,000 terms is the most it holds. */
    private class Json {
        var bytes = ByteArray(1 shl 16)
        var size = 0

        fun clear() {
            size = 0
        }

        private fun room(n: Int) {
            if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
        }

        fun raw(c: Char): Json {
            room(1)
            bytes[size++] = c.code.toByte()
            return this
        }

        fun raw(s: String): Json {
            for (c in s) raw(c)
            return this
        }

        fun number(n: Int): Json = raw(n.toString())

        fun string(s: String): Json = utf8(s.toByteArray(Charsets.UTF_8))

        /** A JSON string of UTF-8 bytes: quotes, backslashes and control characters escaped, the rest as is. */
        fun utf8(text: ByteArray): Json {
            room(text.size + 2)
            bytes[size++] = '"'.code.toByte()
            for (b in text) {
                val c = b.toInt() and 0xFF
                when {
                    c == '"'.code || c == '\\'.code -> {
                        room(2)
                        bytes[size++] = '\\'.code.toByte()
                        bytes[size++] = b
                    }
                    c < 0x20 -> {
                        val escaped = when (c) {
                            '\n'.code -> "\\n"
                            '\r'.code -> "\\r"
                            '\t'.code -> "\\t"
                            else -> "\\u%04x".format(c)
                        }
                        room(escaped.length)
                        for (e in escaped) bytes[size++] = e.code.toByte()
                    }
                    else -> {
                        room(1)
                        bytes[size++] = b
                    }
                }
            }
            room(1)
            bytes[size++] = '"'.code.toByte()
            return this
        }
    }

    /**
     * A zip written entry by entry, each whole in memory first. hoshidicts wants an
     * entry's sizes in its local header, which ZipOutputStream leaves for a trailing
     * descriptor when it deflates as it goes.
     */
    private class ZipWriter(private val out: OutputStream) {
        private class Written(val name: ByteArray, val method: Int, val crc: Long, val compressed: Long, val size: Long, val offset: Long)

        private val written = ArrayList<Written>()
        private var offset = 0L
        private val deflater = Deflater(Deflater.BEST_SPEED, true)
        private var buffer = ByteArray(1 shl 16)

        fun add(name: String, data: ByteArray, length: Int = data.size) {
            val crc = CRC32().apply { update(data, 0, length) }.value
            deflater.reset()
            deflater.setInput(data, 0, length)
            deflater.finish()
            var compressed = 0
            while (!deflater.finished()) {
                if (compressed == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                compressed += deflater.deflate(buffer, compressed, buffer.size - compressed)
            }
            val stored = compressed >= length
            val entry = Written(
                name.toByteArray(Charsets.UTF_8), if (stored) 0 else 8, crc,
                if (stored) length.toLong() else compressed.toLong(), length.toLong(), offset,
            )
            if (offset + 30 + entry.name.size + entry.compressed > 0xFFFF_FFFFL) throw VjdictException("The converted dictionary is too large (over 4 GB).")
            header(LOCAL, entry, central = false)
            if (stored) write(data, length) else write(buffer, compressed)
            written += entry
        }

        fun finish() {
            val start = offset
            for (entry in written) header(CENTRAL, entry, central = true)
            val size = offset - start
            if (written.size > 0xFFFF || offset > 0xFFFF_FFFFL) throw VjdictException("The converted dictionary is too large (over 4 GB).")
            u32(END); u16(0); u16(0); u16(written.size); u16(written.size); u32(size); u32(start); u16(0)
            out.flush()
            deflater.end()
        }

        private fun header(signature: Long, e: Written, central: Boolean) {
            u32(signature)
            if (central) u16(20)
            u16(20) // version needed: deflate
            u16(0x0800) // names are UTF-8
            u16(e.method)
            u16(0); u16(0x21) // 1980-01-01 00:00: fixed, so a conversion is the same each time
            u32(e.crc); u32(e.compressed); u32(e.size)
            u16(e.name.size); u16(0)
            if (central) {
                u16(0); u16(0); u16(0); u32(0); u32(e.offset)
            }
            write(e.name, e.name.size)
        }

        private fun write(bytes: ByteArray, n: Int) {
            out.write(bytes, 0, n)
            offset += n
        }

        private fun u16(v: Int) {
            out.write(v and 0xFF); out.write(v ushr 8 and 0xFF)
            offset += 2
        }

        private fun u32(v: Long) {
            u16((v and 0xFFFF).toInt()); u16((v ushr 16 and 0xFFFF).toInt())
        }

        companion object {
            const val LOCAL = 0x04034b50L
            const val CENTRAL = 0x02014b50L
            const val END = 0x06054b50L
        }
    }

    private const val BANK_TERMS = 10_000
    private const val BANK_BYTES = 16 shl 20
    private const val INDEX_SHARE = 20
    private const val MAX_ATTRIBUTION = 64 * 1024
    private const val BORROWED_AFTER = 1 shl 24
    private val HOSHIDICTS_RULES = Vjdict.RULE_NAMES.take(6)
    private val ATTRIBUTION_FILES = listOf("ATTRIBUTION.md")
    private const val DEFAULT_ATTRIBUTION =
        "This dictionary came from a Rubi .vjdict file. Its sources' attribution travels with that file."
}
