package io.github.beesan.lfg.names

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** The tests of dict_builder.rs that apply to one visual novel from VNDB, with a made-up cast. */
class CharacterDictionaryTest {
    private val vn = VnInfo("Teatime Rooftop", "屋上ティータイム")
    private val all = DictSettings(showSpoilers = true)
    private val noHonorifics = DictSettings(showSpoilers = true, honorifics = false)

    private fun character(id: String, name: String, nameOriginal: String, role: String) = Character(
        id = id,
        name = name,
        nameOriginal = nameOriginal,
        role = role,
        sex = "m",
        age = "17",
        height = 170,
        weight = 60,
        bloodType = "A",
        birthday = listOf(1, 1),
        description = "Test description",
        aliases = listOf("テストエイリアス"),
        personality = listOf(CharacterTrait("Kind", 0)),
    )

    private val haruka = character("c1", "Morikawa Haruka", "森川 遥香", "main")

    private fun entries(settings: DictSettings, vararg cast: Character) = CharacterDictionary.entries(vn, cast.toList(), settings)

    private fun terms(settings: DictSettings, vararg cast: Character) = entries(settings, *cast).map { it.term }

    private val JsonArray.term get() = this[0].jsonPrimitive.content
    private val JsonArray.reading get() = this[1].jsonPrimitive.content
    private val JsonArray.tags get() = this[2].jsonPrimitive.content
    private val JsonArray.score get() = this[4].jsonPrimitive.int
    private val JsonArray.card get() = this[5].jsonArray[0].jsonObject

    @Test
    fun scores() {
        assertEquals(100, CharacterDictionary.getScore("main"))
        assertEquals(75, CharacterDictionary.getScore("primary"))
        assertEquals(50, CharacterDictionary.getScore("side"))
        assertEquals(25, CharacterDictionary.getScore("appears"))
        assertEquals(0, CharacterDictionary.getScore("unknown"))
        assertEquals(0, CharacterDictionary.getScore(""))
    }

    @Test
    fun aCharacterWithoutAJapaneseNameIsSkipped() {
        assertTrue(entries(all, character("c1", "Name", "", "main")).isEmpty())
        assertTrue(entries(noHonorifics, character("c1", "Name", " ", "main")).none { it.term.isEmpty() })
    }

    @Test
    fun twoPartNamesInTheRustOrder() {
        val e = entries(noHonorifics, haruka)
        assertEquals(
            listOf(
                "森川 遥香", "森川遥香", "森川", "遥香",
                "もりかわはるか", "もりかわ はるか", "もりかわ", "はるか",
                "モリカワハルカ", "モリカワ ハルカ", "モリカワ", "ハルカ",
                "テストエイリアス",
            ),
            e.map { it.term },
        )
        assertEquals(
            listOf(
                "もりかわはるか", "もりかわはるか", "もりかわ", "はるか",
                "もりかわはるか", "もりかわはるか", "もりかわ", "はるか",
                "もりかわはるか", "もりかわはるか", "もりかわ", "はるか",
                "もりかわはるか",
            ),
            e.map { it.reading },
        )
        assertTrue(e.all { it.tags == "name main" && it.score == 100 })
    }

    @Test
    fun singleWordNames() {
        val e = entries(noHonorifics, character("c1", "Saber", "セイバー", "main").copy(aliases = listOf("せいばー")))
        assertEquals(listOf("セイバー", "せいばー"), e.map { it.term })
        assertEquals(listOf("名前", "なまえ", "ナマエ", "テストエイリアス"), terms(noHonorifics, character("c1", "Namae", "名前", "main")))
        assertEquals(1, terms(noHonorifics, character("c1", "Namae", "名前", "main").copy(aliases = listOf("名前"))).count { it == "名前" })
    }

    @Test
    fun aliasesNeedJapanese() {
        val t = terms(noHonorifics, haruka.copy(aliases = listOf("Haru", "", "はるちゃん", "ハル")))
        assertFalse("Haru" in t)
        assertTrue("はるちゃん" in t)
        assertTrue("ハル" in t)
    }

