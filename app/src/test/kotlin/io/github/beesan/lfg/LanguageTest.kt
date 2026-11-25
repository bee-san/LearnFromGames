package io.github.beesan.lfg

import io.github.beesan.lfg.capture.ChangeProbe
import io.github.beesan.lfg.capture.PixelProbe
import io.github.beesan.lfg.capture.ReaderProbe
import io.github.beesan.lfg.capture.Readers
import io.github.beesan.lfg.capture.TextReader
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Frame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
    private class FakeReader(var lines: List<TextLine>) : TextReader {
        override val name = "fake"
        override suspend fun read(frame: Frame) = lines
    }

    private val frame = Frame(2, 2, IntArray(4))
    private fun line(text: String, top: Int) = TextLine(text, Box(10, top, 200, top + 30), emptyList())

    @Test
    fun aLanguageIsFoundByItsTagAndNothingElseIs() {
        assertSame(Language.JAPANESE, Language.of("ja"))
        assertNull(Language.of(""))
        assertNull(Language.of("xx"))
        assertEquals(Language.entries.size, Language.entries.map { it.tag }.toSet().size)
    }

    @Test
    fun japaneseIsWatchedByVocrAndFallsBackToPixels() {
        val vocr = object : ChangeProbe {
            override suspend fun signature(frame: Frame) = "vocr"
        }
        assertSame(vocr, Readers.probeFor(Language.JAPANESE, vocr, FakeReader(emptyList())))
        assertTrue(Readers.probeFor(Language.JAPANESE, null, FakeReader(emptyList())) is PixelProbe)
    }

    @Test
    fun theReaderAsAProbeSignsWithWhatItReadsAndSaysWhere() = runBlocking {
        val reader = FakeReader(listOf(line("Hello there.", 800), line("How are you?", 840)))
        val probe = ReaderProbe(reader)
        val look = probe.look(frame)
        assertEquals("Hello there.\nHow are you?", look.signature)
        assertEquals(listOf(Box(10, 800, 200, 830), Box(10, 840, 200, 870)), look.found)
        reader.lines = emptyList()
        assertEquals("", probe.signature(frame))
    }
}
