package io.github.beesan.lfg

import io.github.beesan.lfg.core.BlackBars
import io.github.beesan.lfg.core.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class BlackBarsTest {
    private fun picture(width: Int, height: Int, game: Box, ground: Int = 0xFF000000.toInt()): IntArray {
        val out = IntArray(width * height) { ground }
        for (y in game.top until game.bottom) for (x in game.left until game.right) out[y * width + x] = 0xFF4080C0.toInt()
        return out
    }

    @Test
    fun aGameWithBarsAtItsSidesOrAboveAndBelowIsCutToTheGame() {
        // 4:3 in the middle of 16:9.
        val pillar = Box(240, 0, 1680, 1080)
        assertEquals(pillar, BlackBars.inside(picture(1920, 1080, pillar), 1920, 1080))
        val letter = Box(0, 140, 1920, 940)
        assertEquals(letter, BlackBars.inside(picture(1920, 1080, letter), 1920, 1080))
        // Bars a scaler left a little off black, and a game off to one side.
        val aside = Box(0, 60, 1300, 1080)
        assertEquals(aside, BlackBars.inside(picture(1920, 1080, aside, 0xFF0A0C08.toInt()), 1920, 1080))
    }

    @Test
    fun aPictureWithoutBarsADarkSceneAndABlackScreenAreLeftWhole() {
        val whole = Box(0, 0, 640, 360)
        assertEquals(whole, BlackBars.inside(picture(640, 360, whole), 640, 360))
        assertEquals(whole, BlackBars.inside(IntArray(640 * 360) { 0xFF000000.toInt() }, 640, 360))
        // Only a lit window in the dark: too little would be left for that to be bars.
        assertEquals(whole, BlackBars.inside(picture(640, 360, Box(300, 150, 400, 220)), 640, 360))
        // A dark edge a few pixels thick is the game's own.
        assertEquals(whole, BlackBars.inside(picture(640, 360, Box(3, 0, 640, 360)), 640, 360))
    }

    @Test
    fun theCutPictureIsTheGamesPixelsRowByRow() {
        val game = Box(2, 1, 6, 3)
        val cut = BlackBars.crop(picture(8, 4, game), 8, game)
        assertEquals(8, cut.size)
        assertEquals(setOf(0xFF4080C0.toInt()), cut.toSet())
    }
}
