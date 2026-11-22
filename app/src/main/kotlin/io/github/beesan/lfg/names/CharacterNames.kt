// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import android.content.Context
import android.net.ConnectivityManager
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.downloadProblem
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.data.LfgDatabase
import io.github.beesan.lfg.dict.DictionaryRepository
import io.github.beesan.lfg.lookup.VnQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A visual novel's character names, for the games linked to it: finding the
 * visual novel on VNDB, building its dictionary on the device, and which
 * dictionary a game's lookups use. LFG talks to VNDB and nothing else for this.
 */
class CharacterNames(
    private val context: Context,
    private val db: LfgDatabase,
    private val dictionaries: DictionaryRepository,
    private val scope: CoroutineScope,
    private val prefs: () -> Prefs,
    private val vndb: Vndb = Vndb(),
) {
    /** A build under way or failed, by VNDB ID; a finished one leaves the map. */
    sealed interface Build {
        data class Running(val stage: String, val done: Int, val total: Int) : Build
        data class Failed(val reason: String) : Build
    }

    private val _builds = MutableStateFlow<Map<String, Build>>(emptyMap())
    val builds: StateFlow<Map<String, Build>> = _builds

    private val store = context.getSharedPreferences("character_names", Context.MODE_PRIVATE)

    /** Each game's linked visual novel, "" for none, so a lookup does not ask the database each time. */
    private val vnByGame = ConcurrentHashMap<String, String>()

    /** Games looked for on VNDB since the app started: each once, so a failed search is tried again next time only. */
    private val looked = ConcurrentHashMap.newKeySet<String>()

    /** The visual novel whose names lead in lookups for the game of [gameId], once its dictionary is in; null for none. */
    suspend fun vnFor(gameId: String?): String? {
        if (gameId == null) return null
        val vn = vnByGame[gameId] ?: withContext(Dispatchers.IO) { db.games().byId(gameId)?.linkedVn.orEmpty() }.also { vnByGame[gameId] = it }
        return vn.ifEmpty { null }
    }

    /** A visual novel's title as last seen on VNDB, for showing; null if never seen. */
    fun title(vnId: String): String? = store.getString(KEY_TITLE + vnId, null)

    private fun rememberTitle(vnId: String, title: String) {
        if (title.isNotBlank()) store.edit().putString(KEY_TITLE + vnId, title).apply()
    }

    /** What the cards are built with now; a dictionary built otherwise is built again when its game is next seen. */
    private fun settings(): DictSettings = prefs().let { DictSettings(showSpoilers = it.namesSpoilers, honorifics = it.namesHonorifics) }

    private fun signature(s: DictSettings) = "h=${s.honorifics};s=${s.showSpoilers}"

    // ── the game in front ───────────────────────────────────────────────

    /** When each game was last looked at by [onGameSeen], so a game in front costs this once a minute at most. */
    private val seenAt = ConcurrentHashMap<String, Long>()

    /**
     * Called whenever a game is in front and read. Builds its visual novel's
     * names if they are missing or were built with other settings, and
     * otherwise, once, looks for its visual novel on VNDB. [steamAppId] is
     * Steam's ID for it, or empty.
     */
    fun onGameSeen(gameId: String, steamAppId: String) {
        val now = System.currentTimeMillis()
        val last = seenAt[gameId]
        if (last != null && now - last < SEEN_EVERY_MS) return
        seenAt[gameId] = now
        scope.launch(Dispatchers.IO) {
            val game = db.games().byId(gameId) ?: return@launch
            vnByGame[game.id] = game.linkedVn.orEmpty()
            val linked = game.linkedVn
            if (linked != null) {
                val built = dictionaries.names(linked)
                val stale = built != null && store.getString(KEY_BUILT + linked, null) != signature(settings())
                if ((built == null || stale) && linked !in _builds.value) build(linked)
                return@launch
            }
            if (game.vndb.isNotEmpty() || game.vndbSuggested.isNotEmpty() || game.app || game.reading == Game.READ_OFF) return@launch
            if (!prefs().namesAuto || !looked.add(game.id) || !networkAllowed()) return@launch
            try {
                findFor(game, steamAppId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not found this time; looked for again the next time the app starts.
            }
        }
    }

    private suspend fun findFor(game: Game, steamAppId: String) {
        val platform = VnMatcher.platformFor(game.packageName, ownApp = game.detected.isEmpty())
        val steam = steamAppId.toLongOrNull()?.let { vndb.vnsForSteam(it) }.orEmpty()
        val found = if (steam.size == 1) emptyList() else vndb.search(game.title, platform = null, japaneseOnly = false)
        found.forEach { rememberTitle(it.id, it.alttitle ?: it.title) }
        when (val outcome = VnMatcher.match(game.title, steamAppId.toLongOrNull(), platform, steam, found)) {
            is VnMatch.Link -> {
                if (outcome.how == Game.HOW_STEAM && title(outcome.vnId) == null) {
                    runCatching { vndb.fetchVnInfo(outcome.vnId) }.getOrNull()?.let { rememberTitle(outcome.vnId, it.alttitle.ifEmpty { it.title }) }
                }
                val now = db.games().byId(game.id) ?: return
                if (now.vndb.isNotEmpty()) return
                link(now, outcome.vnId, outcome.how)
            }
            is VnMatch.Suggest -> {
                val now = db.games().byId(game.id) ?: return
                if (now.vndb.isNotEmpty() || now.vndbSuggested.isNotEmpty()) return
                db.games().setVn(now.id, "", "", outcome.vnId, false)
            }
            VnMatch.None -> Unit
        }
    }

    // ── the popup's question ────────────────────────────────────────────

    /** The question for the game's uncertain visual novel, at most once per game: asking marks it asked. */
    suspend fun question(gameId: String): VnQuestion? {
        val game = db.games().byId(gameId) ?: return null
        if (game.vndb.isNotEmpty() || game.vndbSuggested.isEmpty() || game.vndbAsked) return null
        db.games().setVn(game.id, game.vndb, game.vndbHow, game.vndbSuggested, true)
        return VnQuestion(game.id, game.vndbSuggested, title(game.vndbSuggested) ?: game.vndbSuggested)
    }

    suspend fun answer(question: VnQuestion, yes: Boolean) {
        val game = db.games().byId(question.gameId) ?: return
        if (game.vndb.isNotEmpty()) return
        if (yes) link(game, question.vnId, Game.HOW_SUGGESTED) else decline(game)
    }

    // ── the game's page ─────────────────────────────────────────────────

    /** Links [game] to [vnId] and builds its names if they are not already here. [title] is remembered for showing. */
    suspend fun link(game: Game, vnId: String, how: String, title: String? = null) {
        title?.let { rememberTitle(vnId, it) }
        db.games().setVn(game.id, vnId, how, "", true)
        vnByGame[game.id] = vnId
        if (dictionaries.names(vnId) == null && vnId !in _builds.value) build(vnId)
    }

    /** The user said the game is not a visual novel: it is never looked for or asked about again. */
    suspend fun decline(game: Game) {
        db.games().setVn(game.id, Game.VNDB_NONE, "", "", true)
        vnByGame[game.id] = ""
    }

    /** Takes the suggestion on the game's page. */
    suspend fun accept(game: Game) {
        if (game.vndbSuggested.isNotEmpty()) link(game, game.vndbSuggested, Game.HOW_SUGGESTED)
    }

    /**
     * Removes a visual novel's names. Every game linked to it is set to "not a
     * visual novel", so they are not built again by themselves.
     */
    suspend fun remove(vnId: String) {
        dictionaries.names(vnId)?.let { dictionaries.remove(it.title) }
        for (game in db.games().withVn()) {
            if (game.vndb == vnId) {
                db.games().setVn(game.id, Game.VNDB_NONE, "", "", true)
                vnByGame[game.id] = ""
            }
        }
        _builds.update { it - vnId }
    }

    /** Visual novels on VNDB for the search on a game's page: a title, a VNDB link, or an ID. */
    suspend fun search(query: String, game: Game?): List<VnResult> = withContext(Dispatchers.IO) {
        val id = runCatching { Vndb.parseVnId(query) }.getOrNull()
        val platform = game?.let { VnMatcher.platformFor(it.packageName, ownApp = it.detected.isEmpty()) }
        val results = if (id != null) {
            val info = vndb.fetchVnInfo(id)
            listOf(VnResult(id = id, title = info.title, alttitle = info.alttitle.ifEmpty { null }))
        } else {
            vndb.search(query.trim(), platform = null, japaneseOnly = false)
        }
        results.forEach { rememberTitle(it.id, it.alttitle ?: it.title) }
        // Those on the game's platform first; VNDB's own order otherwise.
        if (platform == null) results else results.sortedByDescending { platform in it.platforms }
    }

    // ── building ────────────────────────────────────────────────────────

    /** Starts building [vnId]'s names in the background, or again. */
    fun build(vnId: String) {
        _builds.update { it + (vnId to Build.Running("Waiting for the network", 0, 0)) }
        CharacterNamesWorker.start(context, vnId, prefs().wifiOnly)
    }

    /** Stops a build under way. */
    fun stop(vnId: String) {
        CharacterNamesWorker.stop(context, vnId)
        _builds.update { it - vnId }
    }

    /** The build itself, run by [CharacterNamesWorker]. Returns null when done, or what went wrong. */
    internal suspend fun runBuild(vnId: String): String? {
        fun running(stage: String, done: Int = 0, total: Int = 0) = _builds.update { it + (vnId to Build.Running(stage, done, total)) }
        val zip = File(context.cacheDir, "names-$vnId.zip")
        return try {
            running("Asking VNDB")
            val info = vndb.fetchVnInfo(vnId)
            val shown = info.alttitle.ifEmpty { info.title }
            rememberTitle(vnId, shown)
            val characters = vndb.fetchCharacters(vnId, info.voiceActors)
            if (characters.isEmpty()) return fail(vnId, "VNDB lists no characters for this visual novel yet.")
            val settings = settings()
            val withPortraits = if (!settings.showImage) characters else characters.mapIndexed { i, c ->
                running("Portraits", i, characters.size)
                c.copy(image = c.imageUrl?.let { Portraits.fetch(it) })
            }
            running("Building", 0, withPortraits.size)
            val title = "$shown (VNDB $vnId)".replace('/', '／').replace('\\', '＼')
            zip.outputStream().buffered(1 shl 16).use { out ->
                CharacterDictionary.write(vnId, info, withPortraits, settings, title, "lfg-$vnId-${System.currentTimeMillis() / 1000}", out) { done, total ->
                    running("Building", done, total)
                }
            }
            running("Importing", withPortraits.size, withPortraits.size)
            val error = dictionaries.addFromZip(zip, vndb = vnId)
            if (error != null) return fail(vnId, error)
            store.edit().putString(KEY_BUILT + vnId, signature(settings)).apply()
            _builds.update { it - vnId }
            null
        } catch (e: CancellationException) {
            _builds.update { it - vnId }
            throw e
        } catch (e: Exception) {
            fail(vnId, downloadProblem(e, "The names could not be built."))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { zip.delete() }
        }
    }

    private fun fail(vnId: String, reason: String): String {
        _builds.update { it + (vnId to Build.Failed(reason)) }
        return reason
    }

    private fun networkAllowed(): Boolean {
        if (!prefs().wifiOnly) return true
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return !connectivity.isActiveNetworkMetered
    }

    companion object {
        private const val KEY_TITLE = "title:"
        private const val KEY_BUILT = "built:"
        private const val SEEN_EVERY_MS = 60_000L
    }
}
