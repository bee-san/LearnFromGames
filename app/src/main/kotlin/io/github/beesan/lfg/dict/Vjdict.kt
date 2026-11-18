// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** A .vjdict file that cannot be read, in words for the Dictionaries screen. */
class VjdictException(message: String) : IOException(message)

/**
 * Rubi's own dictionary file (bee-san/Rubi_for_vita, docs/local-dictionaries.md),
 * read where it lies. Versions 2 and 3 are a hash index of keys, each key a list
 * of postings that point at entries; version 1 is the older sorted list of keys.
 * An entry is spelling NUL reading NUL definition NUL, after a frequency word in
 * version 3, and the keys of its spelling and of its reading share it.
 *
 * Only what a conversion needs is read: each entry once, the inflection rules its
 * postings give it, and the keys that are neither its spelling nor its reading.
 */
class Vjdict(file: File) : Closeable {
    private val bytes = Mapped(file)
    val size: Long = bytes.size
    val version: Int
    /** The dictionary's own name (page 0, bytes 192..255), or empty. */
    val title: String
    /** Version 3's frequency list name (page 0, bytes 64..127), or empty. */
    val frequencyList: String
    private val count: Long
    private val dataOffset: Long
    private val pages: Long

    init {
        try {
            if (size < HEADER || magicVersion(bytes.bytes(0, 8)) == 0) throw damaged("it is not a .vjdict file")
            version = bytes.u32(8).toInt()
            if (version != magicVersion(bytes.bytes(0, 8))) throw damaged("its header disagrees with itself")
            count = bytes.u32(16)
            dataOffset = bytes.u64(32)
            if (bytes.u32(12) != HEADER || bytes.u32(20) != RECORD) throw damaged("its header is not one Rubi writes")
            if (bytes.u64(40) != size) throw damaged("it is ${size} bytes, not the ${bytes.u64(40)} its header says; it may be cut short")
            if (size >= 1L shl 39) throw damaged("it is too large")
            if (version == 1) {
                if (bytes.u64(24) != HEADER || dataOffset != HEADER + count * RECORD || dataOffset > size) throw damaged("its index is out of place")
                pages = 0
                title = ""
                frequencyList = ""
            } else {
                val buckets = bytes.u64(48)
                pages = bytes.u64(56)
                if (bytes.u64(24) != PAGE || buckets <= 0 || buckets and (buckets - 1) != 0L || pages < buckets ||
                    pages > Int.MAX_VALUE || dataOffset != PAGE + pages * PAGE || dataOffset > size
                ) throw damaged("its index is out of place")
                title = text(TITLE_AT)
                frequencyList = if (version == 3) text(FREQUENCY_LIST_AT) else ""
            }
        } catch (e: Exception) {
            bytes.close()
            throw e
        }
    }

    /** Page 0's text field at [at]: up to 63 bytes of UTF-8, NUL-padded. Anything else reads as none. */
    private fun text(at: Long): String {
        val raw = bytes.bytes(at, TEXT_FIELD)
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        val s = String(raw, 0, end, Charsets.UTF_8).trim()
        return if (s.any { it < ' ' || it == '\u007f' }) "" else s
    }

    /** One entry as the file stores it. [definition] is UTF-8. */
    class Entry(
        val spelling: String,
        val reading: String,
        val definition: ByteArray,
        /** The rule bits of the entry's postings, bits 0-6 (v1, v5, vs, vk, vz, adj-i, iru). */
        val rules: Int,
        /** Version 3's frequency rank, 1 the most frequent; 0 when the list does not rank it. */
        val rank: Int,
        /** The rank is the word's in kana, lent to this spelling. */
        val borrowed: Boolean,
    )

    /**
     * Every entry once, in the order Rubi lists them: each key's postings come in
     * the order its converter sorted them (dictionary priority, then the source's
     * term score, then row order), which is one order for all keys; entries no key
     * orders keep the order the file stores them in. [entries] packs an entry's
     * offset, length and rules (see [entry]); [aliases] holds the keys that find an
     * entry but are neither its spelling nor its reading (Bee's Ultimate
     * Dictionary finds てしまう from でしまう).
     */
    class Index(val entries: LongArray, val aliases: Map<Long, List<String>>, val keys: Long, val postings: Long)

