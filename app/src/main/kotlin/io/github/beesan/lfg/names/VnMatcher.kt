// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import io.github.beesan.lfg.games.GameTitle

/** What the matcher decided for a game. */
sealed class VnMatch {
    /** A sure match: [vnId] by Steam app ID or by exact title. [how] is "steam" or "title". */
    data class Link(val vnId: String, val how: String) : VnMatch()

    /** A plausible match, [vnId], not a sure one. */
    data class Suggest(val vnId: String) : VnMatch()

    /** Nothing worth offering. */
    data object None : VnMatch()
}

/**
 * Decides which visual novel, if any, a game is. A match is sure by Steam
 * app ID, or by an exact title on the right platform with a Japanese
 * release; anything weaker is only plausible. Either way the player is asked
 * before the game gets its cast, so Journey or Inside never gets a stranger's.
 */
object VnMatcher {
    /**
     * [title] is the game's name and [steamAppId] its Steam app ID, if known.
     * [platform] is the VNDB platform code of the app it runs in, from
     * [platformFor]; null means unknown, and then the title never links by
     * itself. [steamVns] are the VN IDs with a release linked to the Steam app
     * ([Vndb.vnsForSteam]); [results] are title search results, best first
     * ([Vndb.search]).
     */
    fun match(
        title: String,
        steamAppId: Long?,
        platform: String?,
        steamVns: List<String>,
        results: List<VnResult>,
    ): VnMatch {
        val steam = if (steamAppId != null) steamVns.distinct() else emptyList()
        if (steam.size == 1) return VnMatch.Link(steam[0], "steam")

        val key = GameTitle.key(title)
        val exact = if (key.isEmpty()) emptyList() else results.filter { key in titleKeys(it) }
        val passing = if (platform == null) emptyList() else exact.filter { platform in it.platforms && "ja" in it.languages }
        if (passing.size == 1) return VnMatch.Link(passing[0].id, "title")

        // Several VNs share the Steam app: offer the one whose title matches, else VNDB's first.
        if (steam.isNotEmpty()) return VnMatch.Suggest(steam.firstOrNull { id -> exact.any { it.id == id } } ?: steam[0])
        val offer = passing.firstOrNull() ?: exact.firstOrNull() ?: return VnMatch.None
        return VnMatch.Suggest(offer.id)
    }

    /** Every name the VN goes by, normalised as [GameTitle.key] normalises the game's: title, alternative title, aliases, and each `titles` entry with its romanised form. */
    fun titleKeys(vn: VnResult): Set<String> = buildList {
        add(vn.title)
        vn.alttitle?.let(::add)
        addAll(vn.aliases)
        for (t in vn.titles) {
            add(t.title)
            t.latin?.let(::add)
        }
    }.filter { it.isNotBlank() }.map(GameTitle::key).filter { it.isNotEmpty() }.toSet()

    /** Package name, or its start, to VNDB platform code. Order matters only where one start would cover another. */
    private val PLATFORMS = listOf(
        // Eden, yuzu, Citron
        "dev.eden.eden_emulator" to "swi",
        "org.yuzu.yuzu_emu" to "swi",
        "org.citron.citron_emu" to "swi",
        // PPSSPP, and PPSSPP Gold (org.ppsspp.ppssppgold)
        "org.ppsspp.ppsspp" to "psp",
        "org.vita3k.emulator" to "psv",
        // AetherSX2 and NetherSX2 share the package name.
        "xyz.aethersx2.android" to "ps2",
        "com.github.stenzek.duckstation" to "ps1",
        "aenu.aps3e" to "ps3",
        "net.rpcsx" to "ps3",
        "com.dsemu.drastic" to "nds",
        "me.magnum.melonds" to "nds",
        // Citra (org.citra.citra_emu and its forks under org.citra), Lime3DS, Azahar, Mandarine, Panda3DS
        "org.citra" to "n3d",
        "io.github.lime3ds" to "n3d",
        "org.azahar_emu.azahar" to "n3d",
        "io.github.mandarine3ds" to "n3d",
        "com.github.panda3ds" to "n3d",
        // Dolphin and its forks (org.dolphinemu.mmjr). VNDB has no GameCube platform.
        "org.dolphinemu" to "wii",
        "info.cemu.cemu" to "wiu",
        // Kirikiroid2, ONScripter, Winlator, DroidDeck, GameNative, GameHub (Lite and XiaoJi's own)
        "org.tvp.kirikiri2" to "win",
        "jp.ogapee.onscripter" to "win",
        "com.winlator" to "win",
        "com.droiddeck.launcher" to "win",
        "app.gamenative" to "win",
        "gamehub.lite" to "win",
        "com.xiaoji.egggame" to "win",
    )

    /**
     * The VNDB platform a game is running on: "and" when the game is its own
     * Android app ([ownApp]), the emulator's platform when [packageName] is a
     * known one, and null (unknown) otherwise, including RetroArch, ScummVM
     * and Ren'Py, which run games from many platforms.
     */
    fun platformFor(packageName: String?, ownApp: Boolean): String? {
        if (ownApp) return "and"
        if (packageName == null) return null
        return PLATFORMS.firstOrNull { packageName.startsWith(it.first) }?.second
    }
}
