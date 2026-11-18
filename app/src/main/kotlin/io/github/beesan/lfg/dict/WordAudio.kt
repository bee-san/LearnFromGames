// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Words spoken by people, from local-audio-yomichan's `android.db`
 * (yomidevs/local-audio-yomichan, the file AnkiconnectAndroid reads). Its
 * `entries` table says which recordings exist for a word and reading; its
 * `android` table holds each recording's bytes. Nothing leaves the device.
 */
class WordAudio(private val context: Context) {
    data class Clip(val source: String, val file: String, val display: String) {
        /** `NHK16`, `JPod101`, `Forvo (user)` … */
        val name: String get() = (NAMES[source] ?: source) + if (display.isNotBlank()) " · $display" else ""
    }

    data class Info(val clips: Long, val bytes: Long) {
        /** `2 recordings · 1 MB`; the count is left out until it is known. */
        val summary: String
            get() {
                val size = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%,d MB".format((bytes + 999_999) / 1_000_000)
                return if (clips >= 0) "%,d %s · %s".format(clips, if (clips == 1L) "recording" else "recordings", size) else size
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "android.db")
    private var db: SQLiteDatabase? = null
    private var player: MediaPlayer? = null
    private var cachedKey: Pair<String, String>? = null
    private var cachedClips: List<Clip> = emptyList()
    private var playing: Pair<Pair<String, String>, Int>? = null

    private val _info = MutableStateFlow(if (file.isFile) Info(-1, file.length()) else null)

    /** What is installed, or null; `clips` is -1 until counted. */
    val info: StateFlow<Info?> = _info

    val installed: Boolean get() = _info.value != null

    @Synchronized
    private fun database(): SQLiteDatabase? {
        db?.let { return it }
        if (!file.isFile) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).also { db = it }
        } catch (e: Exception) {
            Log.w(TAG, "android.db would not open", e)
            null
        }
    }

    /** The recordings of a word in this reading, best source first. */
    @Synchronized
    fun clips(expression: String, reading: String): List<Clip> {
        val key = expression to reading
        if (key == cachedKey) return cachedClips
        val db = database() ?: return emptyList()
        val kana = reading.ifEmpty { expression }
        val order = SOURCES.withIndex().joinToString(" ") { (i, s) -> "WHEN '$s' THEN $i" }
        val clips = try {
            db.rawQuery(
                "SELECT source, file, display FROM entries WHERE expression = ? AND (reading IS NULL OR reading = ? OR reading = ?) " +
                    "ORDER BY CASE source $order ELSE ${SOURCES.size} END, reading LIMIT 16",
                arrayOf(expression, hiragana(kana), katakana(kana)),
            ).use { c ->
                buildList { while (c.moveToNext()) add(Clip(c.getString(0), c.getString(1), c.getString(2) ?: "")) }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "audio lookup failed", e)
            emptyList()
        }
        cachedKey = key
        cachedClips = clips
        return clips
    }

    fun has(entry: Entry): Boolean = installed && clips(entry.expression, entry.reading).isNotEmpty()

    @Synchronized
    fun bytes(clip: Clip): ByteArray? = try {
        database()?.rawQuery("SELECT data FROM android WHERE file = ? AND source = ? LIMIT 1", arrayOf(clip.file, clip.source))?.use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "audio read failed", e)
        null
    }

    /** The first recording of a word as a file, for a card; null when there is none. */
    fun export(expression: String, reading: String, dir: File, name: String): File? {
        val clip = clips(expression, reading).firstOrNull() ?: return null
        val data = bytes(clip) ?: return null
        val out = File(dir.apply { mkdirs() }, name + "." + clip.file.substringAfterLast('.', "mp3").lowercase())
        out.writeBytes(data)
        return out
    }

    /** Plays the word. Asked again for the same word, it plays the next recording, then starts over. */
    fun play(entry: Entry) {
        scope.launch {
            val key = entry.expression to entry.reading
            val clips = clips(entry.expression, entry.reading)
            if (clips.isEmpty()) return@launch
            val index = synchronized(this@WordAudio) {
                val i = playing?.takeIf { it.first == key }?.second?.plus(1)?.mod(clips.size) ?: 0
                playing = key to i
                i
            }
            val data = bytes(clips[index]) ?: return@launch
            withContext(Dispatchers.Main) { start(data) }
        }
    }

    private fun start(data: ByteArray) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(BytesSource(data))
            setOnPreparedListener { it.start() }
            setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null }
            setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "playback failed: $what $extra")
                mp.release()
                if (player === mp) player = null
                true
            }
            prepareAsync()
        }
    }

    /**
     * Copies a picked `android.db` in. The file is checked before it replaces
     * the one installed, so a wrong pick leaves things as they were. Returns an
     * error, or null.
     */
    suspend fun install(open: () -> InputStream?, progress: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val dir = file.parentFile ?: return@withContext "There is nowhere to keep it."
        val part = File(dir, "android.db.part")
        try {
            val input = open() ?: return@withContext "The file could not be opened."
            input.use { inp ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var total = 0L
                    var shown = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                        if (total - shown >= 64L shl 20) {
                            shown = total
                            progress("Copying word audio… ${total shr 20} MB")
                        }
                    }
                }
            }
            progress("Checking word audio…")
            val clips = count(part) ?: return@withContext "That is not local-audio-yomichan's android.db: it needs its entries and android tables."
            synchronized(this@WordAudio) {
                db?.close()
                db = null
                cachedKey = null
                if (!part.renameTo(file)) return@withContext "The file could not be kept."
            }
            _info.value = Info(clips, file.length())
            null
        } catch (e: Exception) {
            Log.w(TAG, "android.db install failed", e)
            "The copy failed: ${e.message ?: "no space left?"}"
        } finally {
            part.delete()
        }
    }

    /** Counts the recordings, so the screen can say how many there are. */
    suspend fun refreshInfo() = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext
        val clips = synchronized(this@WordAudio) { database()?.let { count(it) } } ?: -1
        _info.value = Info(clips, file.length())
    }

    @Synchronized
    fun remove() {
        player?.release()
        player = null
        db?.close()
        db = null
        cachedKey = null
        file.delete()
        _info.value = null
    }

    private fun count(path: File): Long? = try {
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { count(it) }
    } catch (_: Exception) {
        null
    }

    private fun count(db: SQLiteDatabase): Long? = try {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        if ("entries" !in tables || "android" !in tables) null
        else db.rawQuery("SELECT COUNT(*) FROM android", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } catch (_: Exception) {
        null
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }

    companion object {
        private const val TAG = "LfgWordAudio"

        /** AnkiconnectAndroid's order: pitch-accent dictionaries' own readers first, then people, then JPod. */
        val SOURCES = listOf("nhk16", "shinmeikai8", "forvo", "jpod", "jpod_alternate")

        private val NAMES = mapOf(
            "nhk16" to "NHK16", "shinmeikai8" to "Shinmeikai 8", "forvo" to "Forvo", "jpod" to "JPod101", "jpod_alternate" to "JPod101",
        )

        fun hiragana(text: String) = buildString { for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
        fun katakana(text: String) = buildString { for (c in text) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) }
    }
}
