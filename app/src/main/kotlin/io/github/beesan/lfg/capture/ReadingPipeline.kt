// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Bitmap
import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.SettleDetector
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.ocr.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What was read from one frame: the lines on screen, their rows in the store, and the frame itself. */
data class Reading(
    val game: Game,
    val displayId: Int,
    val lines: List<TextLine>,
    /** The row of each line, in the same order. */
    val saved: List<GameLine>,
    val framePath: String?,
    val atMs: Long,
    /** When each line began to appear on screen, which is before it settled and was read. */
    val appearedAtMs: List<Long> = emptyList(),
)

/**
 * Frame in, lines out. Every picture goes through the cheap probe; only a
 * frame whose text has changed and then held still is read properly. The text
 * is therefore ready before a word is asked for.
 */
class ReadingPipeline(
    private val store: LineStore,
    private val framesDir: File,
    private val reader: () -> TextReader? = { Readers.reader },
    private val probe: () -> ChangeProbe = { Readers.probe },
    private val detector: SettleDetector = SettleDetector(),
) {
    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest

    private var lastGame = ""

    /** Reused between frames: eight megabytes three times a second is too much to hand the collector. */
    private var buffer: IntArray? = null

    /** Forget what was on screen, so the next settled frame is read even if it is the same text. */
    fun forget() {
        detector.reset()
        _latest.value = null
    }

    /**
     * Takes ownership of nothing: the caller recycles [bitmap] afterwards.
     * [offsetX] and [offsetY] place the picture on its display, for a picture
     * of one window rather than of the whole screen.
     */
    suspend fun onFrame(
        bitmap: Bitmap, game: Game, displayId: Int, nowMs: Long, offsetX: Int = 0, offsetY: Int = 0,
        covered: List<Box> = emptyList(),
    ) {
        val engine = reader() ?: return
        val size = bitmap.width * bitmap.height
        val pixels = buffer?.takeIf { it.size == size } ?: IntArray(size).also { buffer = it }
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val frame = Frame(bitmap.width, bitmap.height, pixels)
        if (game.id != lastGame) {
            // A different app in front: whatever settled before is not on this screen.
            detector.reset()
            lastGame = game.id
        }
        FrameRing.offer(frame, nowMs)
        val signature = probe().signature(frame)
        if (signature.isEmpty()) {
            // The text has gone (a scene change, a menu): nothing is on screen to tap.
            detector.offer(signature, nowMs)
            if (_latest.value?.lines?.isNotEmpty() == true) _latest.value = Reading(game, displayId, emptyList(), emptyList(), null, nowMs)
            return
        }
        if (!detector.offer(signature, nowMs)) return

        val rows = engine.read(frame).filter { it.text.isNotBlank() }.map { it.moved(offsetX, offsetY) }
            // Text under another window (a system dialog on a whole-screen picture) is not the game's.
            .filter { line -> covered.none { it.intersects(line.box) } }
        // A reader returns rows; the game shows sentences. From here on a line is a whole piece of text.
        val lines = Blocks.group(rows)
        if (lines.isEmpty()) return
        // A line still on screen from the last reading keeps its row; only new text is saved.
        val carried = _latest.value?.takeIf { it.game.id == game.id }
            ?.let { old -> old.lines.map { it.text }.zip(old.saved).toMap() }
            // Nothing remembered (the app restarted, or the game came back to the front): the line
            // on screen may be one already saved a moment ago, and must not be counted twice.
            ?: store.recent(game).associateBy { it.lineText }
        // The frame is taken before any mark is drawn on the game, so it is the card's picture as is.
        val framePath = saveFrame(bitmap)
        val appearedNow = detector.changeStartedMs.takeIf { it in 1..nowMs } ?: nowMs
        val carriedTimes = _latest.value?.takeIf { it.game.id == game.id }
            ?.let { old -> old.lines.map { it.text }.zip(old.appearedAtMs).toMap() } ?: emptyMap()
        val saved = lines.map { line ->
            carried[line.text] ?: store.saveLine(
                game = game, text = line.text, source = LineStore.SOURCE_OCR, engine = engine.name,
                screenshotPath = framePath, displayId = displayId, box = line.box, nowMs = nowMs,
            )
        }
        _latest.value = Reading(game, displayId, lines, saved, framePath, nowMs, lines.map { carriedTimes[it.text] ?: appearedNow })
    }

    private suspend fun saveFrame(frame: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            framesDir.mkdirs()
            val file = File(framesDir, "${UUID.randomUUID()}.webp")
            file.outputStream().use { frame.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            file.path
        } catch (_: Exception) {
            null
        }
    }
}
