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
import io.github.beesan.lfg.ocr.TextClean
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

    /** The last picture's fingerprint, to tell a screen that has not changed at all. */
    private var lastHash = 0L

    /** What the probe made of the last picture; null when the next one must be probed whatever it shows. */
    private var lastSignature: String? = null

    /** Pictures are written behind the reading: the text is tappable before its picture is on disk. */
    private val pictures = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where each line's own picture goes, beside the frames. */
    private val cropsDir = File(framesDir.parentFile, "crops")

    /**
     * Whether [bitmap] is the last picture again, by a fingerprint of every fourth row of it, read
     * where Android keeps the pixels (about 1 ms against 15 for copying the rows out). Text is
     * taller than four rows, so no change to it can hide between them.
     */
    private fun unchanged(bitmap: Bitmap): Boolean {
        val hash = Pixels.rowsHash(bitmap, 4)
        val same = hash != 0L && hash == lastHash
        lastHash = hash
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

        val rows = Perf.measure("read") { engine.read(frame) }
            // The reader's forms made the game's own (TextClean), and what is not the game's text dropped.
            .mapNotNull(TextClean::line).filter { it.text.isNotBlank() }.map { it.moved(offsetX, offsetY) }
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
        val fresh = ArrayList<Pair<GameLine, Box>>()
        val saved = lines.map { line ->
            carried[line.text] ?: store.saveLine(
                game = game, text = line.text, source = LineStore.SOURCE_OCR, engine = engine.name,
                screenshotPath = framePath, displayId = displayId, box = line.box, nowMs = nowMs,
            ).also { fresh += it to line.box }
        }
        Perf.measure("save.crops") { saveCrops(bitmap, fresh, offsetX, offsetY) }
        _latest.value = Reading(game, displayId, lines, saved, framePath, nowMs, lines.map { carriedTimes[it.text] ?: appearedNow })
        return still
    }

    /**
     * Where the picture of this frame will be. It is compressed and written behind the reading,
     * which takes longer than reading the text did; a card made in that fifth of a second goes
     * without, as it does when the picture has been cleared away.
     */
    /**
     * A picture of each new line alone, cut from [frame] and written behind
     * the reading like the frame's. It is kept when the frame's picture is
     * cleared, so an old line can still go on a card, and a crop beside its
     * text is what a reader learns from.
     */
    private fun saveCrops(frame: Bitmap, fresh: List<Pair<GameLine, Box>>, offsetX: Int, offsetY: Int) {
        for ((line, box) in fresh) {
            val left = (box.left - offsetX - CROP_PAD).coerceIn(0, frame.width)
            val top = (box.top - offsetY - CROP_PAD).coerceIn(0, frame.height)
            val right = (box.right - offsetX + CROP_PAD).coerceIn(left, frame.width)
            val bottom = (box.bottom - offsetY + CROP_PAD).coerceIn(top, frame.height)
            if (right - left <= 0 || bottom - top <= 0) continue
            val crop = try {
                Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            } catch (_: Throwable) {
                continue
            }
            val file = File(cropsDir, "${line.id}.webp")
            pictures.launch {
                try {
                    cropsDir.mkdirs()
                    write(file, crop, 90)
                    store.setCrop(line.id, file.path)
                } catch (_: Exception) {
                    file.delete()
                } finally {
                    if (crop !== frame) crop.recycle()
                }
            }
        }
    }

    /** A still WebP through libwebp at a quick setting; Android's own encoder if that fails. */
    private fun write(file: File, bitmap: Bitmap, quality: Int) {
        val bytes = Pixels.webp(bitmap, quality)
        if (bytes != null) file.writeBytes(bytes) else file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it) }
    }

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
                Perf.measure("picture.write") { write(file, copy, 85) }
            } catch (_: Exception) {
                file.delete()
            } finally {
                copy.recycle()
            }
        }
        return file.path
    }
}

/** Pixels kept around a line's box when it is cut out, so no stroke at its edge is lost. */
private const val CROP_PAD = 4
