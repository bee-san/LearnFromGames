// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.games

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import io.github.beesan.lfg.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

/**
 * Names the game inside the app in front, from the folders and the Steam
 * account chosen in Settings › Game names. Asked on every look at the screen,
 * so it never waits: it answers from what it last read, and reads again in
 * the background every few seconds while an emulator is in front.
 */
class GameFinder(private val context: Context, private val scope: CoroutineScope, private val prefs: () -> Prefs) {
    @Volatile private var launches: List<Launch> = emptyList()
    @Volatile private var steamNow: Named? = null
    @Volatile private var edenGrown: Pair<Long, Long>? = null // title ID, when its time last grew (elapsed ms)
    @Volatile private var edenNames: List<Pair<Long, String>> = emptyList()

    private var refreshing: Job? = null
    private var lastFiles = -FILES_MS
    private var lastSteam = -STEAM_MS
    private var steamFor: String? = null
    @Volatile private var steamId: Pair<String, String>? = null // what was typed, its SteamID64
    // A file's link to its time and what it said. Settings reads folders too, on another thread.
    private val read = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any?>>()
    @Volatile private var edenTimes: Map<Long, Long>? = null

    /** The game [packageName] has open, if it runs many games and one of the sources knows which. */
    fun nameFor(packageName: String): Named? {
        if (!Hosts.isEmulator(packageName)) return null
        val now = SystemClock.elapsedRealtime()
        val steam = Hosts.isSteam(packageName)
        if (steam && steamFor != packageName) {
            // A Steam player just came to the front: ask Steam now, not in half a minute.
            steamFor = packageName
            lastSteam = -STEAM_MS
        }
        if (refreshing?.isActive != true) {
            val files = now - lastFiles >= FILES_MS
            val askSteam = steam && now - lastSteam >= STEAM_MS
            if (files) lastFiles = now
            if (askSteam) lastSteam = now
            if (files || askSteam) refreshing = scope.launch(Dispatchers.IO) { refresh(packageName, files, askSteam) }
        }
        if (!steam) steamFor = null
        val live = when {
            Hosts.isEden(packageName) -> edenLive(now)
            steam -> steamNow
            else -> null
        }
        return GameNames.pick(packageName, live, launches)
    }

    private fun edenLive(now: Long): Named? {
        val names = edenNames
        edenGrown?.let { (id, at) ->
            if (now - at < EDEN_RUNNING_MS) {
                val name = names.lastOrNull { it.first == id }?.second
                return if (name != null) GameTitle.named(name, Eden.SOURCE) else Eden.unnamed(id)
            }
        }
        // Eden starts a new log each time it opens: the last title loaded into it is the one open.
        return names.lastOrNull()?.let { GameTitle.named(it.second, Eden.SOURCE) }
    }

    private fun refresh(packageName: String, files: Boolean, steam: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val p = prefs()
        if (files) {
            // One folder that can't be read (its permission gone, the card out) leaves the others working.
            fun <T> each(source: String, read: () -> T): T? = try {
                read()
            } catch (e: Exception) {
                Log.w(TAG, "reading $source's folder failed", e)
                null
            }
            launches = buildList {
                each(CocoonLog.SOURCE) { folder(p.cocoonFolder)?.let(::cocoon) }?.let(::addAll)
                each(EsdeLog.SOURCE) { folder(p.esdeFolder)?.let(::esde) }?.let(::addAll)
                each(RetroArchHistory.SOURCE) { folder(p.retroArchFolder)?.let(::retroArch) }?.let(::add)
            }
            if (Hosts.isEden(packageName)) each(Eden.SOURCE) { folder(p.edenFolder)?.let { eden(it, now) } }
        }
        if (steam) {
            steamNow = try {
                steamPlaying(p)
            } catch (e: Exception) {
                Log.w(TAG, "asking Steam failed: ${e.message}")
                steamNow // a failed ask keeps the last answer
            }
        }
    }

    // ── the sources ──────────────────────────────────────────────────────────

    private fun cocoon(folder: Folder): List<Launch> {
        // The log sits in the folder chosen, or one below it.
        val doc = folder.find(listOf(CocoonLog.FILE)) ?: folder.children(folder.root).filter { it.isDir }
            .firstNotNullOfOrNull { dir -> folder.child(dir.id, CocoonLog.FILE) } ?: return emptyList()
        return cachedRead(folder, doc) { text -> CocoonLog.launches(text.lines(), ZoneId.systemDefault()) }.orEmpty()
    }

