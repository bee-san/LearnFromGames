// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import java.io.InputStream

/**
 * Finds the voice in a clip of game sound with Silero VAD, so the music and
 * sound effects around a line are cut away. Games play music under nearly
 * every line, so trimming by loudness alone keeps the whole clip. Free of
 * Android, so it is tested on the build machine with the real model.
 *
 * The model is called as Silero's own Java example calls it: 512 samples at
 * 16 kHz a step, after the last 64 of the step before, with its state carried.
 */
class VoiceFinder(private val vad: SileroVad) {

    /** The chance of speech in each 32 ms step of a 16 kHz clip. */
    @Synchronized
    fun probabilities(clip: FloatArray): FloatArray {
        val state = FloatArray(SileroVad.STATE)
        val input = FloatArray(CONTEXT + CHUNK)
        val steps = clip.size / CHUNK
        val out = FloatArray(steps)
        for (step in 0 until steps) {
            // The context is the previous step's last 64 samples; silence before the first.
            System.arraycopy(input, CHUNK, input, 0, CONTEXT)
            System.arraycopy(clip, step * CHUNK, input, CONTEXT, CHUNK)
            out[step] = vad.run(input, state)
        }
        return out
    }

    /**
     * The clip from the start of its first speech to the end of its last, with
     * a short margin; empty when there is no speech in it.
     */
    fun trim(samples: ShortArray, sampleRate: Int, marginMs: Int = 150): ShortArray {
        val span = speechSpan(probabilities(resample(samples, sampleRate))) ?: return ShortArray(0)
        val perStep = CHUNK.toLong() * sampleRate / RATE
        val margin = sampleRate.toLong() * marginMs / 1000
        val from = (span.first * perStep - margin).coerceIn(0, samples.size.toLong()).toInt()
        val to = ((span.last + 1) * perStep + margin).coerceIn(0, samples.size.toLong()).toInt()
        return if (to > from) samples.copyOfRange(from, to) else ShortArray(0)
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = SileroVad.CHUNK
        const val CONTEXT = SileroVad.CONTEXT

        /** Where the model's weights are kept among the app's assets. */
        const val ASSET = "vad/silero_vad_16k.bin"

        /** A finder with the model's weights read from [weights]. */
        fun load(weights: InputStream) = VoiceFinder(SileroVad.load(weights))
        const val STEP_MS = CHUNK * 1000 / RATE

        /** Silero's own defaults (get_speech_timestamps). */
        const val THRESHOLD = 0.5f
        const val NEG_THRESHOLD = THRESHOLD - 0.15f
        const val MIN_SPEECH_MS = 250
        const val MIN_SILENCE_MS = 100

        /** 16-bit samples at any rate as floats at 16 kHz, by straight-line steps between samples. */
        fun resample(samples: ShortArray, fromRate: Int): FloatArray {
            if (samples.isEmpty()) return FloatArray(0)
            val n = (samples.size.toLong() * RATE / fromRate).toInt()
            return FloatArray(n) { i ->
                val at = i.toDouble() * fromRate / RATE
                val a = at.toInt().coerceAtMost(samples.size - 1)
                val b = (a + 1).coerceAtMost(samples.size - 1)
                val t = (at - a).toFloat()
                (samples[a] * (1 - t) + samples[b] * t) / 32768f
            }
        }

        /**
         * Steps `[first, last)` from the start of the first stretch of speech to
         * the end of the last, as Silero's get_speech_timestamps finds them:
         * speech starts above [THRESHOLD], ends after [MIN_SILENCE_MS] below
         * [NEG_THRESHOLD], and counts only when it lasts [MIN_SPEECH_MS].
         */
        fun speechSpan(probabilities: FloatArray): IntRange? {
            val minSilence = MIN_SILENCE_MS / STEP_MS
            val minSpeech = MIN_SPEECH_MS / STEP_MS
            var first = -1
            var last = -1
            var start = -1
            var quietFrom = -1
            fun close(end: Int) {
                if (end - start >= minSpeech) {
                    if (first < 0) first = start
                    last = end
                }
                start = -1
                quietFrom = -1
            }
            for ((i, p) in probabilities.withIndex()) {
                if (start < 0) {
                    if (p >= THRESHOLD) start = i
                    continue
                }
                when {
                    p >= THRESHOLD -> quietFrom = -1
                    p < NEG_THRESHOLD -> {
                        if (quietFrom < 0) quietFrom = i
                        if (i + 1 - quietFrom >= minSilence) close(quietFrom)
                    }
                }
            }
            if (start >= 0) close(probabilities.size)
            return if (first >= 0) first until last else null
        }
    }
}
