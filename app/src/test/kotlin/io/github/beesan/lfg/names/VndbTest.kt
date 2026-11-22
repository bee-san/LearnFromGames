// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// Every record here is made up; nothing is copied from VNDB.
class VndbTest {
    /** Answers each request with the next canned reply, and remembers what was asked. */
    private class FakeTransport(vararg replies: VndbReply) : VndbTransport {
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<Pair<String, String>>()

        override fun post(path: String, body: String): VndbReply {
            requests += path to body
            return queue.removeFirst()
        }
    }

    private val sleeps = mutableListOf<Long>()
    private fun vndb(t: VndbTransport) = Vndb(t) { sleeps += it }
    private fun ok(body: String) = VndbReply(200, body)

    @Test
    fun steamLookupAsksReleasesAndReturnsDistinctVns() {
        val t = FakeTransport(ok("""{"more":false,"results":[{"id":"r1","vns":[{"id":"v900"}]},{"id":"r2","vns":[{"id":"v900"},{"id":"v901"}]}]}"""))
        assertEquals(listOf("v900", "v901"), vndb(t).vnsForSteam(123456))
        val (path, body) = t.requests.single()
        assertEquals("/release", path)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(Json.parseToJsonElement("""["extlink","=",["steam",123456]]"""), json["filters"])
        assertEquals("vns.id", json["fields"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchCombinesFiltersAndParsesResults() {
        val t = FakeTransport(
            ok(
                """{"more":false,"results":[{"id":"v900","title":"Lantern Road","alttitle":"灯籠の道","aliases":["Toro"],
                "titles":[{"title":"灯籠の道","latin":"Tourou no Michi","lang":"ja","official":true,"main":true}],
                "released":"2019-04-01","platforms":["win","swi"],"languages":["ja","en"],"olang":"ja","votecount":12},
                {"id":"","title":"No id"},{"id":"v902","title":null,"alttitle":null}]}""",
            ),
        )
        val results = vndb(t).search("Lantern Road", platform = "swi", japaneseOnly = true)
        val filters = Json.parseToJsonElement(t.requests.single().second).jsonObject["filters"]
        assertEquals(
            Json.parseToJsonElement("""["and",["search","=","Lantern Road"],["platform","=","swi"],["lang","=","ja"]]"""),
            filters,
        )
        val vn = results.single()
        assertEquals("v900", vn.id)
        assertEquals(2019, vn.year)
        assertEquals(listOf("win", "swi"), vn.platforms)
        assertEquals("Tourou no Michi", vn.titles.single().latin)
        assertEquals(12, vn.votecount)
    }

    @Test
    fun plainSearchSendsOneFilter() {
        val t = FakeTransport(ok("""{"results":[]}"""))
        vndb(t).search("x")
        assertEquals(Json.parseToJsonElement("""["search","=","x"]"""), Json.parseToJsonElement(t.requests.single().second).jsonObject["filters"])
    }

    @Test
    fun retriesOn429ThenSucceeds() {
        val t = FakeTransport(VndbReply(429, ""), VndbReply(429, "", retryAfterSeconds = 3), ok("""{"results":[]}"""))
        assertEquals(emptyList<String>(), vndb(t).vnsForSteam(1))
        assertEquals(3, t.requests.size)
        assertEquals(listOf(1000L, 3000L), sleeps)
    }

    @Test
    fun givesUpAfterThreeRetries() {
        val t = FakeTransport(*Array(4) { VndbReply(429, "") })
        val e = assertThrows(VndbException::class.java) { vndb(t).vnsForSteam(1) }
        assertEquals(4, t.requests.size)
        assertEquals(listOf(1000L, 2000L, 4000L), sleeps)
        assertTrue(e.message!!.contains("busy"))
    }

    @Test
    fun otherErrorsAreOneException() {
        assertThrows(VndbException::class.java) { vndb(FakeTransport(VndbReply(500, "oops"))).searchVns("x") }
        assertThrows(VndbException::class.java) { vndb(FakeTransport(ok("not json"))).searchVns("x") }
        assertThrows(VndbException::class.java) { vndb { _, _ -> throw IOException("reset") }.searchVns("x") }
        assertThrows(VndbException::class.java) { vndb(FakeTransport(ok("""{"results":[]}"""))).fetchVnInfo("v1") }
    }

    @Test
    fun vnInfoKeepsFirstVoiceActorAndPrefersJapaneseName() {
        val t = FakeTransport(
            ok(
                """{"results":[{"id":"v900","title":"Lantern Road","alttitle":"灯籠の道","va":[
                {"staff":{"id":"s1","name":"Aoi Hoshino","original":"星野葵"},"character":{"id":"c1"}},
                {"staff":{"id":"s2","name":"Other Person","original":"別人"},"character":{"id":"c1"}},
                {"staff":{"id":"s3","name":"Romaji Only","original":null},"character":{"id":"c2"}}]}]}""",
            ),
        )
        val info = vndb(t).fetchVnInfo("900")
        assertEquals(VnInfo("Lantern Road", "灯籠の道", mapOf("c1" to "星野葵", "c2" to "Romaji Only")), info)
        assertEquals(Json.parseToJsonElement("""["id","=","v900"]"""), Json.parseToJsonElement(t.requests.single().second).jsonObject["filters"])
    }

    @Test
    fun charactersArePagedGroupedAndGivenSeiyuu() {
        val t = FakeTransport(
            ok("""{"more":true,"results":[{"id":"c3","name":"Side One","original":"脇一","vns":[{"id":"v900","role":"side"}]},
                {"id":"c1","name":"Main One","original":"主一","vns":[{"id":"v900","role":"main"}]}]}"""),
            ok("""{"more":false,"results":[{"id":"c2","name":"Odd Role","original":"変","vns":[{"id":"v900","role":"weird"}]},
                {"id":"c9","name":"No vns"}]}"""),
        )
        val cast = vndb(t).fetchCharacters("v900", mapOf("c1" to "星野葵"))
        assertEquals(listOf("c1", "c3", "c2"), cast.map { it.id })
        assertEquals("星野葵", cast[0].seiyuu)
        assertEquals(listOf(200L), sleeps)
        val first = Json.parseToJsonElement(t.requests[0].second).jsonObject
        assertEquals(Vndb.CHARACTER_FIELDS, first["fields"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""["vn","=",["id","=","v900"]]"""), first["filters"])
        assertEquals("2", Json.parseToJsonElement(t.requests[1].second).jsonObject["page"]!!.jsonPrimitive.content)
    }

    @Test
    fun characterFieldListMatchesTheReferenceBuilder() {
        assertEquals(
            "id,name,original,image.url,sex,birthday,age,blood_type,height,weight,description,aliases,vns.role,vns.id,traits.name,traits.group_name,traits.spoiler",
            Vndb.CHARACTER_FIELDS,
        )
    }

    @Test
    fun processCharacterReadsEveryField() {
        val raw = Json.parseToJsonElement(
            """{"id":"c1","name":"Aki Mizuhara","original":"Mizuhara Aki","image":{"url":"https://example.invalid/c1.jpg"},
            "sex":["f","m"],"birthday":[4,12],"age":17,"blood_type":"ab","height":158,"weight":null,
            "description":"Likes [spoiler]secrets[/spoiler].","aliases":["Akki","","水原アキ"],
            "vns":[{"id":"v1","role":"side"},{"id":"v900","role":"primary"}],
            "traits":[{"name":"Kind","group_name":"Personality","spoiler":0},{"name":"Student","group_name":"Role","spoiler":1},
            {"name":"Archery","group_name":"Engages in","spoiler":0},{"name":"Rumours","group_name":"Subject of","spoiler":2},
            {"name":"Black","group_name":"Hair","spoiler":0},{"name":"","group_name":"Personality","spoiler":0}]}""",
        ).jsonObject
        val c = Vndb.processCharacter(raw, "v900", mapOf("c1" to "星野葵"))!!
        assertEquals("c1", c.id)
        assertEquals("Aki Mizuhara", c.name)
        assertEquals("水原アキ", c.nameOriginal) // a romanised original gives way to the Japanese alias
        assertEquals("primary", c.role)
        assertEquals("f", c.sex)
        assertEquals("17", c.age)
        assertEquals(158, c.height)
        assertNull(c.weight)
        assertEquals("ab", c.bloodType)
        assertEquals(listOf(4, 12), c.birthday)
        assertEquals("Likes [spoiler]secrets[/spoiler].", c.description)
        assertEquals(listOf("Akki", "水原アキ"), c.aliases)
        assertEquals(listOf(CharacterTrait("Kind", 0)), c.personality)
        assertEquals(listOf(CharacterTrait("Student", 1)), c.roles)
        assertEquals(listOf(CharacterTrait("Archery", 0)), c.engagesIn)
        assertEquals(listOf(CharacterTrait("Rumours", 2)), c.subjectOf)
        assertEquals("https://example.invalid/c1.jpg", c.imageUrl)
        assertEquals("星野葵", c.seiyuu)
    }

    @Test
    fun processCharacterDefaults() {
        val raw = Json.parseToJsonElement("""{"id":"c5","name":"Plain","original":"平","vns":[{"id":"v1","role":"main"}],"birthday":[4]}""").jsonObject
        val c = Vndb.processCharacter(raw, "v900")!!
        assertEquals("side", c.role)
        assertEquals("平", c.nameOriginal)
        assertNull(c.birthday)
        assertNull(c.seiyuu)
        assertNull(c.sex)
        assertNull(Vndb.processCharacter(Json.parseToJsonElement("""{"id":"c6"}""").jsonObject, "v900"))
    }

    @Test
    fun containsJapaneseCountsSupplementaryKanji() {
        assertTrue(Vndb.containsJapanese("𠀋")) // U+2000B, Extension B
        assertTrue(Vndb.containsJapanese("々"))
        assertEquals(false, Vndb.containsJapanese("Aki"))
    }

    @Test
    fun parseVnIdForms() {
        assertEquals("v17", Vndb.parseVnId("v17"))
        assertEquals("v17", Vndb.parseVnId(" V17 "))
        assertEquals("v17", Vndb.parseVnId("17"))
        assertEquals("v17", Vndb.parseVnId("https://vndb.org/v17"))
        assertEquals("v17", Vndb.parseVnId("https://vndb.org/v17/chars#main"))
        assertEquals("v17", Vndb.parseVnId("vndb.org/v17?x=1"))
        for (bad in listOf("", "abc", "v", "v17x", "https://vndb.org/c17", "https://vndb.org/")) {
            assertThrows(bad, VndbException::class.java) { Vndb.parseVnId(bad) }
        }
        assertEquals("v17", Vndb.normalizeId("17"))
        assertEquals("v17", Vndb.normalizeId("V17"))
    }

    @Test
    fun searchVnsKeepsTheReferenceRequest() {
        val t = FakeTransport(ok("""{"results":[{"id":"v900","title":"","alttitle":"灯籠の道","released":"TBA","votecount":3}]}"""))
        val r = vndb(t).searchVns(" lantern ", limit = 50).single()
        assertEquals("灯籠の道", r.title)
        assertNull(r.year)
        val json = Json.parseToJsonElement(t.requests.single().second).jsonObject
        assertEquals("8", json["results"]!!.jsonPrimitive.content)
        assertEquals("id,title,alttitle,released,votecount", json["fields"]!!.jsonPrimitive.content)
        assertEquals("lantern", json["filters"]!!.jsonArray[2].jsonPrimitive.content)
    }
}
