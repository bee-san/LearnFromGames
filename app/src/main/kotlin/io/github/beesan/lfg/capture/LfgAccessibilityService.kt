// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.core.TreeText
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.ocr.Frame
import kotlinx.coroutines.Job
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.DialogueBox
import io.github.beesan.lfg.core.GameStats
import io.github.beesan.lfg.core.Perf
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.ShortcutHold
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.Note
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.games.Covers
import io.github.beesan.lfg.games.Hosts
import io.github.beesan.lfg.lookup.LookupController
import io.github.beesan.lfg.lookup.LookupLine
import io.github.beesan.lfg.ask.Recap
import io.github.beesan.lfg.ask.Asker
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.overlay.OverlayHost
import kotlinx.serialization.json.Json
import io.github.beesan.lfg.overlay.PickFor
import io.github.beesan.lfg.overlay.Picked
import io.github.beesan.lfg.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * The one switch. This service takes pictures of the game's screen, draws the
 * strips, mark, popup and panel over it, and hears controller buttons, all
 * without a further prompt.
 */
class LfgAccessibilityService : AccessibilityService() {
    /**
     * A task that throws (a full disk, a database error, AnkiDroid going away) is logged and the
     * rest carries on: uncaught, it would take the whole app down with it, game reading and all.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "a service task failed", e) },
    )
    private lateinit var app: App
    private lateinit var pipeline: ReadingPipeline
    private lateinit var controller: LookupController
    private lateinit var overlays: OverlayHost

    /** The game in front right now, or null: where its window is, and its row. */
    private class Front(
        val game: Game, val displayId: Int, val windowId: Int, val bounds: Rect,
        /** Where other windows lie over the game: a system dialog, the keyboard. Text there is not the game's. */
        val covered: List<Box>,
    )

    private var front: Front? = null

    /** The game in front, for the panel's heading; null when none is. */
    private val frontGame = MutableStateFlow<Game?>(null)

    /** The screens this app's own pages are in front on: the lines' screen gives way to them there. */
    private var ownAppOn: Set<Int> = emptySet()
    private val packageOfWindow = HashMap<Int, String>()
    private val games = HashMap<String, Game>()
    /** Rows of games named inside an emulator, by package and key. */
    private val namedGames = HashMap<String, Game>()
    private val unread = HashSet<String>()
    private val lastFullscreen = HashMap<String, Long>()
    /** Whether a window shows an ordinary app's text items, by window id, with when it was last looked at. */
    private val textItems = HashMap<Int, Pair<Long, Boolean>>()
    private var ignored: Set<String> = emptySet()

    private var styles: List<EntryHtml.Style> = emptyList()
    private val down = HashSet<Int>()
    private var chordFired = false

