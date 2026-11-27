// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.platform.LocalContext
import io.github.beesan.lfg.R
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The click of a button: a key of a Hi75 keyboard going down, and coming up
 * again when it is let go. Seven recordings of each, from Josh Comeau's
 * `button-sounds` (MIT), cut into `res/raw/click_down_N` and `click_up_N`.
 *
 * Played as a sonification, not as media: the sound LFG records from a game
 * for a card takes only media and game sound, so a click never lands on a card.
 */
object ButtonSounds {
    /** Off, [down] and [up] play nothing. Settings › Appearance. */
    @Volatile
    var enabled: Boolean = true

    /** Well under full volume, as the library plays them. */
    private const val VOLUME = 0.315f

    /** Settings › Appearance, "Sound volume": the volume is multiplied by this. */
    @Volatile
    var level: Float = 1f

    private val DOWN = intArrayOf(
        R.raw.click_down_1, R.raw.click_down_2, R.raw.click_down_3, R.raw.click_down_4,
        R.raw.click_down_5, R.raw.click_down_6, R.raw.click_down_7,
    )
    private val UP = intArrayOf(
        R.raw.click_up_1, R.raw.click_up_2, R.raw.click_up_3, R.raw.click_up_4,
        R.raw.click_up_5, R.raw.click_up_6, R.raw.click_up_7,
    )

    @Volatile
    private var pool: SoundPool? = null

    /** The pool's names for the recordings, in the order of [DOWN] and [UP]. */
    private val down = IntArray(DOWN.size)
    private val up = IntArray(UP.size)

    /** The recordings the pool has finished reading: until then, playing one does nothing. */
    private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Which recording the last press played, so its release plays the same key; -1 when it played none. */
    @Volatile
    private var pressed = -1

    /** A little higher or lower each press, so the same recording twice does not sound the same. */
    @Volatile
    private var rate = 1f

    /** Reads the recordings, once. They are ready a moment later; a press before then is silent. */
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
            for (i in DOWN.indices) {
                down[i] = made.load(app, DOWN[i], 1)
                up[i] = made.load(app, UP[i], 1)
            }
            pool = made
        }.onFailure { runCatching { made.release() } }
    }

    /** A button went down: one of the seven keys, picked by chance. */
    fun down() {
        if (!enabled) {
            pressed = -1
            return
        }
        val key = Random.nextInt(DOWN.size)
        pressed = key
        rate = 0.97f + Random.nextFloat() * 0.06f
        play(down[key])
    }

    /** The button was let go: the same key coming up. */
    fun up() {
        val key = pressed
        if (enabled && key >= 0) play(up[key])
    }

    private fun play(sample: Int) {
        val pool = pool ?: return
        if (sample !in ready) return
        val volume = (VOLUME * level).coerceAtMost(1f)
        runCatching { pool.play(sample, volume, volume, 1, 0, rate) }
    }
}

/** Whether this part of the app is inside [ClickSounds]. The panels over a game are not, and stay silent. */
private val LocalClickSounds = staticCompositionLocalOf { false }

/**
 * Everything pressable inside clicks: down when it is pressed, by touch or by
 * the pad's A, and up when it is let go. A press that is called off (the
 * finger slides away, the page scrolls) makes no second sound.
 *
 * It works through the indication, the thing every `clickable` asks for to
 * draw its press, so no button has to ask for the sound itself. A control
 * that draws its own press never asks: give it [rememberClickSource].
 */
@Composable
fun ClickSounds(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(context) { ButtonSounds.load(context) }
    SideEffect { ButtonSounds.enabled = enabled }
    val drawn = LocalIndication.current
    // An indication of the old kind cannot be wrapped in a node: it is left as it is, without the sound.
    val clicking: Indication = remember(drawn) { if (drawn is IndicationNodeFactory) ClickIndication(drawn) else drawn }
    CompositionLocalProvider(LocalIndication provides clicking, LocalClickSounds provides true, content = content)
}

/**
 * An interaction source that clicks, for a control that draws its own press
 * and so never asks for the indication: Material's `Button` and `Checkbox`.
 * Outside [ClickSounds] it is an ordinary, silent one.
 */
@Composable
fun rememberClickSource(): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    if (LocalClickSounds.current) LaunchedEffect(source) { source.interactions.collect { click(it) } }
    return source
}

private fun click(interaction: Interaction) {
    when (interaction) {
        is PressInteraction.Press -> ButtonSounds.down()
        is PressInteraction.Release -> ButtonSounds.up()
    }
}

/** The indication in use, drawing as it always did, with the click added. */
private class ClickIndication(private val drawn: IndicationNodeFactory) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = ClickNode(drawn, interactionSource)
    override fun equals(other: Any?) = other is ClickIndication && other.drawn == drawn
    override fun hashCode() = 31 * drawn.hashCode() + 1
}

private class ClickNode(drawn: IndicationNodeFactory, private val source: InteractionSource) : DelegatingNode() {
    init {
        delegate(drawn.create(source))
    }

    override fun onAttach() {
        coroutineScope.launch { source.interactions.collect { click(it) } }
    }
}
