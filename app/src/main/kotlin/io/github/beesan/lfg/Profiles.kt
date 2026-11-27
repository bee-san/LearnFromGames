// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.beesan.lfg.anki.AnkiBridge
import io.github.beesan.lfg.capture.ProfileTile
import io.github.beesan.lfg.core.KeptProfile
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.ProfileRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A profile as the app shows it: [shown] is what it is called, its [name] or else its language's. */
data class Profile(val id: String, val slot: Int, val name: String, val language: Language, val shown: String, val inUse: Boolean)

/**
 * Profiles: each a language and settings of its own, to change between in one
 * tap, for someone learning two languages or sharing the device.
 *
 * The profile in use is the ordinary settings, so nothing else in the app
 * knows of profiles. One not in use is the same settings in a file of its own
 * ([file]), with where its cards go in Anki beside it. Changing profile puts
 * the settings in use away in their file and takes the other's out of its
 * own; what every profile shares ([Prefs.withShared]) stays as it is. The
 * wheels, the games' boxes and the dictionaries are not a profile's: a
 * dictionary already belongs to its language.
 *
 * A game can be given a profile ([link]), kept beside the list and so the
 * same whichever profile is in use: as that game comes to the front the
 * reading service changes to it ([forGame]).
 *
 * Each profile has a quick settings tile ([ProfileTile]), shown to Android
 * only once there are two profiles to change between.
 */
class Profiles(private val context: Context, private val settings: Settings, private val anki: AnkiBridge, scope: CoroutineScope) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Until a second profile is made nothing is kept: there is one, and it is in use. */
    private var kept: List<KeptProfile> = ProfileRules.decode(store.getString(KEY_LIST, null)) ?: listOf(ProfileRules.FIRST)
    private var active: String = store.getString(KEY_ACTIVE, null)?.takeIf { id -> kept.any { it.id == id } } ?: kept.first().id

    private val _list = MutableStateFlow(shown())
    val list: StateFlow<List<Profile>> = _list

    private val _games = MutableStateFlow(ProfileRules.links(store.all))

    /** The games given a profile, each with its profile's id: LFG changes to it as the game comes to the front ([forGame]). */
    val games: StateFlow<Map<String, String>> = _games

    init {
        // The profile in use is named after its language until it is given a name: another language, another name.
        scope.launch { settings.prefs.map { it.language }.distinctUntilChanged().drop(1).collect { changed() } }
    }

    val inUse: Profile get() = _list.value.first { it.inUse }

    private fun shown(): List<Profile> {
        val languages = kept.map { Language.orJapanese(if (it.id == active) settings.prefs.value.language else settings.saved(file(it.id))?.language.orEmpty()) }
        val names = ProfileRules.shown(kept.mapIndexed { i, p -> p.name to languages[i].title })
        return kept.mapIndexed { i, p -> Profile(p.id, p.slot, p.name, languages[i], names[i], p.id == active) }
    }

    @Synchronized
    private fun changed() {
        store.edit().putString(KEY_LIST, ProfileRules.encode(kept)).putString(KEY_ACTIVE, active).apply()
        _list.value = shown()
        syncTiles()
    }

    /**
     * A new profile for [language], with the settings in use but for that, and
     * now the one in use; null when there are already [ProfileRules.MAX].
     */
    @Synchronized
    fun add(language: Language): Profile? {
        val made = ProfileRules.added(kept) ?: return null
        putAway()
        kept = kept + made
        active = made.id
        settings.update { it.copy(language = language.tag) }
        changed()
        return inUse
    }

    /** The profile [id] is the one in use from now; false when it already was, or there is none. */
    @Synchronized
    fun use(id: String): Boolean {
        if (id == active || kept.none { it.id == id }) return false
        val current = settings.prefs.value
        putAway()
        active = id
        // A profile with nothing kept (its file lost) carries on with the settings in use.
        val next = settings.saved(file(id))?.withShared(current)
        store.getString(ankiKey(id), null)?.let { anki.targetKept = it }
        if (next != null) settings.update { next }
        changed()
        return true
    }

    /** The profile after the one in use becomes the one in use, round to the first again: the wheel's Profile tile. Null with only one. */
    @Synchronized
    fun next(): Profile? {
        val to = ProfileRules.after(kept, active) ?: return null
        use(to.id)
        return inUse
    }

    /** The game [gameId] has the profile [id] from now; null, and it leaves the one in use as it is. */
    @Synchronized
    fun link(gameId: String, id: String?) {
        if (id != null && kept.none { it.id == id }) return
        store.edit().apply { if (id == null) remove(gameKey(gameId)) else putString(gameKey(gameId), id) }.apply()
        _games.value = ProfileRules.links(store.all)
    }

    /**
     * The game [gameId], in the app [hostId], has come to the front: its
     * profile, or the app's, becomes the one in use. That profile, or null
     * when the one in use stays ([ProfileRules.forGame]).
     */
    @Synchronized
    fun forGame(gameId: String, hostId: String?): Profile? {
        val to = ProfileRules.forGame(_games.value, gameId, hostId, kept, active) ?: return null
        return if (use(to)) inUse else null
    }

    /** The profile [id] is called [name]; nothing, and it goes by its language's again. */
    @Synchronized
    fun rename(id: String, name: String) {
        kept = kept.map { if (it.id == id) it.copy(name = ProfileRules.named(name)) else it }
        changed()
    }

    /** The profile [id] goes, with its settings; the last one never does. One in use hands over to another first. */
    @Synchronized
    fun remove(id: String): Boolean {
        if (kept.size < 2 || kept.none { it.id == id }) return false
        if (id == active) use(kept.first { it.id != id }.id)
        kept = kept.filter { it.id != id }
        context.deleteSharedPreferences(file(id))
        // Its games leave the profile in use as it is from now.
        store.edit().apply {
            remove(ankiKey(id))
            _games.value.filterValues { it == id }.keys.forEach { remove(gameKey(it)) }
        }.apply()
        _games.value = _games.value.filterValues { it != id }
        changed()
        return true
    }

    /** The settings in use, and where cards go, are kept for the profile in use, which is about to stop being it. */
    private fun putAway() {
        settings.save(file(active), settings.prefs.value)
        store.edit().apply { anki.targetKept?.let { putString(ankiKey(active), it) } ?: remove(ankiKey(active)) }.apply()
    }

    /**
     * Android is shown the tile of each profile, once there are two; the other
     * tiles are taken away, which also takes one off the quick settings where
     * its profile has gone. A tile draws itself by watching [list] while the
     * quick settings are open: Android passes on a request to draw again only
     * for tiles of another kind.
     */
    fun syncTiles() {
        val packages = context.packageManager
        val several = kept.size >= 2
        for (slot in 1..ProfileRules.MAX) {
            val tile = ComponentName(context, ProfileTile.of(slot))
            val on = several && kept.any { it.slot == slot }
            // "Default" is off, as the manifest has them.
            val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            runCatching {
                if (packages.getComponentEnabledSetting(tile) != wanted) packages.setComponentEnabledSetting(tile, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }

    companion object {
        private const val KEY_LIST = "list"
        private const val KEY_ACTIVE = "active"

        /** The settings file of a profile not in use. Backups carry every file so named ([io.github.beesan.lfg.backup.Backup]). */
        const val FILE_PREFIX = "profile-"
        const val STORE = "profiles"

        private fun file(id: String) = FILE_PREFIX + id
        private fun ankiKey(id: String) = "anki.$id"
        private fun gameKey(gameId: String) = ProfileRules.GAME_PREFIX + gameId
    }
}
