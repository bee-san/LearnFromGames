// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Silero VAD v6.2.3's 16 kHz model, worked out by hand instead of through
 * ONNX Runtime, which would otherwise be most of the app's size for one small
 * network. It does what the model's 16 kHz branch does, step for step: a
 * short-time Fourier transform done as a convolution, four convolutions, one
 * LSTM cell and a last convolution to a single chance of speech.
 *
 * The weights are those of the published model, written out by
 * tools/export_silero.py; the layout is described there. One instance keeps
 * scratch space for a step, so it is not to be shared between threads.
 */
class SileroVad private constructor(weights: Weights) {
    constructor(weights: FloatArray) : this(Weights(weights))

    /** Hands out the weights file's arrays in order. */
    private class Weights(private val all: FloatArray) {
        init {
            require(all.size == FLOATS) { "Silero VAD weights: ${all.size} floats, expected $FLOATS" }
        }

        private var at = 0
        fun take(n: Int) = all.copyOfRange(at, at + n).also { at += n }
    }

    private val basis = weights.take(BASIS_ROWS * WINDOW)
    private val w0 = weights.take(128 * BINS * 3)
    private val b0 = weights.take(128)
    private val w1 = weights.take(64 * 128 * 3)
    private val b1 = weights.take(64)
    private val w2 = weights.take(64 * 64 * 3)
    private val b2 = weights.take(64)
    private val w3 = weights.take(128 * 64 * 3)
    private val b3 = weights.take(128)
    private val wIh = weights.take(4 * HIDDEN * HIDDEN)
    private val wHh = weights.take(4 * HIDDEN * HIDDEN)
    private val bIh = weights.take(4 * HIDDEN)
    private val bHh = weights.take(4 * HIDDEN)
    private val wOut = weights.take(HIDDEN)
    private val bOut = weights.take(1)[0]

    // Scratch space for one step. Each layer's output is laid out [channel][time]
    // with a column of zeros either side, the padding the next convolution needs.
    private val padded = FloatArray(INPUT + PAD)
    private val re = FloatArray(WINDOW)
    private val im = FloatArray(WINDOW)
    private val spectrum = FloatArray(BINS * (FRAMES + 2))
    private val e0 = FloatArray(128 * (FRAMES + 2))
    private val e1 = FloatArray(64 * (2 + 2))
    private val e2 = FloatArray(64 * (1 + 2))
    private val e3 = FloatArray(128 * (1 + 2))
    private val features = FloatArray(HIDDEN)
    private val gates = FloatArray(4 * HIDDEN)

    /**
     * The chance of speech in [input], the 64 samples of context and 512 new
     * ones at 16 kHz. [state] is the model's state, h then c, 128 each, as the
     * ONNX model's [2, 1, 128]; it is read and replaced with the next one.
     */
    fun run(input: FloatArray, state: FloatArray): Float {
        require(input.size == INPUT && state.size == STATE)
        stft(input)
        conv(spectrum, BINS, FRAMES, w0, b0, 128, 1, e0)
        conv(e0, 128, FRAMES, w1, b1, 64, 2, e1)
        conv(e1, 64, 2, w2, b2, 64, 2, e2)
        conv(e2, 64, 1, w3, b3, 128, 1, e3)
        for (k in 0 until HIDDEN) features[k] = e3[k * 3 + 1]
        lstm(features, state)
        // The decoder: ReLU, a 1x1 convolution to one channel, and a sigmoid.
        var sum = bOut
        for (k in 0 until HIDDEN) sum += wOut[k] * maxOf(state[k], 0f)
        return sigmoid(sum)
    }

    /**
     * The magnitude of each of 129 frequencies in each of four windows of 256 samples, 128 apart.
     * The model does this as a convolution with 258 rows of a Hann-windowed Fourier basis; the
     * same sums by an FFT take a thirtieth of the work, and the window is the basis's own first
     * row (the cosine of frequency 0), so it is the model's to the last bit.
     */
    private fun stft(input: FloatArray) {
        System.arraycopy(input, 0, padded, 0, INPUT)
        // Reflected on the right only, as the model pads, without repeating the last sample.
        for (k in 0 until PAD) padded[INPUT + k] = input[INPUT - 2 - k]
        for (t in 0 until FRAMES) {
            val from = t * HOP
            for (k in 0 until WINDOW) {
                re[REVERSED[k]] = padded[from + k] * basis[k]
                im[k] = 0f
            }
            fft()
            for (f in 0 until BINS) spectrum[f * (FRAMES + 2) + t + 1] = sqrt(re[f] * re[f] + im[f] * im[f])
        }
    }

