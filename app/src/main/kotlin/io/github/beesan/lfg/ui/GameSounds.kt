// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.github.beesan.lfg.R
import io.github.beesan.lfg.lookup.Heard
import java.util.concurrent.ConcurrentHashMap

/**
 * What the wheel and a lookup sound like over a game: a tick for each slice
 * or word the player moves to, a thock for a pick, two notes going into a
 * wheel and coming back out. A card made is silent: only a failed one is heard.
 *
 * Small and quiet on purpose: they go with what the hand does and are heard
 * under the game, not over it. The tick is one sound, the same every time, as
 * a console's menu has: it is heard many times a minute.
 *
 * Played as sonification, like [ButtonSounds], so the sound LFG records from
 * a game for a card never has them in it.
 */
object GameSounds {
    /** Each sound, its recording, and how loud it plays beside the others. */
    enum class Sound(val recording: Int, val volume: Float) {
        /** The wheel points at another slice; lookup steps to another word. */
        MOVE(R.raw.sound_move, 0.495f),

        /** A tile is picked. */
        PICK(R.raw.sound_pick, 0.72f),

        /** Into a wheel inside the wheel, and back out of it. */
        INTO(R.raw.sound_into, 0.54f),
        BACK(R.raw.sound_back, 0.54f),

        /** The wheel opens, and goes with nothing picked. */
        OPEN(R.raw.sound_open, 0.54f),
        AWAY(R.raw.sound_away, 0.45f),

        /** It cannot be done: a tile that has nothing to act on, a card that failed. */
        NO(R.raw.sound_no, 0.63f),
    }

    /** Settings › Appearance: off, nothing plays. */
    @Volatile
    var enabled: Boolean = true

    /** Settings › Appearance: every sound's volume is multiplied by this. */
    @Volatile
    var level: Float = 1f

    @Volatile
    private var pool: SoundPool? = null
    private val samples = IntArray(Sound.entries.size)
    private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Reads the recordings, once. A sound asked for before it is read is silent. */
    @Synchronized
    fun load(context: Context) {
        if (pool != null) return
        val made = runCatching {
            SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            ).build()
        }.getOrNull() ?: return
        runCatching {
            val app = context.applicationContext
            made.setOnLoadCompleteListener { _, sample, status -> if (status == 0) ready += sample }
            for (sound in Sound.entries) samples[sound.ordinal] = made.load(app, sound.recording, 1)
            pool = made
        }.onFailure { runCatching { made.release() } }
    }

    /** What a lookup did, heard: a step is the wheel's tick, quieter, as it comes many times in a row. */
    fun play(heard: Heard) = when (heard) {
        Heard.STEP -> play(Sound.MOVE, quieter = true)
        Heard.CARD_ASKED -> play(Sound.PICK)
        Heard.CARD_MADE -> Unit
        Heard.CARD_FAILED -> play(Sound.NO)
    }

    /** The pick, at the volume just chosen in Settings, so the choice is heard as it is made. */
    fun sample(volume: Int) {
        level = volume / 100f
        play(Sound.PICK)
    }

    fun play(sound: Sound, quieter: Boolean = false) {
        if (!enabled) return
        val pool = pool ?: return
        val sample = samples[sound.ordinal]
        if (sample !in ready) return
        val volume = (sound.volume * level * if (quieter) 0.6f else 1f).coerceIn(0f, 1f)
        runCatching { pool.play(sample, volume, volume, 1, 0, 1f) }
    }
}