    @Test
    fun honorificsFollowEveryForm() {
        val e = entries(all, haruka)
        val t = e.map { it.term }
        for (form in listOf("森川さん", "遥香さん", "森川遥香さん", "森川 遥香さん", "もりかわさん", "モリカワさん", "モリカワハルカちゃん", "テストエイリアスさん")) {
            assertTrue("Missing $form", form in t)
        }
        assertEquals("Each term:reading pair once", e.size, e.map { "${it.term}:${it.reading}" }.toSet().size)
        val san = e.first { it.term == "森川さん" }
        assertEquals("もりかわさん", san.reading)
        val banner = san.card["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("さん", banner[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue("Generic polite" in banner[1].jsonObject["content"]!!.jsonPrimitive.content)
        // Base entries all come before any honorific.
        assertEquals(13, e.indexOfFirst { it.term == "森川さん" })
        assertEquals(13, entries(noHonorifics, haruka).size)
    }

    @Test
    fun theFirstOfTwoDuplicateSuffixesWins() {
        val e = entries(all, haruka)
        assertEquals(1, e.count { it.term == "森川殿" })
        assertEquals("もりかわどの", e.first { it.term == "森川殿" }.reading)
    }

    @Test
    fun honorificsAreSharedOutAcrossTheCast() {
        val ichiro = character("c1", "Hoshino Ichirou", "星野 一郎", "main")
        val jirou = character("c2", "Hoshino Jirou", "星野 二郎", "side").copy(aliases = emptyList())
        val t = terms(all, ichiro, jirou)
        assertEquals(1, t.count { it == "星野さん" })
        // Base entries are per character, so both brothers have their own 星野.
        assertEquals(2, t.count { it == "星野" })
    }

    @Test
    fun honorificsOffMeansBaseEntriesOnly() {
        val off = terms(noHonorifics, haruka)
        assertFalse(off.any { it.endsWith("さん") })
        assertTrue(terms(all, haruka).size > off.size + 2000)
    }

    @Test
    fun theSameCharacterOrNameIsMerged() {
        val first = haruka.copy(description = null, aliases = listOf("はるちゃん"))
        val again = haruka.copy(role = "side", description = "Fills the gap", seiyuu = "声野 優", aliases = listOf("ハルハル", "はるちゃん"))
        val e = entries(noHonorifics, first, again)
        assertEquals(1, e.count { it.term == "森川" })
        assertTrue("ハルハル" in e.map { it.term })
        val card = e.first().card.toString()
        assertTrue("Fills the gap" in card)
        assertTrue("声野 優" in card)
        // A different ID with the same name, spaced or not, is the same person.
        val other = haruka.copy(id = "c9", nameOriginal = "森川遥香")
        assertEquals(entries(noHonorifics, haruka).size, entries(noHonorifics, haruka, other).size)
    }

    @Test
    fun theHigherRoleWinsTheTagAndScore() {
        val side = haruka.copy(role = "side")
        val e = entries(noHonorifics, side, haruka.copy(id = "c2"))
        assertTrue(e.all { it.tags == "name main" && it.score == 100 })
    }

    @Test
    fun theCastIsTakenMainFirst() {
        val extra = character("c3", "Ookubo Taiga", "大久保 大河", "appears").copy(aliases = emptyList())
        val friend = character("c2", "Kirishima Aoi", "霧島 葵", "primary").copy(aliases = emptyList())
        val odd = character("c4", "Sorano Kumo", "空野 雲", "cameo").copy(aliases = emptyList())
        val e = entries(noHonorifics, extra, odd, friend, haruka)
        assertEquals(listOf("森川 遥香", "霧島 葵", "空野 雲", "大久保 大河"), e.filter { ' ' in it.term && Kana.containsKanji(it.term) }.map { it.term })
        assertEquals(0, e.first { it.term == "空野" }.score)
        assertEquals("name cameo", e.first { it.term == "空野" }.tags)
    }

    @Test
    fun voiceActorsComeFromTheVisualNovel() {
        val withActors = vn.copy(voiceActors = mapOf("c1" to "声野 優"))
        val card = CharacterDictionary.entries(withActors, listOf(haruka.copy(seiyuu = "Someone Else")), noHonorifics).first().card.toString()
        assertTrue("声野 優" in card)
        assertFalse("Someone Else" in card)
    }

    @Test
    fun switchesChangeCardsTagsAndImagesNotEntries() {
        val portrait = Portrait(byteArrayOf(1, 2, 3), "jpg", 160, 200)
        val pictured = haruka.copy(image = portrait)
        val off = DictSettings(showImage = false, showTag = false, showDescription = false, showTraits = false, showSpoilers = false, honorifics = false, showSeiyuu = false)
        val minimal = entries(off, pictured)
        val maximal = entries(noHonorifics, pictured)
        assertEquals(maximal.map { it.term }, minimal.map { it.term })
        assertTrue(minimal.all { it.tags == "name" })
        assertFalse("img" in minimal.first().card.toString())
        assertTrue("img/cc1.jpg" in maximal.first().card.toString())
    }

    @Test
    fun theZipHasAnIndexTagsTermBanksAndPortraits() {
        val cast = listOf(
            haruka.copy(image = Portrait(byteArrayOf(-1, -40, -1, -32, 1, 2, 3), "jpg", 160, 200), description = "Treasurer of the [b]tea club[/b]. [spoiler]Secretly a cat.[/spoiler]"),
            character("c2", "Kirishima Aoi", "霧島 葵", "primary").copy(aliases = listOf("あおちゃん")),
            character("c3", "Saber", "セイバー", "side").copy(aliases = emptyList()),
        )
        val file = File.createTempFile("names", ".zip")
        try {
            val written = file.outputStream().use {
                CharacterDictionary.write("v999", vn, cast, DictSettings(), "Teatime Rooftop (VNDB v999)", "2026-11-22", it)
            }
            assertEquals(3, written.characters)
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals("index.json", names[0])
                assertEquals("tag_bank_1.json", names[1])
                assertTrue("img/cc1.jpg" in names)
                val index = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("index.json")).reader().readText()).jsonObject
                assertEquals("Teatime Rooftop (VNDB v999)", index["title"]!!.jsonPrimitive.content)
                assertEquals(3, index["format"]!!.jsonPrimitive.int)
                assertEquals("2026-11-22", index["revision"]!!.jsonPrimitive.content)
                for (key in listOf("author", "attribution", "description")) {
                    val text = index[key]!!.jsonPrimitive.content
                    assertTrue("$key credits VNDB", "VNDB" in text)
                    assertTrue("$key credits the reference builder", "Japanese_Character_Name_Dictionary" in text || "Japanese Character Name Dictionary" in text)
                }
                assertTrue("屋上ティータイム" in index["description"]!!.jsonPrimitive.content)
                val tags = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("tag_bank_1.json")).reader().readText()).jsonArray
                assertEquals(listOf("name", "main", "primary", "side", "appears"), tags.map { it.jsonArray[0].jsonPrimitive.content })

                val banks = names.filter { it.startsWith("term_bank_") }
                assertTrue(banks.size > 1)
                val read = banks.sortedBy { it.removePrefix("term_bank_").removeSuffix(".json").toInt() }.flatMap { name ->
                    val bank = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(name)).reader().readText()).jsonArray
                    assertTrue(bank.size <= CharacterDictionary.TERM_BANK_LIMIT)
                    bank.map { it.jsonArray }
                }
                assertEquals(written.entries, read.size)
                // What streamed out as bytes is what the JSON tree gives.
                assertEquals(CharacterDictionary.entries(vn, cast, DictSettings()), read)
                val san = read.first { it.term == "霧島さん" }
                assertEquals("きりしまさん", san.reading)
                assertEquals("name primary", san.tags)
                assertEquals(75, san.score)
                assertTrue("あおちゃんさん" in read.map { it.term })
                val card = read.first { it.term == "森川" }.card.toString()
                assertTrue("tea club" in card)
                assertFalse("Secretly a cat" in card)
                assertTrue("From: 屋上ティータイム" in card)
                assertTrue(read.first { it.term == "セイバーくん" }.card.toString().startsWith("{\"type\":\"structured-content\",\"content\":[{\"tag\":\"div\",\"style\""))
                val portrait = zip.getInputStream(zip.getEntry("img/cc1.jpg")).readBytes()
                assertEquals(7, portrait.size)
            }
            System.getenv("LFG_NAMES_ZIP")?.let { file.copyTo(File(it), overwrite = true) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun anEmptyCastStillMakesAZip() {
        val out = java.io.ByteArrayOutputStream()
        val written = CharacterDictionary.write("v1", vn, emptyList(), DictSettings(), "T", "r", out)
        assertEquals(0, written.entries)
        val file = File.createTempFile("names", ".zip")
        try {
            file.writeBytes(out.toByteArray())
            ZipFile(file).use { assertEquals(listOf("index.json", "tag_bank_1.json"), it.entries().toList().map { e -> e.name }) }
        } finally {
            file.delete()
        }
    }
}
