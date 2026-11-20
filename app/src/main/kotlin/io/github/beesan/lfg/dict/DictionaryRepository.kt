// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import kotlinx.coroutines.CancellationException
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
            terms.takeIf { it > 0 }?.let { "%,d %s".format(it, if (it == 1L) "term" else "terms") },
            frequencies.takeIf { it > 0 }?.let { "frequencies" },
            pitches.takeIf { it > 0 }?.let { "pitch accents" },
            kanji.takeIf { it > 0 }?.let { "%,d kanji".format(it) },
        ).joinToString(" · ")
}

/** What a dictionary says about itself: shown on the sources screen, which JMdict's and Jitendex's licences require. */
data class DictionarySource(val title: String, val revision: String, val author: String, val url: String, val attribution: String, val description: String)

sealed interface DictState {
    data object Missing : DictState
    data class Downloading(val name: String, val bytes: Long, val total: Long) : DictState
    data class Installing(val name: String) : DictState
    data class Ready(val dictionaries: List<InstalledDictionary>) : DictState
    data class Failed(val reason: String) : DictState
}

/**
 * The dictionaries on the device and lookups in them. A new install fetches
 * Hachidori's starter set ([DefaultDictionaries]) without being asked. All native calls run one at a time on
 * one thread: a hoshidicts session is not shared across threads here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("dictionaries", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "dictionaries")
    private val native = Dispatchers.IO.limitedParallelism(1)
    private val installing = Mutex()
    private val fetchingDefaults = Mutex()
    private var session = 0L

    private val _state = MutableStateFlow<DictState>(installed().let { if (it.isEmpty()) DictState.Missing else DictState.Ready(it) })
    val state: StateFlow<DictState> = _state

    /**
     * A starter dictionary arriving once another is installed, as Downloading
     * or Installing; null when none is. [state] stays Ready meanwhile, so lookups
     * and a finished set-up carry on.
     */
    private val _fetching = MutableStateFlow<DictState?>(null)
    val fetching: StateFlow<DictState?> = _fetching

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
        val json = io.github.beesan.lfg.core.Perf.measure("lookup.native") { Hoshidicts.lookup(handle, scan, maxResults, SCAN_LENGTH) }
        io.github.beesan.lfg.core.Perf.measure("lookup.decode") { DictJson.decodeFromString<LookupResponse>(json).results }
    }

    /** How many characters of [text] its best match covers; 0 when none. For stepping word by word. */
    suspend fun matchLength(text: String): Int = withContext(native) {
        if (text.isEmpty()) return@withContext 0
        val handle = openSession()
        if (handle == 0L) 0 else io.github.beesan.lfg.core.Perf.measure("match") { Hoshidicts.matchLength(handle, text.take(SCAN_LENGTH), SCAN_LENGTH) }
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
     * The starter dictionaries this install has yet to fetch. They are chosen
     * once, when the device has no dictionary; a device that already had some
     * keeps its own set, and one the user removes later does not come back.
     */
    fun defaultsPending(): Boolean = prefs.getStringSet(KEY_DEFAULTS_PENDING, null)?.isNotEmpty() == true

    /**
     * Downloads and imports each starter dictionary still to come, Jitendex
     * first so lookups work as soon as it is in. Returns true when none is left
     * to fetch. Safe to call again after a failure, which retries only what
     * failed; a second caller waits for the first. Each import holds the same
     * lock as "Add dictionary…", but the downloads do not.
     */
    suspend fun ensureDefaults(): Boolean = fetchingDefaults.withLock {
        val chosen = prefs.getStringSet(KEY_DEFAULTS_PENDING, null)
            ?: if (installed().isEmpty()) DefaultDictionaries.ALL.map { it.id }.toSet() else return true
        val pending = chosen.toMutableSet()
        fun remember() = prefs.edit().putStringSet(KEY_DEFAULTS_PENDING, pending.toSet()).apply()
        remember()
        var lastError: String? = null
        for (source in DefaultDictionaries.ALL) {
            if (source.id !in pending) continue
            if (installed().none(source::matches)) {
                val error = try {
                    install(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "${source.name} could not be downloaded."
                }
                if (error != null) {
                    lastError = error
                    continue
                }
            }
            pending -= source.id
            remember()
        }
        // Unknown ids (a source since dropped) are not waited for.
        pending.retainAll { DefaultDictionaries.byId(it) != null }
        remember()
        val now = installed()
        _state.value = when {
            now.isNotEmpty() -> DictState.Ready(now)
            else -> DictState.Failed(lastError ?: "The dictionary download failed.")
        }
        pending.isEmpty()
    }

    /** Downloads and imports one starter dictionary. Returns null on success, or what went wrong. */
    private suspend fun install(source: DefaultDictionaries.Source): String? {
        val zip = File(context.cacheDir, "default-${source.id}.zip")
        val background = installed().isNotEmpty()
        val report: (DictState) -> Unit = { if (background) _fetching.value = it else _state.value = it }
        try {
            withContext(Dispatchers.IO) { download(source, zip, report) }
            report(DictState.Installing(source.name))
            return installing.withLock {
                val outcome = importZip(zip)
                if (source.accepts(outcome)) {
                    register(outcome)
                    return@withLock null
                }
                if (outcome.title.isNotEmpty() && installed().none { it.title == outcome.title }) File(root, outcome.title).deleteRecursively()
                outcome.error.ifEmpty { "${source.name} looks incomplete: ${outcome.terms} entries, ${outcome.frequencies} frequencies." }
            }
        } finally {
            _fetching.value = null
            withContext(NonCancellable + Dispatchers.IO) { zip.delete() }
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

    private fun download(source: DefaultDictionaries.Source, to: File, report: (DictState) -> Unit) {
        val connection = open(source.url)
        try {
            if (connection.responseCode != 200) error("${source.name}'s download answered ${connection.responseCode}.")
            val total = connection.contentLengthLong
            var done = 0L
            report(DictState.Downloading(source.name, 0, total))
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        report(DictState.Downloading(source.name, done, total))
                    }
                }
            }
            if (done < source.minBytes) error("${source.name}'s download was only $done bytes.")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/zip, application/octet-stream")
        setRequestProperty("User-Agent", "LearnFromGames")
    }

    companion object {
        private const val KEY_INSTALLED = "installed"
        private const val KEY_DEFAULTS_PENDING = "defaults_pending"
        private const val SCAN_LENGTH = 16
    }
}