    private fun esde(folder: Folder): List<Launch> {
        val doc = folder.find(listOf(EsdeLog.FOLDER, EsdeLog.FILE)) ?: return emptyList()
        return cachedRead(folder, doc) { text -> EsdeLog.launches(text.lines(), System.currentTimeMillis(), ZoneId.systemDefault()) }.orEmpty()
    }

    private fun retroArch(folder: Folder): Launch? {
        val doc = RetroArchHistory.PATHS.firstNotNullOfOrNull { folder.find(it) } ?: return null
        return cachedRead(folder, doc) { text -> RetroArchHistory.latest(text, doc.modified) }
    }

    private fun eden(folder: Folder, now: Long) {
        folder.find(Eden.LOG)?.let { doc -> edenNames = cachedRead(folder, doc) { Eden.loaded(it.lines()) }.orEmpty() }
        val doc = folder.find(Eden.PLAY_TIME) ?: return
        val times = Eden.playTimes(folder.bytes(doc, MAX_BYTES))
        edenTimes?.let { before -> Eden.grown(before, times)?.let { edenGrown = it to now } }
        edenTimes = times
    }

    /** Reads a file again only when it has changed. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> cachedRead(folder: Folder, doc: Folder.Doc, parse: (String) -> T): T? {
        val key = doc.uri.toString()
        read[key]?.let { (modified, value) -> if (modified == doc.modified) return value as T? }
        val value = parse(String(folder.bytes(doc, MAX_BYTES), Charsets.UTF_8))
        read[key] = doc.modified to value
        return value
    }

    private fun folder(uri: String): Folder? = if (uri.isEmpty()) null else Folder(context, Uri.parse(uri))

    // ── Steam ────────────────────────────────────────────────────────────────

    /** The game the account in Settings is playing, or null; throws with a message to show. */
    private fun steamPlaying(p: Prefs): Named? {
        if (p.steamKey.isBlank() || p.steamAccount.isBlank()) return null
        val id = resolve(p) ?: throw IOException("Steam knows no profile called “${p.steamAccount.trim()}”.")
        return Steam.playing(get(Steam.summariesUrl(p.steamKey, id)))
    }

