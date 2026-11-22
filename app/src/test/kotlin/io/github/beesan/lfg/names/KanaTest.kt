package io.github.beesan.lfg.names

import io.github.beesan.lfg.names.Kana.alphabetToKana
import io.github.beesan.lfg.names.Kana.containsKanji
import io.github.beesan.lfg.names.Kana.hiraToKata
import io.github.beesan.lfg.names.Kana.kataToHira
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tests of kana.rs. */
class KanaTest {
    @Test
    fun kanjiIsFound() {
        assertTrue(containsKanji("漢字"))
        assertTrue(containsKanji("漢a"))
        assertTrue(containsKanji("a漢"))
        assertTrue(containsKanji("須々木"))
        assertTrue(containsKanji("「漢字」"))
        assertFalse(containsKanji("「ひらがな」"))
    }

    @Test
    fun kanaAndLettersAreNotKanji() {
        assertFalse(containsKanji("kana"))
        assertFalse(containsKanji("ひらがな"))
        assertFalse(containsKanji("カタカナ"))
        assertFalse(containsKanji("abc123"))
        assertFalse(containsKanji(""))
        assertFalse(containsKanji("123!@#$%"))
        assertFalse(containsKanji("①②③"))
    }

    @Test
    fun everyIdeographBlockCounts() {
        assertTrue(containsKanji("㐀"))
        assertTrue(containsKanji("豈"))
        assertTrue(containsKanji(String(java.lang.Character.toChars(0x20000))))
        assertTrue(containsKanji(String(java.lang.Character.toChars(0x30000))))
        assertTrue(containsKanji(String(java.lang.Character.toChars(0x2F800))))
        assertFalse(containsKanji("䷿"))
        assertFalse(containsKanji("ꀀ"))
    }

    @Test
    fun anExtensionBKanjiIsOneCharacterNotTwo() {
        // 𠮷 (U+20BB7) is two Chars in Kotlin; reading by Char would see two surrogates and no kanji.
        assertTrue(containsKanji("𠮷"))
        assertEquals("𠮷", kataToHira("𠮷"))
        assertEquals("𠮷か", kataToHira("𠮷カ"))
    }

    @Test
    fun katakanaBecomesHiragana() {
        assertEquals("あいうえお", kataToHira("アイウエオ"))
        assertEquals("かきくけこ", kataToHira("カキクケコ"))
        assertEquals("あいかき", kataToHira("あいカキ"))
        assertEquals("abc", kataToHira("abc"))
        assertEquals("", kataToHira(""))
        assertEquals("せいばー", kataToHira("セイバー"))
        assertEquals("ー", kataToHira("ー"))
        assertEquals("がぎぐげご", kataToHira("ガギグゲゴ"))
        assertEquals("ざじずぜぞ", kataToHira("ザジズゼゾ"))
        assertEquals("ぱぴぷぺぽ", kataToHira("パピプペポ"))
        assertEquals("ゔ", kataToHira("ヴ"))
        assertEquals("ぁぃぅぇぉ", kataToHira("ァィゥェォ"))
        assertEquals("だぢづでど", kataToHira("ダヂヅデド"))
        assertEquals("ばびぶべぼ", kataToHira("バビブベボ"))
        assertEquals("Hello World 123", kataToHira("Hello World 123"))
        assertEquals("漢字てすと", kataToHira("漢字テスト"))
        assertEquals("あいうかきく漢字abc", kataToHira("あいうカキク漢字abc"))
    }

    @Test
    fun hiraganaBecomesKatakana() {
        assertEquals("アイウエオ", hiraToKata("あいうえお"))
        assertEquals("カキクケコ", hiraToKata("かきくけこ"))
        assertEquals("ー", hiraToKata("ー"))
        assertEquals("ァィゥェォ", hiraToKata("ぁぃぅぇぉ"))
        assertEquals("あいうえおかきくけこ", kataToHira(hiraToKata("あいうえおかきくけこ")))
        val hira = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
        val kata = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
        assertEquals(kata, hiraToKata(hira))
        assertEquals(hira, kataToHira(kata))
    }

