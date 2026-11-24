package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.Held
import io.github.beesan.lfg.core.KeptLine
import io.github.beesan.lfg.core.LineSorter
import io.github.beesan.lfg.core.Part
import io.github.beesan.lfg.core.TextLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSorterTest {
    private class Row(val id: String, var text: String, val interfaceText: Boolean, val at: Long)

    /** A game as the pipeline keeps one: readings go in, and the rows that came of them can be looked at. */
    private class Game(val apart: Boolean = true) {
        val sorter = LineSorter()
        val rows = ArrayList<Row>()
        private var clock = 0L

        /** One reading of the screen. Returns, for each piece, the rows of its parts. */
        fun read(vararg pieces: String): List<List<String>> = runBlocking {
            clock += 1000
            val lines = rows.filter { !it.interfaceText }.asReversed().take(LineSorter.RECENT)
            val known = rows.filter { it.interfaceText }.mapTo(HashSet()) { it.text }
            val sorted = sorter.sort(pieces.toList(), lines.map { KeptLine(it.id, it.text, it.at) }, known, apart) { text ->
                rows.lastOrNull { !it.interfaceText && it.text == text }?.let { KeptLine(it.id, it.text, it.at) }
            }
            val held = pieces.mapIndexed { i, piece ->
                sorted.parts[i].map { part ->
                    val text = piece.substring(part.from, part.to)
                    val row = when (part) {
                        is Part.Same -> rows.first { it.id == part.id }
                        is Part.Grown -> rows.first { it.id == part.id }.also { it.text = text }
                        is Part.New -> Row("r${rows.size + 1}", text, part.interfaceText, clock).also { rows += it }
                    }
                    Held(part.from, part.to, row.id, row.text, row.interfaceText)
                }
            }
            sorter.shown(pieces.toList(), held, sorted.menu)
            held.map { parts -> parts.map { it.id } }
        }

        /** The lines kept, in the order they were read, and what was kept as a menu's text. */
        val lines get() = rows.filter { !it.interfaceText }.map { it.text }
        val menus get() = rows.filter { it.interfaceText }.map { it.text }
    }

    private val line = "今日から俺は今まで以上に動かなくてはならないのだ。"
    private val menu = arrayOf(
        "セーブ", "ロード", "オプション", "バックログ", "オート", "スキップ", "タイトルへ", "ゲーム終了",
        "文字の表示速度や音量などを変更します。",
    )
    private val buttons = arrayOf("セーブ", "ロード", "オート", "スキップ", "ログ", "設定")
    private val pause = arrayOf("アイテム", "そうび", "まほう", "つよさ", "さくせん", "じょうたい", "中断する")

    // ── a line under a menu ──

    @Test
    fun aLineUnderAMenuIsTheSameLineWhenTheMenuCloses() {
        val game = Game()
        val first = game.read(line)
        game.read(*menu)
        assertEquals(first, game.read(line))
        assertEquals(listOf(line), game.lines)
        // None of the menu's pieces is a line, the long one neither.
        assertEquals(menu.toList(), game.menus)
    }

    @Test
    fun aCharacterReadDifferentlyTheSecondTimeIsStillTheSameLine() {
        val game = Game()
        val first = game.read(line)
        game.read(*menu)
        assertEquals(first, game.read(line.replace('動', '勧')))
        assertEquals(listOf(line), game.lines)
    }

    @Test
    fun wordsInThePictureReadDifferentlyAtEachLookAreOnePiece() = runBlocking {
        // CLANNAD's library, on the Thor: a book's spine behind the text box, taken a little differently with each line.
        val sorter = LineSorter()
        val rows = ArrayList<KeptLine>()
        val box = Box(100, 800, 1500, 900)
        val spine = Box(1700, 300, 1760, 560)
        val small = Box(1500, 200, 1540, 330)
        suspend fun read(vararg pieces: Pair<String, Box>): List<String> {
            val texts = pieces.map { it.first }
            val places = pieces.map { it.second }
            val sorted = sorter.sort(texts, rows.asReversed(), places = places)
            val held = texts.mapIndexed { i, text ->
                val part = sorted.parts[i].single()
                val id = when (part) {
                    is Part.Same -> part.id
                    is Part.Grown -> part.id
                    is Part.New -> "r${rows.size + 1}".also { rows += KeptLine(it, text, rows.size + 1L) }
                }
                listOf(Held(0, text.length, id, rows.first { it.id == id }.text))
            }
            sorter.shown(texts, held, sorted.menu, places)
            return held.map { it[0].id }
        }
        val first = read("少女「今日のは、粘土じゃないから」" to box, "重学の基礎(朝超" to spine, "新黨人里" to small)
        val second = read("朋也「…普段は粘土で作った弁当を食ってるのか?」" to box, "重字の基礎(朝场" to spine, "転集人橋" to small)
        val third = read("少女「食べないの。お腹こわすから」" to box, "重字の基礎(制超)" to spine)
        // Each spine is one row throughout, the small one though nothing of it was read twice alike, and each line its own.
        assertEquals(first[1], second[1])
        assertEquals(first[1], third[1])
        assertEquals(first[2], second[2])
        assertEquals(5, rows.size)
        // A short line after a short line in the text box is a new line: no long line came with it.
        val yes = read("うん。" to box, "重字の基礎(制超)" to spine)
        val no = read("ううん。" to box, "重字の基礎(制超)" to spine)
        assertNotEquals(yes[0], no[0])
        // Two lines alike in the same place are still two lines: nothing else on the reading was new.
        val one = read("そうなのかもしれない。" to box, "重字の基礎(制超)" to spine)
        val other = read("そうなのかもしれぬ。" to box, "重字の基礎(制超)" to spine)
        assertNotEquals(one[0], other[0])
    }

    @Test
    fun aLineSaidAgainWhileTheStoryMovesOnIsANewLine() {
        // CLANNAD, on the Thor: she says it, he thinks, she says it again.
        val game = Game()
        val first = game.read("少女「私の手づくりなの」")
        game.read("聞いてないっての。")
        val again = game.read("少女「私の手づくりなの」")
        assertNotEquals(first, again)
        assertEquals(listOf("少女「私の手づくりなの」", "聞いてないっての。", "少女「私の手づくりなの」"), game.lines)
        // But read again after a menu was over it, it is the same line.
        game.read(*menu)
        assertEquals(again, game.read("少女「私の手づくりなの」"))
        assertEquals(3, game.lines.size)
    }

    @Test
    fun linesReadAgainAfterLoadingASaveAreTheLinesAlreadyKept() {
        val game = Game()
        val lines = listOf(
            "図書室は、飲食禁止だった気がする。", "タッパーの中を指さす。この辺が自信作らしい。", "朋也「たしかに、うまそうだけどな…」",
            "少女「今日のメニューは、出汁巻き卵と肉じゃがなの」", "俺はしみじみそう思う。それだけのことだった。",
        )
        val rows = lines.map { game.read(it) }
        // The load screen, then the save's line and those after it, one at a time as before.
        game.read(*menu)
        assertEquals(rows[1], game.read(lines[1]))
        assertEquals(rows[2], game.read(lines[2]))
        assertEquals(rows[4], game.read(lines[4]))
        assertEquals(5, game.lines.size)
        // Past the last line kept, the story is new again.
        game.read("ここからは新しい文章になります。")
        assertEquals(6, game.lines.size)
    }

    @Test
    fun linesThatDifferOnlyInANumberAreEachALine() {
        val game = Game()
        for (day in 1..12) game.read("それから${day}日目の朝が来て、また同じ一日が始まった。")
        game.read("残り時間はあと五分しかない。")
        game.read("残り時間はあと四分しかない。")
        assertEquals(14, game.lines.size)
        // But 二 read as the katakana ニ is the reader's slip, and the same line.
        val two = game.read("二人で一緒に帰ることにした。")
        game.read(*menu)
        assertEquals(two, game.read("ニ人で一緒に帰ることにした。"))
        assertEquals(15, game.lines.size)
    }

    @Test
    fun aShortLineUnderAMenuIsTheSameLineToo() {
        val game = Game()
        val first = game.read("アリス", "うん。")
        game.read(*menu)
        assertEquals(first, game.read("アリス", "うん。"))
        assertEquals(listOf("アリス", "うん。"), game.lines)
    }

    @Test
    fun aMenuOpenedAgainIsStillNoLine() {
        val game = Game()
        game.read(line)
        game.read(*menu)
        game.read(line)
        game.read(*menu)
        game.read(line)
        assertEquals(listOf(line), game.lines)
    }

    @Test
    fun withTellingApartOffTheMenuIsKeptButTheLineStillOnce() {
        val game = Game(apart = false)
        game.read(line)
        game.read(*menu)
        game.read(line)
        assertEquals(listOf(line) + menu, game.lines)
        assertTrue(game.menus.isEmpty())
    }

    // ── Senren Banka on the AYN Thor, 2026-11-22: its quick-save prompt and toast over a line ──

    private val thought = "俺はしみじみそう思う。"
    private val prompt = arrayOf("クイックセーブしますか?", "はい", "いいえ", "次回から確認しない")

    @Test
    fun aLineUnderAPromptAndItsToastIsTheLineAlreadyKept() {
        val game = Game()
        val kept = game.read(thought).single()
        // Dimmed under the prompt, は was read as ば; then it was read right again.
        assertEquals(kept, game.read("俺ばしみじみそう思う。", *prompt).first())
        assertEquals(kept, game.read(thought, *prompt).first())
        // The toast shows the line again with its icon read as 目 in front, and hides 思う of the line itself.
        assertEquals(listOf(kept, kept), game.read("目俺はしみじみそう思う。", "俺はしみじみそう。"))
        assertEquals(listOf(kept), game.read(thought))
        assertEquals(listOf(thought) + prompt, game.lines)
    }

    @Test
    fun aPartlyHiddenLineIsOnlyTheLineThatWasOnScreen() {
        val game = Game()
        val next = "次の日の朝はよく晴れていた。"
        val long = "今日は楽しかったね、また来ようよ。"
        game.read(thought)
        game.read(next)
        // A few characters that happen to be in the last line, in its order, are a line of their own.
        game.read("朝はよく。")
        // So is what would be the line before the last with a word hidden: that line is not on screen.
        game.read("俺はしみじみそう。")
        // And so is less than half of the last line.
        game.read(long)
        game.read("また来ようよ。")
        assertEquals(listOf(thought, next, "朝はよく。", "俺はしみじみそう。", long, "また来ようよ。"), game.lines)
    }

    @Test
    fun aSmallPromptIsKeptOnceWhileItsLinesAreAmongTheLast() {
        // Four pieces are no crowd, so they are lines until they have come back often enough (InterfaceText.comesBack).
        val game = Game()
        game.read(thought)
        val first = game.read(thought, *prompt)
        game.read(thought)
        game.read("次の日の朝はよく晴れていた。")
        assertEquals(first.drop(1), game.read("次の日の朝はよく晴れていた。", *prompt).drop(1))
        assertEquals(listOf(thought) + prompt + "次の日の朝はよく晴れていた。", game.lines)
    }

    @Test
    fun aLineIsFoundAmongTheLastLinesWhateverMenusCameAfterIt() {
        val game = Game()
        val blessing = "神主さんが、安晴さんで良かった。"
        val kept = game.read(blessing)
        // A prompt and menus since then: later lines, and more rows than the six that used to be looked back over.
        game.read(blessing, *prompt)
        game.read(*menu)
        game.read(*pause)
        game.read(*slots)
        // The app restarts while the text box is hidden; then the box is shown again.
        game.sorter.reset()
        assertEquals(kept, game.read(blessing))
        assertEquals(listOf(blessing) + prompt, game.lines)
    }

    // ── lines read before ──

    private val talk = listOf(
        "おはよう、今日はいい天気だね。", "うん。", "どこかへ出かけない？公園とか。", "……", "それとも家でゆっくりする？",
        "そうだね。", "じゃあ、お弁当を作っていこうか。", "やった！",
    )

    @Test
    fun aBacklogPageAddsNoLinesAndMarksNone() {
        val game = Game()
        val read = talk.map { game.read(it).single() }
        // The backlog lists them all, short and long; then it closes on the line it was opened over.
        assertEquals(read, game.read(*talk.toTypedArray()))
        assertEquals(listOf(read.last()), game.read(talk.last()))
        assertEquals(talk, game.lines)
        assertTrue(game.menus.isEmpty())
    }

    @Test
    fun aPageOfABacklogFromLongAgoMarksNoneEither() {
        val game = Game()
        val old = talk.take(6).map { game.read(it).single() }
        // Sixty lines, no two alike: a line that differs from the last by a character would be taken for it.
        val kana = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわ"
        repeat(60) {
            val a = kana[it % 23].toString().repeat(3)
            val b = kana[23 + it / 23].toString().repeat(3)
            game.read("$a${b}と名乗る旅人がこの村を訪れた。")
        }
        // Further back than the last lines kept: found by their text, so not one is a menu's.
        assertEquals(old, game.read(*talk.take(6).toTypedArray()))
        assertTrue(game.menus.isEmpty())
        assertEquals(66, game.lines.size)
    }

    @Test
    fun aShortLineSaidAgainIsANewLine() {
        val game = Game()
        val first = game.read("……")
        for (other in talk.filter { it != "……" }.take(4)) game.read(other)
        assertNotEquals(first, game.read("……"))
        assertEquals(2, game.lines.count { it == "……" })
    }

    @Test
    fun theLineALoadedSaveOpensOnIsTheLineReadBefore() {
        val game = Game()
        game.read(talk[0])
        val last = game.read(line)
        // The app was restarted: nothing is remembered but what the store holds.
        game.sorter.reset()
        assertEquals(last, game.read(line))
        assertEquals(listOf(talk[0], line), game.lines)
    }

    // ── text that grows ──

    private val one = "ひとつ、屋根の下で暮らすこと。"
    private val two = "ふたつ、嘘をつかないこと。"
    private val three = "みっつ、朝は必ず起きること。"

    @Test
    fun aPageThatGrowsBySentencesIsOneLineForEach() {
        val game = Game()
        val a = game.read(one).single().single()
        val ab = game.read(one + two).single()
        assertEquals(listOf(one, two), game.lines)
        assertEquals(a, ab[0])
        val abc = game.read(one + two + three).single()
        assertEquals(listOf(one, two, three), game.lines)
        assertEquals(ab, abc.take(2))
        // Read again as it is, and again after a menu, it is the same three lines.
        assertEquals(listOf(abc), game.read(one + two + three))
        game.read(*menu)
        assertEquals(listOf(abc), game.read(one + two + three))
        assertEquals(listOf(one, two, three), game.lines)
    }

    @Test
    fun aNewPageAfterAGrownOneStartsAfresh() {
        val game = Game()
        game.read(one)
        game.read(one + two)
        game.read(three)
        assertEquals(listOf(one, two, three), game.lines)
    }

    @Test
    fun aLineCaughtHalfTypedGrowsIntoTheWholeOfIt() {
        val game = Game()
        val half = game.read("今日から俺は、")
        assertEquals(half, game.read("今日から俺は、今まで以上に動かなくてはならないのだ。"))
        assertEquals(listOf("今日から俺は、今まで以上に動かなくてはならないのだ。"), game.lines)
    }

    @Test
    fun aLineTypedOutAgainAfterAMenuIsStillTheSameLine() {
        val game = Game()
        val first = game.read(line)
        game.read(*menu)
        assertEquals(first, game.read(line.take(12)))
        assertEquals(first, game.read(line))
        assertEquals(listOf(line), game.lines)
    }

    @Test
    fun aShortLineIsNotTakenForTheStartOfTheNext() {
        val game = Game()
        game.read("え")
        game.read("えっと、それはですね。")
        game.read("うん。")
        game.read("うん。そうだね。")
        assertEquals(listOf("え", "えっと、それはですね。", "うん。", "うん。そうだね。"), game.lines)
    }

    @Test
    fun aQuoteCaughtAfterItsFirstSentenceGrowsIntoTheWholeOfIt() {
        val game = Game()
        val first = game.read("「そうだったのか。")
        assertEquals(first, game.read("「そうだったのか。それなら仕方ないな」"))
        assertEquals(listOf("「そうだったのか。それなら仕方ないな」"), game.lines)
        // Once the quote is closed, what follows is a line of its own.
        game.read("「そうだったのか。それなら仕方ないな」俺はそう答えた。")
        assertEquals(listOf("「そうだったのか。それなら仕方ないな」", "俺はそう答えた。"), game.lines)
    }

    @Test
    fun aClosingBracketReadLateIsNoLineOfItsOwn() {
        val game = Game()
        val first = game.read("「そうだったのか。")
        assertEquals(first, game.read("「そうだったのか。」"))
        assertEquals(1, game.lines.size)
    }

    @Test
    fun twoPiecesReadAsOneAreStillTheirOwnLines() {
        val game = Game()
        val apart = game.read(one, two)
        assertEquals(listOf(apart.flatten()), game.read(one + two))
        assertEquals(listOf(one, two), game.lines)
    }

    @Test
    fun aPartOfALineIsTheLineCutToItsOwnCharacters() {
        val text = one + two
        val wide = 10
        // Two rows of ten characters: the second sentence starts in the middle of the second row.
        val chars = text.indices.map { CharBox(it, it + 1, Box(it % wide * 50, it / wide * 60, it % wide * 50 + 50, it / wide * 60 + 50)) }
        val rows = chars.chunked(wide).map { row -> row.map { it.box }.reduce(Box::union) }
        val page = TextLine(text, rows.reduce(Box::union), chars, rows = rows)
        val second = page.slice(one.length, text.length)
        assertEquals(two, second.text)
        assertEquals(two.length, second.chars.size)
        assertEquals(0, second.chars.first().start)
        // It starts where the first sentence stops, on the row they share, and runs on to the rows below.
        assertEquals(Box(one.length % wide * 50, one.length / wide * 60, wide * 50, one.length / wide * 60 + 50), second.rows.first())
        assertEquals(text.length / wide - one.length / wide + 1, second.rows.size)
        assertEquals(0, second.charIndexAt(second.rows.first().left + 10, second.rows.first().top + 10))
    }

    // ── a menu is pieces arriving together ──

    @Test
    fun aMenuIsACrowdArrivingTogether() {
        val game = Game()
        val labels = arrayOf("オーディオ", "ビデオ", "コントロール", "ネットワーク", "システム", "ゲームを終了する")
        game.read(*labels)
        assertEquals(labels.toList(), game.menus)
        assertTrue(game.lines.isEmpty())
    }

    @Test
    fun aLineAndItsChoicesAreNoMenu() {
        val game = Game()
        game.read("どうする？")
        game.read("どうする？", "戦う", "逃げる", "話しかける")
        assertEquals(listOf("どうする？", "戦う", "逃げる", "話しかける"), game.lines)
        assertTrue(game.menus.isEmpty())
    }

    @Test
    fun theLineAfterTheFirstSightOfARowOfButtonsIsALine() {
        val game = Game()
        // Nothing tells the first line from a menu's description: it is lost, once, with the buttons it came with.
        game.read(*buttons, "ここはどこだろう。見覚えのない天井だ。")
        game.read(*buttons, "誰かの足音が近づいてくる。")
        game.read(*buttons, "……")
        assertEquals(listOf("誰かの足音が近づいてくる。", "……"), game.lines)
    }

    @Test
    fun aLineThatComesBackWithItsButtonsIsALine() {
        val game = Game()
        game.read(*buttons, "ここはどこだろう。見覚えのない天井だ。")
        game.read(*buttons, "誰かの足音が近づいてくる。")
        // A menu over it all, then the next line with the buttons back; and the same when nothing is remembered.
        game.read(*pause)
        game.read(*buttons, "扉が静かに開いた。")
        game.sorter.reset()
        game.read(*buttons, "そこに立っていたのは、妹だった。")
        assertEquals(listOf("誰かの足音が近づいてくる。", "扉が静かに開いた。", "そこに立っていたのは、妹だった。"), game.lines)
    }

    private val slots = (1..10).map { "第${it}章の途中、雨の降る夜の出来事を思い返して…" }.toTypedArray()

    @Test
    fun aSaveScreensSlotsAreNoLines() {
        val game = Game()
        game.read(line)
        game.read(*slots)
        game.read(line)
        // The same list again: known as a menu's now, and still one in such company.
        game.read(*slots)
        game.read(line)
        assertEquals(listOf(line), game.lines)
    }

    @Test
    fun aMenuStillUpAddsNothing() {
        val game = Game()
        game.read(line)
        val first = game.read(*slots)
        val kept = game.rows.size
        assertEquals(first, game.read(*slots))
        assertEquals(kept, game.rows.size)
    }

    @Test
    fun helpTextThatChangesWhileAMenuIsUpIsKeptAsALine() {
        // What cannot be told: it arrives alone among labels that stay, as a line does over a row of buttons.
        val game = Game()
        game.read(line)
        game.read(*menu)
        val other = "ここで既読の文章だけを飛ばすかどうかを決めます。"
        game.read(*menu.dropLast(1).toTypedArray(), other)
        assertEquals(listOf(line, other), game.lines)
    }

    @Test
    fun aSlotThatShowsTheStartOfALineReadIsThatLine() {
        val game = Game()
        val first = game.read(line)
        val shown = game.read(line.take(12) + "…", *slots)
        assertEquals(first.single(), shown.first())
        assertEquals(listOf(line), game.lines)
    }

    @Test
    fun aLineASlotShowedWholeIsALineWhenTheGameTellsIt() {
        val game = Game()
        game.read(line)
        val whole = "ただいま、みんな元気だった？"
        game.read(whole, *slots)
        assertTrue(whole in game.menus)
        // The save is loaded, and the game opens on that very line.
        game.read(whole)
        assertEquals(listOf(line, whole), game.lines)
    }
}
