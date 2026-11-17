// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Bitmap
import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Perf
import io.github.beesan.lfg.core.SettleDetector
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LineStore
import io.github.beesan.lfg.ocr.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
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

    /** Every fourth row of the last picture and of this one, to tell a screen that has not changed at all. */
    private var lastRows = IntArray(0)
    private var rows = IntArray(0)

    /** What the probe made of the last picture; null when the next one must be probed whatever it shows. */
    private var lastSignature: String? = null

    /** Pictures are written behind the reading: the text is tappable before its picture is on disk. */
    private val pictures = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether [bitmap] is the last picture again, by every fourth row of it. Text is taller than
     * four rows, so no change to it can hide between them; and this costs a fortieth of the probe.
     */
    private fun unchanged(bitmap: Bitmap): Boolean {
        val step = 4
        val count = (bitmap.height + step - 1) / step
        if (rows.size != count * bitmap.width) rows = IntArray(count * bitmap.width)
        for (i in 0 until count) bitmap.getPixels(rows, i * bitmap.width, bitmap.width, 0, i * step, bitmap.width, 1)
        val same = rows.contentEquals(lastRows)
        val swap = lastRows
        lastRows = rows
        rows = swap
        return same
    }

    /** Forget what was on screen, so the next settled frame is read even if it is the same text. */
    fun forget() {
        detector.reset()
        lastSignature = null
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
    ): Boolean {
        val engine = reader() ?: return false
        if (game.id != lastGame) {
            // A different app in front: whatever settled before is not on this screen.
            detector.reset()
            lastSignature = null
            lastGame = game.id
        }
        val still = Perf.measure("same?") { unchanged(bitmap) }
        val known = lastSignature
        val size = bitmap.width * bitmap.height
        val pixels = buffer?.takeIf { it.size == size } ?: IntArray(size).also { buffer = it }
        val frame = Frame(bitmap.width, bitmap.height, pixels)
        if (still && known != null) {
            // The same picture has the same text: only the clock has moved. Nothing is copied or probed
            // unless that text has now been still long enough to read.
            if (!detector.offer(known, nowMs)) return true
            Perf.measure("pixels") { bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } else {
            Perf.measure("pixels") { bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            Perf.measure("ring") { FrameRing.offer(frame, nowMs) }
            val signature = Perf.measure("probe") { probe().signature(frame) }
            lastSignature = signature
            if (signature.isEmpty()) {
                // The text has gone (a scene change, a menu): nothing is on screen to tap.
                detector.offer(signature, nowMs)
                if (_latest.value?.lines?.isNotEmpty() == true) _latest.value = Reading(game, displayId, emptyList(), emptyList(), null, nowMs)
                return false
            }
            if (!detector.offer(signature, nowMs)) return false
        }

        val rows = Perf.measure("read") { engine.read(frame) }.filter { it.text.isNotBlank() }.map { it.moved(offsetX, offsetY) }
            // Text under another window (a system dialog on a whole-screen picture) is not the game's.
            .filter { line -> covered.none { it.intersects(line.box) } }
        // A reader returns rows; the game shows sentences. From here on a line is a whole piece of text.
        val lines = Perf.measure("group") { Blocks.group(rows) }
        if (lines.isEmpty()) return still
        // A line still on screen from the last reading keeps its row; only new text is saved.
        val carried = _latest.value?.takeIf { it.game.id == game.id }
            ?.let { old -> old.lines.map { it.text }.zip(old.saved).toMap() }
            // Nothing remembered (the app restarted, or the game came back to the front): the line
            // on screen may be one already saved a moment ago, and must not be counted twice.
            ?: store.recent(game).associateBy { it.lineText }
        // The frame is taken before any mark is drawn on the game, so it is the card's picture as is.
        val framePath = Perf.measure("save.picture") { saveFrame(bitmap) }
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
        return still
    }

    /**
     * Where the picture of this frame will be. It is compressed and written behind the reading,
     * which takes longer than reading the text did; a card made in that fifth of a second goes
     * without, as it does when the picture has been cleared away.
     */
    private fun saveFrame(frame: Bitmap): String? {
        val copy = try {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        } ?: return null
        val file = File(framesDir, "${UUID.randomUUID()}.webp")
        pictures.launch {
            try {
                framesDir.mkdirs()
                Perf.measure("picture.write") { file.outputStream().use { copy.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) } }
            } catch (_: Exception) {
                file.delete()
            } finally {
                copy.recycle()
            }
        }
        return file.path
    }
}
