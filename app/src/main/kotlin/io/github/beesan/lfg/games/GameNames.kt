// SPDX-License-Identifier: GPL-3.0-or-later
//
// The Cocoon, ES-DE and RetroArch readings follow Game Twin's
// (https://github.com/alpha-minus/game-twin, GPL-3.0-or-later,
// Copyright © 2026 Mert Aslantürk): its CocoonLogParser, ESDELogParser and
// RetroArchHistoryParser, rewritten here without Android.
package io.github.beesan.lfg.games

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which game an emulator, or a Steam player, has open. Android only says which
 * app is in front: every game in RetroArch would be "RetroArch". These read
 * what the front-ends and emulators write down, and Steam.
 */

/** A game started by a front-end, or seen running: its name, the app it runs in, and when. */
data class Launch(val title: String, val packageName: String?, val atMs: Long, val source: String)

/** The game an app has open: [key] tells it apart from the app's other games, [title] is shown. [steamAppId]: Steam's id for it, for its cover. */
data class Named(val key: String, val title: String, val source: String, val steamAppId: String = "")

object GameTitle {
    private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

    /** A ROM's file name made a title: no folder, extension, or (USA) [!] (v1.1) tags. */
    fun fromFile(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        return clean(base.replace(EXTENSION, ""))
    }

    /** A front-end's name for a game, without region and version tags. */
    fun clean(name: String): String {
        val stripped = name.replace(TAGS, "").replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        return stripped.ifEmpty { name.trim() }
    }

    /**
     * The same game named by two sources gets one key: "Hollow Knight: Silksong"
     * from Steam and "Hollow Knight Silksong" from a front-end's file.
     */
    fun key(title: String): String =
        Normalizer.normalize(clean(title), Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            .ifEmpty { title.trim().lowercase(Locale.ROOT) }

    fun named(title: String, source: String) = Named(key(title), title, source)
}

/**
 * Cocoon's `launch_debug.log`: a block for each game it starts, or swaps to,
 * then whether that worked.
 *
 *     [2026-05-16 07:28:30.997] [----] ══════ Launch: Axiom Verge ══════
 *     [2026-05-16 07:28:30.999] [INFO] Game: Axiom Verge | File: Axiom Verge (USA).wua
 *     [2026-05-16 07:28:31.001] [INFO] Player: CEMU (info.cemu.cemu/.emulation.EmulationActivity)
 *     [2026-05-16 07:28:31.044] [INFO] Launch SUCCESS — taskId=null
 */
object CocoonLog {
    const val FILE = "launch_debug.log"
    private val HEADER = Regex("""══+ (?:Launch|Swap): (.+?) ══+""")
    private val GAME = Regex("""Game: (.+?) \| File: (.+)""")
    private val PLAYER = Regex("""Player: .*\(([A-Za-z0-9_.]+)/[^)]+\)""")
    private val INTENT = Regex("""component=ComponentInfo\{([A-Za-z0-9_.]+)/""")
    private val SUCCESS = Regex("""(?:Launch|Swap) SUCCESS""")
    private val TIME = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Every game Cocoon started, oldest first. */
    fun launches(lines: List<String>, zone: ZoneId): List<Launch> {
        val starts = lines.indices.filter { HEADER.containsMatchIn(lines[it]) }
        return starts.mapIndexedNotNull { i, start ->
            val block = lines.subList(start, starts.getOrNull(i + 1) ?: lines.size)
            if (block.none { SUCCESS.containsMatchIn(it) }) return@mapIndexedNotNull null
            var title = HEADER.find(block[0])!!.groupValues[1].trim()
            var packageName: String? = null
            for (line in block) {
                GAME.find(line)?.let { title = it.groupValues[1].trim() }
                PLAYER.find(line)?.let { packageName = it.groupValues[1] }
                if (packageName == null) INTENT.find(line)?.let { packageName = it.groupValues[1] }
            }
            val at = TIME.find(block[0])?.groupValues?.get(1)?.let {
                runCatching { LocalDateTime.parse(it, FORMAT).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            } ?: return@mapIndexedNotNull null
            Launch(GameTitle.clean(title), packageName, at, SOURCE)
        }
    }

    const val SOURCE = "Cocoon"
}

/**
 * ES-DE's `logs/es_log.txt`, which names each game it starts and the app it
 * starts it in. Its times carry no year.
 *
 *     May 16 06:46:41 Info:   Launching game "Homebrew Demo" from system "Nintendo Switch (switch)"...
 *     May 16 06:46:41 Info:   Package: dev.eden.eden_emulator
 */
object EsdeLog {
    const val FOLDER = "logs"
    const val FILE = "es_log.txt"
    const val SOURCE = "ES-DE"
    private val LAUNCH = Regex("""Launching game "(.+?)" from system""")
    private val PACKAGE = Regex("""Package: ([A-Za-z0-9_.]+)""")
    private val RETROARCH = Regex("""(?:-n|--package|Package:?)\s+(com\.retroarch[A-Za-z0-9_.]*)""")
    private val TIME = Regex("""^([A-Z][a-z]{2}) +(\d{1,2}) (\d{2}):(\d{2}):(\d{2})""")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun launches(lines: List<String>, nowMs: Long, zone: ZoneId): List<Launch> {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val out = ArrayList<Launch>()
        var i = 0
        while (i < lines.size) {
            val m = LAUNCH.find(lines[i])
            if (m == null) { i++; continue }
            val at = time(lines[i], now)
            var packageName: String? = null
            var j = i + 1
            // The launch's own lines follow it; the next launch ends them.
            while (j < lines.size && j < i + 40 && !LAUNCH.containsMatchIn(lines[j])) {
                if (packageName == null) {
                    packageName = PACKAGE.find(lines[j])?.groupValues?.get(1) ?: RETROARCH.find(lines[j])?.groupValues?.get(1)
                }
                j++
            }
            if (at != null) out += Launch(GameTitle.clean(m.groupValues[1]), packageName, at, SOURCE)
            i = j
        }
        return out
    }

