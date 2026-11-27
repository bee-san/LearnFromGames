package io.github.beesan.lfg

import io.github.beesan.lfg.core.Wav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav(samples: ShortArray, channels: Int, rate: Int, bits: Int = 16, format: Int = 1, extra: ByteArray = ByteArray(0), dataSize: Int? = null): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + extra.size + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra.size + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(bits.toShort())
        b.put(extra)
        b.put("data".toByteArray()).putInt(dataSize ?: data)
        for (s in samples) b.putShort(s)
        return b.array()
    }

    @Test
    fun aVoicesMonoFileIsReadAsItIs() {
        val pcm = Wav.read(wav(shortArrayOf(1, -2, 300, -32768), 1, 22050))!!
        assertEquals(22050, pcm.rate)
        assertArrayEquals(shortArrayOf(1, -2, 300, -32768), pcm.samples)
    }

    @Test
    fun twoChannelsAreMixedToOne() {
        val pcm = Wav.read(wav(shortArrayOf(100, 200, -10, -30), 2, 24000))!!
        assertArrayEquals(shortArrayOf(150, -20), pcm.samples)
    }

    @Test
    fun anOddSizedChunkBeforeTheSoundIsSkippedWithItsPadding() {
        // A "LIST" chunk of 3 bytes, padded to 4.
        val list = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)).array()
        assertArrayEquals(shortArrayOf(7, 8), Wav.read(wav(shortArrayOf(7, 8), 1, 16000, extra = list))!!.samples)
    }

    @Test
    fun aFileWhoseSoundSizeWasNeverWrittenGivesWhatIsThere() {
        assertArrayEquals(shortArrayOf(5, 6, 7), Wav.read(wav(shortArrayOf(5, 6, 7), 1, 16000, dataSize = 0))!!.samples)
    }

    @Test
    fun anythingButSixteenBitPcmIsNotRead() {
        assertNull(Wav.read(wav(shortArrayOf(1, 2), 1, 16000, bits = 8)))
        assertNull(Wav.read(wav(shortArrayOf(1, 2), 1, 16000, format = 3)))
        assertNull(Wav.read("not a wav file at all".toByteArray()))
        assertNull(Wav.read(ByteArray(0)))
    }
}