    /** Walks the whole index. [progress] hears how many of the header's postings have been read. */
    fun index(progress: (Long, Long) -> Unit = { _, _ -> }): Index {
        var packed = LongArray(minOf(count, size / POSTING, Int.MAX_VALUE.toLong() - 8).toInt().coerceAtLeast(16))
        var n = 0
        var keys = 0L
        val aliases = HashMap<Long, MutableList<String>>()
        // Each key's postings in order, as pairs of entries: this one comes before that one.
        var before = LongArray(1024)
        var after = LongArray(1024)
        var pairs = 0
        var previous = -1L
        fun posting(key: ByteArray, at: Long, length: Long, rules: Int) {
            if (at < dataOffset || length <= 0 || at > size - length) throw damaged("an entry lies outside the file")
            if (length > MAX_ENTRY) throw damaged("an entry is larger than ${MAX_ENTRY / 1024} KiB")
            if (n == packed.size) packed = packed.copyOf(packed.size + packed.size / 2 + 16)
            packed[n++] = (at shl 24) or (length shl 7) or (rules and RULE_BITS).toLong()
            if (previous >= 0 && previous != at) {
                if (pairs == before.size) {
                    before = before.copyOf(pairs * 2)
                    after = after.copyOf(pairs * 2)
                }
                before[pairs] = previous
                after[pairs++] = at
            }
            previous = at
            if (!isKeyOf(key, at, length.toInt())) {
                val alias = String(key, Charsets.UTF_8)
                aliases.getOrPut(at) { ArrayList(1) }.let { if (alias !in it) it += alias }
            }
            if (n % 8192 == 0) progress(n.toLong(), count)
        }
        if (version == 1) {
            var last = ByteArray(0)
            for (i in 0 until count) {
                val record = HEADER + i * RECORD
                val keyLength = bytes.u16(record + 16)
                val key = bytes.bytes(bytes.u64(record), keyLength)
                // Records are sorted by key: a key's postings are a run of them.
                if (!key.contentEquals(last)) previous = -1
                last = key
                posting(key, bytes.u64(record + 8), bytes.u32(record + 20), bytes.u16(record + 18))
                keys++
            }
        } else {
            for (page in 0 until pages) {
                val base = PAGE + page * PAGE
                if (bytes.u32(base) != PAGE_MAGIC) throw damaged("an index page is damaged")
                val records = bytes.u32(base + 4)
                if (records > (PAGE - PAGE_HEADER) / RECORD) throw damaged("an index page is damaged")
                for (slot in 0 until records) {
                    val record = base + PAGE_HEADER + slot * RECORD
                    val group = bytes.u64(record + 8)
                    val postings = bytes.u32(record + 16)
                    val keyLength = bytes.u16(record + 20)
                    if (group < dataOffset || group > size - keyLength - postings * POSTING) throw damaged("a key lies outside the file")
                    val key = bytes.bytes(group, keyLength)
                    previous = -1
                    for (j in 0 until postings) {
                        val at = group + keyLength + j * POSTING
                        posting(key, bytes.u64(at), bytes.u32(at + 8), bytes.u16(at + 12))
                    }
                    keys++
                }
            }
        }
        if (n == 0) throw damaged("it has no entries")
        val postings = n.toLong()
        // In file order, each entry once; an entry's keys may give it different rules, so they are joined.
        packed = packed.copyOf(n)
        packed.sort()
        var unique = 0
        for (i in 0 until n) {
            val p = packed[i]
            if (unique > 0 && packed[unique - 1] ushr 7 == p ushr 7) {
                packed[unique - 1] = packed[unique - 1] or (p and RULE_BITS.toLong())
            } else {
                if (unique > 0 && packed[unique - 1] ushr 24 == p ushr 24) throw damaged("two keys disagree on an entry's length")
                packed[unique++] = p
            }
        }
        progress(count, count)
        return Index(postingOrder(packed.copyOf(unique), before, after, pairs), aliases, keys, postings)
    }

