// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Bitmap
import android.util.Log
import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Held
import io.github.beesan.lfg.core.InterfaceText
import io.github.beesan.lfg.core.KeptLine
import io.github.beesan.lfg.core.LineSorter
import io.github.beesan.lfg.core.Part
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
 *
 * A line is kept once: what was read is sorted ([LineSorter]) into lines
 * still on screen, lines read before and shown again, more of a line already
 * kept, a menu's text, and what is new; only the new is saved.
 */
class ReadingPipeline(
    private val store: LineStore,
    private val framesDir: File,
    private val reader: () -> TextReader? = { Readers.reader },
    private val probe: () -> ChangeProbe = { Readers.probe },
    private val detector: SettleDetector = SettleDetector(),
    /** Whether menu labels, a HUD's words and name plates are told apart from lines ([InterfaceText]). */
    private val apart: () -> Boolean = { false },
) {
    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest

    private var lastGame = ""

    private val interfaceText = InterfaceText()

    /** What is remembered of one game between readings, so that a line is kept once. */
    private class Kept {
        val sorter = LineSorter()

        /** The game's last lines, newest first; null until read from the store, and again when the game comes back to the front. */
        var recent: ArrayDeque<GameLine>? = null

        /** The rows on screen lately, which the sorter may name, and when each began to appear. */
        val rows = HashMap<String, GameLine>()
        val appeared = HashMap<String, Long>()

        /** The interface text kept in this run, by its text: a menu opened again adds no rows. */
        val menus = HashMap<String, GameLine>()

        /** The size of the type of the last few long lines kept: the story's, which scenery is small beside. */
        val types = ArrayDeque<Int>()

        /** The story's type size, once a few of its lines have been seen; 0 before. */
        fun storyType(): Int = if (types.size < STORY_LINES) 0 else types.sorted()[types.size / 2]
    }

    /** By game: going to another app and back does not make the line on screen a new one. */
    private val kept = HashMap<String, Kept>()

    /** The texts found to be interface text in [knownFor]'s game, loaded the first time it is read. */
    private val interfaceKnown = HashSet<String>()
    private var knownFor = ""

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
    private fun unchanged(hash: Long): Boolean {
        val same = hash != 0L && hash == lastHash
        lastHash = hash
        return same
    }

    /** [bitmap]'s pixels into [pixels]: read where Android keeps them when that is exact, else by getPixels. */
    private fun pixelsOf(bitmap: Bitmap, pixels: IntArray) {
        if (!Pixels.argb(bitmap, pixels)) bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** Forget what was on screen, so the next settled frame is read even if it is the same text. */
    fun forget() {
        detector.reset()
        interfaceText.reset()
        lastSignature = null
        _latest.value = null
        // The lines may have been changed meanwhile, in the app's own screens.
        kept.values.forEach { it.recent = null }
    }

    /**
     * Takes ownership of nothing: the caller recycles [bitmap] afterwards.
     * [offsetX] and [offsetY] place the picture on its display, for a picture
     * of one window rather than of the whole screen.
     */
    suspend fun onFrame(
        bitmap: Bitmap, game: Game, displayId: Int, nowMs: Long, offsetX: Int = 0, offsetY: Int = 0,
        covered: List<Box> = emptyList(),
    ): Boolean = onFrame(0L, { bitmap }, game, displayId, nowMs, offsetX, offsetY, covered)

    /**
     * The same, for a picture not yet copied out of graphics memory: [hash] is
     * its fingerprint (Thumbnails), or 0 for none, and [picture] copies it out,
     * which is done only when its pixels are needed. A screen found as it was
     * is never copied.
     */
    suspend fun onFrame(
        hash: Long, picture: () -> Bitmap?, game: Game, displayId: Int, nowMs: Long, offsetX: Int = 0, offsetY: Int = 0,
        covered: List<Box> = emptyList(),
    ): Boolean {
        val engine = reader() ?: return false
        if (game.id != lastGame) {
            // A different app in front: whatever settled before is not on this screen.
            detector.reset()
            interfaceText.reset()
            lastSignature = null
            lastGame = game.id
            kept[game.id]?.recent = null
        }
        val fingerprint = if (hash != 0L) hash else picture()?.let { Perf.measure("same?") { Pixels.rowsHash(it, 4) } } ?: return false
        val still = unchanged(fingerprint)
        val known = lastSignature
        // The same picture has the same text: only the clock has moved. Nothing is copied or probed
        // unless that text has now been still long enough to read.
        if (still && known != null && !detector.offer(known, nowMs)) return true
        val bitmap = picture() ?: return still
        val size = bitmap.width * bitmap.height
        val pixels = buffer?.takeIf { it.size == size } ?: IntArray(size).also { buffer = it }
        val frame = Frame(bitmap.width, bitmap.height, pixels)
        if (still && known != null) {
            Perf.measure("pixels") { pixelsOf(bitmap, pixels) }
        } else {
            Perf.measure("pixels") { pixelsOf(bitmap, pixels) }
            Perf.measure("ring") { FrameRing.offer(frame, nowMs) }
            val signature = Perf.measure("probe") { probe().signature(frame) }
            lastSignature = signature
            if (signature.isEmpty()) {
                // The text has gone (a scene change, a menu): nothing is on screen to tap.
                detector.offer(signature, nowMs)
                interfaceText.onReading(emptyList())
                if (_latest.value?.lines?.isNotEmpty() == true) _latest.value = Reading(game, displayId, emptyList(), emptyList(), null, nowMs)
                // A game going on with no text in it, as at the look before, is as good as a still screen
                // and is looked at as seldom: an emulator needs the processor more. A line appearing ends that.
                return known != null && known.isEmpty()
            }
            if (!detector.offer(signature, nowMs)) return false
        }

        val rows = Perf.measure("read") { engine.read(frame) }
            // The reader's forms made the game's own (TextClean), and what is not the game's text dropped.
            .mapNotNull(TextClean::line).filter { it.text.isNotBlank() }.map { it.moved(offsetX, offsetY) }
            // Text under another window (a system dialog on a whole-screen picture) is not the game's.
            .filter { line -> covered.none { it.intersects(line.box) } }
        // A reader returns rows; the game shows sentences. From here on a piece is a whole piece of text.
        val pieces = Perf.measure("group") { Blocks.group(rows) }
        if (pieces.isEmpty()) {
            interfaceText.onReading(emptyList())
            return still
        }
        val telling = apart()
        if (!telling) {
            interfaceKnown.clear()
            knownFor = ""
            // Turned off, every row is a line again: none is a menu's to use again.
            kept.values.forEach { it.menus.clear() }
        } else if (knownFor != game.id) {
            interfaceKnown.clear()
            interfaceKnown += store.interfaceTexts(game)
            knownFor = game.id
        }
        val memory = kept.getOrPut(game.id) { Kept() }
        val recent = memory.recent ?: ArrayDeque(store.recentLines(game, LineSorter.RECENT)).also { memory.recent = it }
        val byId = HashMap(memory.rows)
        recent.forEach { byId[it.id] = it }
        // Each piece is a line still on screen, one read before, more of one, a menu's text, or new: only the new is saved.
        val sorted = Perf.measure("sort") {
            memory.sorter.sort(
                pieces.map { it.text }, recent.map { KeptLine(it.id, it.lineText, it.createdAt) }, interfaceKnown, telling, pieces.map { it.box },
            ) { text ->
                store.lineOf(game, text)?.let { byId[it.id] = it; KeptLine(it.id, it.lineText, it.createdAt) }
            }
        }
        // What stays on screen while the text around it changes is told by the text it is kept as: a sign in
        // the picture read a little differently at each look is still the one piece staying.
        val found = interfaceText.onReading(
            pieces.zip(sorted.parts).map { (piece, parts) -> (parts.singleOrNull() as? Part.Same)?.let { byId[it.id]?.lineText } ?: piece.text },
        )
        // The frame is taken before any mark is drawn on the game, so it is the card's picture as is.
        val framePath = Perf.measure("save.picture") { saveFrame(bitmap) }
        val appearedNow = detector.changeStartedMs.takeIf { it in 1..nowMs } ?: nowMs
        val fresh = ArrayList<Pair<GameLine, Box>>()
        // Interface text found on this reading: a label that stayed, or one come back again.
        val newly = if (telling) found.filterTo(LinkedHashSet()) { it !in interfaceKnown } else LinkedHashSet()
        val lines = ArrayList<TextLine>()
        val saved = ArrayList<GameLine>()
        val appeared = ArrayList<Long>()
        val held = ArrayList<List<Held>>()
        for ((piece, parts) in pieces.zip(sorted.parts)) {
            held += parts.map { part ->
                // A page that has grown by a sentence is several lines, each with its own row.
                val line = if (part.from == 0 && part.to == piece.text.length) piece else piece.slice(part.from, part.to)
                val alone = parts.size == 1
                var began = appearedNow
                val row = when (part) {
                    is Part.Same -> (byId[part.id] ?: store.line(part.id))?.also { began = memory.appeared[it.id] ?: it.createdAt }
                    is Part.Grown -> (byId[part.id] ?: store.line(part.id))?.let { old ->
                        began = memory.appeared[old.id] ?: appearedNow
                        store.grow(old, line.text, line.box, framePath, nowMs).also { grown ->
                            fresh += grown to line.box
                            recent.indexOfFirst { it.id == grown.id }.takeIf { it >= 0 }?.let { recent[it] = grown }
                        }
                    }
                    is Part.New -> null
                } ?: run {
                    val isInterface = telling && (
                        (part as? Part.New)?.interfaceText == true || InterfaceText.lone(line.text) || (alone && line.text in found) ||
                            // Words in the picture: short, and set far smaller than the story is.
                            (alone && !long(line) && InterfaceText.small(line.typeSize, memory.storyType()))
                                .also { if (it) Log.i(TAG, "scenery: type of ${line.typeSize} px beside the story's ${memory.storyType()}") } ||
                            (alone && InterfaceText.short(line.text) && InterfaceText.comesBack(line.text, store.savedTimes(game, line.text), nowMs))
                                .also { if (it) newly += line.text }
                        )
                    (if (isInterface) memory.menus[line.text] else null) ?: store.saveLine(
                        game = game, text = line.text, source = LineStore.SOURCE_OCR, engine = engine.name,
                        screenshotPath = framePath, displayId = displayId, box = line.box, nowMs = nowMs, interfaceText = isInterface,
                    ).also {
                        fresh += it to line.box
                        if (isInterface) {
                            memory.menus[line.text] = it
                            interfaceKnown += line.text
                        } else {
                            recent.addFirst(it)
                            while (recent.size > LineSorter.RECENT) recent.removeLast()
                            if (long(line) && line.typeSize > 0) {
                                memory.types.addLast(line.typeSize)
                                while (memory.types.size > TYPE_LINES) memory.types.removeFirst()
                            }
                        }
                    }
                }
                lines += line
                saved += row
                appeared += began
                Held(part.from, part.to, row.id, row.lineText, row.interfaceText)
            }
        }
        // Found once, it is interface text in this game before and after: the lines saved of it already go too.
        for (text in newly) {
            store.markInterface(game, text, nowMs)
            interfaceKnown += text
            recent.removeAll { it.lineText == text }
        }
        // What was marked just now is interface text to the sorter too.
        val shown = if (newly.isEmpty()) held else held.map { parts -> parts.map { if (it.text in newly) it.copy(interfaceText = true) else it } }
        memory.sorter.shown(pieces.map { it.text }, shown, sorted.menu, pieces.map { it.box })
        saved.forEachIndexed { i, row ->
            memory.rows[row.id] = row
            memory.appeared[row.id] = appeared[i]
        }
        // Only what the sorter may still name is remembered.
        val named = memory.sorter.held()
        memory.rows.keys.retainAll(named)
        memory.appeared.keys.retainAll(named)
        Perf.measure("save.crops") { saveCrops(bitmap, fresh, offsetX, offsetY) }
        _latest.value = Reading(game, displayId, lines, saved, framePath, nowMs, appeared)
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

private const val TAG = "LfgLines"

/** Whether a line is long enough to be told from a label by its length alone. */
private fun long(line: TextLine) = line.text.codePointCount(0, line.text.length) >= LineSorter.LONG

/** How many long lines must have been kept before the story's type size is known, and how many it is taken from. */
private const val STORY_LINES = 3
private const val TYPE_LINES = 9

/** Pixels kept around a line's box when it is cut out, so no stroke at its edge is lost. */
private const val CROP_PAD = 4
