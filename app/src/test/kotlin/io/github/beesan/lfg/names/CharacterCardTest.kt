package io.github.beesan.lfg.names

import io.github.beesan.lfg.names.CharacterCard.Companion.ID_NAME
import io.github.beesan.lfg.names.CharacterCard.Companion.ID_NAME_ROMAJI
import io.github.beesan.lfg.names.CharacterCard.Companion.ID_PORTRAIT_CONTAINER
import io.github.beesan.lfg.names.CharacterCard.Companion.ID_ROLE_CONTAINER
import io.github.beesan.lfg.names.CharacterCard.Companion.ID_SOURCE
import io.github.beesan.lfg.names.CharacterCard.Companion.buildHonorificContent
import io.github.beesan.lfg.names.CharacterCard.Companion.createTermEntry
import io.github.beesan.lfg.names.CharacterCard.Companion.formatBirthday
import io.github.beesan.lfg.names.CharacterCard.Companion.parseBbcodeToStructured
import io.github.beesan.lfg.names.CharacterCard.Companion.parseVndbMarkup
import io.github.beesan.lfg.names.CharacterCard.Companion.stripSpoilers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tests of content_builder.rs that apply to a VNDB character, with a made-up one. */
class CharacterCardTest {
    private val minimal = DictSettings(showDescription = false, showTraits = false, showSpoilers = false)
    private val noSpoilers = DictSettings(showSpoilers = false)
    private val full = DictSettings(showSpoilers = true)

    private fun character() = Character(
        id = "c123",
        name = "Morikawa Haruka",
        nameOriginal = "森川 遥香",
        role = "main",
        sex = "m",
        age = "17",
        height = 165,
        weight = 50,
        bloodType = "A",
        birthday = listOf(9, 1),
        description = "The protagonist.\n[spoiler]Secret info[/spoiler]",
        aliases = listOf("はるか"),
        personality = listOf(CharacterTrait("Kind", 0), CharacterTrait("Secret trait", 2)),
        roles = listOf(CharacterTrait("Student", 0)),
    )

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun items(card: JsonObject): List<JsonElement> = card["content"]!!.jsonArray

