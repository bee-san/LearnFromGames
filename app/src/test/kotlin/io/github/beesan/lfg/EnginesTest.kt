package io.github.beesan.lfg

import io.github.beesan.lfg.capture.SampleRing
import io.github.beesan.lfg.core.Blocks
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.dict.Glossary
import io.github.beesan.lfg.dict.Transform
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.ScreenAiFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginesTest {
    private fun resource(name: String) = javaClass.getResourceAsStream(name)!!.readBytes()

    // ── ScreenAI: results the Android library gave for the test screens ──────

    @Test
    fun screenAiLinesHaveABoxPerCharacter() {
        val lines = ScreenAi.decode(resource("/screenai/dialogue.pb"))
        assertEquals(listOf("昨日の夜、森の奥で不思議な光を見たんだ。", "でも、近づいたら消えてしまった。"), lines.map { it.text })
        val first = lines[0]
        assertEquals(20, first.chars.size)
        assertFalse(first.vertical)
        assertTrue(first.chars.zipWithNext().all { (a, b) -> a.box.left < b.box.left && a.end == b.start })
        // The text was drawn at x=140, y=800 in 56 px type.
        assertTrue("box ${first.box}", first.box.left in 120..160 && first.box.top in 790..830)
        val shi = first.chars[10].box
        assertEquals(10, first.charIndexAt((shi.left + shi.right) / 2, (shi.top + shi.bottom) / 2))
    }

    @Test
    fun screenAiMarksVerticalLines() {
        val lines = ScreenAi.decode(resource("/screenai/vertical.pb"))
        val column = lines.single { it.vertical }
        assertEquals("不思議な光を見た", column.text)
        assertTrue(column.chars.zipWithNext().all { (a, b) -> a.box.top < b.box.top })
        assertTrue(lines.any { it.text == "今日はいい天気ですね。" && !it.vertical })
    }

    @Test
    fun screenAiRowsBecomeWholeSentencesAndFuriganaGoes() {
        val wrap = Blocks.group(ScreenAi.decode(resource("/screenai/wrap.pb")))
        assertEquals(
            listOf("ユイ", "昨日の夜、森の奥で不思議な光を見たんだけど、近づいたら消えてしまって、結局何だったのか分からない。"),
            wrap.map { it.text },
        )
        assertEquals(2, wrap[1].rows.size)
        // Three rows of furigana and the line they annotate: one piece is left.
        assertEquals(4, ScreenAi.decode(resource("/screenai/furigana.pb")).size)
        assertEquals(1, Blocks.group(ScreenAi.decode(resource("/screenai/furigana.pb"))).size)
    }

    /** A ScreenAI line: each symbol with its left edge and width, all [height] tall at y=100. */
    private fun read(vararg symbols: Pair<String, IntRange>, height: Int = 50): Pair<String, List<io.github.beesan.lfg.core.CharBox>> {
        val text = StringBuilder()
        val chars = symbols.map { (s, x) ->
            io.github.beesan.lfg.core.CharBox(text.length, text.length + s.length, io.github.beesan.lfg.core.Box(x.first, 100, x.last, 100 + height))
                .also { text.append(s) }
        }
        val line = io.github.beesan.lfg.core.Box(chars.first().box.left, 100, chars.last().box.right, 100 + height)
        return ScreenAi.ellipses(text.toString(), chars, vertical = false, line = line)
    }

    @Test
    fun screenAiEllipsesAreCountedByTheRoomTheyTake() {
        // From the OCR bench: 「ええ……。」 came out as ええ…………･･。 (boxes overlap and miscount).
        val (text, boxes) = read(
            "え" to 160..205, "え" to 205..250, "…" to 247..283, "…" to 245..290, "…" to 279..315, "…" to 304..340,
            "･" to 338..365, "･" to 354..381, "。" to 381..426, height = 45,
        )
        assertEquals("ええ……。", text)
        assertEquals(5, boxes.size)
        assertEquals(text.indices.toList(), boxes.map { it.start })
        assertTrue(boxes[2].box.left == 245 && boxes[3].box.right == 381)
        // 「あのですね･…････ → あのですね……
        assertEquals("あのですね……", read("あ" to 300..353, "の" to 360..413, "で" to 420..473, "す" to 480..533, "ね" to 500..553,
            "･" to 558..584, "…" to 574..600, "･" to 581..607, "･" to 632..658, "･" to 639..674, "･" to 646..681, height = 53).first)
    }

    @Test
    fun aMiddleDotInANameStays() {
        assertEquals("ジョン・スミス", read("ジ" to 0..50, "ョ" to 55..100, "ン" to 110..160, "・" to 170..190,
            "ス" to 200..250, "ミ" to 260..310, "ス" to 320..370).first)
        assertEquals("もう大丈夫…", read("も" to 346..396, "う" to 396..446, "大" to 446..496, "丈" to 496..546, "夫" to 546..596, "…" to 596..646).first)
    }

    @Test
    fun anEmptyOrBrokenResultIsNoLines() {
        assertEquals(0, ScreenAi.decode(ByteArray(0)).size)
        assertEquals(0, ScreenAi.decode(byteArrayOf(0x12, 0x7F, 0x01)).size)
    }

    @Test
    fun onlyTheJapaneseReadersFilesAreKeptFromThePackage() {
        val r = "resources/gocr/gocr_models/line_recognition_mobile_convnext320_omni/"
        assertEquals("gocr/gocr_models/line_recognition_mobile_convnext320_omni/hanijpan.tflite", ScreenAiFiles.wanted(r + "hanijpan.tflite"))
        assertEquals("gocr/gocr_models/line_recognition_mobile_convnext320_omni/hani_lm.fst", ScreenAiFiles.wanted(r + "hani_lm.fst"))
        assertEquals("gocr/layout/cluster_sort/model_v2.tflite", ScreenAiFiles.wanted("resources/gocr/layout/cluster_sort/model_v2.tflite"))
        assertEquals("aksara/a.binarypb", ScreenAiFiles.wanted("resources/aksara/a.binarypb"))
        assertNull(ScreenAiFiles.wanted(r + "cyrl_lm.fst"))
        assertNull(ScreenAiFiles.wanted(r + "kore.tflite"))
        assertNull(ScreenAiFiles.wanted("resources/libchromescreenai.so"))
        assertNull(ScreenAiFiles.wanted("resources/screen2x_model.tflite"))
        assertNull(ScreenAiFiles.wanted("resources/gocr/../../evil"))
        assertNull(ScreenAiFiles.wanted("manifest.json"))
    }

    // ── sentence audio ───────────────────────────────────────────────────────

    @Test
    fun aStretchOfTimeIsCutFromTheRing() {
        val ring = SampleRing(capacity = 1000)
        // 100 samples a second; sample n is worth n. 300 samples end at t = 10 000 ms, so they start at 7 000.
        ring.write(ShortArray(300) { it.toShort() }, 300, endMs = 10_000)
        assertArrayEquals(ShortArray(100) { (100 + it).toShort() }, ring.clip(8_000, 9_000, 100))
        // Asking for more than is held gives what there is.
        assertEquals(300, ring.clip(0, 20_000, 100)!!.size)
        assertNull(ring.clip(1_000, 2_000, 100))
        assertNull(ring.clip(9_000, 8_000, 100))
    }

    @Test
    fun theRingKeepsOnlyTheNewestSamples() {
        val ring = SampleRing(capacity = 100)
        ring.write(ShortArray(250) { it.toShort() }, 250, endMs = 2_500)
        val all = ring.clip(0, 2_500, 100)!!
        assertEquals(100, all.size)
        assertEquals(150.toShort(), all.first())
        assertEquals(249.toShort(), all.last())
        assertNull(SampleRing(10).clip(0, 1000, 100))
    }

    // ── entries as HTML ──────────────────────────────────────────────────────

    private val sense = """[{"content":{"content":[{"content":"to see","tag":"li"},{"content":"to look","tag":"li"}],"data":{"content":"glossary"},"lang":"en","style":{"listStyleType":"circle"},"tag":"ul"},"type":"structured-content"}]"""

    @Test
    fun structuredContentKeepsYomitansClassesAndData() {
        assertEquals(
            """<ul class="gloss-sc-ul" data-sc-content="glossary" style="list-style-type:circle" lang="en"><li class="gloss-sc-li">to see</li><li class="gloss-sc-li">to look</li></ul>""",
            EntryHtml.glossary(sense, "JMdict"),
        )
    }

    @Test
    fun textIsEscapedAndUnknownMarkupIsDropped() {
        val json = """[{"type":"structured-content","content":[{"tag":"script","content":"alert(1)"},{"tag":"span","content":"<b>&","style":{"color":"red;}</style>"},"title":"\"x"}]}]"""
        assertEquals("""alert(1)<span class="gloss-sc-span" title="&quot;x">&lt;b&gt;&amp;</span>""", EntryHtml.glossary(json, "D"))
        assertEquals("""<ul class="gloss-list"><li>a &lt; b</li><li>c</li></ul>""", EntryHtml.glossary("""["a < b","c"]""", "D"))
    }

    @Test
    fun picturesComeFromTheDictionarysOwnFiles() {
        val json = """[{"type":"image","path":"img/猫 1.png","width":2,"height":1.5,"sizeUnits":"em","title":"cat"}]"""
        assertEquals(
            """<img class="gloss-image" src="lfgdict://media/My%20Dict/img/%E7%8C%AB%201.png" style="width:2.0em;height:1.5em" alt="cat">""",
            EntryHtml.glossary(json, "My Dict"),
        )
    }

    @Test
    fun aDictionarysStylesApplyOnlyToItsOwnEntries() {
        val css = """/* note */ .a, li > b { color: red } @media (min-width: 1px) { .c { top: 0 } } @font-face { src: url(x) } </style>"""
        assertEquals(
            """[data-dictionary="J \"x\""] .a,[data-dictionary="J \"x\""] li > b{ color: red }@media (min-width: 1px){[data-dictionary="J \"x\""] .c{ top: 0 }}""",
            EntryHtml.scope(css, "J \"x\""),
        )
    }

    @Test
    fun anEntryPageShowsHeadwordPathAndSensesPerDictionary() {
        val entry = Entry(
            matched = "見た", deinflected = "見る", trace = listOf(Transform("-た")), expression = "見る", reading = "みる",
            glossaries = listOf(Glossary("JMdict", sense, "1 v1 vt", "")),
        )
        val html = EntryHtml.page(entry, listOf(EntryHtml.Style("JMdict", ".x{color:red}")))
        assertTrue(html.contains("""<span class="lfg-term" lang="ja"><ruby>見<rt>み</rt></ruby>る</span>"""))
        assertTrue(html.contains("見た → 見る · -た"))
        assertTrue(html.contains("""<div class="lfg-dict" data-dictionary="JMdict">"""))
        assertTrue(html.contains("""<span class="lfg-tag">v1</span><span class="lfg-tag">vt</span>"""))
        assertTrue(html.contains("""[data-dictionary="JMdict"] .x{color:red}"""))
        assertFalse(html.contains("<script"))
        assertTrue(EntryHtml.page(null, emptyList(), "No entry").contains("No entry"))
    }

    @Test
    fun jmdictsListOfFormsIsNotAMeaning() {
        val entry = Entry(
            expression = "得る", reading = "える",
            glossaries = listOf(Glossary("JMdict", """["to get"]""", "1 v1 vt", ""), Glossary("JMdict", """["得る（★）","獲る"]""", "forms", "")),
        )
        assertEquals(listOf("to get"), entry.senses())
    }
}
