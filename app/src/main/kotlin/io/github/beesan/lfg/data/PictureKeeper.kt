// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import io.github.beesan.lfg.ask.Recap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The screens saved with each line, kept for a while and then let go, so a
 * handheld does not fill up over months of play. The line itself, its crop
 * (the line alone, a few KB) and its text are never deleted. A screen is kept
 * for good when a card was made from its line, a card waiting for AnkiDroid
 * still needs it, a recap chose the line as a moment to look back at, or it is
 * the one picture the diary keeps of its day (data/Diary.kt).
 */
class PictureKeeper(private val db: LfgDatabase, private val files: File, private val diary: Diary = Diary(db)) {
    val frames = File(files, "frames")

    /** The screens kept whatever their age: from a line a card was made from, or a recap's moment. */
    suspend fun keptForGood(): Set<String> {
        val lines = db.lines()
        val moments = db.recaps().all().flatMap { row ->
            runCatching { JSON.decodeFromString<Recap>(row.json).moments.map { it.lineId } }.getOrDefault(emptyList())
        }.filter { it.isNotEmpty() }.distinct()
        // A card still waiting for AnkiDroid is sent later with the picture it was made with.
        val waiting = db.cards().pending().mapNotNull { card ->
            runCatching { JSON.parseToJsonElement(card.payload).jsonObject["picturePath"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return lines.keptPictures().toSet() + moments.chunked(500).flatMap { lines.picturesOf(it) } + waiting + db.diary().pictures()
    }

    /**
     * Lets go of screens older than [days] (0 keeps them all) and of any file in
     * the screens folder no line points to. Returns how many files went.
     */
    suspend fun sweep(days: Int, now: Long = System.currentTimeMillis()): Int {
        val lines = db.lines()
        // Each day about to lose its screens keeps one of them for the diary.
        if (days > 0) diary.keepPictures(now - days * DAY_MS)
        val kept = keptForGood()
        var gone = 0
        if (days > 0) {
            for (path in lines.stalePictures(now - days * DAY_MS)) {
                if (path in kept) continue
                File(path).delete()
                lines.forgetPicture(path)
                gone++
            }
        }
        // A file no line points to: its line went with a restore, or the app stopped while writing it.
        // A day's grace, so a picture still being written behind a reading is never taken.
        val pointed = lines.allPictures().toSet()
        frames.listFiles()?.forEach { file ->
            if (file.path !in pointed && file.path !in kept && file.lastModified() < now - DAY_MS && file.delete()) gone++
        }
        return gone
    }

    /** Bytes each kind of thing the app keeps takes on this device. */
    data class Usage(
        val screens: Long, val screenCount: Int, val keptScreens: Int, val crops: Long, val cards: Long,
        val notes: Long, val dictionaries: Long, val reader: Long, val database: Long,
    ) {
        val total get() = screens + crops + cards + notes + dictionaries + reader + database
    }

    suspend fun usage(databaseFile: File): Usage {
        val screens = frames.listFiles().orEmpty()
        val kept = keptForGood()
        return Usage(
            screens = screens.sumOf { it.length() },
            screenCount = screens.size,
            keptScreens = screens.count { it.path in kept },
            crops = size(File(files, "crops")),
            cards = size(File(files, "media")),
            notes = size(File(files, "notes")),
            dictionaries = size(File(files, "dictionaries")),
            reader = size(File(files, "models")),
            database = listOf("", "-wal", "-shm").sumOf { File(databaseFile.path + it).length() },
        )
    }

    private fun size(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val DAY_MS = 24 * 3600 * 1000L
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
