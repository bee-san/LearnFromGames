package io.github.beesan.lfg

import ai.onnxruntime.OrtEnvironment
import io.github.beesan.lfg.capture.VoiceFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The real Silero VAD model on a spoken line laid over music, as a game
 * plays it. The voice is Windows' Haruka reading a line, 16 kHz, made once
 * with System.Speech; the music is drawn here.
 */
class VoiceFinderTest {
    private val rate = 44_100

    private fun finder(): VoiceFinder {
        val env = OrtEnvironment.getEnvironment()
        return VoiceFinder(env, env.createSession(File("src/main/assets/vad/silero_vad.onnx").path))
    }

    /** The voice at 44.1 kHz, as the game's sound is kept. */
    private fun voice(): ShortArray {
        val bytes = javaClass.getResourceAsStream("/sound/voice.s16le")!!.readBytes()
        val at16k = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        return ShortArray((at16k.size.toLong() * rate / 16_000).toInt()) { i -> at16k[(i.toLong() * 16_000 / rate).toInt().coerceAtMost(at16k.size - 1)] }
    }

    /** A chord with a moving melody over it, never quiet: what loudness trimming cannot cut. */
    private fun music(seconds: Double): ShortArray = ShortArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        val melody = doubleArrayOf(523.25, 587.33, 659.25, 783.99)[(t * 4).toInt() % 4]
        val v = 1800 * sin(2 * PI * 220.0 * t) + 1400 * sin(2 * PI * 277.18 * t) + 1400 * sin(2 * PI * 329.63 * t) + 1500 * sin(2 * PI * melody * t)
        v.toInt().toShort()
    }

    private fun mix(under: ShortArray, over: ShortArray, at: Int): ShortArray = ShortArray(under.size) { i ->
        val o = if (i - at in over.indices) over[i - at].toInt() else 0
        (under[i] + o).coerceIn(-32768, 32767).toShort()
    }

    @Test
    fun `cuts the music before and after the voice`() {
        val voice = voice()
        val lead = rate
        val clip = mix(music(1.0 + voice.size.toDouble() / rate + 1.5), voice, lead)

        // Where the voice really starts and ends, from the clean recording.
        val spoken = voice.indices.filter { abs(voice[it].toInt()) > 500 }
        val start = lead + spoken.first()
        val end = lead + spoken.last()

        val trimmed = finder().use { it.trim(clip, rate, marginMs = 0) }
        val from = (0..clip.size - trimmed.size).first { clip.copyOfRange(it, it + trimmed.size).contentEquals(trimmed) }
        val to = from + trimmed.size
        val tolerance = rate * 3 / 10
        assertTrue("starts at ${from - start} samples from the voice", abs(from - start) < tolerance)
        assertTrue("ends at ${to - end} samples from the voice", abs(to - end) < tolerance)
    }

    @Test
    fun `finds no voice in music alone`() {
        assertEquals(0, finder().use { it.trim(music(4.0), rate) }.size)
    }

    @Test
    fun `speech counts only when it lasts and ends after a pause`() {
        val p = { s: String -> FloatArray(s.length) { if (s[it] == '#') 0.9f else 0.1f } }
        // 4 steps (128 ms) is too short to count; 10 steps is speech.
        assertNull(VoiceFinder.speechSpan(p("....####....")))
        assertEquals(4 until 14, VoiceFinder.speechSpan(p("....##########......")))
        // A two-step dip is shorter than the 100 ms of quiet that ends speech, so it is one stretch.
        assertEquals(2 until 22, VoiceFinder.speechSpan(p("..########..##########....")))
    }
}