    @Test
    fun romajiSyllables() {
        assertEquals("あ", alphabetToKana("a"))
        assertEquals("い", alphabetToKana("i"))
        assertEquals("う", alphabetToKana("u"))
        assertEquals("え", alphabetToKana("e"))
        assertEquals("お", alphabetToKana("o"))
        assertEquals("か", alphabetToKana("ka"))
        assertEquals("し", alphabetToKana("shi"))
        assertEquals("つ", alphabetToKana("tsu"))
        assertEquals("ふ", alphabetToKana("fu"))
        assertEquals("しゃ", alphabetToKana("sha"))
        assertEquals("ち", alphabetToKana("chi"))
        assertEquals("にゃ", alphabetToKana("nya"))
        assertEquals("りょ", alphabetToKana("ryo"))
        assertEquals("", alphabetToKana(""))
    }

    @Test
    fun romajiWords() {
        assertEquals("さくら", alphabetToKana("sakura"))
        assertEquals("ときょ", alphabetToKana("tokyo"))
        assertEquals("さくら", alphabetToKana("Sakura"))
        assertEquals("ときょ", alphabetToKana("TOKYO"))
        assertEquals("あおい", alphabetToKana("aoi"))
        assertEquals("おうい", alphabetToKana("oui"))
        assertEquals("ゆうこ", alphabetToKana("yuuko"))
        assertEquals("しょういち", alphabetToKana("shouichi"))
        assertEquals("おかべ", alphabetToKana("okabe"))
        assertEquals("りんたろう", alphabetToKana("rintarou"))
        assertEquals("とおさか", alphabetToKana("toosaka"))
        assertEquals("ふじわら", alphabetToKana("fujiwara"))
        assertEquals("すずみや", alphabetToKana("suzumiya"))
        assertEquals("えみや", alphabetToKana("emiya"))
        assertEquals("しろう", alphabetToKana("shirou"))
    }

    @Test
    fun doubledConsonantsAreSmallTsu() {
        assertEquals("かっぱ", alphabetToKana("kappa"))
        assertEquals("まって", alphabetToKana("matte"))
        assertEquals("かっぺい", alphabetToKana("kappei"))
        assertEquals("せっぷく", alphabetToKana("seppuku"))
        assertEquals("っか", alphabetToKana("kka"))
        assertEquals("っさ", alphabetToKana("ssa"))
        assertEquals("った", alphabetToKana("tta"))
        assertEquals("っぱ", alphabetToKana("ppa"))
        assertEquals("っち", alphabetToKana("cchi"))
        assertEquals("っし", alphabetToKana("sshi"))
        assertEquals("っつ", alphabetToKana("ttsu"))
    }

    @Test
    fun nRules() {
        assertEquals("かんたん", alphabetToKana("kantan"))
        assertEquals("さん", alphabetToKana("san"))
        assertEquals("かな", alphabetToKana("kana"))
        assertEquals("かんあ", alphabetToKana("kan'a"))
        // nn is a doubled consonant before anything, as in the Rust builder.
        assertEquals("っな", alphabetToKana("nna"))
        assertEquals("っん", alphabetToKana("nn"))
        assertEquals("あっな", alphabetToKana("anna"))
        for ((romaji, kana) in listOf("nba" to "んば", "npa" to "んぱ", "nda" to "んだ", "nga" to "んが", "nka" to "んか", "nsa" to "んさ", "nta" to "んた", "nma" to "んま", "nra" to "んら")) {
            assertEquals(kana, alphabetToKana(romaji))
        }
        assertEquals("ん", alphabetToKana("n"))
        assertEquals("きん", alphabetToKana("kin"))
        assertEquals("しん", alphabetToKana("shin"))
        assertEquals("ほん", alphabetToKana("hon"))
        assertEquals("りんく", alphabetToKana("rinku"))
    }

    @Test
    fun romanisationVariants() {
        assertEquals("し", alphabetToKana("si"))
        assertEquals("ち", alphabetToKana("ti"))
        assertEquals("つ", alphabetToKana("tu"))
        assertEquals("ふ", alphabetToKana("hu"))
        assertEquals("ちゃ", alphabetToKana("tya"))
        assertEquals("しゃ", alphabetToKana("sya"))
        assertEquals("じ", alphabetToKana("ji"))
        assertEquals("じま", alphabetToKana("jima"))
        assertEquals("どうじま", alphabetToKana("doujima"))
        assertEquals("しょうじ", alphabetToKana("shouji"))
        assertEquals("ら", alphabetToKana("la"))
        assertEquals("り", alphabetToKana("li"))
        assertEquals("る", alphabetToKana("lu"))
        assertEquals("れ", alphabetToKana("le"))
        assertEquals("ろ", alphabetToKana("lo"))
        assertEquals("らいん", alphabetToKana("lain"))
        assertEquals("じゃ", alphabetToKana("ja"))
        assertEquals("じゅ", alphabetToKana("ju"))
        assertEquals("じょ", alphabetToKana("jo"))
        assertEquals("じゃ", alphabetToKana("jya"))
        assertEquals("じゅ", alphabetToKana("jyu"))
        assertEquals("じょ", alphabetToKana("jyo"))
        assertEquals("ゐ", alphabetToKana("wi"))
        assertEquals("ゑ", alphabetToKana("we"))
        assertEquals("を", alphabetToKana("wo"))
    }