    /**
     * [entries] (in file order) reordered so that each [before] entry comes ahead
     * of its [after] one: a topological sort, ties in file order. Should the keys
     * disagree, the entries caught in it follow the rest in file order.
     */
    private fun postingOrder(entries: LongArray, before: LongArray, after: LongArray, pairs: Int): LongArray {
        val n = entries.size
        fun ordinal(at: Long): Int {
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (entries[mid] ushr 24 < at) lo = mid + 1 else hi = mid
            }
            return lo
        }
        val from = IntArray(pairs) { ordinal(before[it]) }
        val to = IntArray(pairs) { ordinal(after[it]) }
        // Who comes after whom, grouped by entry; and how many each still waits for.
        val first = IntArray(n + 1)
        for (e in 0 until pairs) first[from[e] + 1]++
        for (i in 0 until n) first[i + 1] += first[i]
        val fill = first.copyOf(n)
        val next = IntArray(pairs)
        val waiting = IntArray(n)
        for (e in 0 until pairs) {
            next[fill[from[e]]++] = to[e]
            waiting[to[e]]++
        }
        // A min-heap of the entries that wait for nothing, so ties go in file order.
        val heap = IntArray(n)
        var size = 0
        fun push(v: Int) {
            var i = size++
            while (i > 0 && heap[(i - 1) / 2] > v) {
                heap[i] = heap[(i - 1) / 2]
                i = (i - 1) / 2
            }
            heap[i] = v
        }
        fun pop(): Int {
            val top = heap[0]
            val v = heap[--size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && heap[c + 1] < heap[c]) c++
                if (heap[c] >= v) break
                heap[i] = heap[c]
                i = c
            }
            if (size > 0) heap[i] = v
            return top
        }
        for (i in 0 until n) if (waiting[i] == 0) push(i)
        val order = LongArray(n)
        var k = 0
        while (size > 0) {
            val o = pop()
            order[k++] = entries[o]
            for (e in first[o] until first[o + 1]) if (--waiting[next[e]] == 0) push(next[e])
        }
        for (i in 0 until n) if (waiting[i] > 0) order[k++] = entries[i]
        return order
    }

    /** The entry [packed] (one of [Index.entries]) points at. */
    fun entry(packed: Long): Entry {
        val at = packed ushr 24
        val length = ((packed ushr 7) and 0x1FFFF).toInt()
        val raw = bytes.bytes(at, length)
        val start = if (version == 3) 4 else 0
        if (length < start + 2) throw damaged("an entry is cut short")
        val word = if (version == 3) bytes.u32(at).toInt() else 0
        val a = raw.indexOf(0, start)
        val b = if (a < 0) -1 else raw.indexOf(0, a + 1)
        if (b < 0) throw damaged("an entry is cut short")
        val c = raw.indexOf(0, b + 1).let { if (it < 0) raw.size else it }
        return Entry(
            spelling = String(raw, start, a - start, Charsets.UTF_8),
            reading = String(raw, a + 1, b - a - 1, Charsets.UTF_8),
            definition = raw.copyOfRange(b + 1, c),
            rules = (packed and RULE_BITS.toLong()).toInt(),
            rank = word and RANK_BITS,
            borrowed = word and BORROWED != 0,
        )
    }

    /** Whether [key] is the entry's spelling or reading as Rubi keys them (empty reading: the spelling). */
    private fun isKeyOf(key: ByteArray, at: Long, length: Int): Boolean {
        val start = if (version == 3) 4 else 0
        val head = bytes.bytes(at + start, minOf(length - start, HEAD).coerceAtLeast(0))
        val a = head.indexOf(0)
        if (a < 0) return true // A long head: not worth a closer look; such an entry gets no alias.
        val b = head.indexOf(0, a + 1).let { if (it < 0) head.size else it }
        if (sameBytes(key, head, 0, a) || sameBytes(key, head, a + 1, b)) return true
        val spelling = String(head, 0, a, Charsets.UTF_8)
        val reading = String(head, a + 1, b - a - 1, Charsets.UTF_8)
        val k = String(key, Charsets.UTF_8)
        return k == normalize(spelling.ifEmpty { reading }) || k == normalize(reading.ifEmpty { spelling })
    }

    override fun close() = bytes.close()

    /** The whole file mapped, in pieces of 1 GiB that overlap by the longest read. */
    private class Mapped(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        val size: Long = raf.length()
        private val pieces: Array<ByteBuffer> = try {
            Array(((size + PIECE - 1) / PIECE).toInt().coerceAtLeast(1)) { i ->
                val start = i * PIECE
                raf.channel.map(FileChannel.MapMode.READ_ONLY, start, minOf(PIECE + OVERLAP, size - start)).order(ByteOrder.LITTLE_ENDIAN)
            }
        } catch (e: Exception) {
            raf.close()
            throw e
        }

        private fun at(offset: Long, n: Int): ByteBuffer {
            if (offset < 0 || n < 0 || n > OVERLAP || offset > size - n) throw damaged("it points outside itself")
            return pieces[(offset / PIECE).toInt()]
        }

        private fun index(offset: Long) = (offset % PIECE).toInt()
        fun u16(offset: Long): Int = at(offset, 2).getShort(index(offset)).toInt() and 0xFFFF
        fun u32(offset: Long): Long = at(offset, 4).getInt(index(offset)).toLong() and 0xFFFFFFFFL
        fun u64(offset: Long): Long = at(offset, 8).getLong(index(offset)).also { if (it < 0) throw damaged("it points outside itself") }
        fun bytes(offset: Long, n: Int): ByteArray {
            val out = ByteArray(n)
            // ByteBuffer.get(index, array) is Android 14's; Android 13 (the AYN Thor's) has only the relative get.
            val piece = at(offset, n).duplicate()
            piece.position(index(offset))
            piece.get(out)
            return out
        }

        override fun close() = raf.close()
    }

    companion object {
        private const val HEADER = 64L
        private const val PAGE = 4096L
        private const val PAGE_HEADER = 16L
        private const val RECORD = 24L
        private const val POSTING = 16L
        private const val PAGE_MAGIC = 0x5048_4A56L // "VJHP"
        private const val TITLE_AT = 192L
        private const val FREQUENCY_LIST_AT = 64L
        private const val TEXT_FIELD = 64
        private const val PIECE = 1L shl 30
        private const val OVERLAP = 1 shl 20
        private const val HEAD = 1024
        private const val RULE_BITS = 0x7F
        private const val RANK_BITS = 0xFFFFFF
        private const val BORROWED = 1 shl 24

        /** Rubi writes at most 8 KiB an entry; the packing of [Index.entries] allows up to this. */
        const val MAX_ENTRY = (1L shl 17) - 1

        /** Rubi's rule bits, 0-6, by the names hoshidicts' deinflector knows (iru it does not). */
        val RULE_NAMES = listOf("v1", "v5", "vs", "vk", "vz", "adj-i", "iru")

        /** 1, 2 or 3 when [head] starts with a .vjdict magic, else 0. */
        fun magicVersion(head: ByteArray): Int {
            if (head.size < 8) return 0
            val magic = "VJDICT"
            for (i in magic.indices) if (head[i] != magic[i].code.toByte()) return 0
            if (head[7] != 0.toByte()) return 0
            return when (head[6]) {
                '1'.code.toByte() -> 1
                '2'.code.toByte() -> 2
                '3'.code.toByte() -> 3
                else -> 0
            }
        }

        /** Rubi's key form: katakana ァ..ヶ as hiragana, full-width ASCII as ASCII, all else kept. */
        fun normalize(text: String): String {
            if (text.none { it in 'ァ'..'ヶ' || it in '！'..'～' }) return text
            return buildString(text.length) {
                for (c in text) append(
                    when (c) {
                        in 'ァ'..'ヶ' -> c - 0x60
                        in '！'..'～' -> c - 0xFEE0
                        else -> c
                    },
                )
            }
        }

        private fun sameBytes(key: ByteArray, head: ByteArray, from: Int, to: Int): Boolean {
            if (to - from != key.size) return false
            for (i in key.indices) if (key[i] != head[from + i]) return false
            return true
        }

        private fun ByteArray.indexOf(value: Int, from: Int = 0): Int {
            for (i in from until size) if (this[i] == value.toByte()) return i
            return -1
        }

        private fun damaged(why: String) = VjdictException("This .vjdict file cannot be read: $why.")
    }
}
