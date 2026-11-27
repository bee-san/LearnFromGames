package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.BoxDrawing
import io.github.beesan.lfg.core.DialogueBox
import io.github.beesan.lfg.ocr.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueBoxTest {
    private val drawn = DialogueBox(Box(180, 780, 1710, 980), 1920, 1080)

    @Test
    fun aBoxIsKeptAndReadBackAsItWasDrawn() {
        assertEquals("180,780,1710,980,1920,1080", drawn.encode())
        assertEquals(drawn, DialogueBox.decode(drawn.encode()))
    }

    @Test
    fun aBoxReadAllTheTimeIsKeptSoAndOneKeptBeforeIsReadWhenPicked() {
        val always = drawn.copy(always = true)
        assertEquals("180,780,1710,980,1920,1080,1", always.encode())
        assertEquals(always, DialogueBox.decode(always.encode()))
        assertFalse(DialogueBox.decode("180,780,1710,980,1920,1080")!!.always)
    }

    @Test
    fun severalBoxesAreReadInOnePartRoundThemAll() {
        val (whole, each) = DialogueBox.reading(listOf(Box(100, 900, 1800, 1050), Box(100, 40, 900, 120)), 1920, 1080)!!
        assertEquals(2, each.size)
        assertEquals(each[0].union(each[1]), whole)
        assertNull(DialogueBox.reading(emptyList(), 1920, 1080))
    }

    @Test
    fun aPartCutRoundSeveralBoxesIsBlackBetweenThem() {
        val frame = Frame(4, 3, IntArray(12) { 0xFFFFFFFF.toInt() })
        frame.keepOnly(listOf(Box(0, 0, 2, 1), Box(3, 2, 4, 3)))
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(
            listOf(white, white, black, black, black, black, black, black, black, black, black, white),
            frame.argb.toList(),
        )
    }

    @Test
    fun whatIsNotABoxIsNotReadBack() {
        assertNull(DialogueBox.decode(""))
        assertNull(DialogueBox.decode("180,780,1710,980"))
        assertNull(DialogueBox.decode("180,780,190,980,1920,1080"))
        assertNull(DialogueBox.decode("180,780,1710,980,0,1080"))
    }

    @Test
    fun onTheScreenItWasDrawnOnTheBoxIsItself() {
        assertEquals(drawn.box, drawn.on(1920, 1080))
    }

    @Test
    fun onAScreenOfAnotherSizeTheBoxIsStretchedWithIt() {
        assertEquals(Box(90, 390, 855, 490), drawn.on(960, 540))
        assertEquals(Box(120, 520, 1140, 653), drawn.on(1280, 720))
    }

    @Test
    fun aBoxIsReadWithRoomAroundIt() {
        // 60 pixels of a 1080p picture each way.
        assertEquals(Box(120, 720, 1770, 1040), DialogueBox.reading(drawn.box, 1920, 1080))
    }

    @Test
    fun theRoomStopsAtThePicturesEdge() {
        assertEquals(Box(0, 840, 1920, 1080), DialogueBox.reading(Box(0, 900, 1920, 1080), 1920, 1080))
    }

    @Test
    fun aBoxOutsideThePictureIsNotRead() {
        // The game's window is the left half of the screen, and the box was drawn on the right.
        assertNull(DialogueBox.reading(Box(1000, 780, 1700, 980), 960, 1080))
        assertNull(DialogueBox.reading(Box(940, 780, 1700, 980), 960, 1080))
    }

    @Test
    fun aPartOfAPictureIsCutOutRowByRow() {
        val picture = Frame(4, 3, IntArray(12) { it })
        val part = picture.cut(1, 1, 2, 2)
        assertEquals(2, part.width)
        assertEquals(2, part.height)
        assertEquals(listOf(5, 6, 9, 10), part.argb.take(4))
    }

    @Test
    fun aPartIsCutIntoTheMemoryGivenWhenItFits() {
        val picture = Frame(4, 3, IntArray(12) { it })
        val memory = IntArray(8)
        assertTrue(picture.cut(0, 0, 2, 2, memory).argb === memory)
        assertFalse(picture.cut(0, 0, 4, 3, memory).argb === memory)
    }

    @Test
    fun theControllerDrawsABoxFromOneCornerToTheOpposite() {
        val drawing = BoxDrawing(1920, 1080)
        assertEquals(BoxDrawing.Stage.AIMING, drawing.stage)
        assertNull(drawing.box)
        assertEquals(960 to 540, drawing.x to drawing.y)
        drawing.move(-780, 240)
        assertFalse(drawing.confirm())
        assertEquals(BoxDrawing.Stage.STRETCHING, drawing.stage)
        drawing.move(1530, 200)
        assertEquals(Box(180, 780, 1710, 980), drawing.box)
        assertTrue(drawing.confirm())
        assertEquals(BoxDrawing.Stage.DONE, drawing.stage)
        assertEquals(Box(180, 780, 1710, 980), drawing.box)
    }

    @Test
    fun theSecondCornerMayBeAnyOfTheOtherThree() {
        val drawing = BoxDrawing(1920, 1080)
        drawing.confirm()
        drawing.move(-400, -200)
        assertEquals(Box(560, 340, 960, 540), drawing.box)
    }

    @Test
    fun theCursorStopsAtTheScreensEdge() {
        val drawing = BoxDrawing(1920, 1080)
        drawing.move(5000, -5000)
        assertEquals(1920 to 0, drawing.x to drawing.y)
    }

    @Test
    fun aBoxTooSmallToHoldALineIsNotFinished() {
        val drawing = BoxDrawing(1920, 1080)
        drawing.confirm()
        drawing.move(300, 10)
        assertFalse(drawing.confirm())
        assertEquals(BoxDrawing.Stage.STRETCHING, drawing.stage)
        drawing.move(0, 40)
        assertTrue(drawing.confirm())
    }

    @Test
    fun backTakesTheLastCornerBackAndThenLeaves() {
        val drawing = BoxDrawing(1920, 1080)
        drawing.confirm()
        drawing.move(300, 100)
        drawing.confirm()
        assertTrue(drawing.back())
        assertEquals(BoxDrawing.Stage.STRETCHING, drawing.stage)
        assertTrue(drawing.back())
        assertEquals(BoxDrawing.Stage.AIMING, drawing.stage)
        assertNull(drawing.box)
        assertFalse(drawing.back())
    }

    @Test
    fun aGamesOwnBoxIsShownFinishedAndItsCornerCanBeMoved() {
        val drawing = BoxDrawing(1920, 1080, start = Box(180, 780, 1710, 980))
        assertEquals(BoxDrawing.Stage.DONE, drawing.stage)
        assertFalse(drawing.changed)
        // Finished, it does not move.
        drawing.move(10, 10)
        assertEquals(Box(180, 780, 1710, 980), drawing.box)
        // Back loosens its bottom right corner, as Rubi's suggested box does.
        assertTrue(drawing.back())
        drawing.move(-100, 20)
        assertTrue(drawing.changed)
        assertTrue(drawing.confirm())
        assertEquals(Box(180, 780, 1610, 1000), drawing.box)
    }

    @Test
    fun aFingerDrawsTheBoxInOneDrag() {
        val drawing = BoxDrawing(1920, 1080, start = Box(0, 0, 500, 500))
        drawing.touchDown(180, 780)
        drawing.touchMove(1710, 980)
        assertTrue(drawing.touchUp())
        assertEquals(Box(180, 780, 1710, 980), drawing.box)
        assertTrue(drawing.changed)
    }

    @Test
    fun aTapOnEachCornerDrawsTheBox() {
        val drawing = BoxDrawing(1920, 1080)
        drawing.touchDown(180, 780)
        drawing.touchMove(183, 782)
        assertFalse(drawing.touchUp())
        assertEquals(BoxDrawing.Stage.STRETCHING, drawing.stage)
        drawing.touchDown(1710, 980)
        assertTrue(drawing.touchUp())
        assertEquals(Box(180, 780, 1710, 980), drawing.box)
    }

    @Test
    fun aSecondTapTooCloseWaitsForAThird() {
        val drawing = BoxDrawing(1920, 1080)
        drawing.touchDown(500, 500)
        drawing.touchUp()
        drawing.touchDown(510, 505)
        assertFalse(drawing.touchUp())
        drawing.touchDown(900, 700)
        assertTrue(drawing.touchUp())
        assertEquals(Box(500, 500, 900, 700), drawing.box)
    }

    @Test
    fun theStickStartsSlowAndSpeedsUpWhileHeld() {
        val first = BoxDrawing.stickStep(1f, 0, 16, 1080)
        val held = BoxDrawing.stickStep(1f, 900, 16, 1080)
        val longer = BoxDrawing.stickStep(1f, 5000, 16, 1080)
        assertTrue(first in 2f..5f)
        assertTrue(held > first * 5)
        assertEquals(held, longer, 0.001f)
        // Half pushed, half as far; the other way, the other way.
        assertEquals(held / 2, BoxDrawing.stickStep(0.5f, 900, 16, 1080), 0.001f)
        assertEquals(-held, BoxDrawing.stickStep(-1f, 900, 16, 1080), 0.001f)
    }
}
