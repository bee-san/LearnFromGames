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

    @Test
    fun screenAiLinesComeInReadingOrderWhateverOrderTheyWereFoundIn() {
        fun line(text: String, left: Int, top: Int, right: Int, bottom: Int, vertical: Boolean = false) =
            io.github.beesan.lfg.core.TextLine(text, io.github.beesan.lfg.core.Box(left, top, right, bottom), emptyList(), vertical)
        // As the fast mode gave a real screen: the end of a row before its start, the buttons before both.
        val found = listOf(
            line("ッ!!", 860, 826, 1007, 882), line("自動", 1784, 932, 1866, 972), line("次へ", 1779, 1009, 1875, 1051),
            line("ガリガリガリ", 216, 822, 581, 877),
        )
        assertEquals(listOf("ガリガリガリ", "ッ!!", "自動", "次へ"), ScreenAi.ordered(found).map { it.text })
        // A row's second piece that sits a few pixels higher than its first still follows it.
        val uneven = listOf(line("後", 600, 818, 700, 874), line("前", 200, 822, 500, 878), line("下", 200, 900, 500, 956))
        assertEquals(listOf("前", "後", "下"), ScreenAi.ordered(uneven).map { it.text })
        // Columns are read from the right, after the rows.
        val mixed = listOf(line("左", 100, 100, 150, 500, vertical = true), line("右", 300, 100, 350, 500, vertical = true), line("横", 500, 600, 900, 650))
        assertEquals(listOf("横", "右", "左"), ScreenAi.ordered(mixed).map { it.text })
    }

    @Test
    fun aRowCutAtItsSpacesIsOneRowAgain() {
        fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): io.github.beesan.lfg.core.TextLine {
            // Each character in a cell of its own, as wide as the others.
            val cell = (right - left) / text.length
            val chars = text.indices.map { io.github.beesan.lfg.core.CharBox(it, it + 1, io.github.beesan.lfg.core.Box(left + it * cell, top, left + (it + 1) * cell, bottom)) }
            return io.github.beesan.lfg.core.TextLine(text, io.github.beesan.lfg.core.Box(left, top, right, bottom), chars)
        }
        fun read(vararg found: io.github.beesan.lfg.core.TextLine) = ScreenAi.joined(ScreenAi.ordered(found.toList()))
        // Pokémon Let's Go on an AYN Thor, a word at a time: two rows, not columns of words.
        val words = read(
            line("タイプの", 395, 884, 593, 935), line("相性を", 665, 884, 808, 933),
            line("知っている", 395, 974, 640, 1020), line("トレーナー", 707, 974, 956, 1016), line("でないと", 1022, 968, 1218, 1016),
        )
        assertEquals(listOf("タイプの 相性を", "知っている トレーナー でないと"), words.map { it.text })
        assertEquals(io.github.beesan.lfg.core.Box(395, 968, 1218, 1020), words[1].box)
        // Each character keeps its place, after the space.
        assertEquals("ト", words[1].text.substring(words[1].chars[5].start, words[1].chars[5].end))
        assertEquals(707, words[1].chars[5].box.left)
        assertEquals(listOf("タイプの 相性を知っている トレーナー でないと"), Blocks.group(words).map { it.text })
        // A word of small kana is lower than its row, and still of it.
        assertEquals(listOf("いつ 来ても"), read(line("いつ", 403, 893, 495, 929), line("来ても", 558, 885, 702, 932)).map { it.text })
        assertEquals(listOf("あのう…… この 森に"), read(line("あのう……", 397, 891, 654, 931), line("この", 724, 888, 812, 931), line("森に", 880, 885, 979, 933)).map { it.text })
        // A sentence that ends in the middle of a row.
        assertEquals(listOf("「え? あの男って理事長の息子なの?」"), read(line("「え?", 343, 865, 454, 914), line("あの男って理事長の息子なの?」", 522, 864, 1258, 916)).map { it.text })
        // A menu's buttons are further apart than words are.
        assertEquals(listOf("ポケモン", "バッグ"), read(line("ポケモン", 1538, 946, 1684, 986), line("バッグ", 1756, 945, 1868, 983)).map { it.text })
        // A speaker's name beside what is said stays a piece of its own.
        assertEquals(listOf("果音", "「仕方ないなぁ。私が手伝ってあげるよ」"), read(line("果音", 184, 781, 343, 837), line("「仕方ないなぁ。私が手伝ってあげるよ」", 434, 781, 1566, 845)).map { it.text })
        assertEquals(listOf("【純夏】", "「まだ言ってる……」"), read(line("【純夏】", 624, 973, 768, 1023), line("「まだ言ってる……」", 828, 980, 1289, 1029)).map { it.text })
        // A piece begun on the end of the one before it, its first character read twice.
        assertEquals(
            listOf("ふたりで 冒険を 楽しもう!"),
            read(line("ふたりで", 402, 858, 590, 906), line("冒険を", 660, 857, 807, 905), line("を 楽しもう!", 773, 857, 1101, 907)).map { it.text },
        )
        // Two pieces over one another that are not the same text stay two.
        assertEquals(listOf("冒険を", "に 楽しもう!"), read(line("冒険を", 660, 857, 807, 905), line("に 楽しもう!", 773, 857, 1101, 907)).map { it.text })
        // Small print beside a line is not the line's.
        assertEquals(listOf("とても大きい", "注"), read(line("とても大きい", 200, 800, 500, 850), line("注", 530, 820, 550, 840)).map { it.text })
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

    @Test
    fun aNewRecordersSoundGoesOverWhatTheOldOneGaveAlready() {
        // 100 samples a second, sample n heard at n × 10 ms. The old recorder gave sound to 3 000 ms; the new one
        // began at 2 500 ms, so it gives that half second again before going on.
        val ring = SampleRing(capacity = 1000)
        ring.write(ShortArray(300) { it.toShort() }, 300, endMs = 3_000)
        ring.rewind(500, 100)
        ring.write(ShortArray(100) { (250 + it).toShort() }, 100, endMs = 3_500)
        assertArrayEquals(ShortArray(350) { it.toShort() }, ring.clip(0, 3_500, 100))
        assertArrayEquals(ShortArray(100) { (200 + it).toShort() }, ring.clip(2_000, 3_000, 100))
        // No more is taken back than is held, and what the taken back samples were written over stays gone.
        val small = SampleRing(capacity = 100)
        small.write(ShortArray(250) { it.toShort() }, 250, endMs = 2_500)
        small.rewind(300, 100)
        assertArrayEquals(ShortArray(70) { (150 + it).toShort() }, small.clip(0, 2_500, 100))
        small.rewind(10_000, 100)
        assertNull(small.clip(0, 2_500, 100))
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
