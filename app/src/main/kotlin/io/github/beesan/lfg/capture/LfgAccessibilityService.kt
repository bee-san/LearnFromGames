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
import android.view.accessibility.AccessibilityWindowInfo
import io.github.beesan.lfg.App
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.LookupController
import io.github.beesan.lfg.lookup.LookupLine
import io.github.beesan.lfg.overlay.OverlayHost
import io.github.beesan.lfg.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * The one switch. This service takes pictures of the game's screen, draws the
 * strips, mark, popup and panel over it, and hears controller buttons, all
 * without a further prompt.
 */
class LfgAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    private val packageOfWindow = HashMap<Int, String>()
    private val games = HashMap<String, Game>()
    private val unread = HashSet<String>()
    private val lastFullscreen = HashMap<String, Long>()
    private var ignored: Set<String> = emptySet()

    private var styles: List<EntryHtml.Style> = emptyList()
    private val down = HashSet<Int>()
    private var chordFired = false

    override fun onServiceConnected() {
        app = application as App
        pipeline = ReadingPipeline(app.lines, File(filesDir, "frames"))
        controller = LookupController(scope, { app.dictionaries.lookup(it) }, app.cards, app.anki, app.lines)
        overlays = OverlayHost(
            service = this,
            controller = controller,
            prefs = { app.settings.prefs.value },
            chordActive = { chordFired },
            onToggleStrips = ::toggleStrips,
            onHistoryLine = { controller.setLines(listOf(it)) },
            styles = { styles },
            media = { dictionary, path -> app.dictionaries.mediaBlocking(dictionary, path) },
        )
        scope.launch { app.dictionaries.state.collect { styles = app.dictionaries.styles() } }
        ignored = ignoredPackages()
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
            combine(app.settings.prefs, app.models.meiki, app.models.screenAi) { prefs, _, _ -> prefs }.collect { prefs ->
                FrameRing.enabled = prefs.animatedPicture
                withContext(Dispatchers.Default) { Readers.refresh(this@LfgAccessibilityService, app.models, prefs) }
            }
        }
        scope.launch {
            pipeline.latest.collect { reading ->
                if (reading == null) return@collect
                controller.setLines(
                    reading.lines.mapIndexed { i, line ->
                        LookupLine(
                            line.text, reading.saved.getOrNull(i)?.id, reading.framePath, reading.game.title, line,
                            reading.appearedAtMs.getOrNull(i),
                        )
                    },
                )
                // A new line on screen ends the sound of the one before it.
                reading.appearedAtMs.maxOrNull()?.let { app.media.onNextLine(it) }
                overlays.onScreen(reading.displayId, reading.game.tapStrips, front != null)
            }
        }
        scope.launch { controller.state.collect(overlays::onState) }
        scope.launch {
            app.database.lines().recent(8).collect { rows ->
                overlays.onHistory(rows.map { LookupLine(it.lineText, it.id, it.screenshotPath, it.gameName) })
            }
        }
        scope.launch { captureLoop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

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
        _pipeline.value = null
        if (::overlays.isInitialized) overlays.destroy()
        scope.cancel()
    }

    // ── the controller ───────────────────────────────────────────────────────

    /**
     * Button presses, heard whatever window has focus. The shortcut starts and
     * stops lookup mode. Android cannot hide a chord from the game, which is
     * why the default is the two stick clicks: emulators leave them unbound.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!KeyEvent.isGamepadButton(event.keyCode) && event.keyCode !in DPAD) return false
        val (first, second) = when (app.settings.prefs.value.shortcut) {
            Prefs.SHORTCUT_L1_R1 -> KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_R1
            else -> KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_THUMBR
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            down += event.keyCode
            if (first in down && second in down && !chordFired) {
                chordFired = true
                val state = controller.state.value
                if (state.controllerMode) controller.exitControllerMode()
                else if (front != null && state.lines.isNotEmpty()) controller.enterControllerMode()
            } else if (event.keyCode != first && event.keyCode != second && event.repeatCount == 0) {
                // A button meant for the game: the word looked up by touch is done with.
                val state = controller.state.value
                if (!state.controllerMode && state.selected) controller.clear()
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            down -= event.keyCode
            if (first !in down && second !in down) chordFired = false
        }
        return false
    }

    private fun toggleStrips() {
        val current = front?.game ?: pipeline.latest.value?.game ?: return
        val next = current.copy(tapStrips = !current.tapStrips)
        games[next.packageName] = next
        overlays.onScreen(front?.displayId ?: Display.INVALID_DISPLAY, next.tapStrips, front != null)
        scope.launch(Dispatchers.IO) { app.database.games().update(next) }
    }

    // ── pictures ─────────────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        var lastFront: String? = null
        while (scope.isActive) {
            delay(INTERVAL_MS)
            val now = findFront()
            front = now
            if (now?.game?.packageName != lastFront) {
                lastFront = now?.game?.packageName
                // The game went behind something else, or came back: the overlays follow at once.
                val reading = pipeline.latest.value
                if (now == null) controller.clear()
                overlays.onScreen(now?.displayId ?: reading?.displayId ?: Display.INVALID_DISPLAY, now?.game?.tapStrips ?: true, now != null)
            }
            // No reader, no picture: nothing is captured until something can read it.
            if (now == null || Readers.reader == null) continue
            // While a word is being read, the screen is the player's; and the cursor must not move under them.
            if (controller.state.value.controllerMode) continue

            val windowShot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            // A picture of the whole screen would show our own mark and popup; one of the game's window does not.
            if (!windowShot && overlays.drawingOnGame) continue
            val bitmap = (if (windowShot) windowScreenshot(now.windowId) else null)
            val whole = bitmap == null
            val frame = bitmap ?: (if (overlays.drawingOnGame) null else displayScreenshot(now.displayId)) ?: continue
            try {
                withContext(Dispatchers.Default) {
                    pipeline.onFrame(
                        frame, now.game, now.displayId, System.currentTimeMillis(),
                        if (whole) 0 else now.bounds.left, if (whole) 0 else now.bounds.top,
                        // A picture of the game's own window has nothing else in it.
                        if (whole) now.covered else emptyList(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "reading a frame failed", e)
            } finally {
                frame.recycle()
            }
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
            unread.clear()
        }
        class Candidate(
            val window: AccessibilityWindowInfo, val displayId: Int, val name: String, val fullscreen: Boolean, val covered: List<Box>,
        )
        val candidates = ArrayList<Candidate>()
        try {
            val all = windowsOnAllDisplays
            val alive = HashSet<Int>()
            for (i in 0 until all.size()) {
                val windows = all.valueAt(i)
                // Windows come front to back: the first app window is the one on top of its screen.
                val top = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: continue
                alive += top.id
                val name = packageOfWindow[top.id] ?: top.root?.packageName?.toString()?.also { packageOfWindow[top.id] = it } ?: continue
                // A status or navigation bar on the screen means an ordinary app; a game hides them.
                val bars = windows.any { w ->
                    w.type == AccessibilityWindowInfo.TYPE_SYSTEM && Rect().also(w::getBoundsInScreen).let { !it.isEmpty && it.width() > it.height() * 4 }
                }
                // Everything in front of the game that is not ours: its text must not be read as the game's.
                val covered = windows.takeWhile { it.id != top.id }
                    .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                    .map { w -> Rect().also(w::getBoundsInScreen) }
                    .filter { !it.isEmpty }
                    .map { Box(it.left, it.top, it.right, it.bottom) }
                candidates += Candidate(top, all.keyAt(i), name, !bars, covered)
            }
            if (packageOfWindow.size > 32) packageOfWindow.keys.retainAll(alive)
        } catch (e: Exception) {
            Log.w(TAG, "could not list windows", e)
            return null
        }
        for (c in candidates) {
            if (c.name in ignored) continue
            val known = games[c.name] ?: if (c.name in unread) null else withContext(Dispatchers.IO) { app.lines.gameOrNull(c.name) }
            val read = when (known?.reading ?: Game.READ_AUTO) {
                Game.READ_ON -> true
                Game.READ_OFF -> false
                else -> {
                    // A swipe from the edge slides the system bars over a game for a few seconds; it is still the game.
                    val now = SystemClock.uptimeMillis()
                    if (c.fullscreen) lastFullscreen[c.name] = now
                    c.fullscreen || now - (lastFullscreen[c.name] ?: -BARS_GRACE_MS) < BARS_GRACE_MS || declaredGame(c.name)
                }
            }
            if (!read) {
                // Remembered so an app that is never read costs no database lookup each time round.
                if (known == null) unread += c.name else games[c.name] = known
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
            return Front(game, c.displayId, c.window.id, bounds, c.covered)
        }
        return null
    }

    private val declared = HashMap<String, Boolean>()

    /** Whether an app says it is a game, or is an emulator known by its package name. */
    private fun declaredGame(packageName: String): Boolean = declared.getOrPut(packageName) {
        if (EMULATORS.any { packageName.startsWith(it) }) return@getOrPut true
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
        return (launchers + listOf(packageName, "com.android.systemui", "com.android.settings", "com.ichi2.anki", "android")).toSet()
    }

    private fun titleOf(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun copy(result: ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w(TAG, "could not copy the screenshot", e)
            null
        } finally {
            buffer.close()
        }
    }

    /** A software copy of a screen, or null when Android refuses (it allows about three a second). */
    private suspend fun displayScreenshot(displayId: Int): Bitmap? = suspendCancellableCoroutine { continuation ->
        try {
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
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

    /** From Android 14: the game's own window, without anything drawn over it. */
    private suspend fun windowScreenshot(windowId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshotOfWindow(windowId, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = copy(result)
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "takeScreenshotOfWindow threw", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** Old pictures go; the lines they came from never do. A picture a card was made from is kept. */
    private suspend fun clearOldPictures() {
        try {
            val dao = app.database.lines()
            val kept = dao.keptPictures().toSet()
            for (path in dao.stalePictures(System.currentTimeMillis() - PICTURE_DAYS * 24 * 3600 * 1000L)) {
                if (path in kept) continue
                File(path).delete()
                dao.forgetPicture(path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearing old pictures failed", e)
        }
    }

    companion object {
        private const val TAG = "LfgService"
        private const val PREFS = "service"
        private const val KEY_AWAITING = "awaiting"

        // Android answers a service's screenshot request at most once per 333 ms.
        private const val INTERVAL_MS = 350L
        private const val PICTURE_DAYS = 14
        private const val BARS_GRACE_MS = 8_000L

        // Emulators and visual-novel players, which rarely declare themselves games.
        private val EMULATORS = listOf(
            "com.retroarch", "org.ppsspp.ppsspp", "org.dolphinemu.dolphinemu", "com.github.stenzek.duckstation",
            "xyz.aethersx2.android", "com.dsemu.drastic", "me.magnum.melonds", "org.vita3k.emulator",
            "org.citra", "io.github.lime3ds", "org.azahar_emu.azahar", "org.yuzu.yuzu_emu", "dev.eden.eden_emulator",
            "org.mupen64plusae", "org.scummvm.scummvm", "org.tvp.kirikiri2", "com.winlator", "aenu.aps3e",
            "com.github.panda3ds", "com.explusalpha", "com.fastemulator", "jp.ogapee.onscripter", "org.renpy",
        )
        private val DPAD = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

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