    @Test
    fun wholeRows() {
        assertEquals("かきくけこ", alphabetToKana("kakikukeko"))
        assertEquals("さしすせそ", alphabetToKana("sasisuseso"))
        assertEquals("たちつてと", alphabetToKana("tatituteto"))
        assertEquals("なにぬねの", alphabetToKana("naninuneno"))
        assertEquals("はひふへほ", alphabetToKana("hahihuheho"))
        assertEquals("まみむめも", alphabetToKana("mamimumemo"))
        assertEquals("らりるれろ", alphabetToKana("rarirurero"))
        assertEquals("がぎぐげご", alphabetToKana("gagigugego"))
        assertEquals("ざじずぜぞ", alphabetToKana("zazizuzezo"))
        assertEquals("だぢづでど", alphabetToKana("dadidudedo"))
        assertEquals("ばびぶべぼ", alphabetToKana("babibubebo"))
        assertEquals("ぱぴぷぺぽ", alphabetToKana("papipupepo"))
        assertEquals("きゃきゅきょしゃしゅしょ", alphabetToKana("kyakyukyoshashusho"))
        assertEquals("ちゃちゅちょにゃにゅにょ", alphabetToKana("chachuchonyanyunyo"))
        assertEquals("ひゃひゅひょみゃみゅみょ", alphabetToKana("hyahyuhyomyamyumyo"))
        assertEquals("りゃりゅりょぎゃぎゅぎょ", alphabetToKana("ryaryuryogyagyugyo"))
        assertEquals("びゃびゅびょぴゃぴゅぴょ", alphabetToKana("byabyubyopyapyupyo"))
    }

    @Test
    fun foreignSounds() {
        assertEquals("ふぁ", alphabetToKana("fa"))
        assertEquals("ふぃ", alphabetToKana("fi"))
        assertEquals("ふぇ", alphabetToKana("fe"))
        assertEquals("ふぉ", alphabetToKana("fo"))
        assertEquals("じぇ", alphabetToKana("je"))
        assertEquals("しぇ", alphabetToKana("she"))
        assertEquals("ちぇ", alphabetToKana("che"))
        assertEquals("つぁ", alphabetToKana("tsa"))
        assertEquals("つぃ", alphabetToKana("tsi"))
        assertEquals("つぇ", alphabetToKana("tse"))
        assertEquals("つぉ", alphabetToKana("tso"))
    }

    @Test
    fun boundaryMarksSplitSyllables() {
        assertEquals("しんいち", alphabetToKana("Shin'ichi"))
        assertEquals("じゅんいち", alphabetToKana("Jun'ichi"))
        assertEquals("けんいち", alphabetToKana("Ken'ichi"))
        assertEquals("しんいちろう", alphabetToKana("Shin'ichirou"))
        assertEquals("げんいちろう", alphabetToKana("Gen'ichirou"))
        assertEquals("てんよう", alphabetToKana("Ten'you"))
        assertEquals("しにち", alphabetToKana("Shinichi"))
        assertEquals("せいいち", alphabetToKana("Sei-ichi"))
        assertEquals("あこ", alphabetToKana("A.ko"))
        assertEquals("しんいちろう", alphabetToKana("Shin'ichi-rou"))
        assertEquals("しんいち", alphabetToKana("Shin’ichi"))
    }

    @Test
    fun otherCharacters() {
        assertEquals("b", alphabetToKana("2B"))
        assertEquals("りん たろう", alphabetToKana("Rin Tarou"))
        assertEquals("x", alphabetToKana("x"))
        assertEquals("q", alphabetToKana("q"))
        val long = alphabetToKana("suzumiyaharuhikyonnagatoyukiasahinamikurukoizumiitsuki")
        assertTrue(long.isNotEmpty())
        assertTrue(long, long.all { it.code in 0x3040..0x309F })
    }
}
