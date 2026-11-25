// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.data

import io.github.beesan.lfg.ask.Recap
import io.github.beesan.lfg.core.DayPicture
import io.github.beesan.lfg.core.ReadingStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** A day of a game in the diary: what was read, its picture, what the model wrote of it, and the entries written on it. */
data class DiaryPage(
    val gameId: String,
    val gameName: String,
    val day: LocalDate,
    /** Which of the game's days this is, from 1. */
    val number: Int,
    val lines: Int,
    val chars: Int,
    val seconds: Double,
    /** The day's picture, or null when there is none at all. */
    val picture: String?,
    /** The line the picture is of: its id, its text, and when it was read, in milliseconds. */
    val lineId: String,
    val lineText: String,
    val lineAt: Long,
    /** Up to two more screens of the day, while they are kept. */
    val more: List<String>,
    val title: String,
    val summary: String,
    val entries: List<Note>,
) {
    val written: Boolean get() = title.isNotBlank() || summary.isNotBlank()
}

/** A game's days, newest first. */
data class DiaryBook(val gameId: String, val title: String, val pages: List<DiaryPage>) {
    val last: LocalDate get() = pages.first().day
}

/**
 * The diary: every day a game was read, with one picture kept for good, the
 * few sentences the model wrote of it (never asked for here: they come with a
 * recap, data/DiaryWorker.kt), and the entries the reader wrote. An entry is
 * a [Note] that is on no word.
 */
