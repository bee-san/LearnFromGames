// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context
import io.github.beesan.lfg.core.downloadProblem
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
import io.github.beesan.lfg.core.Language
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
    /**
     * A visual novel's character names, by its VNDB ID (`v17`): searched only for
     * games linked to it, and first there. Null for an ordinary dictionary.
     */
    val vndb: String? = null,
    /** The tag of the language it is a dictionary of. Those from before there was a second language are Japanese. */
    val language: String = "ja",
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
class DictionaryRepository(
    private val context: Context,
    /** The language being learnt, or null before set-up has asked: its dictionaries are the ones listed, fetched and looked up in. */
    private val language: () -> Language? = { Language.JAPANESE },
) {
    private val prefs = context.getSharedPreferences("dictionaries", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "dictionaries")
    private val native = Dispatchers.IO.limitedParallelism(1)
    private val installing = Mutex()
    private val fetchingDefaults = Mutex()
    /**
     * Open hoshidicts sessions: "" holds the ordinary dictionaries, a VNDB ID
     * that visual novel's names before them. At most [MAX_SESSIONS], as the
     * Thor can show one game on a screen and another game's lines on the other.
     */
    private val sessions = LinkedHashMap<String, Long>(4, 0.75f, true)

    private val _state = MutableStateFlow<DictState>(installed().let { if (it.isEmpty()) DictState.Missing else DictState.Ready(it) })
    val state: StateFlow<DictState> = _state

    /**
     * A starter dictionary arriving once another is installed, as Downloading
     * or Installing; null when none is. [state] stays Ready meanwhile, so lookups
     * and a finished set-up carry on.
     */
    private val _fetching = MutableStateFlow<DictState?>(null)
    val fetching: StateFlow<DictState?> = _fetching

    /** The dictionaries of the language being learnt. Another language's stay on the device, for when it is learnt again. */
    fun installed(): List<InstalledDictionary> = (language() ?: Language.JAPANESE).tag.let { tag -> everything().filter { it.language == tag } }

    private fun everything(): List<InstalledDictionary> = prefs.getString(KEY_INSTALLED, null)?.let {
        try {
            DictJson.decodeFromString<List<InstalledDictionary>>(it)
        } catch (_: Exception) {
            null
        }
    }?.filter { File(it.path).isDirectory } ?: emptyList()

    /**
     * Entries for the longest words starting at the first character of [text], best first.
     * [vndb] is the visual novel the text is from, whose character names lead; null for none.
     */
    suspend fun lookup(text: String, vndb: String? = null, maxResults: Int = 8): List<Entry> = withContext(native) {
        if (text.isEmpty()) return@withContext emptyList()
        val handle = openSession(vndb)
        if (handle == 0L) return@withContext emptyList()
        // The engine scans at most this many characters ahead; no word is longer.
        val scan = text.take(SCAN_LENGTH)
        val json = io.github.beesan.lfg.core.Perf.measure("lookup.native") { Hoshidicts.lookup(handle, scan, maxResults, SCAN_LENGTH, tag()) }
        io.github.beesan.lfg.core.Perf.measure("lookup.decode") { counted(DictJson.decodeFromString<LookupResponse>(json).results) }
    }

    /** Each dictionary's declared frequency mode, by its folder: read once from its index.json. */
    private val modes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [entries] with the figures of dictionaries that count words, rather than rank them, so marked. */
    private fun counted(entries: List<Entry>): List<Entry> {
        if (entries.none { it.frequencies.isNotEmpty() }) return entries
        val dictionaries = listed ?: installed().also { listed = it }
        val counting = dictionaries.filter { it.frequencies > 0 && modeOf(it) == FrequencyModes.OCCURRENCE }.mapTo(HashSet()) { it.title }
        if (counting.isEmpty()) return entries
        return entries.map { e ->
            if (e.frequencies.none { it.dictionary in counting }) e
            else e.copy(frequencies = e.frequencies.map { if (it.dictionary in counting) it.copy(occurrence = true) else it })
        }
    }

    private fun modeOf(d: InstalledDictionary): String = modes.getOrPut(d.path) {
        try {
            FrequencyModes.declared(File(d.path, "index.json").readText()).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * A list that says it counts words but holds ranks, as some made with
     * yomitanfrequencies.org do (Hachidori #684), is marked as ranks once it is
     * imported into [home]. The [zip] is read only for a list that says it counts.
     */
    private fun ranksIfSo(zip: File, home: File) {
        try {
            val index = File(home, "index.json")
            val text = index.readText()
            if (FrequencyModes.declared(text) != FrequencyModes.OCCURRENCE) return
            if (!FrequencyModes.holdRanks(FrequencyModes.values(zip))) return
            index.writeText(FrequencyModes.asRanks(text))
            modes.remove(home.path)
            android.util.Log.i(TAG, "${home.name} says it counts words but ranks them: read as ranks")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "${home.name}: its frequency figures could not be checked", e)
        }
    }

    /**
     * The entries for the one kanji [kanji] in the kanji dictionary: the one titled [title],
     * else Bee's Ultimate Kanji Dictionary. With neither installed, the entries headed by
     * [kanji] alone in every dictionary.
     */
    suspend fun lookupKanji(kanji: String, title: String = ""): List<Entry> = withContext(native) {
        if (kanji.isEmpty()) return@withContext emptyList()
        val dictionaries = listed ?: installed().also { listed = it }
        val chosen = kanjiDictionary(dictionaries, title)
        val handle = if (chosen != null) openSession(only = chosen) else openSession()
        if (handle == 0L) return@withContext emptyList()
        val json = Hoshidicts.lookup(handle, kanji, 16, SCAN_LENGTH, tag())
        counted(DictJson.decodeFromString<LookupResponse>(json).results.filter { it.expression == kanji })
    }

    /** How many characters of [text] its best match covers; 0 when none. For stepping word by word. */
    suspend fun matchLength(text: String, vndb: String? = null): Int = withContext(native) {
        if (text.isEmpty()) return@withContext 0
        val handle = openSession(vndb)
        if (handle == 0L) 0 else io.github.beesan.lfg.core.Perf.measure("match") { Hoshidicts.matchLength(handle, text.take(SCAN_LENGTH), SCAN_LENGTH, tag()) }
    }

    /**
     * The session for [vndb]'s names and the ordinary dictionaries, or the
     * ordinary ones alone when [vndb] is null or its names are not installed.
     */
    private fun openSession(vndb: String? = null, only: InstalledDictionary? = null): Long {
        val dictionaries = listed ?: installed().also { listed = it }
        val names = vndb?.let { id -> dictionaries.firstOrNull { it.vndb == id } }
        val learning = language() ?: Language.JAPANESE
        val key = learning.tag + ":" + (only?.let { "only:" + it.title } ?: names?.vndb ?: "")
        sessions[key]?.let { return it }
        val chosen = if (only != null) listOf(only) else listOfNotNull(names) + dictionaries.filter { it.vndb == null }
        if (chosen.isEmpty()) return 0L
        val handle = Hoshidicts.open()
        if (handle == 0L) return 0L
        // Japanese's rules are in the engine; every other language's come with the app, as hoshidicts' pack.
        if (!learning.japanese) {
            val problem = try {
                val pack = "languages/${learning.tag}"
                Hoshidicts.addLanguage(handle, context.assets.open("$pack/language.json").use { it.readBytes() }, context.assets.open("$pack/transforms.json").use { it.readBytes() })
            } catch (e: java.io.IOException) {
                "its files are missing: ${e.message}"
            }
            if (problem.isNotEmpty()) {
                android.util.Log.e(TAG, "the ${learning.title} language pack could not be used: $problem")
                Hoshidicts.close(handle)
                return 0L
            }
        }
        for (d in chosen) {
            if (d.terms > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_TERM)
            if (d.frequencies > 0 && (only != null || !kanjiRanksOnly(d))) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_FREQUENCY)
            if (d.pitches > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_PITCH)
            if (d.kanji > 0) Hoshidicts.addDictionary(handle, d.path, Hoshidicts.KIND_KANJI)
        }
        while (sessions.size >= MAX_SESSIONS) {
            val eldest = sessions.entries.first()
            Hoshidicts.close(eldest.value)
            sessions.remove(eldest.key)
        }
        sessions[key] = handle
        return handle
    }

    private fun tag() = (language() ?: Language.JAPANESE).tag

    /**
     * Another language is being learnt: its dictionaries are the ones listed and looked up in from here on.
     * The caller starts [DictionaryWorker] for those it has none of.
     */
    suspend fun onLanguage() {
        closeSession()
        _state.value = installed().let { if (it.isEmpty()) DictState.Missing else DictState.Ready(it) }
    }

    /** The starter dictionaries of the language being learnt; none before it is chosen. */
    private fun defaults(): List<DefaultDictionaries.Source> = language()?.let(DefaultDictionaries::of).orEmpty()

    /** Japanese keeps the key it had before there was a second language. */
    private fun pendingKey() = tag().let { if (it == "ja") KEY_DEFAULTS_PENDING else "$KEY_DEFAULTS_PENDING-$it" }

    /** [installed] as the sessions last saw it, so a lookup does not read the list each time. */
    private var listed: List<InstalledDictionary>? = null

    private suspend fun closeSession() = withContext(native) {
        for (handle in sessions.values) Hoshidicts.close(handle)
        sessions.clear()
        listed = null
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
        // A character's portrait is in its own visual novel's session.
        val handle = openSession((listed ?: installed().also { listed = it }).firstOrNull { it.title == dictionary }?.vndb)
        if (handle == 0L) null else Hoshidicts.media(handle, dictionary, path).takeIf { it.isNotEmpty() }
    }

    /**
     * Imports a Yomitan zip the user picked. Returns null on success, or what
     * went wrong in words.
     */
    suspend fun addFromZip(zip: File, vndb: String? = null): String? = installing.withLock {
        try {
            if (vndb != null) {
                // A rebuild replaces the old one, which may have had another title.
                val old = installed().filter { it.vndb == vndb }
                if (old.isNotEmpty()) {
                    save(installed() - old.toSet())
                    withContext(Dispatchers.IO) { old.forEach { File(it.path).deleteRecursively() } }
                }
            }
            val outcome = importZip(zip)
            if (!outcome.success || outcome.title.isEmpty()) return outcome.error.ifEmpty { "This is not a Yomitan dictionary." }
            if (outcome.terms + outcome.frequencies + outcome.pitches + outcome.kanji == 0L) {
                File(root, outcome.title).deleteRecursively()
                return "The dictionary is empty."
            }
            if (outcome.frequencies > 0) withContext(Dispatchers.IO) { ranksIfSo(zip, File(root, outcome.title)) }
            register(outcome, vndb)
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

    /** Entries show in this order. Character dictionaries keep their places: they always lead for their own game. */
    suspend fun move(title: String, up: Boolean) {
        val all = installed().toMutableList()
        val from = all.indexOfFirst { it.title == title }
        if (from < 0 || all[from].vndb != null) return
        var to = if (up) from - 1 else from + 1
        while (to in all.indices && all[to].vndb != null) to += if (up) -1 else 1
        if (to !in all.indices) return
        all.add(to, all.removeAt(from))
        save(all)
    }

    /** The character dictionary of a visual novel, if it is installed. */
    fun names(vndb: String): InstalledDictionary? = installed().firstOrNull { it.vndb == vndb }

    suspend fun remove(title: String) {
        val all = installed()
        val gone = all.firstOrNull { it.title == title } ?: return
        save(all - gone)
        withContext(Dispatchers.IO) { File(gone.path).deleteRecursively() }
    }

    /** [all] are the dictionaries of the language being learnt, in their order; the other languages' are kept as they are. */
    private suspend fun save(all: List<InstalledDictionary>) {
        val others = tag().let { tag -> everything().filter { it.language != tag } }
        prefs.edit().putString(KEY_INSTALLED, DictJson.encodeToString(others + all)).apply()
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
    /** The starter dictionaries not installed here, whether or not they are being fetched. */
    fun defaultsMissing(): List<DefaultDictionaries.Source> = installed().let { have -> defaults().filter { have.none(it::matches) } }

    /**
     * Asks for every starter dictionary that is missing, on a device that had
     * dictionaries of its own and so was never given them. [ensureDefaults] fetches them.
     */
    fun requestDefaults() {
        prefs.edit().putStringSet(pendingKey(), defaultsMissing().map { it.id }.toSet()).apply()
    }

    fun defaultsPending(): Boolean = prefs.getStringSet(pendingKey(), null)?.isNotEmpty() == true

    /**
     * Downloads and imports each starter dictionary still to come, Jitendex
     * first so lookups work as soon as it is in. Returns true when none is left
     * to fetch. Safe to call again after a failure, which retries only what
     * failed; a second caller waits for the first. Each import holds the same
     * lock as "Add dictionary…", but the downloads do not.
     */
    suspend fun ensureDefaults(): Boolean = fetchingDefaults.withLock {
        // Which language is not known until set-up has asked: nothing is fetched before.
        val all = defaults().ifEmpty { return true }
        val key = pendingKey()
        val chosen = prefs.getStringSet(key, null)
            ?: if (installed().isEmpty()) all.map { it.id }.toSet() else return true
        val pending = chosen.toMutableSet()
        fun remember() = prefs.edit().putStringSet(key, pending.toSet()).apply()
        remember()
        var lastError: String? = null
        for (source in all) {
            if (source.id !in pending) continue
            if (installed().none(source::matches)) {
                val error = try {
                    install(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    downloadProblem(e, "${source.name} could not be downloaded.")
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
        pending.retainAll { id -> all.any { it.id == id } }
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
            withContext(Dispatchers.IO) { download(source.name, source.url, source.minBytes, zip, report) }
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

    /** When the dictionaries were last asked about, in epoch milliseconds; 0 for never. */
    fun updatesCheckedAt(): Long = prefs.getLong(KEY_UPDATES_CHECKED, 0L)

    /**
     * Asks each dictionary that can be updated ([DictionaryUpdates]) whether its publisher has another
     * release, and puts that in its place: the same place in the order, since entries show in it.
     * Character names and dictionaries that name no index are left alone. A release that fails to
     * import, or holds far less than the one installed, is dropped and not fetched again until the
     * publisher's revision changes. Returns the names of those updated; a publisher that cannot be
     * reached is passed over until the next time.
     */
    suspend fun updateAll(): List<String> = fetchingDefaults.withLock {
        val updated = ArrayList<String>()
        for (old in installed().filter { it.vndb == null }) {
            try {
                update(old)?.let { updated += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no update for ${old.title}", e)
            }
        }
        prefs.edit().putLong(KEY_UPDATES_CHECKED, System.currentTimeMillis()).apply()
        updated
    }

    /** Updates [old] if its publisher has another release. Returns the new title, or null when nothing was changed. */
    private suspend fun update(old: InstalledDictionary): String? {
        val mine = withContext(Dispatchers.IO) { DictionaryUpdates.index(File(old.path, "index.json").readText()) } ?: return null
        if (!DictionaryUpdates.asks(mine)) return null
        val theirs = withContext(Dispatchers.IO) { DictionaryUpdates.index(fetchText(mine.indexUrl)) } ?: return null
        val url = DictionaryUpdates.download(mine, theirs) ?: return null
        val skip = KEY_UPDATE_SKIPPED + mine.indexUrl
        if (prefs.getString(skip, null) == theirs.revision) return null
        val zip = File(context.cacheDir, "update-${System.currentTimeMillis()}.zip")
        // Imported beside the dictionaries, not among them: the one in use is untouched until the new one is whole.
        val staging = File(context.filesDir, "dictionaries-update")
        try {
            withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                download(old.title, url, 1, zip) { _fetching.value = it }
            }
            _fetching.value = DictState.Installing(old.title)
            return installing.withLock {
                val outcome = importZip(zip, staging)
                val all = installed()
                val at = all.indexOfFirst { it.title == old.title }
                val taken = all.any { it.title == outcome.title && it.title != old.title }
                if (at < 0) return@withLock null
                if (!DictionaryUpdates.replaces(old, outcome) || taken || defaults().any { it.matches(old) && !it.accepts(outcome) }) {
                    android.util.Log.w(TAG, "${old.title}: release ${theirs.revision} not taken: ${outcome.error.ifEmpty { "${outcome.terms} entries" }}")
                    prefs.edit().putString(skip, theirs.revision).apply()
                    return@withLock null
                }
                val home = File(root, outcome.title)
                if (outcome.frequencies > 0) withContext(Dispatchers.IO) { ranksIfSo(zip, File(staging, outcome.title)) }
                val moved = withContext(native) {
                    // No session may hold the old files open while they go.
                    for (handle in sessions.values) Hoshidicts.close(handle)
                    sessions.clear()
                    File(old.path).deleteRecursively()
                    home.deleteRecursively()
                    File(staging, outcome.title).renameTo(home)
                }
                modes.remove(home.path)
                val new = old.copy(
                    title = outcome.title, path = home.path, terms = outcome.terms, frequencies = outcome.frequencies,
                    pitches = outcome.pitches, kanji = outcome.kanji, installedAt = System.currentTimeMillis(),
                )
                // The old one is gone either way; a move that failed leaves the starter set to fetch it again.
                save(all.toMutableList().apply { if (moved) set(at, new) else removeAt(at) })
                if (moved) outcome.title else null
            }
        } finally {
            _fetching.value = null
            withContext(NonCancellable + Dispatchers.IO) {
                zip.delete()
                staging.deleteRecursively()
            }
        }
    }

    /** A small text file, as an index.json is; more than a megabyte is not one. */
    private fun fetchText(url: String): String {
        val connection = open(url, "application/json")
        try {
            if (connection.responseCode != 200) error("$url answered ${connection.responseCode}.")
            return connection.inputStream.use { String(it.readNBytes(1024 * 1024), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
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

    private suspend fun importZip(zip: File, into: File = root): ImportOutcome = withContext(native) {
        into.mkdirs()
        DictJson.decodeFromString<ImportOutcome>(Hoshidicts.import(zip.path, into.path, lowRam = false))
    }

    private suspend fun register(outcome: ImportOutcome, vndb: String? = null) {
        val added = InstalledDictionary(
            title = outcome.title,
            path = File(root, outcome.title).path,
            terms = outcome.terms,
            frequencies = outcome.frequencies,
            pitches = outcome.pitches,
            kanji = outcome.kanji,
            installedAt = System.currentTimeMillis(),
            vndb = vndb,
            language = tag(),
        )
        modes.remove(added.path)
        save(installed().filter { it.title != added.title } + added)
    }

    private fun download(name: String, url: String, minBytes: Long, to: File, report: (DictState) -> Unit) {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) error("$name's download answered ${connection.responseCode}.")
            val total = connection.contentLengthLong
            var done = 0L
            report(DictState.Downloading(name, 0, total))
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        report(DictState.Downloading(name, done, total))
                    }
                }
            }
            if (done < minBytes) error("$name's download was only $done bytes.")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String = "application/zip, application/octet-stream"): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", "LearnFromGames")
    }

    companion object {
        private const val KEY_INSTALLED = "installed"
        private const val KEY_DEFAULTS_PENDING = "defaults_pending"
        private const val KEY_UPDATES_CHECKED = "updates_checked"
        private const val KEY_UPDATE_SKIPPED = "update_skipped:"
        private const val TAG = "LfgDictionaries"
        private const val SCAN_LENGTH = 16
        private const val MAX_SESSIONS = 4

        /** Bee's Ultimate Kanji Dictionary, which a tapped kanji is looked up in unless another is chosen. */
        val DEFAULT_KANJI = DefaultDictionaries.byId("bees-ultimate-kanji-dictionary")!!

        /**
         * Bee's Ultimate Kanji Dictionary ranks characters, not words: a word
         * of one kanji would get the character's rank, and pull its average
         * with it (Hachidori #724). Its ranks are kept for a tapped kanji alone.
         */
        fun kanjiRanksOnly(dictionary: InstalledDictionary): Boolean = DEFAULT_KANJI.matches(dictionary)

        /** The dictionary a tapped kanji is looked up in: the one titled [title], else [DEFAULT_KANJI]; null with neither installed. */
        fun kanjiDictionary(dictionaries: List<InstalledDictionary>, title: String): InstalledDictionary? {
            val ordinary = dictionaries.filter { it.vndb == null && it.terms > 0 }
            return ordinary.firstOrNull { title.isNotEmpty() && it.title == title } ?: ordinary.firstOrNull { DEFAULT_KANJI.matches(it) }
        }
    }
}