    private fun byId(card: JsonObject, id: String): JsonObject? =
        items(card).map { it.jsonObject }.firstOrNull { (it["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.content == id }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun details(card: JsonObject) = items(card).count { text(it.jsonObject["tag"]) == "details" }

    @Test
    fun spoilersAreTakenOut() {
        assertEquals("before  after", stripSpoilers("before [spoiler]hidden[/spoiler] after"))
        assertEquals("clean text", stripSpoilers("clean text"))
        assertEquals("a  b  c", stripSpoilers("a [spoiler]x[/spoiler] b [spoiler]y[/spoiler] c"))
        assertEquals("", stripSpoilers("[spoiler]everything[/spoiler]"))
        assertEquals("", stripSpoilers("[spoiler][/spoiler]"))
        assertEquals("", stripSpoilers("[spoiler]a[/spoiler][spoiler]b[/spoiler]"))
        assertEquals("before    after", stripSpoilers("before  [spoiler]hidden[/spoiler]  after"))
        assertEquals("", stripSpoilers("[SPOILER]hidden[/SPOILER]"))
        assertEquals("", stripSpoilers("[spoiler]line1\nline2[/spoiler]"))
        assertEquals("visible", stripSpoilers("[spoiler][b]hidden bold[/b][/spoiler] visible"))
        // The shortest match is taken, so a nested spoiler leaves its tail behind.
        assertTrue(stripSpoilers("[spoiler]outer [spoiler]inner[/spoiler] still hidden[/spoiler]").contains("still hidden"))
        // AniList's ~!…!~ is not VNDB's and is left alone.
        assertEquals("before ~!kept!~ after", stripSpoilers("before ~!kept!~ after"))
    }

    @Test
    fun vndbMarkupIsReducedToText() {
        assertEquals("see this link here", parseVndbMarkup("see [url=https://example.com]this link[/url] here"))
        assertEquals("plain text", parseVndbMarkup("plain text"))
        assertEquals("text underlined here", parseVndbMarkup("text [u]underlined[/u] here"))
        assertEquals("text struck here", parseVndbMarkup("text [s]struck[/s] here"))
        assertEquals("before quoted text after", parseVndbMarkup("before [quote]quoted text[/quote] after"))
        assertEquals("see some code here", parseVndbMarkup("see [code]some code[/code] here"))
        assertEquals("text [b]not bold[/b] end", parseVndbMarkup("text [raw][b]not bold[/b][/raw] end"))
        assertEquals("link and quoted", parseVndbMarkup("[url=https://example.com]link[/url] and [quote]quoted[/quote]"))
        assertEquals("A and B", parseVndbMarkup("[url=https://a.com]A[/url] and [url=https://b.com]B[/url]"))
        assertEquals(
            "link quoted code raw under strike",
            parseVndbMarkup("[url=x]link[/url] [quote]quoted[/quote] [code]code[/code] [raw]raw[/raw] [u]under[/u] [s]strike[/s]"),
        )
        assertEquals("", parseVndbMarkup("[quote][/quote]"))
        assertEquals("", parseVndbMarkup("[code][/code]"))
    }

    @Test
    fun bbcodeBecomesSpans() {
        assertEquals(json("""{"tag":"span","style":{"fontWeight":"bold"},"content":"bold text"}"""), parseBbcodeToStructured("[b]bold text[/b]"))
        assertEquals(json("""{"tag":"span","style":{"fontStyle":"italic"},"content":"italic text"}"""), parseBbcodeToStructured("[i]italic text[/i]"))
        assertEquals(
            json("""{"tag":"span","style":{"fontWeight":"bold"},"content":["Club treasurer of ",{"tag":"span","style":{"fontStyle":"italic"},"content":"the Tea Room"}]}"""),
            parseBbcodeToStructured("[b]Club treasurer of [i]the Tea Room[/i][/b]"),
        )
        assertEquals(
            json("""[{"tag":"span","style":{"fontWeight":"bold"},"content":"Treasurer"},"  The heroine's friend."]"""),
            parseBbcodeToStructured("[b]Treasurer[/b]  The heroine's friend."),
        )
        assertEquals(JsonPrimitive("plain text"), parseBbcodeToStructured("plain text"))
        assertEquals(JsonPrimitive("[Translated from official website]"), parseBbcodeToStructured("[Translated from official website]"))
        assertEquals(JsonPrimitive("text"), parseBbcodeToStructured("[b]text[/i]"))
        assertEquals(json("""{"tag":"span","style":{"fontWeight":"bold"},"content":""}"""), parseBbcodeToStructured("[b][/b]"))
        assertEquals(JsonPrimitive("[b]no close"), parseBbcodeToStructured("[b]no close"))
        assertEquals(3, (parseBbcodeToStructured("[b]bold[/b] and [i]italic[/i]") as JsonArray).size)
        assertTrue(parseBbcodeToStructured("[b]first[/b][b]second[/b]") is JsonArray)
        assertEquals(
            json("""{"tag":"span","style":{"fontWeight":"bold"},"content":{"tag":"span","style":{"fontStyle":"italic"},"content":"bold italic"}}"""),
            parseBbcodeToStructured("[b][i]bold italic[/i][/b]"),
        )
        assertEquals(json("""{"tag":"span","style":{"fontWeight":"bold"},"content":"<>&\"'"}"""), parseBbcodeToStructured("[b]<>&\"'[/b]"))
    }

    @Test
    fun birthdays() {
        assertEquals("September 1", formatBirthday(listOf(9, 1)))
        assertEquals("January 15", formatBirthday(listOf(1, 15)))
        assertEquals("December 25", formatBirthday(listOf(12, 25)))
        assertEquals("", formatBirthday(listOf(9)))
        assertEquals("", formatBirthday(emptyList()))
        assertEquals("Unknown 1", formatBirthday(listOf(13, 1)))
        assertEquals("Unknown 15", formatBirthday(listOf(0, 15)))
        assertEquals("January 0", formatBirthday(listOf(1, 0)))
        assertEquals("March 14", formatBirthday(listOf(3, 14, 1999)))
        val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        for ((i, name) in months.withIndex()) assertEquals("$name 15", formatBirthday(listOf(i + 1, 15)))
    }

    @Test
    fun stats() {
        val card = CharacterCard(full)
        val stats = card.formatStats(character())
        for (part in listOf("♂ Male", "17 years", "165cm", "50kg", "Blood Type A", "Birthday: September 1")) assertTrue(part, part in stats)
        assertEquals("♂ Male • 17 years • 165cm • 50kg • Blood Type A • Birthday: September 1", stats)
        val bare = character().copy(sex = null, age = null, height = null, weight = null, bloodType = null, birthday = null)
        assertEquals("", card.formatStats(bare))
        assertEquals("", card.formatStats(bare.copy(sex = "X")))
        assertEquals("0cm • 0kg", card.formatStats(bare.copy(height = 0, weight = 0)))
        assertTrue("Female" in card.formatStats(bare.copy(sex = "f")))
        assertTrue("Female" in card.formatStats(bare.copy(sex = "female")))
        assertTrue("Female" in card.formatStats(bare.copy(sex = "F")))
        assertEquals("♂ Male • 17 years", card.formatStats(character().copy(height = null, weight = null, bloodType = null, birthday = null)))
    }

    @Test
    fun traitsHideSpoilersUnlessAsked() {
        val hidden = CharacterCard(noSpoilers).buildTraitsByCategory(character()).joinToString(" ") { text(it.jsonObject["content"])!! }
        assertTrue("Kind" in hidden)
        assertTrue("Student" in hidden)
        assertFalse("Secret trait" in hidden)
        val shown = CharacterCard(full).buildTraitsByCategory(character()).joinToString(" ") { text(it.jsonObject["content"])!! }
        assertTrue("Secret trait" in shown)
        assertEquals("Personality: Kind, Secret trait Role: Student", shown)
        val minor = character().copy(personality = listOf(CharacterTrait("Minor", 1)), roles = emptyList())
        assertTrue(CharacterCard(noSpoilers).buildTraitsByCategory(minor).isEmpty())
        val blankName = character().copy(personality = listOf(CharacterTrait("", 0), CharacterTrait("Kind", 0)), roles = emptyList())
        assertEquals("Personality: Kind", text(CharacterCard(full).buildTraitsByCategory(blankName).single().jsonObject["content"]))
    }

    @Test
    fun minimalCardHasNameSourceAndRole() {
        val card = CharacterCard(minimal).buildContent(character(), null, null, "Test Game")
        assertEquals("structured-content", text(card["type"]))
        assertEquals(0, details(card))
        assertEquals("森川 遥香", text(byId(card, ID_NAME)!!["content"]))
        assertEquals("Morikawa Haruka", text(byId(card, ID_NAME_ROMAJI)!!["content"]))
        assertEquals("Protagonist", text(byId(card, ID_ROLE_CONTAINER)!!["content"]!!.jsonObject["content"]))
        assertEquals("From: Test Game", text(byId(card, ID_SOURCE)!!["content"]))
        assertNull(byId(card, ID_PORTRAIT_CONTAINER))
        assertEquals(4, items(card).size)
    }

    @Test
    fun descriptionAndTraitsFollowTheirSwitches() {
        assertEquals(2, details(CharacterCard(noSpoilers).buildContent(character(), null, null, "T")))
        assertEquals(2, details(CharacterCard(full).buildContent(character(), null, null, "T")))
        val described = character().copy(description = "A detailed description")
        assertFalse("A detailed description" in CharacterCard(minimal).buildContent(described, null, null, "T").toString())
        assertTrue("A detailed description" in CharacterCard(noSpoilers).buildContent(described, null, null, "T").toString())
        val noTraits = CharacterCard(DictSettings(showTraits = false)).buildContent(character(), null, null, "T").toString()
        assertFalse("Character Information" in noTraits)
        assertTrue("Description" in noTraits)
        val noDescription = CharacterCard(DictSettings(showDescription = false)).buildContent(character(), null, null, "T").toString()
        assertFalse("\"Description\"" in noDescription)
        assertTrue("Character Information" in noDescription)
    }

    @Test
    fun spoilersOnlyChangeTheDescription() {
        assertTrue("Secret info" in CharacterCard(full).buildContent(character(), null, null, "T").toString())
        assertFalse("Secret info" in CharacterCard(noSpoilers).buildContent(character(), null, null, "T").toString())
        val allSpoiler = character().copy(description = "[spoiler]everything is hidden[/spoiler]")
        val card = CharacterCard(noSpoilers).buildContent(allSpoiler, null, null, "T")
        assertFalse("\"Description\"" in card.toString())
    }

    @Test
    fun portraitIsScaledToAHundredPixelsHigh() {
        val card = CharacterCard(minimal).buildContent(character(), "img/c1.jpg", 100 to 150, "Test")
        val img = byId(card, ID_PORTRAIT_CONTAINER)!!["content"]!!.jsonObject
        assertEquals("img", text(img["tag"]))
        assertEquals("img/c1.jpg", text(img["path"]))
        assertEquals(67, img["width"]!!.jsonPrimitive.int)
        assertEquals(100, img["height"]!!.jsonPrimitive.int)
        val wide = byId(CharacterCard(minimal).buildContent(character(), "img/c1.jpg", 160 to 200, "T"), ID_PORTRAIT_CONTAINER)!!["content"]!!.jsonObject
        assertEquals(80, wide["width"]!!.jsonPrimitive.int)
        val zero = byId(CharacterCard(minimal).buildContent(character(), "img/c1.jpg", 0 to 0, "T"), ID_PORTRAIT_CONTAINER)!!["content"]!!.jsonObject
        assertEquals(67, zero["width"]!!.jsonPrimitive.int)
        val unknown = byId(CharacterCard(minimal).buildContent(character(), "img/c1.jpg", null, "T"), ID_PORTRAIT_CONTAINER)!!["content"]!!.jsonObject
        assertEquals(100, unknown["height"]!!.jsonPrimitive.int)
        assertNull(byId(CharacterCard(DictSettings(showImage = false)).buildContent(character(), "img/c1.jpg", 100 to 150, "T"), ID_PORTRAIT_CONTAINER))
    }

    @Test
    fun roleBadges() {
        val expected = mapOf(
            "main" to ("Protagonist" to "#4CAF50"),
            "primary" to ("Main Character" to "#2196F3"),
            "side" to ("Side Character" to "#FF9800"),
            "appears" to ("Minor Role" to "#9E9E9E"),
            "custom_role" to ("Unknown" to "#9E9E9E"),
        )
        for ((role, labelColour) in expected) {
            val badge = byId(CharacterCard(minimal).buildContent(character().copy(role = role), null, null, "T"), ID_ROLE_CONTAINER)!!["content"]!!.jsonObject
            assertEquals(labelColour.first, text(badge["content"]))
            assertEquals(labelColour.second, text(badge["style"]!!.jsonObject["background"]))
        }
        assertNull(byId(CharacterCard(DictSettings(showTag = false)).buildContent(character(), null, null, "T"), ID_ROLE_CONTAINER))
    }

    @Test
    fun emptyTitleAndNamesAreLeftOut() {
        assertFalse("From: " in CharacterCard(minimal).buildContent(character(), null, null, "").toString())
        val nameless = CharacterCard(DictSettings(showImage = false, showTag = false, showDescription = false, showTraits = false, showSeiyuu = false))
            .buildContent(character().copy(name = "", nameOriginal = ""), null, null, "")
        assertEquals(0, items(nameless).size)
    }

    @Test
    fun voiceActor() {
        val card = CharacterCard(full).buildContent(character().copy(seiyuu = "声野 優"), null, null, "T").toString()
        assertTrue("Voiced by" in card)
        assertTrue("声野 優" in card)
        assertFalse("Voiced by" in CharacterCard(DictSettings(showSeiyuu = false)).buildContent(character().copy(seiyuu = "声野 優"), null, null, "T").toString())
    }

    @Test
    fun termEntries() {
        val sc = json("""{"type":"structured-content","content":[]}""")
        val entry = createTermEntry("森川", "もりかわ", "main", 100, sc)
        assertEquals(8, entry.size)
        assertEquals("森川", text(entry[0]))
        assertEquals("もりかわ", text(entry[1]))
        assertEquals("name main", text(entry[2]))
        assertEquals("", text(entry[3]))
        assertEquals(100, entry[4].jsonPrimitive.int)
        assertEquals(JsonArray(listOf(sc)), entry[5])
        assertEquals(0, entry[6].jsonPrimitive.int)
        assertEquals("", text(entry[7]))
        assertEquals("name", text(createTermEntry("test", "test", "", 50, sc)[2]))
    }

    @Test
    fun honorificCardsStartWithTheBanner() {
        val base = json("""{"type":"structured-content","content":[{"tag":"div","content":"first"},{"tag":"div","content":"second"}]}""")
        val result = buildHonorificContent(base, "さん", "Generic polite suffix (Mr./Ms./Mrs.)").jsonObject
        val items = result["content"]!!.jsonArray
        assertEquals(3, items.size)
        assertEquals("div", text(items[0].jsonObject["tag"]))
        val banner = items[0].jsonObject["content"]!!.jsonArray
        assertEquals("さん", text(banner[0].jsonObject["content"]))
        assertEquals(" — Generic polite suffix (Mr./Ms./Mrs.)", text(banner[1].jsonObject["content"]))
        assertEquals("first", text(items[1].jsonObject["content"]))
        assertEquals("second", text(items[2].jsonObject["content"]))
        val bare = json("""{"type":"structured-content"}""")
        assertEquals(bare, buildHonorificContent(bare, "さん", "test"))
        assertNotNull(result["type"])
    }
}
