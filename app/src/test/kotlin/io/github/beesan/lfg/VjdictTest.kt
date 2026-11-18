// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.dict.DictJson
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.dict.GlossaryText
import io.github.beesan.lfg.dict.RubiDictionarySource
import io.github.beesan.lfg.dict.Vjdict
import io.github.beesan.lfg.dict.VjdictException
import io.github.beesan.lfg.dict.VjdictImport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rubi's .vjdict files, made here by a small writer that lays them out as
 * Rubi's tools/convert_dictionary.py does, then turned into Yomitan zips.
 */
class VjdictTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** One row of a dictionary, as convert_dictionary.py takes it. [keys] adds lookup keys beyond spelling and reading. */
    class Row(
        val spelling: String,
        val reading: String,
        val definition: String,
        val rules: Int = 0,
        val rank: Int = 0,
        val borrowed: Boolean = false,
        val keys: List<String> = emptyList(),
        val score: Int = 0,
    )

    /** Writes [rows] as a version [version] .vjdict, with [buckets] hash buckets (versions 2 and 3). */
    private fun vjdict(version: Int, rows: List<Row>, title: String = "", frequencyList: String = "Jiten global", buckets: Int = 1): ByteArray {
        class Lookup(val key: ByteArray, val score: Int, val seq: Int, val entry: Int, val keyAt: Int, val rules: Int, val size: Int)
        val data = ByteArrayOutputStream()
        val lookups = ArrayList<Lookup>()
        rows.forEachIndexed { seq, row ->
            val payload = ByteArrayOutputStream()
            if (version == 3) payload.le(row.rank.toLong() or (if (row.borrowed) 1L shl 24 else 0L), 4)
            payload.write((row.spelling + "\u0000" + row.reading + "\u0000" + row.definition + "\u0000").toByteArray())
            val entry = data.size()
            data.write(payload.toByteArray())
            val keys = (listOf(row.spelling, row.reading).filter { it.isNotEmpty() }.map { Vjdict.normalize(it) } + row.keys).distinct()
            for (key in keys) {
                val bytes = key.toByteArray()
                val keyAt = data.size()
                if (version == 1) data.write(bytes)
                lookups += Lookup(bytes, row.score, seq, entry, keyAt, row.rules, payload.size())
            }
        }
        val ordered = lookups.sortedWith(compareBy<Lookup, ByteArray>(Comparator { a, b -> java.util.Arrays.compareUnsigned(a, b) }) { it.key }
            .thenBy { -it.score }.thenBy { it.seq })
        val out = ByteArrayOutputStream()
        if (version == 1) {
            val dataAt = 64L + 24L * ordered.size
            val size = dataAt + data.size()
            out.header(1, ordered.size, 64, dataAt, size, 0, 0)
            for (l in ordered) {
                out.le(dataAt + l.keyAt, 8); out.le(dataAt + l.entry, 8); out.le(l.key.size.toLong(), 2); out.le(l.rules.toLong(), 2); out.le(l.size.toLong(), 4)
            }
            out.write(data.toByteArray())
            return out.toByteArray()
        }
        val groups = ordered.groupBy { String(it.key) }
        val byBucket = groups.keys.groupBy { hash(it.toByteArray()) and (buckets - 1).toLong() }
        val pageCounts = (0 until buckets).map { maxOf(1, ((byBucket[it.toLong()]?.size ?: 0) + 169) / 170) }
        val pages = pageCounts.sum()
        val dataAt = 4096L * (1 + pages)
        // The key groups follow the entries.
        var at = dataAt + data.size()
        val groupAt = HashMap<String, Long>()
        val tail = ByteArrayOutputStream()
        for ((key, postings) in groups.entries.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.key.toByteArray(), b.key.toByteArray()) }) {
            groupAt[key] = at
            tail.write(key.toByteArray())
            for (p in postings) {
                tail.le(dataAt + p.entry, 8); tail.le(p.size.toLong(), 4); tail.le(p.rules.toLong(), 2); tail.le(0, 2)
            }
            at = dataAt + data.size() + tail.size()
        }
        val size = at
        val file = ByteArray(dataAt.toInt())
        val head = ByteArrayOutputStream().apply { header(version, lookups.size, 4096, dataAt, size, buckets.toLong(), pages.toLong()) }.toByteArray()
        head.copyInto(file)
        if (version == 3) frequencyList.toByteArray().copyInto(file, 64)
        title.toByteArray().copyInto(file, 192)
        var overflow = buckets
        for (bucket in 0 until buckets) {
            val keys = byBucket[bucket.toLong()].orEmpty().sortedWith { a, b -> java.lang.Long.compareUnsigned(hash(a.toByteArray()), hash(b.toByteArray())) }
            var page = bucket
            for ((i, chunk) in keys.chunked(170).ifEmpty { listOf(emptyList()) }.withIndex()) {
                val next = if (i + 1 == pageCounts[bucket]) 0L else 4096L * (1 + overflow)
                val p = ByteArrayOutputStream()
                p.write("VJHP".toByteArray()); p.le(chunk.size.toLong(), 4); p.le(next, 8)
                for (key in chunk) {
                    val postings = groups.getValue(key)
                    p.le(hash(key.toByteArray()), 8); p.le(groupAt.getValue(key), 8); p.le(postings.size.toLong(), 4)
                    p.le(key.toByteArray().size.toLong(), 2); p.le(postings.fold(0) { u, l -> u or l.rules }.toLong(), 2)
                }
                p.toByteArray().copyInto(file, 4096 * (1 + page))
                if (next != 0L) page = overflow++
            }
        }
        return file + data.toByteArray() + tail.toByteArray()
    }

    private fun ByteArrayOutputStream.le(v: Long, n: Int) {
        for (i in 0 until n) write((v ushr (8 * i)).toInt() and 0xFF)
    }

    private fun ByteArrayOutputStream.header(version: Int, count: Int, index: Long, data: Long, size: Long, buckets: Long, pages: Long) {
        write("VJDICT$version\u0000".toByteArray())
        le(version.toLong(), 4); le(64, 4); le(count.toLong(), 4); le(24, 4)
        le(index, 8); le(data, 8); le(size, 8); le(buckets, 8); le(pages, 8)
    }

    /** Rubi's key hash: FNV-1a-64, then murmur3's finaliser. */
    private fun hash(key: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        for (b in key) h = (h xor (b.toLong() and 0xFF)) * 1099511628211L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L // 0xff51afd7ed558ccd
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        return h xor (h ushr 33)
    }

    private val rows = listOf(
        Row("日本語", "にほんご", "The language of Japan.\n日=day, sun\n本=book, origin\n語=word, language", rank = 4372),
        Row("食べる", "たべる", "To eat.", rules = 1, rank = 300),
        Row("上", "うえ", "Above.", rank = 90),
        Row("上", "じょう", "Upper; first.", rank = 2000),
        Row("テレビ", "", "Television.", rank = 900),
        Row("上手い", "うまい", "Skilful.", rules = 32, rank = 1500, borrowed = true),
        Row("てしまう", "", "To do completely.", rules = 2 or 64, keys = listOf("でしまう")),
        Row("", "ほげ", "A reading alone."),
    )

    private class Out(val index: JsonObject, val terms: List<JsonArray>, val meta: List<JsonArray>, val names: List<String>)

    private fun convert(bytes: ByteArray, name: String = "test.vjdict", attribution: String? = null): Pair<VjdictImport.Converted, Out> {
        val input = File(temp.newFolder(), name).apply { writeBytes(bytes) }
        val zip = temp.newFile()
        val progress = ArrayList<Int>()
        val converted = VjdictImport.toYomitan(input, name, attribution, zip) { progress += it }
        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            fun json(n: String) = DictJson.parseToJsonElement(z.getInputStream(z.getEntry(n)).readBytes().decodeToString())
            fun banks(prefix: String) = names.filter { it.startsWith(prefix) }.sortedBy { it.filter(Char::isDigit).toInt() }
                .flatMap { json(it).jsonArray.map { row -> row.jsonArray } }
            return converted to Out(json("index.json").jsonObject, banks("term_bank_"), banks("term_meta_bank_"), names)
        }
    }

    private fun JsonArray.str(i: Int) = this[i].jsonPrimitive.content
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content

    @Test
    fun aVersion2FileBecomesATermPerEntryInFileOrder() {
        val (converted, out) = convert(vjdict(2, rows, title = "Test Dictionary"))
        assertEquals("Test Dictionary", out.index.str("title"))
        assertEquals("Test Dictionary", converted.title)
        assertEquals(3, out.index["format"]!!.jsonPrimitive.int)
        assertTrue(out.index.str("revision")!!.startsWith("vjdict-"))
        assertEquals("This dictionary came from a Rubi .vjdict file. Its sources' attribution travels with that file.", out.index.str("attribution"))
        assertNull(out.index.str("frequencyMode"))
        assertTrue(out.meta.isEmpty())

        // Each entry once, in order, then the extra key's term right after its entry.
        assertEquals(
            listOf("日本語/にほんご", "食べる/たべる", "上/うえ", "上/じょう", "テレビ/テレビ", "上手い/うまい", "てしまう/てしまう", "でしまう/でしまう", "ほげ/ほげ"),
            out.terms.map { it.str(0) + "/" + it.str(1) },
        )
        assertEquals(8, converted.entries)
        assertEquals(9, converted.terms)
        assertEquals(1, converted.aliases)
        // Rules by hoshidicts' names; iru has none there.
        assertEquals(listOf("", "v1", "", "", "", "adj-i", "v5", "v5", ""), out.terms.map { it.str(3) })
        // Earlier entries score higher; an extra key shares its entry's score and sequence.
        val scores = out.terms.map { it[4].jsonPrimitive.int }
        assertEquals(listOf(0, -1, -2, -3, -4, -5, -6, -6, -7), scores)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 7, 8), out.terms.map { it[6].jsonPrimitive.int })
        // The definition is one plain string, its line breaks kept.
        val glossary = out.terms[0][5].jsonArray
        assertEquals(1, glossary.size)
        assertEquals("The language of Japan.\n日=day, sun\n本=book, origin\n語=word, language", glossary[0].jsonPrimitive.content)
        assertEquals(listOf("", "", "", "", "", "", "", "", ""), out.terms.map { it.str(2) })
        assertEquals(listOf("", "", "", "", "", "", "", "", ""), out.terms.map { it.str(7) })
    }

    @Test
    fun version3RanksBecomeAFrequencyList() {
        val (converted, out) = convert(vjdict(3, rows, title = "Bee's Test"))
        assertEquals("Bee's Test", out.index.str("title"))
        assertEquals("rank-based", out.index.str("frequencyMode"))
        assertTrue(out.index.str("description")!!.contains("Jiten global"))
        val freq = out.meta.map { row ->
            val data = row[2].jsonObject
            Triple(row.str(0), data.str("reading"), data["frequency"]!!)
        }
        assertTrue(out.meta.all { it.str(1) == "freq" })
        assertEquals(
            listOf("日本語/にほんご", "食べる/たべる", "上/うえ", "上/じょう", "テレビ/テレビ", "上手い/うまい"),
            freq.map { it.first + "/" + it.second },
        )
        assertEquals(listOf(4372, 300, 90, 2000, 900), freq.take(5).map { it.third.jsonPrimitive.int })
        // A rank lent from the kana form sorts after every own rank, and says what it is.
        val lent = freq[5].third.jsonObject
        assertEquals((1 shl 24) + 1500, lent["value"]!!.jsonPrimitive.int)
        assertEquals("1500㋕", lent.str("displayValue"))
        assertEquals(6, converted.frequencies)
        // The terms are as in version 2.
        assertEquals(9, out.terms.size)
        assertEquals("To eat.", out.terms[1][5].jsonArray[0].jsonPrimitive.content)

        // Two entries of one spelling and reading are two terms but one rank.
        val (_, twice) = convert(vjdict(3, listOf(Row("日本", "にほん", "Japan.", rank = 902), Row("日本", "にほん", "Nippon.", rank = 902))), name = "twice.vjdict")
        assertEquals(2, twice.terms.size)
        assertEquals(1, twice.meta.size)
    }

    @Test
    fun aKeysOwnOrderOutranksFileOrder() {
        // Rubi lists かみ's postings by the source's score: 神 first, though it was written last.
        val kami = listOf(Row("紙", "かみ", "Paper."), Row("髪", "かみ", "Hair."), Row("神", "かみ", "God.", score = 5), Row("犬", "いぬ", "Dog."))
        for (version in 1..3) {
            val (_, out) = convert(vjdict(version, kami), name = "kami$version.vjdict")
            assertEquals(listOf("神", "紙", "髪", "犬"), out.terms.map { it.str(0) })
            assertEquals(listOf(0, -1, -2, -3), out.terms.map { it[4].jsonPrimitive.int })
        }
    }

    @Test
    fun version1IsReadToo() {
        val (converted, out) = convert(vjdict(1, rows), name = "legacy.vjdict")
        // Version 1 has no title: the file's name stands in.
        assertEquals("legacy", out.index.str("title"))
        assertEquals("legacy", converted.title)
        assertEquals(9, out.terms.size)
        assertEquals("食べる", out.terms[1].str(0))
        assertEquals("v1", out.terms[1].str(3))
    }

    @Test
    fun manyEntriesSpillAcrossIndexPagesAndBanks() {
        val many = (0 until 10_300).map { i -> Row("語$i", "ご$i", "Word $i.", rules = i % 2, rank = i + 1) }
        val (converted, out) = convert(vjdict(3, many, buckets = 4))
        assertEquals(10_300, converted.entries)
        assertEquals(listOf("index.json", "term_bank_1.json", "term_meta_bank_1.json", "term_bank_2.json", "term_meta_bank_2.json"), out.names)
        assertEquals(many.map { it.spelling }, out.terms.map { it.str(0) })
        assertEquals(10_300, out.meta.size)
        // One bucket for 300 keys needs an overflow page.
        val (_, small) = convert(vjdict(2, many.take(300), buckets = 1), name = "small.vjdict")
        assertEquals(300, small.terms.size)
    }

    @Test
    fun aDamagedFileSaysSo() {
        val good = vjdict(2, rows)
        for (bad in listOf(good.copyOf(good.size - 1), good.copyOf(100), good.copyOf().also { it[4097] = 0 })) {
            try {
                convert(bad)
                fail("read a damaged file")
            } catch (e: VjdictException) {
                assertTrue(e.message!!, e.message!!.startsWith("This .vjdict file cannot be read"))
            }
        }
    }

    @Test
    fun findsAVjdictPickedOnItsOwnOrInAZip() {
        val cache = temp.newFolder()
        val raw = temp.newFile("x.bin").apply { writeBytes(vjdict(3, rows)) }
        val direct = VjdictImport.find(raw, "mine.vjdict", cache)!!
        assertEquals(raw, direct.file)
        assertEquals("mine.vjdict", direct.name)
        assertFalse(direct.temporary)

        fun zip(vararg entries: Pair<String, ByteArray>) = temp.newFile().apply {
            ZipOutputStream(outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        }
        val packed = zip("plaintext-attribution.txt" to "# Sources\n\nDaijirin \"4\"\n- JMdict".toByteArray(), "dict/plaintext.vjdict" to raw.readBytes())
        val found = VjdictImport.find(packed, "plaintext.vjdict.zip", cache)!!
        assertTrue(found.temporary)
        assertEquals("plaintext.vjdict", found.name)
        assertEquals(cache, found.file.parentFile)
        assertTrue(found.file.readBytes().contentEquals(raw.readBytes()))
        val (_, out) = convert(found.file.readBytes(), found.name, found.attribution)
        assertEquals("Sources\n\nDaijirin \"4\"\n- JMdict", out.index.str("attribution"))

        // A Yomitan zip, or anything else, is left to the Yomitan import.
        assertNull(VjdictImport.find(zip("index.json" to "{}".toByteArray(), "a.vjdict" to raw.readBytes()), "y.zip", cache))
        assertNull(VjdictImport.find(zip("a.vjdict" to "not one".toByteArray()), "z.zip", cache))
        assertNull(VjdictImport.find(temp.newFile().apply { writeText("PK but not a zip") }, "p.zip", cache))
        assertNull(VjdictImport.find(temp.newFile().apply { writeText("hello") }, "h.txt", cache))
    }

    @Test
    fun rubisReleaseZipGivesUpItsWordDictionaryAndItsAttribution() {
        val cache = temp.newFolder()
        val words = vjdict(3, rows, title = "Words")
        val names = vjdict(2, rows.take(1), title = "Names")
        // Laid out as Rubi's package_release.py lays it out: the names dictionary comes first.
        val release = temp.newFile("Rubi-0.11.0.zip").apply {
            ZipOutputStream(outputStream()).use { z ->
                for ((n, b) in listOf(
                    "data/VitaJPOverlay/dictionaries/vndb.vjdict" to names,
                    "data/VitaJPOverlay/ocr/ATTRIBUTION.md" to "the reader's models".toByteArray(),
                    "data/VitaJPOverlay/dictionaries/plaintext.vjdict" to words,
                    "docs/plaintext-attribution.txt" to "Bee's Ultimate Dictionary\nJMdict: EDRDG".toByteArray(),
                )) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            }
        }
        val found = VjdictImport.find(release, release.name, cache)!!
        assertEquals("plaintext.vjdict", found.name)
        assertTrue(found.file.readBytes().contentEquals(words))
        assertEquals("Bee's Ultimate Dictionary\nJMdict: EDRDG", found.attribution)
        // Rubi wraps its attribution at a fixed width: it reads as paragraphs and list items.
        assertEquals(
            "Bee's Ultimate Dictionary, built once and frozen (release ultimate-v1).\n\nSources:\n\n- Yokubi (CC BY 4.0, summarized)\n- IMABI",
            VjdictImport.reflow(
                "Bee's Ultimate Dictionary, built once and frozen\n(release ultimate-v1).\r\n\n# Sources:\n\n" +
                    "- Yokubi (CC BY\n  4.0, summarized)\n- IMABI\n",
            ),
        )

        val releases = """[
          {"tag_name":"v0.12.0-rc1","prerelease":true,"assets":[{"name":"Rubi-0.12.0.zip","size":70000000,"browser_download_url":"https://x/rc.zip"},
            {"name":"SHA256SUMS","size":340,"browser_download_url":"https://x/rc.sums"}]},
          {"tag_name":"v0.11.0","prerelease":false,"assets":[
            {"name":"Rubi-0.11.0-debug.zip","size":67319968,"browser_download_url":"https://x/debug.zip"},
            {"name":"Rubi-0.11.0.zip","size":67297232,"browser_download_url":"https://x/Rubi-0.11.0.zip"},
            {"name":"SHA256SUMS","size":340,"browser_download_url":"https://x/SHA256SUMS"}]},
          {"tag_name":"v0.10.0","assets":[{"name":"Rubi-0.10.0.zip","size":67000000,"browser_download_url":"https://x/old.zip"}]},
          {"tag_name":"v0.9.0","assets":[{"name":"Rubi-0.9.0.zip","size":4000,"browser_download_url":"https://x/tiny.zip"},
            {"name":"SHA256SUMS","size":340,"browser_download_url":"https://x/s"}]}
        ]"""
        // The release candidate, the zip with no checksums and the one too small to hold the dictionary are passed over.
        assertEquals(
            listOf(RubiDictionarySource.Asset("v0.11.0", "Rubi-0.11.0.zip", "https://x/Rubi-0.11.0.zip", 67297232, "https://x/SHA256SUMS")),
            RubiDictionarySource.candidates(releases),
        )
        val sum = "a".repeat(64)
        val sums = "${"b".repeat(64)}  Rubi-0.11.0-debug.zip\n${sum.uppercase()}  Rubi-0.11.0.zip\n"
        assertEquals(sum, RubiDictionarySource.checksum(sums, "Rubi-0.11.0.zip"))
        assertEquals(sum, RubiDictionarySource.checksum("$sum *Rubi-0.11.0.zip", "Rubi-0.11.0.zip"))
        assertNull(RubiDictionarySource.checksum(sums, "Rubi-0.12.0.zip"))
        assertEquals(VjdictImport.sha256(temp.newFile().apply { writeText("abc") }), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun aPlainGlossaryKeepsItsLineBreaks() {
        val json = """["The language of Japan.\n日=day, sun\n本=book"]"""
        assertEquals(listOf("The language of Japan.\n日=day, sun\n本=book"), GlossaryText.senses(json))
        // Blank lines and runs of spaces go, as before.
        assertEquals(listOf("to eat; to\nconsume"), GlossaryText.senses("""["to  eat","to \n\n consume"]"""))
        assertTrue(EntryHtml.glossary(json, "d").contains("The language of Japan.\n日=day"))
        assertTrue(EntryHtml.BASE_CSS.contains(".gloss-text"))
    }
}
