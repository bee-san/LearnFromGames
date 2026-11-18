// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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

    private val _fetching = MutableStateFlow<String?>(null)

    /** How the download of Bee's Ultimate Dictionary is going, in words; null when it is not under way. */
    val fetching: StateFlow<String?> = _fetching

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

    /**
     * Imports what the user picked: a Yomitan zip, or Rubi's dictionary (a .vjdict
     * file, or a zip holding one), which is first turned into a Yomitan zip in the
     * cache. [name] is the picked file's own, the title of a dictionary that names
     * none; [status] hears how the conversion is going. Returns null on success,
     * or what went wrong in words.
     */
    suspend fun addPicked(file: File, name: String, status: (String) -> Unit = {}): String? {
        val found = try {
            withContext(Dispatchers.IO) { VjdictImport.find(file, name, context.cacheDir) }
        } catch (e: Exception) {
            return e.message ?: "The file could not be read."
        } ?: return addFromZip(file)
        val zip = File(context.cacheDir, "vjdict-${System.currentTimeMillis()}.zip")
        return try {
            withContext(Dispatchers.IO) {
                VjdictImport.toYomitan(found.file, found.name, found.attribution, zip) { status("Converting… $it%") }
                // The copy is not needed for the import; its room is.
                if (found.temporary) found.file.delete()
            }
            status("Importing…")
            addFromZip(zip)
        } catch (e: Exception) {
            e.message ?: "The dictionary could not be converted."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                zip.delete()
                if (found.temporary) found.file.delete()
            }
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
        fun field(name: String) = unescape((index?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
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

    /**
     * hoshidicts stores index.json's strings still escaped and escapes them again, so a
     * line break reads back as a backslash and an n. Read once more as a JSON string,
     * they are the dictionary's own text; anything that is not one stays as it is.
     */
    private fun unescape(text: String): String = if ('\\' !in text) text else try {
        DictJson.decodeFromString<String>("\"" + text + "\"")
    } catch (_: Exception) {
        text
    }

    /** Bee's Ultimate Dictionary has been installed once (and perhaps removed since): it is never fetched again. */
    fun rubiSettled(): Boolean = prefs.getBoolean(KEY_RUBI_DONE, false) || installed().any { it.title == RubiDictionarySource.TITLE }

    /**
     * Downloads Bee's Ultimate Dictionary from Rubi's latest release, checks it
     * against the release's checksums, converts it and puts it first. Once: after
     * that, removing it is final. Returns false when it should be tried again (no
     * network, a download cut short); a release whose contents fail
     * [MAX_RUBI_FAILURES] times is not downloaded again. Only the import itself
     * waits for, and holds up, other dictionaries being added.
     */
    suspend fun ensureRubiDictionary(): Boolean {
        if (rubiSettled()) {
            prefs.edit().putBoolean(KEY_RUBI_DONE, true).apply()
            return true
        }
        val title = RubiDictionarySource.TITLE
        val asset = try {
            withContext(Dispatchers.IO) { RubiDictionarySource.candidates(fetchText(RubiDictionarySource.RELEASES_URL)) }
                .firstOrNull { prefs.getInt(KEY_RUBI_FAILED + it.release, 0) < MAX_RUBI_FAILURES }
        } catch (_: Exception) {
            return false
        } ?: return true // Nothing to try until Rubi releases again.
        val cache = context.cacheDir
        // The most it holds at once is the zip and the dictionary out of it (then the Yomitan zip, 47 MB, and the import, 111 MB).
        if (cache.usableSpace < asset.size + 2 * RUBI_DICTIONARY_BYTES) return true
        val zip = File(cache, "rubi-${asset.release}.zip")
        val yomitan = File(cache, "rubi-${asset.release}-yomitan.zip")
        var found: VjdictImport.Found? = null
        // Only a connection that failed before the zip was opened leaves it, for the next try to carry on from (its checksum is checked again).
        var keepZip = false
        try {
            val sums = withContext(Dispatchers.IO) { fetchText(asset.sumsUrl) }
            val want = RubiDictionarySource.checksum(sums, asset.name) ?: throw BadRelease("Rubi ${asset.release} lists no checksum for its zip.")
            var shown = -1L
            withContext(Dispatchers.IO) {
                // A download cut short last time carries on from where it stopped.
                if (zip.length() != asset.size) downloadTo(asset.url, zip, resume = true) { done ->
                    val mb = done shr 20
                    if (mb != shown) {
                        shown = mb
                        _fetching.value = "Downloading $title… $mb of ${asset.size shr 20} MB"
                    }
                }
            }
            _fetching.value = "Checking $title…"
            if (zip.length() < asset.size) error("The download was cut short.")
            if (withContext(Dispatchers.IO) { VjdictImport.sha256(zip) } != want) {
                throw BadRelease("Rubi ${asset.release}'s zip does not match its checksum.")
            }
            found = withContext(Dispatchers.IO) { VjdictImport.find(zip, asset.name, cache) }
                ?: throw BadRelease("Rubi ${asset.release} holds no dictionary.")
            zip.delete()
            withContext(Dispatchers.IO) {
                VjdictImport.toYomitan(found.file, found.name, found.attribution, yomitan) { _fetching.value = "Converting $title… $it%" }
            }
            found.file.delete()
            _fetching.value = "Importing $title…"
            installing.withLock {
                val outcome = importZip(yomitan)
                if (!outcome.success || outcome.terms < RubiDictionarySource.MIN_TERMS) {
                    if (outcome.title.isNotEmpty() && installed().none { it.title == outcome.title }) File(root, outcome.title).deleteRecursively()
                    throw BadRelease(outcome.error.ifEmpty { "Rubi ${asset.release}'s dictionary has only ${outcome.terms} entries." })
                }
                register(outcome, first = true)
            }
            prefs.edit().putBoolean(KEY_RUBI_DONE, true).apply()
            return true
        } catch (e: BadRelease) {
            android.util.Log.w("LfgDictionaries", e.message ?: "bad release")
            prefs.edit().putInt(KEY_RUBI_FAILED + asset.release, prefs.getInt(KEY_RUBI_FAILED + asset.release, 0) + 1).apply()
            return false
        } catch (e: VjdictException) {
            android.util.Log.w("LfgDictionaries", "Rubi ${asset.release}: ${e.message}")
            prefs.edit().putInt(KEY_RUBI_FAILED + asset.release, prefs.getInt(KEY_RUBI_FAILED + asset.release, 0) + 1).apply()
            return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Android stopped the work (the network went): it carries on from here next time.
            keepZip = found == null && zip.length() <= asset.size
            throw e
        } catch (e: Exception) {
            // The connection, most likely: what was downloaded stays for the next try.
            android.util.Log.w("LfgDictionaries", "Rubi ${asset.release}: ${e.message}")
            keepZip = found == null && zip.length() <= asset.size
            return false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!keepZip) zip.delete()
                yomitan.delete()
                found?.takeIf { it.temporary }?.file?.delete()
            }
            _fetching.value = null
        }
    }

    /** What is wrong with a release's contents rather than with the connection: it counts against the release. */
    private class BadRelease(message: String) : Exception(message)

    private suspend fun importZip(zip: File): ImportOutcome = withContext(native) {
        root.mkdirs()
        DictJson.decodeFromString<ImportOutcome>(Hoshidicts.import(zip.path, root.path, lowRam = false))
    }

    /** [first]: it goes to the top of the list, so its entries show first. */
    private suspend fun register(outcome: ImportOutcome, first: Boolean = false) {
        val added = InstalledDictionary(
            title = outcome.title,
            path = File(root, outcome.title).path,
            terms = outcome.terms,
            frequencies = outcome.frequencies,
            pitches = outcome.pitches,
            kanji = outcome.kanji,
            installedAt = System.currentTimeMillis(),
        )
        val others = installed().filter { it.title != added.title }
        save(if (first) listOf(added) + others else others + added)
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
        _state.value = DictState.Downloading(0, asset.size)
        val done = downloadTo(asset.url, to) { _state.value = DictState.Downloading(it, asset.size) }
        if (done < JmdictSource.MIN_ZIP_BYTES) error("The download was only $done bytes.")
    }

    /**
     * Saves [url] to [to], telling [progress] the bytes so far; returns how many there are.
     * With [resume], what [to] already holds is kept and only the rest is asked for.
     */
    private fun downloadTo(url: String, to: File, resume: Boolean = false, progress: (Long) -> Unit): Long {
        val have = if (resume && to.isFile) to.length() else 0L
        val connection = open(url)
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = connection.responseCode
            if (code != 200 && code != 206) error("The download answered $code.")
            // 206: the rest follows on from what is there. 200: the server sent it all again.
            val append = code == 206
            var done = if (append) have else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(to, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        progress(done)
                    }
                }
            }
            return done
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
        private const val KEY_RUBI_DONE = "rubiDictionaryDone"
        private const val KEY_RUBI_FAILED = "rubiDictionaryFailed."
        private const val MAX_RUBI_FAILURES = 3

        /** Bee's Ultimate Dictionary unpacked, about 156 MB. */
        private const val RUBI_DICTIONARY_BYTES = 160L * 1024 * 1024
        private const val SCAN_LENGTH = 16
    }
}
