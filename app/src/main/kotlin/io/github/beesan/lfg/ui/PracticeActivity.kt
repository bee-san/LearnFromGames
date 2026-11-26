// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.beesan.lfg.App
import io.github.beesan.lfg.anki.AnkiStatus
import io.github.beesan.lfg.capture.LfgAccessibilityService
import io.github.beesan.lfg.capture.Readers
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.Tutorial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tutorial: a screen made to look like a game, with one line of dialogue
 * in the language being learnt. The service reads it as it reads a game
 * ([LfgAccessibilityService.practiseOn]), so the shortcut, the cursor, the
 * card and the wheel tried here are the real ones. It fills the screen, as a
 * game does, and on a two-screen handheld it opens on the game's screen.
 */
class PracticeActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // No bars, as a game has none: that is also how the service tells a game from an app.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // Back leaves as Skip does, so what was read here is still cleared away.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        setContent {
            val prefs by app.settings.prefs.collectAsStateWithLifecycle()
            val seen by LfgAccessibilityService.practice.collectAsStateWithLifecycle()
            val reading by LfgAccessibilityService.running.collectAsStateWithLifecycle()
            val palette = Palette.of(prefs.colours, prefs.theme, isSystemInDarkTheme())
            if (Lfg.palette != palette) Lfg.palette = palette
            ClickSounds(prefs.buttonSounds) {
                PracticeScreen(
                    line = prefs.learning.practice,
                    controller = prefs.usesController,
                    shortcut = shortcutLabel(prefs),
                    add = if (prefs.swapConfirm) "B" else "A",
                    close = if (prefs.swapConfirm) "A" else "B",
                    seen = Tutorial.Seen(seen.lookup, seen.word, seen.moves, seen.cards, seen.closes, seen.wheels, seen.wheelUp),
                    read = seen.read,
                    problem = when {
                        !reading -> "Screen reading is off. Turn it on in LFG first."
                        Readers.reader == null -> "LFG is still downloading. Try again in a minute."
                        else -> null
                    },
                    cardsWait = app.anki.status() != AnkiStatus.READY,
                    onLine = { LfgAccessibilityService.practiceBox = it },
                    onLeave = ::leave,
                )
            }
        }
    }

    /**
     * The pad's buttons are for what is being practised, never for this screen's own button. Android turns
     * a stick click, Start or Select that nothing took into "press what is focused": the shortcut itself
     * would then press Skip. A presses Finish at the end, and B is Back, which leaves.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (android.view.KeyEvent.isGamepadButton(code) && code != android.view.KeyEvent.KEYCODE_BUTTON_A && code != android.view.KeyEvent.KEYCODE_BUTTON_B) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        LfgAccessibilityService.practiseOn(display?.displayId ?: Display.DEFAULT_DISPLAY)
        _showing.value = true
    }

    override fun onPause() {
        _showing.value = false
        LfgAccessibilityService.practiseOn(null)
        super.onPause()
    }

    /** Done, or skipped: what was read here is not kept as reading, and set-up hears that the tutorial is over. */
    private fun leave() {
        LfgAccessibilityService.practiseOn(null)
        app.scope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                app.database.games().byPackage(packageName)?.let { app.database.lines().forgetPractice(it.id) }
            }
        }
        app.settings.update { it.copy(setupDone = true) }
        // The app's pages are under this screen, or on the other one where they never went away: either way
        // they are what is left. Starting them from here would carry them onto the game's screen.
        finish()
    }

    companion object {
        private val _showing = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** The tutorial is on a screen now: on a two-screen handheld the app's pages, on the other, say to look there. */
        val showing: kotlinx.coroutines.flow.StateFlow<Boolean> = _showing

        /**
         * Opens the tutorial from one of the app's pages. On a two-screen handheld whose pages are on the
         * second screen it opens on the first, where games are; the pages stay where they are.
         */
        fun start(from: android.app.Activity) {
            val intent = Intent(from, PracticeActivity::class.java)
            val here = from.display?.displayId ?: Display.DEFAULT_DISPLAY
            if (here == Display.DEFAULT_DISPLAY) {
                from.startActivity(intent)
                return
            }
            try {
                from.startActivity(
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                )
            } catch (_: Exception) {
                // The first screen will not take it: it opens here, over the pages.
                from.startActivity(Intent(from, PracticeActivity::class.java))
            }
        }
    }
}