class Diary(private val db: LfgDatabase, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {

    /** Every game's days, the game read last first. An ordinary app shows only for the days an entry was written in it. */
    suspend fun books(): List<DiaryBook> = withContext(Dispatchers.IO) {
        val zone = zone()
        val games = db.games().allNow().associateBy { it.id }
        val lines = db.lines().all()
        val kept = db.diary().all().associateBy { it.gameId to it.day }
        val lookups = db.lookups().counts().associate { it.lineId to it.count }
        val moments = moments()
        val entries = db.notes().entries().groupBy { it.gameId to dayOf(it, zone) }
        val totals = ReadingStats.summarise(lines.map { ReadingStats.Stamp(it.timestamp, ReadingStats.characters(it.lineText), it.gameId) }, zone)
            .gameDays.mapValues { (_, days) -> days.associateBy { it.day } }
        val byDay = lines.groupBy { it.gameId to ReadingStats.dayOf(it.timestamp, zone) }
        val keys = (byDay.keys + entries.keys).filter { (gameId, _) -> games[gameId]?.app != true || entries.keys.any { it.first == gameId } }
        keys.groupBy { it.first }.map { (gameId, days) ->
            val ordered = days.map { it.second }.sorted()
            val name = games[gameId]?.title ?: byDay[gameId to ordered.last()]?.lastOrNull()?.gameName
                ?: entries[gameId to ordered.last()]?.lastOrNull()?.gameName.orEmpty()
            val pages = ordered.mapIndexed { i, day ->
                val read = byDay[gameId to day].orEmpty()
                val written = entries[gameId to day].orEmpty()
                val row = kept[gameId to day.toEpochDay()]
                val total = totals[gameId]?.get(day)
                val shown = picture(read, written, row, lookups, moments)
                DiaryPage(
                    gameId = gameId, gameName = name.ifBlank { "Other" }, day = day, number = i + 1,
                    lines = total?.lines ?: 0, chars = total?.chars ?: 0, seconds = total?.seconds ?: 0.0,
                    picture = shown.path, lineId = shown.line?.id.orEmpty(), lineText = shown.line?.lineText.orEmpty(),
                    lineAt = shown.line?.createdAt ?: 0,
                    more = spread(read.mapNotNull { it.screenshotPath }.distinct().filter { it != shown.path }),
                    title = row?.title.orEmpty(), summary = row?.summary.orEmpty(), entries = written,
                )
            }
            DiaryBook(gameId, name.ifBlank { "Other" }, pages.asReversed())
        }.sortedByDescending { it.last }
    }

    private class Shown(val path: String?, val line: GameLine?)

    /**
     * The day's picture: the one kept for it, else chosen now by the same rule
     * while its screens are still there, else the last line's own picture,
     * which is never cleared.
     */
    private fun picture(read: List<GameLine>, written: List<Note>, row: DiaryDay?, lookups: Map<String, Int>, moments: Set<String>): Shown {
        val byId = read.associateBy { it.id }
        written.lastOrNull { it.picturePath?.let(::File)?.isFile == true }?.let { return Shown(it.picturePath, byId[it.lineId] ?: read.lastOrNull()) }
        row?.picturePath?.takeIf { File(it).isFile }?.let { return Shown(it, byId[row.lineId] ?: read.lastOrNull()) }
        val chosen = DayPicture.line(candidates(read, lookups), moments)?.let { byId[it.id] }
        if (chosen != null && File(chosen.screenshotPath!!).isFile) return Shown(chosen.screenshotPath, chosen)
        val crop = read.lastOrNull { it.cropPath?.let(::File)?.isFile == true }
        return Shown(crop?.cropPath, crop ?: read.lastOrNull())
    }

    private fun candidates(read: List<GameLine>, lookups: Map<String, Int>) =
        read.map { DayPicture.Line(it.id, it.screenshotPath, it.noteIds.isNotEmpty(), lookups[it.id] ?: 0) }

    /** Two of [paths], from a third and two thirds of the way through the day. */
    private fun spread(paths: List<String>): List<String> = when {
        paths.size <= 2 -> paths
        else -> listOf(paths[paths.size / 3], paths[paths.size * 2 / 3])
    }

    private suspend fun moments(): Set<String> = db.recaps().all().flatMap { row ->
        runCatching { JSON.decodeFromString<Recap>(row.json).moments.map { it.lineId } }.getOrDefault(emptyList())
    }.filter { it.isNotEmpty() }.toSet()

    /**
     * Before screens older than [before] (milliseconds) are cleared: each day
     * of a game that has any of them, and no picture kept yet, keeps one for
     * good. A day with an entry's picture needs none: the entry has its own copy.
     */
    suspend fun keepPictures(before: Long) = withContext(Dispatchers.IO) {
        val zone = zone()
        val old = db.lines().withPicturesBefore(before)
        if (old.isEmpty()) return@withContext
        val kept = db.diary().all().associateBy { it.gameId to it.day }
        val lookups = db.lookups().counts().associate { it.lineId to it.count }
        val moments = moments()
        val entries = db.notes().entries().filter { it.picturePath != null }.map { it.gameId to dayOf(it, zone) }.toSet()
        for ((key, read) in old.groupBy { it.gameId to ReadingStats.dayOf(it.timestamp, zone) }) {
            val row = kept[key.first to key.second.toEpochDay()]
            if (key in entries || row?.picturePath?.let(::File)?.isFile == true) continue
            val line = DayPicture.line(candidates(read, lookups), moments) ?: continue
            db.diary().put((row ?: DiaryDay(key.first, key.second.toEpochDay())).copy(picturePath = line.screen, lineId = line.id))
        }
    }

    /** What the model wrote of a day of a game, kept with the day's picture. */
    suspend fun write(gameId: String, day: LocalDate, title: String, summary: String, model: String, now: Long = System.currentTimeMillis()) {
        val row = db.diary().of(gameId, day.toEpochDay()) ?: DiaryDay(gameId, day.toEpochDay())
        db.diary().put(row.copy(title = title, summary = summary, model = model, writtenAt = now))
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The day an entry belongs to. */
        fun dayOf(entry: Note, zone: ZoneId): LocalDate = ReadingStats.dayOf(entry.createdAt / 1000.0, zone)

        /** "42 min · 3,210 characters", or "1 h 5 min · …". */
        fun stats(seconds: Double, chars: Int): String {
            val minutes = (seconds / 60).toLong()
            val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
            return "$time · %,d characters".format(chars)
        }
    }
}