    private fun time(line: String, now: java.time.ZonedDateTime): Long? {
        val m = TIME.find(line) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1]) + 1
        if (month == 0) return null
        val day = runCatching { MonthDay.of(month, m.groupValues[2].toInt()) }.getOrNull() ?: return null
        var at = now.withMonth(day.monthValue).withDayOfMonth(day.dayOfMonth)
            .withHour(m.groupValues[3].toInt()).withMinute(m.groupValues[4].toInt()).withSecond(m.groupValues[5].toInt()).withNano(0)
        // Later in the year than now: it was last year.
        if (at.isAfter(now.plusDays(1))) at = at.minusYears(1)
        return at.toInstant().toEpochMilli()
    }
}

/**
 * RetroArch's play history, `playlists/builtin/content_history.lpl` (or
 * `playlists/` on some builds): JSON, newest first. It holds no times; the
 * file's own time is when the newest was started.
 */
object RetroArchHistory {
    const val SOURCE = "RetroArch"
    val PATHS = listOf(listOf("playlists", "builtin", "content_history.lpl"), listOf("playlists", "content_history.lpl"))
    private val CORE_PACKAGE = Regex("""/data/(?:data|user/\d+)/([A-Za-z0-9_.]+)/""")

    fun latest(json: String, fileTimeMs: Long): Launch? {
        val item = runCatching { Json.parseToJsonElement(json).jsonObject["items"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull() ?: return null
        fun field(name: String) = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val path = field("path").substringBefore('#')
        val title = field("label").takeIf { it.isNotEmpty() }?.let(GameTitle::clean) ?: path.takeIf { it.isNotEmpty() }?.let(GameTitle::fromFile) ?: return null
        val packageName = CORE_PACKAGE.find(field("core_path"))?.groupValues?.get(1)
        return Launch(title, packageName, fileTimeMs, SOURCE)
    }
}

/**
 * Eden, the Switch emulator. Its folder, which it shows in Android's file
 * picker, holds `play_time/playtime.bin`: (title ID, seconds) pairs as
 * little-endian 64-bit numbers, written every 30 seconds while a game runs.
 * The title whose seconds grew is the one being played. `log/eden_log.txt`
 * names each title it loads.
 */
object Eden {
    const val SOURCE = "Eden"
    val PLAY_TIME = listOf("play_time", "playtime.bin")
    val LOG = listOf("log", "eden_log.txt")
    private val LOADING = Regex("""Loading (.+) \(([0-9A-Fa-f]{16})\) \.\.\.""")

    fun playTimes(bytes: ByteArray): Map<Long, Long> {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = HashMap<Long, Long>()
        while (buffer.remaining() >= 16) {
            val id = buffer.long
            val seconds = buffer.long
            if (id != 0L) out[id] = seconds
        }
        return out
    }

    /** The title whose time grew between two readings, or null. */
    fun grown(before: Map<Long, Long>, after: Map<Long, Long>): Long? =
        after.entries.filter { (id, seconds) -> seconds > (before[id] ?: 0L) }.maxByOrNull { it.value - (before[it.key] ?: 0L) }?.key

    /** The titles the log saw loaded, last one last: ID to name. */
    fun loaded(lines: List<String>): List<Pair<Long, String>> = lines.mapNotNull { line ->
        LOADING.find(line)?.let { m ->
            val id = java.lang.Long.parseUnsignedLong(m.groupValues[2], 16)
            id to m.groupValues[1].trim()
        }
    }.filter { it.second.isNotEmpty() && it.second != "Unknown program" }

    fun hex(id: Long): String = java.lang.Long.toUnsignedString(id, 16).uppercase(Locale.ROOT).padStart(16, '0')

    /** A Switch title with no name known yet: its ID, until a log names it. */
    fun unnamed(id: Long) = Named("switch:${hex(id)}", "Switch game ${hex(id)}", SOURCE)
}

/**
 * Steam's Web API, with the player's own key: what they are playing right now,
 * whatever runs it on this device (DroidDeck, GameNative, Winlator).
 *
 *     GET https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=…&steamids=…
 *     →  {"response": {"players": [{"gameid": "1030300", "gameextrainfo": "Hollow Knight: Silksong"}]}}
 */
object Steam {
    const val API = "https://api.steampowered.com"
    const val SOURCE = "Steam"
    private val ID64 = Regex("""^\d{17}$""")
    private val PROFILE = Regex("""steamcommunity\.com/profiles/(\d{17})""")
    private val VANITY = Regex("""steamcommunity\.com/id/([^/?#]+)""")

    /** What was typed: a SteamID64, a profile link, or a custom profile name to look up. */
    sealed interface Account {
        data class Id(val id: String) : Account
        data class Vanity(val name: String) : Account
    }

    fun account(input: String): Account? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (ID64.matches(text)) return Account.Id(text)
        PROFILE.find(text)?.let { return Account.Id(it.groupValues[1]) }
        VANITY.find(text)?.let { return Account.Vanity(it.groupValues[1]) }
        return if (text.none { it == '/' || it == ' ' }) Account.Vanity(text) else null
    }

    fun summariesUrl(key: String, id: String) = "$API/ISteamUser/GetPlayerSummaries/v2/?key=${enc(key)}&steamids=${enc(id)}"

    fun vanityUrl(key: String, name: String) = "$API/ISteamUser/ResolveVanityURL/v1/?key=${enc(key)}&vanityurl=${enc(name)}"

    /** The game being played, or null when none; throws when the answer is not Steam's. */
    fun playing(json: String): Named? {
        val player = Json.parseToJsonElement(json).jsonObject["response"]!!.jsonObject["players"]!!.jsonArray.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Steam knows no such account")
        val name = (player["gameextrainfo"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val appId = (player["gameid"] as? JsonPrimitive)?.content?.trim().orEmpty().takeIf { it.all(Char::isDigit) }.orEmpty()
        return if (name.isEmpty()) null else GameTitle.named(name, SOURCE).copy(steamAppId = appId)
    }

    fun vanityId(json: String): String? {
        val response = Json.parseToJsonElement(json).jsonObject["response"]?.jsonObject ?: return null
        return (response["steamid"] as? JsonPrimitive)?.content?.takeIf { ID64.matches(it) }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")
}

/** Which apps run many games, and so are worth naming the game of. */
object Hosts {
    /** Emulators and visual-novel players, by package name or its start. */
    val EMULATORS = listOf(
        "com.retroarch", "org.ppsspp.ppsspp", "org.dolphinemu.dolphinemu", "com.github.stenzek.duckstation",
        "xyz.aethersx2.android", "com.dsemu.drastic", "me.magnum.melonds", "org.vita3k.emulator",
        "org.citra", "io.github.lime3ds", "org.azahar_emu.azahar", "org.yuzu.yuzu_emu", "dev.eden.eden_emulator",
        "org.citron.citron_emu", "info.cemu.cemu", "net.rpcsx", "io.github.mandarine3ds",
        "org.mupen64plusae", "org.scummvm.scummvm", "org.tvp.kirikiri2", "com.winlator", "aenu.aps3e",
        "com.github.panda3ds", "com.explusalpha", "com.fastemulator", "jp.ogapee.onscripter", "org.renpy",
        "com.izzy2lost.x1box",
    )

    /** Apps that run Steam games: Steam knows which one. */
    val STEAM = listOf("com.droiddeck.launcher", "app.gamenative", "gamehub.lite", "com.winlator")

    val EDEN = listOf("dev.eden.eden_emulator")

    fun isEmulator(packageName: String) = EMULATORS.any { packageName.startsWith(it) } || isSteam(packageName)

    fun isSteam(packageName: String) = STEAM.any { packageName.startsWith(it) }

    fun isEden(packageName: String) = EDEN.any { packageName.startsWith(it) }

    /** RetroArch comes as com.retroarch, com.retroarch.aarch64 and com.retroarch.ra32: one app to a front-end's log. */
    fun same(a: String?, b: String): Boolean {
        if (a == null) return false
        if (a == b) return true
        return a.startsWith("com.retroarch") && b.startsWith("com.retroarch")
    }
}

/** The choice between what each source says, for the app in front. */
object GameNames {
    /**
     * [live] is what Steam or Eden say is running right now, and wins. Otherwise
     * the newest game a front-end or RetroArch's history started in this app.
     */
    fun pick(packageName: String, live: Named?, launches: List<Launch>): Named? {
        if (live != null) return live
        val newest = launches.filter { Hosts.same(it.packageName, packageName) }.maxByOrNull { it.atMs } ?: return null
        return GameTitle.named(newest.title, newest.source)
    }
}
