// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.fakegame

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * A dialogue box over a flat ground. A tap or any button shows the next page;
 * its text types itself out, as a visual novel's does. Every input is logged
 * (`adb logcat -s FakeVN`), so a test can tell what reached the game.
 *
 * Sound, for testing sentence audio: each new page plays a short tone. Started
 * with `--ez music true`, music plays throughout instead, and a new page plays
 * the recording `voice.s16le` (16 kHz, 16-bit, mono) from the app's external
 * files folder when one has been pushed there, except on every third page,
 * which stays unvoiced.
 *
 * Started with `--ez scene true`, the box sits over a classroom with a
 * character in it (CC0 art, see assets/scene/CREDITS.txt), for the website's
 * recording. Without it the ground stays flat, as the OCR tests expect.
 */
class GameActivity : Activity() {
    private lateinit var game: GameView
    private var music: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = GameView(this)
        if (intent.getBooleanExtra("scene", false)) {
            game.scene = assets.open("scene/classroom.jpg").use(BitmapFactory::decodeStream) to
                assets.open("scene/yui.png").use(BitmapFactory::decodeStream)
        }
        if (intent.getBooleanExtra("music", false)) {
            game.voice = getExternalFilesDir(null)?.resolve("voice.s16le")?.takeIf { it.canRead() }?.readBytes()
            music = game.play(music(), 22_050, loop = true)
        }
        setContentView(game)
        window.decorView.windowInsetsController?.hide(WindowInsets.Type.systemBars())
    }

    override fun onDestroy() {
        music?.release()
        super.onDestroy()
    }

    /** Four seconds of a chord with a moving melody over it, never quiet, so loudness alone cannot find a voice in it. */
    private fun music() = ShortArray(22_050 * 4) { i ->
        val t = i / 22_050.0
        val melody = doubleArrayOf(523.25, 587.33, 659.25, 783.99)[(t * 4).toInt() % 4]
        (1800 * sin(2 * PI * 220.0 * t) + 1400 * sin(2 * PI * 277.18 * t) + 1400 * sin(2 * PI * 329.63 * t) + 1500 * sin(2 * PI * melody * t)).toInt().toShort()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"}")
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in ADVANCE) game.advance()
        return event.keyCode != KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "focus $hasFocus")
    }

    class GameView(context: Context) : View(context) {
        private val pages = listOf(
            // A sentence that wraps in the middle of a word (景色), as text boxes do.
            listOf("そのとき彼女は静かに頷いて、窓の外の景", "色をしばらく眺めていた。"),
            // Furigana over two words; see [ruby].
            listOf("彼は憂鬱な表情で曖昧に答えた。"),
            listOf("昨日の夜、森の奥で不思議な光を見たんだ。"),
            listOf("でも、近づいたら消えてしまった。", "あれは一体何だったんだろう。"),
            listOf("明日、もう一度確かめに行こう。"),
            listOf("彼女は静かに頷いて、窓の外を眺めた。"),
        )
        /** Readings drawn small above their words, on the page that has them. */
        private val ruby = mapOf(1 to listOf("憂鬱" to "ゆううつ", "曖昧" to "あいまい"))
        private var page = 0
        private var shown = 0
        private val handler = Handler(Looper.getMainLooper())
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3F5F7.toInt() }
        private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FA6B5.toInt() }
        private val box = Paint().apply { color = 0xFF0C141B.toInt() }
        private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        /** A background and a character drawn behind the box, when the scene is on. */
        var scene: Pair<Bitmap, Bitmap>? = null
            set(value) {
                field = value
                if (value != null) box.color = 0xE60C141B.toInt()
                invalidate()
            }
        private val typing = object : Runnable {
            override fun run() {
                if (shown < pages[page].sumOf { it.length }) {
                    shown++
                    invalidate()
                    handler.postDelayed(this, 35)
                }
            }
        }

        init {
            setBackgroundColor(0xFF1B2A36.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            // A pad's first press takes Android out of touch mode; no highlight should then wash over the picture.
            defaultFocusHighlightEnabled = false
            typing.run()
        }

        fun advance() {
            val total = pages[page].sumOf { it.length }
            if (shown < total) {
                shown = total
            } else {
                page = (page + 1) % pages.size
                shown = 0
                handler.removeCallbacks(typing)
                typing.run()
                speak()
            }
            Log.i(TAG, "page $page")
            invalidate()
        }

        /** With music playing: the recorded voice for a new page, when there is one. */
        var voice: ByteArray? = null
        private var music = false

        /** A stand-in for a voiced line, played as game sound: the recording, or a second and a bit of tone. */
        private fun speak() {
            val rate = 22_050
            val recording = voice
            val track = when {
                recording != null -> if (page % 3 == 2) return else play(
                    ShortArray(recording.size / 2).also { ByteBuffer.wrap(recording).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) },
                    16_000,
                )
                music -> return
                else -> play(ShortArray(rate * 12 / 10) { i -> (sin(i * 2.0 * PI * (330 + page * 60) / rate) * 9000).toInt().toShort() }, rate)
            }
            handler.postDelayed({ track.release() }, track.bufferSizeInFrames * 1000L / track.sampleRate + 1300)
        }

        fun play(samples: ShortArray, rate: Int, loop: Boolean = false): AudioTrack {
            if (loop) music = true
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            if (loop) track.setLoopPoints(0, samples.size, -1)
            track.play()
            return track
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                Log.i(TAG, "tap ${event.x.toInt()},${event.y.toInt()}")
                advance()
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val size = h * 0.052f
            text.textSize = size
            name.textSize = size * 0.6f
            scene?.let { (ground, person) ->
                canvas.drawBitmap(ground, null, RectF(0f, 0f, w, h), art)
                val ph = h * 0.98f
                val pw = ph * person.width / person.height
                canvas.drawBitmap(person, null, RectF(w * 0.62f - pw / 2, h * 0.06f, w * 0.62f + pw / 2, h * 0.06f + ph), art)
            }
            canvas.drawRect(w * 0.04f, h * 0.70f, w * 0.96f, h * 0.95f, box)
            canvas.drawText("ユイ", w * 0.07f, h * 0.745f, name)
            var left = shown
            pages[page].forEachIndexed { i, line ->
                val part = line.take(left.coerceAtLeast(0))
                left -= line.length
                canvas.drawText(part, w * 0.07f, h * 0.81f + i * size * 1.6f, text)
                if (i == 0) for ((word, reading) in ruby[page] ?: emptyList()) {
                    val at = line.indexOf(word)
                    if (at < 0 || part.length < at + word.length) continue
                    name.textSize = size * 0.4f
                    val x = w * 0.07f + text.measureText(line, 0, at) + (text.measureText(word) - name.measureText(reading)) / 2
                    canvas.drawText(reading, x, h * 0.81f - size * 0.95f, name)
                    name.textSize = size * 0.6f
                }
            }
        }
    }

    private companion object {
        const val TAG = "FakeVN"
        val ADVANCE = setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}
