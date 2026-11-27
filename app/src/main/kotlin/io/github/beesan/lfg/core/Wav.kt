// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sound as mono 16-bit samples at [rate] a second. */
class Pcm(val samples: ShortArray, val rate: Int)

/**
 * The sound in a WAV file, as Android's voice writes one: 16-bit PCM, mono or
 * more channels (mixed to one). Null for anything else, or a file cut short.
 * Free of Android, so it is tested on the build machine.
 */
object Wav {
    fun read(bytes: ByteArray): Pcm? {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 0
        var rate = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = data.getInt(at + 4)
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) return null
                    val format = data.getShort(body).toInt() and 0xffff
                    channels = data.getShort(body + 2).toInt()
                    rate = data.getInt(body + 4)
                    val bits = data.getShort(body + 14).toInt()
                    // 1 is PCM; 0xFFFE is the extensible form, which a voice uses for the same PCM.
                    if ((format != 1 && format != 0xFFFE) || bits != 16 || channels < 1 || rate <= 0) return null
                }
                "data" -> {
                    if (channels == 0) return null
                    // A voice that streams its file may leave the size unset: take what is there.
                    val length = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    val frames = length / (2 * channels)
                    val out = ShortArray(frames)
                    for (i in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += data.getShort(body + (i * channels + c) * 2)
                        out[i] = (sum / channels).toShort()
                    }
                    return Pcm(out, rate)
                }
            }
            if (size < 0) return null
            at = body + size + (size and 1)
        }
        return null
    }
}