    private fun resolve(p: Prefs): String? {
        steamId?.let { (typed, id) -> if (typed == p.steamAccount) return id }
        val id = when (val account = Steam.account(p.steamAccount) ?: return null) {
            is Steam.Account.Id -> account.id
            is Steam.Account.Vanity -> Steam.vanityId(get(Steam.vanityUrl(p.steamKey, account.name))) ?: return null
        }
        steamId = p.steamAccount to id
        return id
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            when (val code = connection.responseCode) {
                200 -> return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                401, 403 -> throw IOException("Steam turned the key down. Check it at steamcommunity.com/dev/apikey.")
                else -> throw IOException("Steam answered $code. Try again later.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** For Settings: what Steam says right now, in words. */
    suspend fun testSteam(): String = withContext(Dispatchers.IO) {
        val p = prefs()
        if (p.steamKey.isBlank()) return@withContext "Add a Web API key first."
        if (p.steamAccount.isBlank()) return@withContext "Add your Steam profile first."
        try {
            steamId = null
            val playing = steamPlaying(p)
            steamNow = playing
            if (playing != null) "Steam says you are playing ${playing.title}." else "Steam can see the profile; it is not in a game now."
        } catch (e: IOException) {
            e.message ?: "Steam could not be reached."
        } catch (e: Exception) {
            "Steam’s answer made no sense: ${e.message}"
        }
    }

    /** For Settings: what a folder holds, in words. */
    suspend fun describe(source: String): String = withContext(Dispatchers.IO) {
        val p = prefs()
        try {
            when (source) {
                CocoonLog.SOURCE -> folder(p.cocoonFolder)?.let { f ->
                    lastLaunch(cocoon(f), "No $LOG_NAME here yet. Start a game from Cocoon, or choose the folder that holds Cocoon's files.")
                }
                EsdeLog.SOURCE -> folder(p.esdeFolder)?.let { f ->
                    lastLaunch(esde(f), "No logs/es_log.txt with a game in it. Choose the ES-DE folder, and start a game from ES-DE.")
                }
                RetroArchHistory.SOURCE -> folder(p.retroArchFolder)?.let { f ->
                    retroArch(f)?.let { "Last played: ${it.title}" } ?: "No play history here. Choose the RetroArch folder, and check Settings › Playlists › History is on."
                }
                Eden.SOURCE -> folder(p.edenFolder)?.let { f ->
                    val names = f.find(Eden.LOG)?.let { doc -> Eden.loaded(String(f.bytes(doc, MAX_BYTES)).lines()) }.orEmpty()
                    val times = f.find(Eden.PLAY_TIME)?.let { Eden.playTimes(f.bytes(it, MAX_BYTES)) }
                    when {
                        names.isNotEmpty() -> "Last loaded: ${names.last().second}"
                        times != null -> "Play times for ${times.size} games; no names until Eden opens one."
                        else -> "Neither play_time nor log here. Choose Eden in the file picker's side menu."
                    }
                }
                else -> null
            } ?: "No folder chosen."
        } catch (e: Exception) {
            "This folder can't be read any more. Choose it again."
        }
    }

    private fun lastLaunch(launches: List<Launch>, none: String) =
        launches.maxByOrNull { it.atMs }?.let { "Last started: ${it.title}" + (it.packageName?.let { p -> " in $p" } ?: "") } ?: none

    companion object {
        private const val TAG = "LfgGameFinder"
        private const val LOG_NAME = CocoonLog.FILE
        /** Front-ends' logs and play times are looked at this often while an emulator is in front. */
        private const val FILES_MS = 5_000L
        /** Steam is asked this often while a Steam player is in front: well inside its 100,000 calls a day. */
        private const val STEAM_MS = 30_000L
        /** Eden writes play time every 30 seconds: a title is running while its time grew this recently. */
        private const val EDEN_RUNNING_MS = 75_000L
        /** Only the end of a long log is read; the newest launches are there. */
        private const val MAX_BYTES = 512 * 1024
    }
}

/** A folder chosen in Android's picker, walked by name. */
class Folder(private val context: Context, private val tree: Uri) {
    class Doc(val id: String, val uri: Uri, val name: String, val isDir: Boolean, val modified: Long, val size: Long)

    val root: String = DocumentsContract.getTreeDocumentId(tree)

    fun children(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Doc>()
        context.contentResolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += Doc(
                    id, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(1).orEmpty(),
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4),
                )
            }
        }
        return out
    }

    fun child(parentId: String, name: String): Doc? = children(parentId).firstOrNull { it.name == name }

    /** The document at [path] under the chosen folder, or null. */
    fun find(path: List<String>): Doc? {
        var id = root
        var doc: Doc? = null
        for (name in path) {
            doc = child(id, name) ?: return null
            id = doc.id
        }
        return doc
    }

    /** The file's last [max] bytes: all of it, if it is shorter. */
    fun bytes(doc: Doc, max: Int): ByteArray = context.contentResolver.openInputStream(doc.uri)?.use { input ->
        if (doc.size > max) {
            var skip = doc.size - max
            while (skip > 0) {
                val skipped = input.skip(skip)
                if (skipped <= 0) break
                skip -= skipped
            }
        }
        input.readBytes()
    } ?: ByteArray(0)

    companion object {
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

/** The sources read from a folder, and which setting holds each one's. */
object GameNameSources {
    val ALL = listOf(CocoonLog.SOURCE, EsdeLog.SOURCE, RetroArchHistory.SOURCE, Eden.SOURCE)

    fun folder(p: Prefs, source: String): String = when (source) {
        CocoonLog.SOURCE -> p.cocoonFolder
        EsdeLog.SOURCE -> p.esdeFolder
        RetroArchHistory.SOURCE -> p.retroArchFolder
        Eden.SOURCE -> p.edenFolder
        else -> ""
    }

    fun withFolder(p: Prefs, source: String, uri: String): Prefs = when (source) {
        CocoonLog.SOURCE -> p.copy(cocoonFolder = uri)
        EsdeLog.SOURCE -> p.copy(esdeFolder = uri)
        RetroArchHistory.SOURCE -> p.copy(retroArchFolder = uri)
        Eden.SOURCE -> p.copy(edenFolder = uri)
        else -> p
    }
}
