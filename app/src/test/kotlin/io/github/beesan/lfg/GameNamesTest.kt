package io.github.beesan.lfg

import io.github.beesan.lfg.games.CocoonLog
import io.github.beesan.lfg.games.Eden
import io.github.beesan.lfg.games.EsdeLog
import io.github.beesan.lfg.games.GameNames
import io.github.beesan.lfg.games.GameTitle
import io.github.beesan.lfg.games.Hosts
import io.github.beesan.lfg.games.Launch
import io.github.beesan.lfg.games.RetroArchHistory
import io.github.beesan.lfg.games.Steam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset

class GameNamesTest {
    private val utc = ZoneOffset.UTC

    private fun cocoonBlock(kind: String, game: String, file: String, player: String, time: String, success: Boolean = true) = listOfNotNull(
        "[$time.117] [----] ══════ $kind: $game ══════",
        "[$time.118] [INFO] Game: $game | File: $file",
        "[$time.119] [INFO] Player: $player",
        "[$time.120] [INFO] Emulator installed: true",
        "[$time.141] [INFO] Intent: action=android.intent.action.VIEW, data=null, component=ComponentInfo{x.y/x.y.Z}",
        if (success) "[$time.162] [INFO] $kind SUCCESS — taskId=null" else "[$time.162] [ERROR] $kind FAILED",
    )

    @Test
    fun cocoonsLogGivesEachGameThatStartedAndTheAppItRanIn() {
        val lines = cocoonBlock("Launch", "Super Mario Advance 4", "SMA4.zip", "RetroArch - vba_next (com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture)", "2026-05-16 07:03:22") +
            cocoonBlock("Launch", "Broken", "b.zip", "CEMU (info.cemu.cemu/.emulation.EmulationActivity)", "2026-05-16 07:10:00", success = false) +
            cocoonBlock("Swap", "Zelda (USA)", "z.xci", "Eden (dev.eden.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity)", "2026-05-16 08:00:00") +
            cocoonBlock("Launch", "Hollow Knight Silksong", "silksong.desktop", "DroidDeck (com.droiddeck.launcher/.FrontEndActivity)", "2026-05-16 09:00:00")
        val launches = CocoonLog.launches(lines, utc)
        assertEquals(listOf("Super Mario Advance 4", "Zelda", "Hollow Knight Silksong"), launches.map { it.title })
        assertEquals(listOf("com.retroarch", "dev.eden.eden_emulator", "com.droiddeck.launcher"), launches.map { it.packageName })
        assertEquals(LocalDateTime.of(2026, 5, 16, 7, 3, 22).toInstant(utc).toEpochMilli(), launches[0].atMs)
    }

    @Test
    fun esDesLogNamesTheGameAndTheAppAndGuessesTheYear() {
        val lines = listOf(
            """May 16 06:46:41 Info:   Launching game "Homebrew Demo" from system "Nintendo Switch (switch)"...""",
            "May 16 06:46:41 Info:   Expanded emulator launch arguments:",
            "May 16 06:46:41 Info:   Package: dev.eden.eden_emulator",
            "May 16 06:46:41 Info:   Activity: org.yuzu.yuzu_emu.activities.EmulationActivity",
            """Dec 30 23:00:00 Info:   Launching game "Strider 2 (USA)" from system "Sony PlayStation (psx)"...""",
            "Dec 30 23:00:00 Info:   Package: com.retroarch.aarch64",
        )
        val now = LocalDateTime.of(2026, 6, 1, 12, 0).toInstant(utc).toEpochMilli()
        val launches = EsdeLog.launches(lines, now, utc)
        assertEquals(listOf("Homebrew Demo", "Strider 2"), launches.map { it.title })
        assertEquals(listOf("dev.eden.eden_emulator", "com.retroarch.aarch64"), launches.map { it.packageName })
        // December is after June: last year's.
        assertEquals(LocalDateTime.of(2025, 12, 30, 23, 0).toInstant(utc).toEpochMilli(), launches[1].atMs)
    }

    @Test
    fun retroArchsHistoryGivesTheNewestGameAndItsRetroArch() {
        val json = """{"version":"1.5","items":[
            {"path":"/storage/emulated/0/ROMs/gba/Golden Sun (USA, Europe).gba","label":"","core_path":"/data/user/0/com.retroarch.aarch64/cores/mgba_libretro_android.so"},
            {"path":"/x/old.gba","label":"Old"}]}"""
        val launch = RetroArchHistory.latest(json, 42)!!
        assertEquals("Golden Sun", launch.title)
        assertEquals("com.retroarch.aarch64", launch.packageName)
        assertEquals(42L, launch.atMs)
        assertNull(RetroArchHistory.latest("""{"items":[]}""", 0))
        assertNull(RetroArchHistory.latest("not json", 0))
    }

