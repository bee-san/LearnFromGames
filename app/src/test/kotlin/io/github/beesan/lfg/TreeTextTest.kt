package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.core.TreeNode
import io.github.beesan.lfg.core.TreeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeTextTest {
    private val screen = Box(0, 0, 1080, 2400)

    /** Where a TextView draws [text]: square characters, [perRow] to a row, starting at ([left], [top]). */
    private fun drawn(text: String, left: Int, top: Int, size: Int = 50, perRow: Int = 100): List<Box?> {
        var n = 0
        return text.map { c ->
            if (c == '\n') {
                n = (n / perRow + 1) * perRow
                null
            } else {
                val box = Box(left + (n % perRow) * size, top + (n / perRow) * size, left + (n % perRow + 1) * size, top + (n / perRow + 1) * size)
                n++
                box
            }
        }
    }

    /** Where a TextView draws right-to-left [rows]: each from [right] leftwards, letters 30 wide and spaces 15, rows 60 high. */
    private fun drawnRightToLeft(rows: List<String>, right: Int, top: Int): List<Box?> = rows.flatMapIndexed { r, row ->
        var x = right
        row.map { c ->
            val w = if (c == ' ') 15 else 30
            x -= w
            Box(x, top + r * 60, x + w, top + (r + 1) * 60)
        }
    }

    @Test
    fun aWrappedParagraphIsOneLineWithARowForEachLine() {
        val text = "京都の紅葉が見頃を迎えました"
        val node = TreeNode(text, Box(0, 100, 400, 200), drawn(text, 0, 100, perRow = 8))
        val line = TreeText.lines(listOf(node), screen).single()
        assertEquals(text, line.text)
        assertEquals(2, line.rows.size)
        assertEquals(Box(0, 100, 400, 150), line.rows[0])
        // A tap on the second row lands on the ninth character.
        assertEquals(8, line.charIndexAt(25, 175))
        // A word cut by the wrap is marked in both rows.
        assertEquals(2, line.spanBoxes(6, 10).size)
    }

    @Test
    fun eachParagraphOfANodeIsItsOwnLine() {
        val text = "明日の待ち合わせ\n駅前でいい？"
        val node = TreeNode(text, Box(0, 0, 1000, 100), drawn(text, 0, 0))
        assertEquals(listOf("明日の待ち合わせ", "駅前でいい？"), TreeText.lines(listOf(node), screen).map { it.text })
    }

    @Test
    fun charactersScrolledOutOfTheNodeAreNotTappableButTheSentenceStaysWhole() {
        val text = "今週末は多くの観光客が訪れる見込みです"
        // Only the top row of the node shows; the second row is scrolled away below it.
        val node = TreeNode(text, Box(0, 0, 500, 50), drawn(text, 0, 0, perRow = 10))
        val line = TreeText.lines(listOf(node), screen).single()
        assertEquals(text, line.text)
        assertEquals(10, line.chars.size)
        assertEquals(1, line.rows.size)
    }

    @Test
    fun passwordsAreNeverRead() {
        val node = TreeNode("hunter2", Box(0, 0, 400, 50), sensitive = true)
        assertTrue(TreeText.lines(listOf(node), screen).isEmpty())
    }

    @Test
    fun aNodeWithoutPositionsIsPlacedByGuess() {
        val node = TreeNode("観光客", Box(100, 100, 250, 150))
        val line = TreeText.lines(listOf(node), screen).single()
        assertEquals(3, line.chars.size)
        assertEquals(1, line.charIndexAt(175, 125))
    }

    @Test
    fun rightToLeftTextWrapsOnlyWhereItsRowsDo() {
        // Hebrew: each letter to the left of the one before, a word apart by a space, and the next row back at the right.
        val rows = listOf("שלום עולם ", "יפה מאוד")
        val node = TreeNode(rows.joinToString(""), Box(400, 100, 1000, 220), drawnRightToLeft(rows, 1000, 100))
        val line = TreeText.lines(listOf(node), screen).single()
        assertEquals(2, line.rows.size)
        assertEquals(Box(745, 100, 1000, 160), line.rows[0])
        // A tap on the second word of either row lands on it.
        assertEquals(5, line.charIndexAt(850, 130))
        assertEquals(14, line.charIndexAt(880, 190))
    }

    @Test
    fun aRightToLeftNodeWithoutPositionsIsPlacedFromItsRightEdge() {
        val line = TreeText.lines(listOf(TreeNode("שלום", Box(100, 100, 300, 150))), screen).single()
        assertEquals(0, line.charIndexAt(275, 125))
        assertEquals(3, line.charIndexAt(125, 125))
    }

    @Test
    fun verticalTextRunsDownColumnsFromTheRight() {
        val text = "お前が犯人だったのか"
        // Five characters to a column, the first column on the right.
        val boxes = text.indices.map { i -> Box(500 - (i / 5 + 1) * 40, 100 + (i % 5) * 40, 500 - (i / 5) * 40, 100 + (i % 5 + 1) * 40) }
        val line = TreeText.lines(listOf(TreeNode(text, Box(400, 100, 500, 300), boxes)), screen).single()
        assertTrue(line.vertical)
        assertEquals(2, line.rows.size)
        assertEquals(5, line.charIndexAt(440, 110))
    }

    @Test
    fun theTreeWinsWhereOcrReadTheSamePlace() {
        val tree = TreeText.lines(listOf(TreeNode("立入禁止", Box(0, 0, 200, 50), drawn("立入禁止", 0, 0))), screen)
        val sameSpot = ocr("立人禁止", Box(2, 2, 198, 48))
        val inAPicture = ocr("営業中", Box(0, 300, 150, 350))
        val overPassword = ocr("ab", Box(0, 600, 100, 650))
        val kept = TreeText.preferTree(tree, listOf(sameSpot, inAPicture, overPassword), sensitive = listOf(Box(0, 590, 400, 660)))
        assertEquals(listOf("立入禁止", "営業中"), kept.map { it.text })
    }

    @Test
    fun aRegionKeepsTheLinesMostlyInsideIt() {
        val a = ocr("次の駅で降りよう", Box(100, 2000, 900, 2050))
        val b = ocr("08:12", Box(0, 2300, 100, 2340))
        val kept = TreeText.within(listOf(a, b), Box(50, 1980, 1000, 2080))
        assertEquals(listOf("次の駅で降りよう"), kept.map { it.text })
        assertFalse(TreeText.within(listOf(a), Box(0, 0, 300, 300)).isNotEmpty())
    }

    private fun ocr(text: String, box: Box): TextLine {
        val w = box.width / text.length
        return TextLine(text, box, text.indices.map { CharBox(it, it + 1, Box(box.left + it * w, box.top, box.left + (it + 1) * w, box.bottom)) })
    }
}
