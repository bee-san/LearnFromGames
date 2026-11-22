// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

// What the dictionaries LFG writes itself are made with: Rubi's, converted, and a visual novel's character names.

/** JSON text built straight into UTF-8 bytes; a bank of 10,000 terms is the most it holds. */
internal class JsonBytes {
    var bytes = ByteArray(1 shl 16)
    var size = 0

    fun clear() {
        size = 0
    }

    private fun room(n: Int) {
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    fun raw(c: Char): JsonBytes {
        room(1)
        bytes[size++] = c.code.toByte()
        return this
    }

    fun raw(s: String): JsonBytes {
        for (c in s) raw(c)
        return this
    }

    fun number(n: Int): JsonBytes = raw(n.toString())

    fun string(s: String): JsonBytes = utf8(s.toByteArray(Charsets.UTF_8))

    /** A JSON string of UTF-8 bytes: quotes, backslashes and control characters escaped, the rest as is. */
    fun utf8(text: ByteArray): JsonBytes {
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
internal class YomitanZip(private val out: OutputStream) {
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
        if (offset + 30 + entry.name.size + entry.compressed > 0xFFFF_FFFFL) throw java.io.IOException("The dictionary is too large (over 4 GB).")
        header(LOCAL, entry, central = false)
        if (stored) write(data, length) else write(buffer, compressed)
        written += entry
    }

    fun finish() {
        val start = offset
        for (entry in written) header(CENTRAL, entry, central = true)
        val size = offset - start
        if (written.size > 0xFFFF || offset > 0xFFFF_FFFFL) throw java.io.IOException("The dictionary is too large (over 4 GB).")
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