    @Test
    fun edensPlayTimeShowsWhichTitleIsRunning() {
        fun file(vararg pairs: Pair<Long, Long>): ByteArray {
            val b = ByteBuffer.allocate(pairs.size * 16).order(ByteOrder.LITTLE_ENDIAN)
            pairs.forEach { (id, s) -> b.putLong(id).putLong(s) }
            return b.array()
        }
        val zelda = 0x0100F2C0115B6000L
        val mario = 0x010028600EBDA000L
        val before = Eden.playTimes(file(zelda to 100, mario to 50))
        val after = Eden.playTimes(file(zelda to 100, mario to 80))
        assertEquals(mapOf(zelda to 100L, mario to 80L), after)
        assertEquals(mario, Eden.grown(before, after))
        assertNull(Eden.grown(after, after))
        assertEquals("switch:010028600EBDA000", Eden.unnamed(mario).key)

        val log = listOf(
            "[  0.5] Core <Info> core.cpp:337:Load: Loading Unknown program (0000000000000000) ...",
            "[  1.2] Core <Info> core.cpp:337:Load: Loading The Legend of Zelda: Tears of the Kingdom (0100F2C0115B6000) ...",
        )
        assertEquals(listOf(zelda to "The Legend of Zelda: Tears of the Kingdom"), Eden.loaded(log))
    }

    @Test
    fun steamSaysWhatIsPlayingAndTakesAnyFormOfProfile() {
        val playing = Steam.playing("""{"response":{"players":[{"steamid":"1","gameid":"1030300","gameextrainfo":"Hollow Knight: Silksong"}]}}""")!!
        assertEquals("Hollow Knight: Silksong", playing.title)
        assertEquals("1030300", playing.steamAppId)
        assertNull(Steam.playing("""{"response":{"players":[{"steamid":"1"}]}}"""))
        assertThrows(IllegalArgumentException::class.java) { Steam.playing("""{"response":{"players":[]}}""") }

        assertEquals(Steam.Account.Id("76561197960287930"), Steam.account("76561197960287930"))
        assertEquals(Steam.Account.Id("76561197960287930"), Steam.account("https://steamcommunity.com/profiles/76561197960287930/"))
        assertEquals(Steam.Account.Vanity("gabelogannewell"), Steam.account("https://steamcommunity.com/id/gabelogannewell"))
        assertEquals(Steam.Account.Vanity("bee"), Steam.account("bee"))
        assertNull(Steam.account(""))
        assertEquals("76561197960287930", Steam.vanityId("""{"response":{"steamid":"76561197960287930","success":1}}"""))
        assertNull(Steam.vanityId("""{"response":{"success":42,"message":"No match"}}"""))
    }

    @Test
    fun oneGameNamedTwoWaysIsOneGame() {
        assertEquals(GameTitle.key("Hollow Knight: Silksong"), GameTitle.key("Hollow Knight Silksong"))
        assertEquals(GameTitle.key("Golden Sun"), GameTitle.key("Golden Sun (USA, Europe) [!]"))
        assertEquals("ドラゴンクエストxi", GameTitle.key("ドラゴンクエストＸＩ"))
        assertEquals("Golden Sun", GameTitle.fromFile("/roms/gba/Golden_Sun (USA).gba"))
    }

    @Test
    fun theLiveSourceWinsThenTheNewestLaunchInThatApp() {
        val launches = listOf(
            Launch("Old", "com.retroarch", 1, "Cocoon"),
            Launch("Newer", "com.retroarch.aarch64", 5, "RetroArch"),
            Launch("Other app", "org.ppsspp.ppsspp", 9, "Cocoon"),
        )
        assertEquals("Newer", GameNames.pick("com.retroarch.aarch64", null, launches)!!.title)
        // Cocoon logs com.retroarch for any RetroArch.
        assertEquals("Newer", GameNames.pick("com.retroarch", null, launches)!!.title)
        assertNull(GameNames.pick("dev.eden.eden_emulator", null, launches))
        val live = GameTitle.named("Live", "Steam")
        assertEquals(live, GameNames.pick("com.retroarch", live, launches))
    }

    @Test
    fun steamPlayersAndEmulatorsAreHostsButOtherAppsAreNot() {
        assertTrue(Hosts.isSteam("com.droiddeck.launcher"))
        assertTrue(Hosts.isEmulator("com.retroarch.aarch64"))
        assertTrue(Hosts.isEden("dev.eden.eden_emulator.nightly"))
        assertTrue(!Hosts.isEmulator("jp.co.example.game"))
    }
}
