package io.github.beesan.lfg

import ai.onnxruntime.OrtEnvironment
import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.MeikiOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Runs the real meikiocr models on a drawn game screen. Skipped when the
 * models are not in the Hugging Face cache of the build machine.
 */
class MeikiOcrTest {
    private val hub = File(System.getProperty("user.home"), ".cache/huggingface/hub")

    private fun model(repo: String, name: String): File? =
        File(hub, "models--rtr46--$repo/snapshots").listFiles()?.map { File(it, name) }?.firstOrNull { it.isFile }

    private fun open(): MeikiOcr? {
        val det = model("meiki.text.detect.v0", "meiki.text.detect.v0.1.960x544.onnx") ?: return null
        val rec = model("meiki.txt.recognition.v0", "meiki.text.rec.v0.960x32.onnx") ?: return null
        val vrec = model("meiki.txt.recognition.v0", "meiki.text.rec.v0.vertical.32x480.onnx")
        val env = OrtEnvironment.getEnvironment()
        return MeikiOcr(env, env.createSession(det.path), env.createSession(rec.path), vrec?.let { env.createSession(it.path) })
    }

    /** Screens drawn by tools/make_test_screens.py: 1920x1080, a dialogue box with 56 px type at x=140. */
    private fun screen(name: String): Frame =
        DataInputStream(GZIPInputStream(javaClass.getResourceAsStream("/screens/$name.argb.gz")!!).buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            Frame(width, height, IntArray(width * height) { input.readInt() })
        }

    @Test
    fun readsADialogueBoxWithABoxPerCharacter() {
        val ocr = open()
        assumeTrue("meikiocr models not cached", ocr != null)
        ocr!!.use {
            val first = "昨日の夜、森の奥で不思議な光を見たんだ。"
            val second = "でも、近づいたら消えてしまった。"
            val lines = it.read(screen("dialogue"))
            assertEquals(listOf(first, second), lines.map { l -> l.text })
            val line = lines[0]
            assertEquals(first.length, line.chars.size)
            assertTrue(line.chars.zipWithNext().all { (a, b) -> a.box.left < b.box.left })
            // 不思議 starts at index 9; a tap in the middle of 思 must land on it.
            val shi = line.chars[10].box
            assertEquals(10, line.charIndexAt((shi.left + shi.right) / 2, (shi.top + shi.bottom) / 2))
            // Drawn at x=140, y=800, 56 px type: the line box sits there.
            assertTrue("line box ${line.box}", line.box.left in 110..160 && line.box.top in 780..830 && line.box.bottom in 850..900)
        }
    }

    @Test
    fun readsVerticalText() {
        val ocr = open()
        assumeTrue("meikiocr models not cached", ocr != null)
        ocr!!.use {
            val lines = it.read(screen("vertical"))
            val column = lines.firstOrNull { l -> l.vertical }
            assertTrue("no vertical line in ${lines.map { l -> l.text }}", column != null)
            assertEquals("不思議な光を見た", column!!.text)
            assertTrue(column.chars.zipWithNext().all { (a, b) -> a.box.top < b.box.top })
        }
    }

    // Screens from tools/make_ocr_corpus.py.
    private val wrapped = "昨日の夜、森の奥で不思議な光を見たんだけど、近づいたら消えてしまって、結局何だったのか分からない。"

    @Test
    fun aWrappedSentenceComesOutWholeWithARowForEachLine() {
        val ocr = open()
        assumeTrue("meikiocr models not cached", ocr != null)
        ocr!!.use {
            val blocks = Blocks.group(it.read(screen("wrap")))
            assertEquals(listOf("ユイ", wrapped), blocks.map { b -> b.text })
            val sentence = blocks[1]
            assertEquals(2, sentence.rows.size)
            // 近づい|たら is cut by the line break: its two halves are marked on their own rows.
            val start = wrapped.indexOf("近づいたら")
            val marks = sentence.spanBoxes(start, start + 5)
            assertEquals(2, marks.size)
            assertTrue(marks[0].top < marks[1].top && marks[0].left > marks[1].left)
        }
    }

    @Test
    fun furiganaIsNotReadAsText() {
        val ocr = open()
        assumeTrue("meikiocr models not cached", ocr != null)
        ocr!!.use {
            assertEquals(listOf("彼は憂鬱な表情で曖昧に頷いた。"), Blocks.group(it.read(screen("furigana"))).map { b -> b.text })
        }
    }

    @Test
    fun anEmptyScreenHasNoLines() {
        val ocr = open()
        assumeTrue("meikiocr models not cached", ocr != null)
        ocr!!.use { assertEquals(emptyList<String>(), it.read(screen("empty")).map { l -> l.text }) }
    }
}