    override fun onServiceConnected() {
        app = application as App
        pipeline = ReadingPipeline(
            app.lines, File(filesDir, "frames"),
            apart = { app.settings.prefs.value.interfaceApart },
            dialogueDir = { if (app.settings.prefs.value.keepDialogue) DialogueSamples.dir(filesDir) else null },
            matchLength = { app.dictionaries.matchLength(it) },
        )
        controller = LookupController(
            scope, { text, gameId -> app.dictionaries.lookup(text, app.characterNames.vnFor(gameId)) }, app.cards, app.anki, app.lines, app.wordAudio, { app.notes.withNotes(it) }, ::prepareLine,
            selection = { app.settings.prefs.value.picking },
            vnQuestion = { app.characterNames.question(it) },
            vnAnswer = { question, yes -> app.characterNames.answer(question, yes) },
            lookupKanji = { app.dictionaries.lookupKanji(it, app.settings.prefs.value.kanjiDictionary) },
        )
        overlays = OverlayHost(
            service = this,
            controller = controller,
            prefs = { app.settings.prefs.value },
            chordActive = { chordFired },
            tapOn = ::tapOwnWindow,
            onTapElsewhere = { if (asked?.continuous == false) endAsked() },
            // While a reading is up, the button takes it away, a region kept reading included.
            onReadButton = { if (asked != null) endAsked() else ask() },
            onReadButtonHeld = { if (asked?.continuous == true) endAsked() else pickRegion() },
            onPicked = ::onPicked,
            onPickBox = { pad, once, slot, fresh -> pickRegion(pad = pad || controller.state.value.controllerMode, once = once, slot = slot, fresh = fresh) },
            onReadBox = ::readBox,
            gameBoxes = { front?.let { f -> (1..1 + app.extraBoxes[f.game.id].size).map { gameBox(f.game, f.displayId, it) } }.orEmpty() },
            onAskGame = ::askAboutGame,
            onAsk = { id, what ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val line = app.database.lines().byId(id) ?: error("That line is not saved.")
                            if (what == OverlayHost.Ask.TRANSLATE) app.translator.translate(line) else app.explainer.explain(line).explanation
                        }
                    }
                    overlays.onAnswer(id, what, result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: "That did not work. Try again." })
                }
            },
            conversation = { app.explainer.kept(it) },
            onAskMore = { id, question ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { app.explainer.ask(app.database.lines().byId(id) ?: error("That line is not saved."), question) }
                    }
                    overlays.onMore(id, result.exceptionOrNull()?.let { it.message ?: "That did not work. Try again." })
                }
            },
            onRecapAsked = { gameId, name, what ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            when (what) {
                                OverlayHost.RecapOf.SCENE -> app.recapper.scene(gameId, name)
                                else -> {
                                    val recap = Json.decodeFromString<Recap>(app.recapper.write(gameId, name).json)
                                    if (what == OverlayHost.RecapOf.LAST) recap.last else Recapper.storyText(recap)
                                }
                            }.ifBlank { error("The recap came back empty. Try again.") }
                        }
                    }
                    overlays.onRecap(what, result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: "That did not work. Try again." })
                }
            },
            styles = { styles },
            media = { dictionary, path -> app.dictionaries.mediaBlocking(dictionary, path) },
            onSaveNote = { line, entry, text, keepPicture ->
                // On a word, on a line alone, or on the game as it is now: then the newest screen is its picture.
                val game = front?.game ?: pipeline.latest.value?.game
                val picture = line?.framePath ?: pipeline.latest.value?.framePath
                scope.launch {
                    val note = Note(
                        gameId = game?.id.orEmpty(), gameName = line?.gameName ?: game?.title.orEmpty(),
                        lineId = line?.lineId, lineText = line?.text.orEmpty(),
                        term = entry?.expression.orEmpty(), reading = entry?.reading.orEmpty(), gloss = entry?.senses()?.firstOrNull().orEmpty(),
                        text = text, createdAt = System.currentTimeMillis(),
                    )
                    app.notes.save(note, if (keepPicture) picture else null)
                }
            },
        )
        scope.launch { app.dictionaries.state.collect { styles = app.dictionaries.styles() } }
        // Conversations read in as the app starts, or added to on the app's own page, show over the game too.
        scope.launch { app.explainer.conversations.collect { overlays.onExplained() } }
        ignored = ignoredPackages()
        instance = this
        _running.value = true
        _pipeline.value = pipeline

        // Set-up is waiting on Android's page: come back by ourselves.
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AWAITING, false)) {
            prefs.edit().putBoolean(KEY_AWAITING, false).apply()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        scope.launch(Dispatchers.IO) {
            app.cards.flushPending()
            clearOldPictures()
        }
        // Whatever the settings ask for and the device has, as soon as it changes.
        scope.launch {
            combine(app.settings.prefs, app.models.screenAi) { prefs, _ -> prefs }.collect { prefs ->
                FrameRing.enabled = prefs.animatedPicture
                val language = Language.of(prefs.language) ?: Language.JAPANESE
                withContext(Dispatchers.Default) { Readers.refresh(this@LfgAccessibilityService, app.models, language) }
            }
        }
        scope.launch {
            pipeline.latest.collect { reading ->
                if (reading == null) return@collect
                controller.setLines(
                    reading.lines.mapIndexed { i, line ->
                        LookupLine(
                            line.text, reading.saved.getOrNull(i)?.id, reading.framePath, reading.game.title, line,
                            reading.appearedAtMs.getOrNull(i), gameId = reading.game.id,
                        )
                    },
                )
                // A new line on screen ends the sound of the one before it.
                reading.appearedAtMs.maxOrNull()?.let { app.media.onNextLine(it) }
                if (asideSince != 0L) Log.i(TAG, "read behind the entry in ${android.os.SystemClock.uptimeMillis() - asideSince} ms")
                // The clean picture the mark and the entry stood aside for has been read.
                endAside(read = true)
                overlays.onScreen(reading.displayId, true, front != null)
                // The box was drawn from lookup: lookup comes back on the first lines read in it.
                if (reading.lines.isNotEmpty() && afterBox?.isActive == true) {
                    afterBox?.cancel()
                    if (front != null && asked == null) controller.enterControllerMode()
                    boxSaid?.let(::toast)
                    boxSaid = null
                }
            }
        }
        scope.launch { controller.state.collect(overlays::onState) }
        scope.launch {
            // Enough of every game's newest lines that the lines' screen still has those of the one being played.
            app.database.lines().recent(80).collect(overlays::onHistory)
        }
        scope.launch { app.notes.all.collect(overlays::onNotes) }
        // The session still going, on the panel: again with each new line, and each minute so it ends when its gap has passed.
        scope.launch {
            combine(app.database.lines().recent(1), minutes(), frontGame) { rows, _, playing -> rows.firstOrNull() to playing }.collect { (latest, playing) ->
                // A game just opened has no line kept yet: the panel names it, not the game the newest line came from.
                if (playing != null && latest?.gameId != playing.id) overlays.onSession(playing.title, null)
                else overlays.onSession(latest?.gameName, withContext(Dispatchers.IO) { sessionLine(latest) })
            }
        }
        scope.launch { captureLoop() }
    }

    private fun minutes() = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /**
     * The newest line's session, while it is still going. Counted from the
     * last [SESSION_LOOK_BACK_S] of lines alone, so the paces start afresh and
     * may differ a little from the Stats screen's, which learns them from all.
     */
    private suspend fun sessionLine(latest: GameLine?): String? {
        val now = System.currentTimeMillis() / 1000.0
        if (latest == null || now - latest.timestamp > ReadingStats.SESSION_GAP_SECONDS) return null
        val left = if (app.settings.prefs.value.appsInReadingTime) emptySet() else app.database.games().appIds().first().toSet()
        val stamps = app.database.lines().stampsSince(now - SESSION_LOOK_BACK_S).filter { it.gameId !in left }
            .map { ReadingStats.Stamp(it.timestamp, ReadingStats.characters(it.text), it.gameId) }
        val session = ReadingStats.summarise(stamps, ZoneId.systemDefault()).sessions
            .lastOrNull { it.gameId == latest.gameId }?.takeIf { GameStats.live(it, now) } ?: return null
        val cards = GameStats.sessionCards(listOf(session), app.database.cards().madeSince((session.start * 1000).toLong()))[session] ?: 0
        return GameStats.strip("", session, cards)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A window that keeps its contents from accessibility (DroidDeck's game screen) still says whose it is here.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.windowId >= 0) {
            event.packageName?.toString()?.takeIf { it.isNotEmpty() }?.let { packageOfWindow[event.windowId] = it }
        }
        val now = asked ?: return
        val from = event.packageName?.toString() ?: return
        if (from == packageName || from == "com.android.systemui") return
        when (event.eventType) {
            // Another app, or another window of this one: the lines read are not on screen any more. The same
            // window reports a change too when it gets focus back from lookup mode; that changes nothing.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (from != now.packageName || (!now.continuous && event.windowId != now.windowId)) endAsked()
            // The text moved under the strips.
            // Some apps report a scroll as they settle after the reading; only a later one is the user's.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (
                from == now.packageName && !now.continuous && SystemClock.uptimeMillis() - now.startedAt > SCROLL_GRACE_MS
            ) {
                endAsked()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlays.isInitialized) overlays.onSystemTheme()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        if (!_running.value) return
        _running.value = false
        if (instance === this) instance = null
        _asking.value = false
        _pipeline.value = null
        if (::overlays.isInitialized) overlays.destroy()
        scope.cancel()
        shots.execute { thumbnails.close() }
        shots.shutdown()
    }

    // ── the controller ───────────────────────────────────────────────────────

    /**
     * Button presses, heard whatever window has focus. The shortcut starts and
     * stops lookup mode. Android cannot hide a pair of buttons from the game,
     * which is why the default is the two stick clicks: emulators leave them
     * unbound. A shortcut of one button is kept from the game instead
     * ([oneButton]).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Back first takes away the strips of a reading asked for, before it reaches the app.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && asked?.continuous == false && !controller.state.value.controllerMode) {
            if (event.action == KeyEvent.ACTION_UP) endAsked()
            return true
        }
        val (first, second) = when (app.settings.prefs.value.shortcut) {
            Prefs.SHORTCUT_L1_R1 -> KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_R1
            Prefs.SHORTCUT_L2_R2 -> KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_R2
            Prefs.SHORTCUT_SELECT_START -> KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_START
            Prefs.SHORTCUT_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT
            Prefs.SHORTCUT_START -> KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_START
            Prefs.SHORTCUT_L3 -> KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_THUMBL
            Prefs.SHORTCUT_R3 -> KeyEvent.KEYCODE_BUTTON_THUMBR to KeyEvent.KEYCODE_BUTTON_THUMBR
            else -> KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_THUMBR
        }
        if (first == second && event.keyCode == first) return oneButton(event)
        if (!KeyEvent.isGamepadButton(event.keyCode) && event.keyCode !in DPAD) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            down += event.keyCode
            if (first != second && first in down && second in down && !chordFired) {
                chordFired = true
                shortcutDown(anyApp = true)
            } else if (event.keyCode != first && event.keyCode != second && event.repeatCount == 0) {
                // A button meant for the game: the word looked up by touch is done with. Not one pointing the wheel.
                val state = controller.state.value
                if (!state.controllerMode && state.selected && !overlays.wheelUp) controller.clear()
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            down -= event.keyCode
            // The first of the pair let go ends the press.
            if (chordFired && !chordLetGo && (event.keyCode == first || event.keyCode == second)) {
                chordLetGo = true
                shortcutUp(anyApp = true)
            }
            if (first !in down && second !in down) {
                chordFired = false
                chordLetGo = false
            }
        }
        return false
    }

    /** Whether the press of the one-button shortcut now held is ours, so its release is too. */
    private var oneButtonOurs = false

    /** One of the pair of the shortcut has been let go since the pair was pressed. */
    private var chordLetGo = false

    private val shortcutHold = ShortcutHold()
    private var holdJob: Job? = null

    /**
     * The button of a one-button shortcut. While a game is in front, or lookup
     * is on, it starts and stops lookup and the game never receives it;
     * anywhere else it is the app's as ever.
     */
    private fun oneButton(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            oneButtonOurs = controller.state.value.controllerMode || front != null || asked != null
            if (oneButtonOurs) shortcutDown(anyApp = false)
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (oneButtonOurs) shortcutUp(anyApp = false)
            return oneButtonOurs.also { oneButtonOurs = false }
        }
        return oneButtonOurs
    }

    /**
     * The shortcut went down. Over a game it waits to see whether it is held,
     * which opens the wheel, and is a tap only when let go soon; anywhere else
     * it acts at once, as it always has.
     */
    private fun shortcutDown(anyApp: Boolean) {
        holdJob?.cancel()
        when {
            // A box being drawn, or the wheel waiting to be pointed at: the shortcut puts it away.
            overlays.picking -> overlays.closePicker()
            overlays.wheelShown -> closeOverlay()
            overlays.canOpenWheel -> {
                shortcutHold.press(SystemClock.uptimeMillis())
                holdJob = scope.launch {
                    delay(ShortcutHold.HOLD_MS)
                    if (shortcutHold.holdReached(SystemClock.uptimeMillis()) && !overlays.openWheel()) shortcutHold.cancelHold()
                }
            }
            // A note being written in the box from the wheel: nothing else opens over it.
            overlays.wheelUp -> closeOverlay()
            else -> toggleLookup(anyApp)
        }
    }

    /** The shortcut was let go: a tap turns lookup on or off, or closes the box from the wheel with it; a hold picks on the wheel. */
    private fun shortcutUp(anyApp: Boolean) {
        holdJob?.cancel()
        when (shortcutHold.release()) {
            ShortcutHold.Release.TAP -> if (overlays.wheelUp) closeOverlay() else toggleLookup(anyApp)
            ShortcutHold.Release.HOLD -> overlays.letGoWheel()
            ShortcutHold.Release.NONE -> Unit
        }
    }

    /** The shortcut closes what it opened: the wheel or the box it opened, and lookup under them. */
    private fun closeOverlay() {
        overlays.putAwayWheel()
        if (controller.state.value.controllerMode) controller.exitControllerMode()
    }

    /** A question about the game in front, asked from the wheel. */
    private fun askAboutGame(question: String) {
        val game = front?.game ?: run {
            overlays.onGameAnswer(null, "There is no game in front to ask about.")
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.asker.ask(game.id, game.title, question, withScreen = false) } }
            val row = result.getOrNull()
            val sources = row?.let(Asker::sources).orEmpty().map { it.title.ifBlank { android.net.Uri.parse(it.url).host ?: it.url } }
            overlays.onGameAnswer(
                row?.let { it.answer + if (sources.isEmpty()) "" else "\n\nFrom " + sources.distinct().joinToString(", ") },
                result.exceptionOrNull()?.let { it.message ?: "That did not work. Try again." },
            )
        }
    }

    /**
     * Starts lookup mode, or stops it. With [anyApp], an ordinary app in front
     * is read first. A press in a game where nothing has been read yet does
     * nothing: the message it used to bring sat over the game's text, which
     * was then not read from under it.
     */
    private fun toggleLookup(anyApp: Boolean) {
        val state = controller.state.value
        when {
            // A box is being drawn: the shortcut puts it away, as it would lookup.
            overlays.picking -> overlays.closePicker()
            state.controllerMode -> controller.exitControllerMode()
            (front != null || asked != null) && state.lines.isNotEmpty() -> controller.enterControllerMode()
            // An ordinary app, read on demand: read it now, then hand the controller the cursor.
            front == null -> if (anyApp) ask(thenController = true)
            // Nothing read in a game with a dialogue box: the box may be in the wrong place, and with no
            // line there is no lookup to change it from. It is shown, to keep, draw again or take away.
            front?.game?.let { app.dialogueBoxes[it.id] } != null -> pickRegion()
        }
    }

    /**
     * Taps a point of a screen as a finger would, once the window on top
     * there is one of ours. The tap is for the lookup window in the corner of
     * the game's screen; sent a moment before that window was up, it reached
     * the game under it, and a visual novel takes a tap as "next line". If the
     * window never comes, nothing is tapped.
     */
    private fun tapOwnWindow(display: Int, x: Int, y: Int) {
        scope.launch {
            repeat(TAP_TRIES) {
                if (ownWindowAt(display, x, y)) {
                    val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                    val tap = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 30))
                        .setDisplayId(display)
                        .build()
                    if (!dispatchGesture(tap, null, null)) Log.w(TAG, "the tap that brings the controller back was refused")
                    return@launch
                }
                delay(TAP_RETRY_MS)
            }
            Log.w(TAG, "the lookup window was not up to be tapped")
        }
    }

    /** Whether a finger at this point of the screen would touch a window of ours, not the app under it. */
    private fun ownWindowAt(display: Int, x: Int, y: Int): Boolean {
        val windows = try {
            windowsOnAllDisplays.get(display)
        } catch (e: Exception) {
            null
        } ?: return false
        val bounds = Rect()
        // Front to back, and only windows a touch can land on: the first one holding the point takes it.
        val top = windows.firstOrNull { w -> w.getBoundsInScreen(bounds).let { bounds.contains(x, y) } } ?: return false
        return top.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
    }

    // ── readings asked for ───────────────────────────────────────────────────

    /** An ordinary app in front, which is read only when asked: its package, screen and window. */
    private class Ordinary(val packageName: String, val displayId: Int, val windowId: Int, val bounds: Rect)

    /**
     * A reading asked for over an ordinary app. Its strips stay until a tap
     * lands elsewhere, Back, or the app changes; a [continuous] one reads its
     * region again on each settled change until it is turned off.
     */
    private class Asked(
        val game: Game, val packageName: String, val displayId: Int, val windowId: Int, val continuous: Boolean,
        /** The picture it was read from, kept in memory until a card wants it written. */
        var frame: Bitmap?,
        var framePath: String?,
        val startedAt: Long = SystemClock.uptimeMillis(),
    )

    private var ordinaryFront: Ordinary? = null
    private var asked: Asked? = null

    /** While a picture is taken for a reading asked for, the read button stays out of it. */
    private var shooting = false
    private var askedJobs: List<Job> = emptyList()

    /** Whether a reading asked for is up, for the tiles. */
    private fun setAsked(next: Asked?) {
        asked?.takeIf { it !== next }?.frame?.recycle()
        asked = next
        _asking.value = next?.continuous == true
    }

    /**
     * Reads the app in front now: the tree where it has text, OCR for the
     * rest. With [region], only that part of the screen; with [continuous],
     * that region again on every settled change, stored as a game's lines are.
     */
    fun ask(region: Box? = null, continuous: Boolean = false, thenController: Boolean = false, collapse: Boolean = false) {
        Log.i(TAG, "reading asked for: region=${region != null} continuous=$continuous controller=$thenController")
        scope.launch {
            endAsked()
            // From a quick settings tile the shade is still down, and would be read instead of the app.
            if (collapse) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                delay(SHADE_MS)
            }
            val playing = findFront().also { front = it }
            if (playing != null && region == null) {
                // A game is read by itself; asking only reads it again.
                pipeline.forget()
                toast("LFG reads this game by itself.")
                return@launch
            }
            // A region of a game (a fullscreen video player counts as one) is read as asked, in its own row.
            val target = playing?.let { Ordinary(it.game.packageName, it.displayId, it.windowId, it.bounds) } ?: ordinaryFront ?: run {
                toast("There is no app in front to read.")
                return@launch
            }
            val game = playing?.game ?: withContext(Dispatchers.IO) { app.lines.gameFor(target.packageName, titleOf(target.packageName), app = true) }
            if (game.reading == Game.READ_OFF) {
                toast("Reading is off for ${game.title} in Settings › Games and apps.")
                return@launch
            }
            if (continuous && region != null) {
                startContinuous(game, target, region)
            } else {
                // The read button comes back however the reading ends.
                try {
                    readOnce(game, target, region, thenController)
                } finally {
                    shooting = false
                }
            }
        }
    }

    private suspend fun readOnce(game: Game, target: Ordinary, region: Box?, thenController: Boolean) {
        // Nothing of ours may be in the picture, or on the text the tree is asked about.
        controller.clear()
        overlays.onScreen(target.displayId, false, false)
        shooting = true
        overlays.showReadButton(null)
        delay(OVERLAY_SETTLE_MS)

        val window = windowsOnAllDisplays.let { all ->
            (0 until all.size()).asSequence().flatMap { all.valueAt(it).asSequence() }.firstOrNull { it.id == target.windowId }
        }
        val tree = withContext(Dispatchers.IO) { TreeReader.read(window?.root) }
        val screen = Box(target.bounds.left, target.bounds.top, target.bounds.right, target.bounds.bottom)
        val treeLines = TreeText.lines(tree.nodes, screen)

        // Android gives a service about three screenshots a second; one refused is tried again once.
        val shot = if (Readers.reader != null) {
            shoot(target)?.takeIf { it.bitmap != null || it.secure } ?: run {
                delay(INTERVAL_MS)
                shoot(target)
            }
        } else {
            null
        }
        val reader = Readers.reader
        val ocrLines = if (shot?.bitmap != null && reader != null) {
            withContext(Dispatchers.Default) { readPixels(reader, shot.bitmap, shot.offsetX, shot.offsetY, region) }
        } else {
            emptyList()
        }
        var lines = TreeText.preferTree(treeLines, ocrLines, tree.sensitive)
        if (region != null) lines = TreeText.within(lines, region)
        shooting = false
        // Counts only: what an app shows can be private, and the log is readable by anyone with adb.
        Log.i(TAG, "read on demand: ${treeLines.size} from the tree, ${ocrLines.size} by OCR, ${tree.sensitive.size} sensitive, kept ${lines.size}, secure=${shot?.secure}")
        if (lines.isEmpty()) {
            shot?.bitmap?.recycle()
            toast(
                when {
                    shot?.secure != true -> "No text was found here."
                    shot.bitmap == null -> "This app blocks pictures of its screen, and shares no text here."
                    else -> "The screen came back black: the app may block pictures of it, or it was dark. Try again when something is showing."
                },
            )
            return
        }
        if (shot?.secure == true && shot.bitmap == null) toast("This app blocks pictures of its screen; only the text it shares was read.")

        val fromTree = treeLines.toHashSet()
        val now = Asked(game, target.packageName, target.displayId, target.windowId, continuous = false, frame = shot?.bitmap, framePath = null)
        val keep = app.settings.prefs.value.keepAppLines
        val saved = if (keep) withContext(Dispatchers.IO) {
            now.framePath = now.frame?.let(::writeFrame)
            lines.map { line ->
                val source = if (line in fromTree) LineStore.SOURCE_TREE else LineStore.SOURCE_OCR
                app.lines.saveLine(
                    game, line.text, source, if (source == LineStore.SOURCE_OCR) reader?.name.orEmpty() else "",
                    now.framePath, target.displayId, line.box,
                )
            }
        } else {
            null
        }
        setAsked(now)
        controller.setLines(
            lines.mapIndexed { i, line ->
                LookupLine(
                    line.text, saved?.getOrNull(i)?.id, now.framePath, game.title, line,
                    fromApp = true, source = if (line in fromTree) LineStore.SOURCE_TREE else LineStore.SOURCE_OCR, gameId = game.id,
                )
            },
        )
        overlays.onScreen(target.displayId, true, true, asking = true)
        if (thenController) controller.enterControllerMode()
    }

    /** One region of the app read on every settled change, as a game is, until it is turned off. */
    private fun startContinuous(game: Game, target: Ordinary, region: Box) {
        val regional = ReadingPipeline(app.lines, File(filesDir, "frames"), matchLength = { app.dictionaries.matchLength(it) })
        setAsked(Asked(game, target.packageName, target.displayId, target.windowId, continuous = true, frame = null, framePath = null))
        toast("Reading this region until you turn it off.", avoid = region)
        // The box was picked over text already showing: it is read at once, not after a second picture.
        regional.readNext()
        val reading = scope.launch {
            regional.latest.collect { r ->
                if (r == null || asked?.continuous != true) return@collect
                controller.setLines(
                    r.lines.mapIndexed { i, line ->
                        LookupLine(line.text, r.saved.getOrNull(i)?.id, r.framePath, r.game.title, line, fromApp = true, gameId = r.game.id)
                    },
                )
                overlays.onScreen(r.displayId, true, true, asking = true)
            }
        }
        val looking = scope.launch {
            var blackLooks = 0
            while (isActive && asked?.continuous == true) {
                delay(INTERVAL_MS)
                if (Readers.reader == null || controller.state.value.controllerMode) continue
                val shot = shoot(target, quiet = true) ?: continue
                if (shot.secure) {
                    shot.bitmap?.recycle()
                    // Android 14 says when a window is secure; before it, a secure window is only black,
                    // and so is a fade, a loading screen or a dark scene. Black for a while is the app's doing.
                    if (shot.bitmap == null || ++blackLooks * INTERVAL_MS >= BLACK_GIVE_UP_MS) {
                        toast("This app blocks pictures of its screen, so LFG cannot read it.")
                        endAsked()
                        break
                    }
                    continue
                }
                blackLooks = 0
                val whole = shot.bitmap ?: continue
                val left = (region.left - shot.offsetX).coerceIn(0, whole.width - 1)
                val top = (region.top - shot.offsetY).coerceIn(0, whole.height - 1)
                val width = (region.right - shot.offsetX).coerceIn(left + 1, whole.width) - left
                val height = (region.bottom - shot.offsetY).coerceIn(top + 1, whole.height) - top
                val cut = try {
                    Bitmap.createBitmap(whole, left, top, width, height)
                } catch (_: Exception) {
                    null
                }
                try {
                    if (cut != null) withContext(Dispatchers.Default) {
                        regional.onFrame(
                            cut, game, target.displayId, System.currentTimeMillis(), left + shot.offsetX, top + shot.offsetY,
                            // A whole-screen picture has our notice in it.
                            ours = if (shot.whole) listOfNotNull(overlays.noticeOn(target.displayId)) else emptyList(),
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "reading a region failed", e)
                } finally {
                    if (cut !== whole) cut?.recycle()
                    whole.recycle()
                }
            }
        }
        askedJobs = listOf(reading, looking)
    }

    /** The strips of a reading asked for go, and the app has its taps back. */
    fun endAsked() {
        if (asked == null) return
        Log.i(TAG, "reading asked for ended")
        askedJobs.forEach { it.cancel() }
        askedJobs = emptyList()
        setAsked(null)
        if (controller.state.value.controllerMode) controller.exitControllerMode()
        controller.setLines(emptyList())
        overlays.onScreen(Display.INVALID_DISPLAY, true, false)
    }

    /**
     * Shows where a box can be drawn on the app in front. Over a game it is
     * the game's box [slot]: 1, its dialogue box, which the game is then read
     * in, or 2 onwards, read when picked on the wheel. Over any other app, a
     * region to read once or to keep reading. With [fresh], the box is drawn
     * anew, not shown as it is.
     */
    fun pickRegion(
        collapse: Boolean = false,
        pad: Boolean = controller.state.value.controllerMode,
        once: Boolean = false,
        slot: Int = 1,
        fresh: Boolean = false,
    ) {
        scope.launch {
            endAsked()
            if (collapse) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                delay(SHADE_MS)
            }
            val playing = findFront().also { front = it }
            val display = (playing?.displayId ?: ordinaryFront?.displayId) ?: run {
                toast("There is no app in front to read.")
                overlays.padPickFailed()
                return@launch
            }
            pickingOn = display
            pickingPad = pad
            overlays.pickRegion(
                // A box read once is none of the game's boxes.
                display, playing?.game?.takeIf { !once }?.let { PickFor(it.id, it.title, if (fresh) null else gameBox(it, display, slot), slot) },
                lookup = pad, once = once,
            )
        }
    }

    /** The screen a box is being drawn on. */
    private var pickingOn = Display.INVALID_DISPLAY

    /** The box being drawn is drawn with the controller: what it reads then has the cursor. */
    private var pickingPad = false

    /**
     * Running while the first lines read may bring lookup back, after a dialogue box was drawn from it.
     * What was done is said when they do, or when the wait is over: said at once, on a screen whose
     * picture is the whole of it, the words would be in the way of the reading they announce.
     */
    private var afterBox: Job? = null
    private var boxSaid: String? = null

    /** The game's dialogue box on this screen, or null for a game read on its whole screen. */
    private fun dialogueBox(game: Game, displayId: Int): Box? = gameBox(game, displayId, 1)

    /** The game's box [slot] (1, its dialogue box, or 2 onwards) on this screen, or null when it is not drawn. */
    private fun gameBox(game: Game, displayId: Int, slot: Int): Box? {
        val kept = (if (slot == 1) app.dialogueBoxes[game.id] else app.extraBoxes[game.id].getOrNull(slot - 2)) ?: return null
        val screen = overlays.screen(displayId) ?: return null
        return kept.on(screen.width, screen.height)
    }

    /** The game's box [slot] picked on the wheel: read once, now, with the cursor on it. */
    private fun readBox(slot: Int) {
        val playing = front ?: return
        val box = gameBox(playing.game, playing.displayId, slot) ?: return
        ask(box, thenController = true)
    }

    /** A box was drawn on the app in front, and this chosen for it. */
    private fun onPicked(picked: Picked) {
        val lookup = controller.state.value.controllerMode
        when (picked) {
            is Picked.Cancelled -> Unit
            is Picked.Once, is Picked.Keep -> {
                // The lines under lookup are the game's own; the box's take their place, and lookup with them.
                if (lookup) controller.exitControllerMode()
                if (picked is Picked.Once) ask(picked.box, thenController = lookup) else ask((picked as Picked.Keep).box, continuous = true)
            }
            is Picked.ForGame -> if (picked.game.slot > 1) {
                // Box 2 onwards is read only when asked: it was drawn to be read, so it is read now.
                val screen = overlays.screen(pickingOn) ?: return
                app.extraBoxes.set(picked.game.gameId, picked.game.slot, DialogueBox(picked.box, screen.width, screen.height))
                if (lookup) controller.exitControllerMode()
                ask(picked.box, thenController = lookup || pickingPad)
            } else {
                val screen = overlays.screen(pickingOn) ?: return
                app.dialogueBoxes[picked.game.gameId] = DialogueBox(picked.box, screen.width, screen.height)
                readAfresh(lookup, "${picked.game.title} is read only in this box now.", picked.box)
            }
            is Picked.WholeScreen -> if (picked.game.slot > 1) {
                app.extraBoxes.set(picked.game.gameId, picked.game.slot, null)
                toast("Box ${picked.game.slot} of ${picked.game.title} is removed.")
            } else {
                app.dialogueBoxes[picked.game.gameId] = null
                readAfresh(lookup, "${picked.game.title} is read on its whole screen again.")
            }
        }
    }

    /**
     * What a game is read in has changed: the lines of the old part go, and the
     * new part is read at once. From lookup, lookup comes back on the first
     * lines read, as Rubi's overlay stays open and reads the new box.
     */
    private fun readAfresh(lookup: Boolean, said: String, box: Box? = null) {
        afterBox?.cancel()
        // Nothing is read while lookup holds the screen.
        if (lookup) controller.exitControllerMode()
        controller.setLines(emptyList())
        pipeline.forget()
        // The box was drawn round text already showing: it is read on the first picture, not after a second.
        pipeline.readNext()
        if (!lookup) {
            // Off the box, so the words do not cover what they announce.
            toast(said, avoid = box)
            return
        }
        boxSaid = said
        afterBox = scope.launch {
            delay(LOOKUP_AFTER_BOX_MS)
            // No text there now: lookup stays closed, and the next line is read as any other.
            boxSaid?.let(::toast)
            boxSaid = null
        }
    }

    /**
     * A card is being made from a line that was not kept: the line gets its
     * row, and the picture it was read from is written, now and not before.
     */
    private suspend fun prepareLine(line: LookupLine): LookupLine {
        val now = asked ?: return line
        return withContext(Dispatchers.IO) {
            if (now.framePath == null) now.framePath = now.frame?.let(::writeFrame)
            val row = app.lines.saveLine(
                now.game, line.text, line.source, if (line.source == LineStore.SOURCE_OCR) Readers.reader?.name.orEmpty() else "",
                now.framePath, now.displayId, line.geometry?.box,
            )
            line.copy(lineId = row.id, framePath = now.framePath)
        }
    }

    /** [whole]: a picture of the whole screen, with whatever of ours is drawn on it. */
    private class Shot(val bitmap: Bitmap?, val offsetX: Int, val offsetY: Int, val secure: Boolean, val whole: Boolean = false)

    /**
     * A picture of the app's window, or of its screen before Android 14. A
     * window marked secure gives none (Android 14 says so) or a black one.
     */
    private suspend fun shoot(target: Ordinary, quiet: Boolean = false): Shot? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val (bitmap, error) = windowShot(target.windowId)
            if (error == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) return Shot(null, 0, 0, secure = true)
            if (bitmap != null) return Shot(bitmap, target.bounds.left, target.bounds.top, secure = isBlack(bitmap))
        }
        // The whole screen: nothing of ours may be drawn on it.
        if (quiet && overlays.drawingOnGame) return null
        val bitmap = displayScreenshot(target.displayId) ?: return null
        return Shot(bitmap, 0, 0, secure = isBlack(bitmap), whole = true)
    }

    /** A capture that is black all over: what a secure window gives instead of itself. */
    private fun isBlack(bitmap: Bitmap): Boolean {
        val steps = 24
        for (y in 0 until steps) for (x in 0 until steps) {
            val p = bitmap.getPixel((x * 2 + 1) * bitmap.width / (steps * 2), (y * 2 + 1) * bitmap.height / (steps * 2))
            if ((p and 0xFFFFFF) != 0) return false
        }
        return true
    }

    /** OCR over the picture, or the part of it in [region], as lines in display pixels. */
    private suspend fun readPixels(reader: TextReader, bitmap: Bitmap, offsetX: Int, offsetY: Int, region: Box?): List<TextLine> {
        val left = region?.let { (it.left - offsetX).coerceIn(0, bitmap.width - 1) } ?: 0
        val top = region?.let { (it.top - offsetY).coerceIn(0, bitmap.height - 1) } ?: 0
        val right = region?.let { (it.right - offsetX).coerceIn(left + 1, bitmap.width) } ?: bitmap.width
        val bottom = region?.let { (it.bottom - offsetY).coerceIn(top + 1, bitmap.height) } ?: bitmap.height
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val rows = try {
            reader.read(Frame(width, height, pixels))
        } catch (e: Exception) {
            Log.w(TAG, "reading the app failed", e)
            emptyList()
        }
        return Blocks.group(rows.filter { it.text.isNotBlank() }.map { it.moved(left + offsetX, top + offsetY) })
    }

    /** Writes a picture for a card or a kept line, beside the game's frames. */
    private fun writeFrame(bitmap: Bitmap): String? {
        val dir = File(filesDir, "frames").apply { mkdirs() }
        val file = File(dir, "${java.util.UUID.randomUUID()}.webp")
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            file.path
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** A message over the app in front, drawn by us: a toast would be hidden when notifications are off. */
    private fun toast(text: String, avoid: Box? = null) {
        val display = asked?.displayId ?: ordinaryFront?.displayId ?: front?.displayId ?: Display.DEFAULT_DISPLAY
        overlays.notice(display, text, avoid)
    }

    // ── pictures ─────────────────────────────────────────────────────────────

    /** Screenshots are copied out of the graphics memory here, not on the thread that draws the overlays. */
    private val shots = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Since when the mark and the entry have been off the game's screen for a clean picture of it; 0 when they are not. */
    private var asideSince = 0L

    /** When they last stood aside for nothing: the clean picture had the same text. Not tried again for a while. */
    private var asideForNothing = 0L

    /** [read]: a clean picture was read and gave the lines now shown. */
    private fun endAside(read: Boolean = false) {
        if (asideSince == 0L) return
        asideForNothing = if (read) 0L else android.os.SystemClock.uptimeMillis()
        asideSince = 0L
        overlays.aside = false
    }

    private suspend fun captureLoop() {
        var lastFront: String? = null
        var missed = 0
        var ticks = 0
        var stillLooks = 0
        while (scope.isActive) {
            // A screen that has not changed for a while, or has gone on with no text in it, is looked at half
            // as often, until it does or has.
            delay(if (stillLooks >= RESTING_AFTER) INTERVAL_MS * 2 else INTERVAL_MS)
            // Whatever became of the clean picture, the mark and the entry are not kept away for long.
            if (asideSince != 0L && android.os.SystemClock.uptimeMillis() - asideSince > ASIDE_MS) endAside()
            if (++ticks % PERF_EVERY == 0) Perf.report().takeIf { it.isNotEmpty() }?.let { Log.i("LfgPerf", it) }
            val now = try {
                Perf.measure("front") { findFront() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A database error naming the game: this look is skipped, the next tries again. The game
                // that was in front still is, for all this look can tell.
                Log.w(TAG, "finding the game in front failed", e)
                continue
            }
            // While a word is looked up the controller is ours. One look that misses the game, as a window
            // changes hands, must not hand the buttons back to it mid-press: it takes a few in a row.
            if (now == null && front != null && controller.state.value.controllerMode && ++missed < LOST_LOOKS) continue
            missed = 0
            front = now
            overlays.onOwnApp(ownAppOn)
            val prefs = app.settings.prefs.value
            if (!shooting) overlays.showReadButton(ordinaryFront?.displayId?.takeIf { prefs.readButton && (now == null || asked != null) })
            // A reading asked for owns the overlays until it ends; the game's own reading waits.
            if (asked != null) {
                lastFront = null
                continue
            }
            if (now?.game?.id != lastFront) {
                lastFront = now?.game?.id
                frontGame.value = now?.game
                // The game went behind something else, or came back: the overlays follow at once.
                val reading = pipeline.latest.value
                if (now == null) controller.clear()
                // A game has come to the front: its sound is wanted from here on, and Android has to be asked first.
                else if (prefs.sentenceAudio && !now.game.app) SoundCapture.askOnce(this, now.displayId)
                overlays.onScreen(now?.displayId ?: reading?.displayId ?: Display.INVALID_DISPLAY, true, now != null)
            }
            // No reader, no picture: nothing is captured until something can read it.
            if (now == null || Readers.reader == null) continue
            // A reading asked for is taking its own picture: Android allows only about three a second.
            if (shooting) continue
            // The game is read while a word is looked up too: text that changes behind the entry is the
            // text the entry should be on (LookupController.setLines moves it there).
            val windowShot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            val shotAt = System.nanoTime()
            val window = if (windowShot) uncopiedShot(now.windowId, null) else null
            val whole = window == null
            // No picture of the whole screen is any use while a box is being drawn on it, or the wheel is over it.
            if (whole && overlays.covering) continue
            val frame = window ?: uncopiedShot(null, now.displayId) ?: continue
            Perf.add("shot", System.nanoTime() - shotAt)
            // Where this game's dialogue is, if the player has drawn it: only that is read.
            val area = dialogueBox(now.game, now.displayId)
            // A picture of the game's window has nothing of ours in it. One of the whole screen (all there is
            // before Android 14) shows our mark and entry, and cannot be read: it is only watched for the text
            // around them changing. When it has, they stand aside for one clean picture, read as any other.
            val drawn = if (whole) overlays.drawnOn(now.displayId) else emptyList()
            if (drawn.isNotEmpty()) {
                stillLooks = 0
                try {
                    val changed = withContext(Dispatchers.Default) {
                        pipeline.watch(
                            frame.hash, frame::bitmap, 0, 0, area,
                            drawn + listOfNotNull(overlays.noticeOn(now.displayId)),
                        )
                    }
                    // A game whose picture never matches its last clean one would have them blink for ever.
                    val lately = asideForNothing != 0L && android.os.SystemClock.uptimeMillis() - asideForNothing < ASIDE_AGAIN_MS
                    if (changed && !lately) {
                        Log.i(TAG, "the text changed behind the entry: reading it")
                        asideSince = android.os.SystemClock.uptimeMillis()
                        overlays.aside = true
                        // It has held still already: read on the first clean picture.
                        pipeline.readNext()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "watching a frame failed", e)
                } finally {
                    frame.close()
                }
                continue
            }
            pipeline.endWatch()
            try {
                val still = withContext(Dispatchers.Default) {
                    Perf.measure("frame") {
                    pipeline.onFrame(
                        frame.hash, frame::bitmap, now.game, now.displayId, System.currentTimeMillis(),
                        if (whole) 0 else now.bounds.left, if (whole) 0 else now.bounds.top,
                        // A picture of the game's own window has nothing else in it.
                        if (whole) now.covered else emptyList(),
                        area,
                        // A notice of ours on the screen's picture: cut out, not waited for.
                        if (whole) listOfNotNull(overlays.noticeOn(now.displayId)) else emptyList(),
                    )
                    }
                }
                stillLooks = if (still) stillLooks + 1 else 0
                // The clean picture showed nothing new to wait for: the mark and the entry come back as they were.
                if (asideSince != 0L && !pipeline.settling) endAside()
            } catch (e: Exception) {
                Log.w(TAG, "reading a frame failed", e)
            } finally {
                frame.close()
            }
        }
    }

    /** A screenshot left in graphics memory, with its fingerprint; copied out only when asked for. */
    private inner class Uncopied(private val result: ScreenshotResult, val hash: Long) : AutoCloseable {
        private var copied: Bitmap? = null
        private var closed = false

        @Synchronized
        fun bitmap(): Bitmap? = if (closed) null else copied ?: copyOut(result)?.also { copied = it }

        @Synchronized
        override fun close() {
            closed = true
            copied?.recycle()
            copied = null
            result.hardwareBuffer.close()
        }
    }

    /** Only the shots thread draws with it. */
    private val thumbnails = Thumbnails()

    /** The game's own window ([windowId], from Android 14) or a whole screen ([displayId]), not yet copied. */
    private suspend fun uncopiedShot(windowId: Int?, displayId: Int?): Uncopied? = suspendCancellableCoroutine { continuation ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = Uncopied(result, Perf.measure("shot.hash") { thumbnails.hash(result.hardwareBuffer, result.colorSpace) })
                if (continuation.isActive) continuation.resume(shot) else shot.close()
            }

            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
        try {
            if (windowId != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) takeScreenshotOfWindow(windowId, shots, callback)
                else continuation.resume(null)
            } else {
                takeScreenshot(displayId!!, shots, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "taking a screenshot threw", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /**
     * The game in front: the topmost app window, on any screen, that is not
     * this app, a launcher, or an app the user switched reading off for. On a
     * two-screen handheld that is not always the default display, nor always
     * the window with focus.
     */
    private suspend fun findFront(): Front? {
        if (gamesChanged) {
            gamesChanged = false
            games.clear()
            namedGames.clear()
            unread.clear()
        }
        class Candidate(
            val window: AccessibilityWindowInfo, val displayId: Int, val name: String, val fullscreen: Boolean, val covered: List<Box>,
        )
        val candidates = ArrayList<Candidate>()
        try {
            val all = windowsOnAllDisplays
            val alive = HashSet<Int>()
            val own = HashSet<Int>()
            for (i in 0 until all.size()) {
                val windows = all.valueAt(i)
                // Windows come front to back: the first app window is the one on top of its screen.
                val top = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: continue
                alive += top.id
                val name = packageOfWindow[top.id]
                    ?: (top.root?.packageName?.toString() ?: hostTitled(top.title?.toString()))?.also { packageOfWindow[top.id] = it }
                if (name == null) {
                    // Said once for each window: why a game on screen is not being read.
                    if (unowned.add(top.id)) Log.i(TAG, "window '${top.title}' on screen ${all.keyAt(i)} shows no contents and no known app has that name: ${hostTitles?.keys}")
                    continue
                }
                if (name == packageName) own += all.keyAt(i)
                // A status or navigation bar on the screen means an ordinary app; a game hides them.
                // A phone held sideways hides them for every app, though: an app
                // whose window holds text items of its own is ordinary, while a
                // game draws its text into one picture.
                val bars = windows.any { w ->
                    w.type == AccessibilityWindowInfo.TYPE_SYSTEM && Rect().also(w::getBoundsInScreen).let { !it.isEmpty && it.width() > it.height() * 4 }
                } || showsTextItems(top)
                // Everything in front of the game that is not ours: its text must not be read as the game's.
                val covered = windows.takeWhile { it.id != top.id }
                    .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                    .map { w -> Rect().also(w::getBoundsInScreen) }
                    .filter { !it.isEmpty }
                    .map { Box(it.left, it.top, it.right, it.bottom) }
                candidates += Candidate(top, all.keyAt(i), name, !bars, covered)
            }
            ownAppOn = own
            if (packageOfWindow.size > 32) packageOfWindow.keys.retainAll(alive)
            if (textItems.size > 32) textItems.keys.retainAll(alive)
        } catch (e: Exception) {
            Log.w(TAG, "could not list windows", e)
            ordinaryFront = null
            return null
        }
        var ordinary: Ordinary? = null
        fun noteOrdinary(c: Candidate) {
            if (ordinary == null && c.name !in neverAsked) ordinary = Ordinary(c.name, c.displayId, c.window.id, Rect().also(c.window::getBoundsInScreen))
        }
        for (c in candidates) {
            if (c.name in ignored) {
                // Settings and AnkiDroid are never games, but their text can still be asked for.
                noteOrdinary(c)
                continue
            }
            val known = games[c.name] ?: if (c.name in unread) null else withContext(Dispatchers.IO) { app.lines.gameOrNull(c.name) }
            val read = when (known?.reading ?: Game.READ_AUTO) {
                Game.READ_ON -> true
                Game.READ_OFF, Game.READ_ON_DEMAND -> false
                else -> {
                    // A swipe from the edge slides the system bars over a game for a few seconds; it is still the game.
                    val now = SystemClock.uptimeMillis()
                    // A question or a note being written in our own card brings the keyboard, and the bars with it,
                    // for as long as it is written: the game under it is still the game.
                    if (c.fullscreen || (overlays.typing && front?.game?.packageName == c.name)) lastFullscreen[c.name] = now
                    c.fullscreen || now - (lastFullscreen[c.name] ?: -BARS_GRACE_MS) < BARS_GRACE_MS || declaredGame(c.name)
                }
            }
            if (!read) {
                // Remembered so an app that is never read costs no database lookup each time round.
                if (known == null) unread += c.name else games[c.name] = known
                if (known?.reading != Game.READ_OFF) noteOrdinary(c)
                continue
            }
            unread -= c.name
            val game = games[c.name] ?: withContext(Dispatchers.IO) {
                val label = titleOf(c.name)
                val row = app.lines.gameFor(c.name, label)
                // A row made before the app's label could be read still carries its package as its name.
                if (row.title == row.packageName && label != c.name) {
                    row.copy(title = label).also {
                        app.database.games().update(it)
                        app.database.games().renameLines(it.id, label)
                    }
                } else {
                    row
                }
            }.also { games[c.name] = it }
            val bounds = Rect().also(c.window::getBoundsInScreen)
            ordinaryFront = ordinary
            return Front(namedInside(game), c.displayId, c.window.id, bounds, c.covered)
        }
        ordinaryFront = ordinary
        return null
    }

    /** In an emulator or a Steam player, the game a front-end, Eden or Steam says it has open; else the app's own row. */
    private suspend fun namedInside(host: Game): Game {
        val named = app.gameFinder.nameFor(host.packageName)
        // Said when it changes: which app is in front and what it is taken to be running.
        val said = host.packageName + " / " + host.title + " / " + named?.title + " / " + named?.source
        if (said != lastNamed) {
            lastNamed = said
            Log.i(TAG, "in front: $said")
        }
        if (named == null) return host.also { app.characterNames.onGameSeen(it.id, "") }
        val key = host.packageName + "\n" + named.key
        return (namedGames[key] ?: withContext(Dispatchers.IO) {
            app.lines.namedGame(host, named.key, named.title, named.source)
        }.also { namedGames[key] = it }).also { game ->
            coverFor(game, named.steamAppId)
            app.characterNames.onGameSeen(game.id, named.steamAppId)
        }
    }

    /** Steam games whose header has been asked for since the service started: each once. */
    private val coversAsked = HashSet<String>()

    private fun coverFor(game: Game, appId: String) {
        if (appId.isEmpty() || !coversAsked.add(appId)) return
        scope.launch(Dispatchers.IO) {
            if (Covers.noteSteam(this@LfgAccessibilityService, game.id, appId)) Covers.fetchSteam(this@LfgAccessibilityService, appId)
        }
    }

    private val declared = HashMap<String, Boolean>()

    /** Whether an app says it is a game, or is an emulator known by its package name. */
    /** Looked at again every few seconds, as an app's screens change. */
    private fun showsTextItems(window: AccessibilityWindowInfo): Boolean {
        val now = SystemClock.uptimeMillis()
        textItems[window.id]?.let { (at, shows) -> if (now - at < TEXT_ITEMS_MS) return shows }
        var found = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        window.root?.let(queue::add)
        var seen = 0
        while (queue.isNotEmpty() && found < TEXT_ITEMS && seen < 400) {
            val node = queue.removeFirst()
            seen++
            if (node.isVisibleToUser && !node.text.isNullOrBlank()) found++
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return (found >= TEXT_ITEMS).also { textItems[window.id] = now to it }
    }

    private fun declaredGame(packageName: String): Boolean = declared.getOrPut(packageName) {
        if (Hosts.isEmulator(packageName)) return@getOrPut true
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            @Suppress("DEPRECATION")
            info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun ignoredPackages(): Set<String> {
        val launchers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }
            // DroidDeck offers itself as a home screen too; it is still where the game is.
            .filterNot { Hosts.isEmulator(it) }
        return (launchers + listOf(packageName, "com.android.systemui", "com.android.settings", "com.ichi2.anki", "android")).toSet()
    }

    /** Never read even when asked: this app, the system's own bars and dialogs, and launchers. */
    private val neverAsked: Set<String> by lazy {
        val launchers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }
            // DroidDeck offers itself as a home screen too; it is still where the game is.
            .filterNot { Hosts.isEmulator(it) }
        (launchers + listOf(packageName, "com.android.systemui", "android")).toSet()
    }

    private var lastNamed: String? = null
    private var hostTitles: Map<String, String>? = null
    private val unowned = HashSet<Int>()
    private var hostTitlesAt = 0L

    /**
     * The emulator or Steam player whose name a window carries, for a window
     * with nothing in it to ask: DroidDeck's game screen shows accessibility
     * no contents, only its title.
     */
    private fun hostTitled(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val now = SystemClock.uptimeMillis()
        val known = hostTitles?.takeIf { now - hostTitlesAt < HOST_TITLES_MS } ?: try {
            packageManager.getInstalledApplications(0)
                .filter { Hosts.isEmulator(it.packageName) }
                .associate { packageManager.getApplicationLabel(it).toString() to it.packageName }
        } catch (_: Exception) {
            emptyMap()
        }.also {
            hostTitles = it
            hostTitlesAt = now
        }
        return known[title]
    }

    private fun titleOf(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun copy(result: ScreenshotResult): Bitmap? = try {
        copyOut(result)
    } finally {
        result.hardwareBuffer.close()
    }

    /** A software copy of a screenshot, leaving its buffer open. */
    private fun copyOut(result: ScreenshotResult): Bitmap? = try {
        Perf.measure("shot.copy") {
            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.let { hardware ->
                hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not copy the screenshot", e)
        null
    }

    /** A software copy of a screen, or null when Android refuses (it allows about three a second). */
    private suspend fun displayScreenshot(displayId: Int): Bitmap? = suspendCancellableCoroutine { continuation ->
        try {
            takeScreenshot(displayId, shots, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = copy(result)
                    if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot threw", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /** Android 14's picture of one window, or the reason it gave for none. */
    private suspend fun windowShot(windowId: Int): Pair<Bitmap?, Int?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null to null
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshotOfWindow(windowId, shots, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = copy(result)
                        if (continuation.isActive) continuation.resume(bitmap to null) else bitmap?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null to errorCode)
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "takeScreenshotOfWindow threw", e)
                if (continuation.isActive) continuation.resume(null to null)
            }
        }
    }

    /** Old screens go (data/PictureKeeper.kt); the lines they came from never do. */
    private suspend fun clearOldPictures() {
        try {
            app.pictures.sweep(app.settings.prefs.value.keepScreensDays)
        } catch (e: Exception) {
            Log.w(TAG, "clearing old pictures failed", e)
        }
    }

    companion object {
        private const val TAG = "LfgService"
        private const val SESSION_LOOK_BACK_S = 12 * 3600.0
        private const val PREFS = "service"
        private const val KEY_AWAITING = "awaiting"

        // Android answers a service's screenshot request at most once per 333 ms.
        private const val INTERVAL_MS = 350L

        /** The longest the mark and the entry stay off the screen for a clean picture of it. */
        private const val ASIDE_MS = 2_000L

        /** After standing aside for a picture that had nothing new, how long before they do again. */
        private const val ASIDE_AGAIN_MS = 10_000L

        /** How long a region read waits through a black screen before taking it for an app that blocks pictures. */
        private const val BLACK_GIVE_UP_MS = 15_000L

        /** Timings go to the log (tag LfgPerf) every this many looks at the screen. */
        private const val PERF_EVERY = 60

        /** Looks at an unchanged screen (about ten seconds of them) before the pace halves. */
        private const val RESTING_AFTER = 28
        private const val BARS_GRACE_MS = 8_000L

        /** Text items that make a window an ordinary app's: a game's own menus seldom show this many as items. */
        private const val TEXT_ITEMS = 5
        private const val TEXT_ITEMS_MS = 5_000L

        /** How long the names of the installed emulators are trusted: one may be installed while the service runs. */
        private const val HOST_TITLES_MS = 60_000L

        /** Looks in a row that must miss the game before lookup mode gives it up: about a second. */
        private const val LOST_LOOKS = 3

        /** How long after a dialogue box is drawn from lookup its first lines may bring lookup back: three looks. */
        private const val LOOKUP_AFTER_BOX_MS = 2000L

        /** How often, and how many times, the lookup window is looked for before its tap: a third of a second in all. */
        private const val TAP_RETRY_MS = 30L
        private const val TAP_TRIES = 12

        private val DPAD = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** A quick settings tile's swipe away of the shade takes this long. */
        private const val SHADE_MS = 450L

        /** Scrolls an app reports this soon after a reading are its own settling, not the user's. */
        private const val SCROLL_GRACE_MS = 800L

        /** Our windows take a frame or two to leave the screen before a picture of it is taken. */
        private const val OVERLAY_SETTLE_MS = 60L

        /** The service while it runs, for the quick settings tiles. */
        @Volatile
        var instance: LfgAccessibilityService? = null
            private set

        private val _asking = MutableStateFlow(false)

        /** A region of an app is being read on every change. */
        val asking: StateFlow<Boolean> = _asking

        private val _pipeline = MutableStateFlow<ReadingPipeline?>(null)
        val pipeline: StateFlow<ReadingPipeline?> = _pipeline

        /** Set by the Games page: the service re-reads its rows before the next picture. */
        @Volatile
        var gamesChanged = false

        /** Whether the switch is on in Android's settings, which holds even before the service has connected. */
        fun enabled(context: Context): Boolean {
            val wanted = ComponentName(context, LfgAccessibilityService::class.java)
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            // Android writes the class name in full or with a leading dot; parsing accepts both.
            return on.split(':').any { ComponentName.unflattenFromString(it) == wanted }
        }

        /** Remember that set-up sent the user to Android's page, so the service brings them back. */
        fun awaitConnection(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AWAITING, true).apply()
        }
    }
}