    /** An in-place radix-2 FFT of [re] and [im], whose input is already in bit-reversed order. */
    private fun fft() {
        var size = 2
        while (size <= WINDOW) {
            val half = size / 2
            val step = WINDOW / size
            var start = 0
            while (start < WINDOW) {
                for (k in 0 until half) {
                    val wr = COS[k * step]
                    val wi = -SIN[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }

    /**
     * A convolution of width 3, padded by one each side, then ReLU. [x] and
     * [out] carry their padding columns; those of [out] are left at zero.
     */
    private fun conv(x: FloatArray, inCh: Int, inT: Int, w: FloatArray, b: FloatArray, outCh: Int, stride: Int, out: FloatArray) {
        val outT = (inT - 1) / stride + 1
        val inRow = inT + 2
        for (o in 0 until outCh) {
            for (t in 0 until outT) {
                // The window's first column, in padded terms.
                val s = t * stride
                // One sum for each tap, so that they do not wait on each other.
                var s0 = 0f
                var s1 = 0f
                var s2 = 0f
                var wAt = o * inCh * 3
                var xAt = s
                for (c in 0 until inCh) {
                    s0 += w[wAt] * x[xAt]
                    s1 += w[wAt + 1] * x[xAt + 1]
                    s2 += w[wAt + 2] * x[xAt + 2]
                    wAt += 3
                    xAt += inRow
                }
                out[o * (outT + 2) + t + 1] = maxOf(b[o] + (s0 + s1 + s2), 0f)
            }
        }
    }

    /** One step of the LSTM cell, gates in PyTorch's order: input, forget, cell, output. */
    private fun lstm(x: FloatArray, state: FloatArray) {
        for (g in 0 until 4 * HIDDEN) {
            val row = g * HIDDEN
            gates[g] = bIh[g] + bHh[g] + dot(wIh, row, x, 0, HIDDEN) + dot(wHh, row, state, 0, HIDDEN)
        }
        for (k in 0 until HIDDEN) {
            val i = sigmoid(gates[k])
            val f = sigmoid(gates[HIDDEN + k])
            val g = tanh(gates[2 * HIDDEN + k])
            val o = sigmoid(gates[3 * HIDDEN + k])
            val c = f * state[HIDDEN + k] + i * g
            state[HIDDEN + k] = c
            state[k] = o * tanh(c)
        }
    }

    companion object {
        const val CONTEXT = 64
        const val CHUNK = 512
        const val INPUT = CONTEXT + CHUNK
        const val HIDDEN = 128
        const val STATE = 2 * HIDDEN

        private const val WINDOW = 256
        private const val HOP = 128
        private const val PAD = 64
        private const val BINS = WINDOW / 2 + 1
        private const val BASIS_ROWS = 2 * BINS
        private const val FRAMES = (INPUT + PAD - WINDOW) / HOP + 1

        /** The number of floats in the weights file. */
        const val FLOATS = BASIS_ROWS * WINDOW +
            128 * BINS * 3 + 128 + 64 * 128 * 3 + 64 + 64 * 64 * 3 + 64 + 128 * 64 * 3 + 128 +
            2 * 4 * HIDDEN * HIDDEN + 2 * 4 * HIDDEN + HIDDEN + 1

        /** The model from the weights file, little-endian float32. */
        fun load(stream: InputStream): SileroVad {
            val bytes = stream.readBytes()
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            return SileroVad(floats)
        }

        private val COS = FloatArray(WINDOW / 2) { kotlin.math.cos(2 * Math.PI * it / WINDOW).toFloat() }
        private val SIN = FloatArray(WINDOW / 2) { kotlin.math.sin(2 * Math.PI * it / WINDOW).toFloat() }

        /** Each index's bits reversed, eight of them: the order a radix-2 FFT takes its input in. */
        private val REVERSED = IntArray(WINDOW) { Integer.reverse(it) ushr (32 - 8) }

        private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

        /** The dot product of [n] floats, a multiple of four, in four sums that do not wait on each other. */
        private fun dot(a: FloatArray, aAt: Int, b: FloatArray, bAt: Int, n: Int): Float {
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var k = 0
            while (k < n) {
                s0 += a[aAt + k] * b[bAt + k]
                s1 += a[aAt + k + 1] * b[bAt + k + 1]
                s2 += a[aAt + k + 2] * b[bAt + k + 2]
                s3 += a[aAt + k + 3] * b[bAt + k + 3]
                k += 4
            }
            return (s0 + s1) + (s2 + s3)
        }
    }
}
