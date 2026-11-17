// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class InstalledDictionary(
    val title: String,
    val path: String,
    val terms: Long,
    val frequencies: Long = 0,
    val pitches: Long = 0,
    val kanji: Long = 0,
    val installedAt: Long,
) {
    /** What the dictionary holds, for its row: `Japanese–English terms`-style detail is not stored, so counts are shown. */
    val summary: String
        get() = listOfNotNull(
            terms.takeIf { it > 0 }?.let { "%,d terms".format(it) },
            frequencies.takeIf { it > 0 }?.let { "frequencies" },
            pitches.takeIf { it > 0 }?.let { "pitch accents" },
            kanji.takeIf { it > 0 }?.let { "%,d kanji".format(it) },
        ).joinToString(" · ")
}

/** What a dictionary says about itself: shown on the sources screen, which JMdict's licence requires. */
data class DictionarySource(val title: String, val revision: String, val author: String, val url: String, val attribution: String, val description: String)

sealed interface DictState {
    data object Missing : DictState
    data class Downloading(val bytes: Long, val total: Long) : DictState
    data object Installing : DictState
    data class Ready(val dictionaries: List<InstalledDictionary>) : DictState
    data class Failed(val reason: String) : DictState
}

/**
 * The dictionaries on the device and lookups in them. The first one, JMdict,
 * installs itself without being asked. All native calls run one at a time on
 * one thread: a hoshidicts session is not shared across threads here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("dictionaries", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "dictionaries")
    private val native = Dispatchers.IO.limitedParallelism(1)
    private val installing = Mutex()
    private var session = 0L

    private val _state = MutableStateFlow<DictState>(installed().let { if (it.isEmpty()) DictState.Missing else DictState.Ready(it) })
    val state: StateFlow<DictState> = _state

    fun installed(): List<InstalledDictionary> = prefs.getString(KEY_INSTALLED, null)?.let {
        try {
            DictJson.decodeFromString<List<InstalledDictionary>>(it)
        } catch (_: Exception) {
            null
        }
    }?.filter { File(it.path).isDirectory } ?: emptyList()

    /** Entries for the longest words starting at the first character of [text], best first. */
    suspend fun lookup(text: String, maxResults: Int = 8): List<Entry> = withContext(native) {
        if (text.isEmpty()) return@withContext emptyList()
        val handle = openSession()
        if (handle == 0L) return@withContext emptyList()
        // The engine scans at most this many characters ahead; no word is longer.
        val scan = text.take(SCAN_LENGTH)
        DictJson.decodeFromString<LookupResponse>(Hoshidicts.lookup(handle, scan, maxResults, SCAN_LENGTH)).results
    }

    private fun openSession(): Long {
        if (session != 0L) return session
        val dictionaries = installed()
        if (dictionaries.isEmpty()) return 0L
        val handle = Hoshidicts.open()
        if (handle == 0L) return 0L
        for (d in dictionaries) {
            if (d.terms > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_TERM)
            if (d.frequencies > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_FREQUENCY)
            if (d.pitches > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_PITCH)
            if (d.kanji > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_KANJI)
        }
        session = handle
        return handle
    }

    private suspend fun closeSession() = withContext(native) {
        if (session != 0L) Hoshidicts.close(session)
        session = 0L
        cachedStyles = null
    }

    private var cachedStyles: List<EntryHtml.Style>? = null

    /** Each dictionary's own stylesheet, for rendering its entries as it means them to look. */
    suspend fun styles(): List<EntryHtml.Style> = withContext(native) {
        cachedStyles ?: run {
            val handle = openSession()
            val parsed = if (handle == 0L) emptyList() else try {
                (DictJson.parseToJsonElement(Hoshidicts.styles(handle)) as kotlinx.serialization.json.JsonArray).mapNotNull {
                    val o = it as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val css = (o["css"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    val name = (o["dictionary"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    if (css.isBlank()) null else EntryHtml.Style(name, css)
                }
            } catch (_: Exception) {
                emptyList()
            }
            parsed.also { cachedStyles = it }
        }
    }

    /** A picture a dictionary ships, or null. Called from the WebView's own thread, so it blocks. */
    fun mediaBlocking(dictionary: String, path: String): ByteArray? = kotlinx.coroutines.runBlocking(native) {
        val handle = openSession()
        if (handle == 0L) null else Hoshidicts.media(handle, dictionary, path).takeIf { it.isNotEmpty() }
    }

    /**
     * Imports a Yomitan zip the user picked. Returns null on success, or what
     * went wrong in words.
     */
    suspend fun addFromZip(zip: File): String? = installing.withLock {
        try {
            val outcome = importZip(zip)
            if (!outcome.success || outcome.title.isEmpty()) return outcome.error.ifEmpty { "This is not a Yomitan dictionary." }
            if (outcome.terms + outcome.frequencies + outcome.pitches + outcome.kanji == 0L) {
                File(root, outcome.title).deleteRecursively()
                return "The dictionary is empty."
            }
            register(outcome)
            null
        } catch (e: Exception) {
            e.message ?: "The dictionary could not be imported."
        }
    }

    /** Entries show in this order. */
    suspend fun move(title: String, up: Boolean) {
        val all = installed().toMutableList()
        val from = all.indexOfFirst { it.title == title }
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in all.indices) return
        all.add(to, all.removeAt(from))
        save(all)
    }

    suspend fun remove(title: String) {
        val all = installed()
        val gone = all.firstOrNull { it.title == title } ?: return
        save(all - gone)
        withContext(Dispatchers.IO) { File(gone.path).deleteRecursively() }
    }

    private suspend fun save(all: List<InstalledDictionary>) {
        prefs.edit().putString(KEY_INSTALLED, DictJson.encodeToString(all)).apply()
        closeSession()
        _state.value = if (all.isEmpty()) DictState.Missing else DictState.Ready(all)
    }

    fun sources(): List<DictionarySource> = installed().map { d ->
        val index = try {
            DictJson.parseToJsonElement(File(d.path, "index.json").readText()) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) {
            null
        }
        fun field(name: String) = (index?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        DictionarySource(d.title, field("revision"), field("author"), field("url"), field("attribution"), field("description"))
    }

    /**
     * Downloads and imports JMdict unless a dictionary is already there.
     * Returns true when one is installed at the end. Safe to call again after a
     * failure; a second caller waits for the first.
     */
    suspend fun ensureJmdict(): Boolean = installing.withLock {
        if (installed().isNotEmpty()) return true
        try {
            val candidates = withContext(Dispatchers.IO) { JmdictSource.candidates(fetchText(JmdictSource.RELEASES_URL)) }
            if (candidates.isEmpty()) error("No usable JMdict release was found.")
            var lastError = "JMdict could not be installed."
            // The newest plausible release, then the one before it.
            for (asset in candidates.take(2)) {
                val zip = File(context.cacheDir, "jmdict-${asset.release}.zip")
                try {
                    withContext(Dispatchers.IO) { download(asset, zip) }
                    _state.value = DictState.Installing
                    val outcome = importZip(zip)
                    if (JmdictSource.plausible(outcome)) {
                        register(outcome)
                        return true
                    }
                    lastError = outcome.error.ifEmpty { "JMdict ${asset.release} has only ${outcome.terms} entries." }
                    if (outcome.title.isNotEmpty()) File(root, outcome.title).deleteRecursively()
                } finally {
                    zip.delete()
                }
            }
            _state.value = DictState.Failed(lastError)
            false
        } catch (e: Exception) {
            _state.value = DictState.Failed(e.message ?: "The dictionary download failed.")
            false
        }
    }

    private suspend fun importZip(zip: File): ImportOutcome = withContext(native) {
        root.mkdirs()
        DictJson.decodeFromString<ImportOutcome>(Hoshidicts.import(zip.path, root.path, lowRam = false))
    }

    private suspend fun register(outcome: ImportOutcome) {
        val added = InstalledDictionary(
            title = outcome.title,
            path = File(root, outcome.title).path,
            terms = outcome.terms,
            frequencies = outcome.frequencies,
            pitches = outcome.pitches,
            kanji = outcome.kanji,
            installedAt = System.currentTimeMillis(),
        )
        save(installed().filter { it.title != added.title } + added)
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) error("GitHub answered ${connection.responseCode}.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(asset: JmdictSource.Asset, to: File) {
        val connection = open(asset.url)
        try {
            if (connection.responseCode != 200) error("The download answered ${connection.responseCode}.")
            var done = 0L
            _state.value = DictState.Downloading(0, asset.size)
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        _state.value = DictState.Downloading(done, asset.size)
                    }
                }
            }
            if (done < JmdictSource.MIN_ZIP_BYTES) error("The download was only $done bytes.")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        setRequestProperty("User-Agent", "LearnFromGames")
    }

    companion object {
        private const val KEY_INSTALLED = "installed"
        private const val SCAN_LENGTH = 16
    }
}
