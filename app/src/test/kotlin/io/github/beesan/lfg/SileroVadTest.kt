package io.github.beesan.lfg

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.beesan.lfg.capture.SileroVad
import io.github.beesan.lfg.capture.VoiceFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The app's own Silero VAD against the published model run by ONNX Runtime,
 * step by step on the same sound, with each side carrying its own state.
 * The .onnx is kept among the test resources for this alone.
 */
class SileroVadTest {
    private val tolerance = 1e-4f

    private fun port() = File("src/main/assets/${VoiceFinder.ASSET}").inputStream().use { SileroVad.load(it) }

    /** The published model, called as VoiceFinder used to call it. */
    private class Reference : AutoCloseable {
        val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        val session: OrtSession = env.createSession(javaClass.getResourceAsStream("/vad/silero_vad.onnx")!!.readBytes())
        private val sr = OnnxTensor.createTensor(env, longArrayOf(16_000L))
        var state = Array(2) { Array(1) { FloatArray(SileroVad.HIDDEN) } }

        fun run(input: FloatArray): Float =
            OnnxTensor.createTensor(env, arrayOf(input)).use { x ->
                OnnxTensor.createTensor(env, state).use { s ->
                    session.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        state = result[1].value as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        (result[0].value as Array<FloatArray>)[0][0]
                    }
                }
            }

        override fun close() {
            sr.close()
            session.close()
        }
    }

    /** The test voice, 16 kHz, as floats. */
    private fun voice(): FloatArray {
        val bytes = javaClass.getResourceAsStream("/sound/voice.s16le")!!.readBytes()
        val shorts = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        return FloatArray(shorts.size) { shorts[it] / 32768f }
    }

    private fun noise(samples: Int, loudness: Float): FloatArray {
        val random = Random(7)
        return FloatArray(samples) { (random.nextFloat() * 2 - 1) * loudness }
    }

    /** The voice over a chord, louder than the voice in places. */
    private fun voiceOverMusic(): FloatArray {
        val voice = voice()
        return FloatArray(voice.size + 16_000) { i ->
            val t = i / 16_000.0
            val music = 0.15 * (sin(2 * PI * 220 * t) + sin(2 * PI * 277.18 * t) + sin(2 * PI * 329.63 * t))
            val v = if (i - 8_000 in voice.indices) voice[i - 8_000] else 0f
            (music + v).toFloat().coerceIn(-1f, 1f)
        }
    }

    /** The largest difference in chance of speech between the port and the reference, over every step of [clip]. */
    private fun compare(clip: FloatArray): Float {
        val vad = port()
        val state = FloatArray(SileroVad.STATE)
        val input = FloatArray(SileroVad.INPUT)
        var worst = 0f
        Reference().use { ref ->
            for (step in 0 until clip.size / SileroVad.CHUNK) {
                System.arraycopy(input, SileroVad.CHUNK, input, 0, SileroVad.CONTEXT)
                System.arraycopy(clip, step * SileroVad.CHUNK, input, SileroVad.CONTEXT, SileroVad.CHUNK)
                val ours = vad.run(input, state)
                val theirs = ref.run(input.copyOf())
                assertEquals("step $step", theirs, ours, tolerance)
                // The state carried to the next step is the same as the model's, h then c.
                // The cell state grows past 10 in long silence, so it is compared relative to its size.
                for (k in 0 until SileroVad.HIDDEN) {
                    val h = ref.state[0][0][k]
                    val c = ref.state[1][0][k]
                    assertEquals("h[$k] after step $step", h, state[k], tolerance * maxOf(1f, abs(h)))
                    assertEquals("c[$k] after step $step", c, state[SileroVad.HIDDEN + k], tolerance * maxOf(1f, abs(c)))
                }
                worst = maxOf(worst, abs(ours - theirs))
            }
        }
        return worst
    }

    @Test
    fun `matches the published model on a spoken line`() {
        val worst = compare(voice())
        println("Silero port, voice: largest difference $worst")
    }

    @Test
    fun `matches the published model on a line over music`() {
        println("Silero port, voice over music: largest difference ${compare(voiceOverMusic())}")
    }

    @Test
    fun `matches the published model on noise and silence`() {
        println("Silero port, noise: largest difference ${compare(noise(16_000 * 3, 0.5f))}")
        println("Silero port, quiet noise: largest difference ${compare(noise(16_000 * 2, 0.01f))}")
        println("Silero port, silence: largest difference ${compare(FloatArray(16_000 * 2))}")
    }

    @Test
    fun `the state carries from step to step`() {
        val vad = port()
        val clip = voice()
        val input = FloatArray(SileroVad.INPUT)
        val from = 40 * SileroVad.CHUNK
        System.arraycopy(clip, from, input, 0, SileroVad.INPUT)
        val fresh = vad.run(input, FloatArray(SileroVad.STATE))
        // The same step after the speech before it.
        val state = FloatArray(SileroVad.STATE)
        val before = FloatArray(SileroVad.INPUT)
        for (step in 0 until 40) {
            System.arraycopy(clip, step * SileroVad.CHUNK, before, 0, SileroVad.INPUT)
            vad.run(before, state)
        }
        val carried = vad.run(input, state)
        assertTrue("state makes no difference: $fresh and $carried", abs(fresh - carried) > 1e-3f)
    }

    @Test
    fun `speed against ONNX Runtime`() {
        val clip = voiceOverMusic()
        val steps = clip.size / SileroVad.CHUNK
        val input = FloatArray(SileroVad.INPUT)
        fun perStep(run: (FloatArray) -> Unit): Double {
            repeat(20) { for (step in 0 until steps) run(input.also { System.arraycopy(clip, step * SileroVad.CHUNK, it, 0, SileroVad.CHUNK) }) }
            val start = System.nanoTime()
            val rounds = 20
            repeat(rounds) { for (step in 0 until steps) run(input.also { System.arraycopy(clip, step * SileroVad.CHUNK, it, 0, SileroVad.CHUNK) }) }
            return (System.nanoTime() - start) / 1e3 / (rounds * steps)
        }
        val vad = port()
        val state = FloatArray(SileroVad.STATE)
        val ours = perStep { vad.run(it, state) }
        val theirs = Reference().use { ref -> perStep { ref.run(it) } }
        println("Silero port: %.1f µs a step; ONNX Runtime: %.1f µs a step".format(ours, theirs))
    }
}