/** A night sky, a line of dialogue in a box at the foot, and what to try in a card at the top. */
@Composable
private fun PracticeScreen(
    line: String,
    controller: Boolean,
    shortcut: String,
    add: String,
    close: String,
    seen: Tutorial.Seen,
    read: Boolean,
    problem: String?,
    cardsWait: Boolean,
    onLine: (io.github.beesan.lfg.core.Box?) -> Unit,
    onLeave: () -> Unit,
) {
    val steps = remember(controller) { Tutorial.steps(controller) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    // What had been seen when this step began: a step is done by what happens after.
    var then by remember { mutableStateOf(seen) }
    val step = steps[index.coerceIn(steps.indices)]
    LaunchedEffect(seen, step) {
        if (Tutorial.done(step, then, seen)) {
            // Long enough to see the tick, short enough not to wait on.
            delay(STEP_MS)
            then = seen
            index = (index + 1).coerceAtMost(steps.lastIndex)
        }
    }
    // Reading takes a moment. Much longer, and something is wrong that waiting will not mend.
    var stuck by remember { mutableStateOf(false) }
    LaunchedEffect(read) {
        stuck = false
        if (!read) {
            delay(STUCK_MS)
            stuck = true
        }
    }
    Box(Modifier.fillMaxSize()) {
        Sky()
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Guide(
                step = step, number = index + 1, of = steps.size - 1,
                words = Tutorial.words(step, shortcut, add, close).let { (ask, why) ->
                    when {
                        step == Tutorial.Step.DONE && controller -> ask to "Open a game and press $shortcut."
                        step == Tutorial.Step.DONE -> ask to "Open a game and tap a word."
                        (step == Tutorial.Step.CARD || step == Tutorial.Step.TAP_CARD) && cardsWait -> ask to "This word becomes a flashcard. It waits in LFG until AnkiDroid is connected."
                        else -> ask to why
                    }
                },
                waiting = problem ?: when {
                    read || step == Tutorial.Step.DONE -> null
                    stuck -> "LFG has not read this screen. In Android's settings, turn LFG's screen reading off and on again."
                    else -> "Reading the screen…"
                },
                onLeave = onLeave,
            )
            Spacer(Modifier.weight(1f))
            // The dialogue box, as low as a game has it: the entry opens above the word, and must leave the guide clear.
            // Its place is told to the service: only what is in it is the game's text.
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Color(0xE60B1020))
                    .border(2.dp, Color(0x66FFFFFF), shape)
                    .onGloballyPositioned { at ->
                        val corner = at.positionOnScreen()
                        onLine(
                            io.github.beesan.lfg.core.Box(
                                corner.x.toInt(), corner.y.toInt(), corner.x.toInt() + at.size.width, corner.y.toInt() + at.size.height,
                            ),
                        )
                    }
                    .padding(horizontal = 26.dp, vertical = 18.dp),
            ) {
                Text(line, color = Color.White, fontSize = 28.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * What to try now, in a strip along the top: what to do, one line on what it does, how far through, and the way out.
 * A strip because the entry opens over the middle of the screen (two thirds of its height, above the word), and
 * what to do next has to stay readable beside it.
 */
@Composable
private fun Guide(step: Tutorial.Step, number: Int, of: Int, words: Pair<String, String>, waiting: String?, onLeave: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Lfg.Surface).border(1.dp, Lfg.Border, shape).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(words.first, color = Lfg.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            waiting ?: words.second, color = if (waiting != null) Lfg.Warn else Lfg.Dim, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (step != Tutorial.Step.DONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(of) { i ->
                    Box(Modifier.size(if (i == number - 1) 9.dp else 7.dp).clip(CircleShape).background(if (i < number) Lfg.Fill else Lfg.BorderStrong))
                }
            }
        }
        LfgButton(if (step == Tutorial.Step.DONE) "Finish" else "Skip", onLeave, primary = step == Tutorial.Step.DONE, compact = true,
            // The pad reaches it only once there is nothing left to try: until then A is for making the card, and
            // must never land here instead. Touch and Back skip at any time.
            modifier = if (step == Tutorial.Step.DONE) Modifier.firstFocus() else Modifier.focusProperties { canFocus = false },
        )
    }
}

/** The scene behind the line: night, a wood, and the light the practice line once spoke of. Nothing in it reads as text. */
@Composable
private fun Sky() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF1B2452), Color(0xFF0A1A1E))))
        val light = Offset(size.width * 0.72f, size.height * 0.42f)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFE9A8), Color.Transparent), light, size.minDimension * 0.42f), size.minDimension * 0.42f, light)
        drawCircle(Color(0xFFFFF3C4), size.minDimension * 0.018f, light)
        // Hills, darker towards the front.
        val hills = listOf(0.62f to Color(0xFF10203A), 0.72f to Color(0xFF0B1728), 0.82f to Color(0xFF060E18))
        for ((height, colour) in hills) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, size.height * height)
                var x = 0f
                var up = true
                while (x < size.width) {
                    val next = x + size.width / 5f
                    quadraticTo((x + next) / 2f, size.height * (height + if (up) -0.07f else 0.03f), next, size.height * height)
                    x = next
                    up = !up
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, colour)
        }
    }
}

private const val STEP_MS = 700L

/** How long the line may go unread before the guide says what to do about it. */
private const val STUCK_MS = 10_000L
