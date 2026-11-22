// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import io.github.beesan.lfg.core.downloadProblem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * VNDB's API, ported from vndb_client.rs in bee-san/Japanese_Character_Name_Dictionary
 * (yomitan-dict-builder), the reference builder. User lists are left out. The
 * release lookup by Steam app ID and the search with platform and language
 * filters are new. Everything here blocks: call it off the main thread, and
 * only when the Wi-Fi setting allows.
 */

/** What VNDB answered: the HTTP status, the body, and Retry-After in seconds when it sent one. */
data class VndbReply(val status: Int, val body: String, val retryAfterSeconds: Long? = null)

/** Sends one POST to VNDB's API. [path] is the endpoint, such as "/vn"; [body] is the JSON request. Throws IOException when the network fails. */
fun interface VndbTransport {
    fun post(path: String, body: String): VndbReply
}

/** The one error VNDB calls raise, with a message that can be shown as it is. */
class VndbException(message: String) : Exception(message)

/** One of a visual novel's titles, as VNDB's `titles` field: [latin] is the romanised form when [title] is not in Latin script. */
data class VnTitle(val title: String, val latin: String?, val lang: String, val official: Boolean, val main: Boolean)

/**
 * A visual novel found by a search. [platforms] and [languages] cover all its
 * releases together, so "ja" in [languages] means it has a Japanese release.
 * Fields the search did not ask for are left empty.
 */
data class VnResult(
    val id: String,
    val title: String,
    val alttitle: String? = null,
    val aliases: List<String> = emptyList(),
    val titles: List<VnTitle> = emptyList(),
    val year: Int? = null,
    val platforms: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val olang: String? = null,
    val votecount: Int? = null,
) {
    val url get() = "https://vndb.org/$id"
}

/** Talks to VNDB through [transport]. [sleep] waits between pages and before retries; tests pass one that returns at once. */
class Vndb(
    private val transport: VndbTransport = HttpVndbTransport(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** The visual novel's titles and each character's first-credited voice actor, Japanese name preferred. */
    fun fetchVnInfo(vnId: String): VnInfo {
        val id = normalizeId(vnId)
        val payload = buildJsonObject {
            putFilter("id", id)
            put("fields", "title, alttitle, va.staff.id, va.staff.name, va.staff.original, va.character.id")
        }
        val results = results(request("/vn", payload), "VNDB's answer about the visual novel had no results.")
        val vn = results.firstOrNull() as? JsonObject ?: throw VndbException("VNDB has no visual novel $id.")
        val voiceActors = LinkedHashMap<String, String>()
        for (entry in vn.array("va")) {
            val o = entry as? JsonObject ?: continue
            val characterId = o.obj("character")?.str("id").orEmpty()
            val staff = o.obj("staff")
            val display = staff?.str("original")?.takeIf { it.isNotEmpty() } ?: staff?.str("name").orEmpty()
            // The first voice actor wins when a character has several.
            if (characterId.isNotEmpty() && display.isNotEmpty()) voiceActors.putIfAbsent(characterId, display)
        }
        return VnInfo(vn.str("title").orEmpty(), vn.str("alttitle").orEmpty(), voiceActors)
    }

    /**
     * Every character in the visual novel, a page of 100 at a time with 200 ms
     * between pages. They come back grouped as the reference builder's
     * CharacterData holds them: main, then primary, side and appears, each in
     * VNDB's order; an unknown role goes with side. [voiceActors] fills
     * [Character.seiyuu].
     */
    fun fetchCharacters(vnId: String, voiceActors: Map<String, String> = emptyMap()): List<Character> {
        val id = normalizeId(vnId)
        val groups = linkedMapOf<String, MutableList<Character>>(
            "main" to mutableListOf(), "primary" to mutableListOf(), "side" to mutableListOf(), "appears" to mutableListOf(),
        )
        var page = 1
        while (true) {
            val payload = buildJsonObject {
                put("filters", buildJsonArray { add("vn"); add("="); addJsonArray { add("id"); add("="); add(id) } })
                put("fields", CHARACTER_FIELDS)
                put("results", 100)
                put("page", page)
            }
            val data = request("/character", payload)
            for (item in results(data, "VNDB's list of characters could not be read.")) {
                val character = processCharacter(item as? JsonObject ?: continue, id, voiceActors) ?: continue
                (groups[character.role] ?: groups.getValue("side")).add(character)
            }
            if ((data["more"] as? JsonPrimitive)?.booleanOrNull != true) break
            page++
            sleep(PAGE_GAP_MS)
        }
        return groups.values.flatten()
    }

    /** Searches visual novels by title, as search_vns does for the site's autocomplete: at most 8, best first. */
    fun searchVns(query: String, limit: Int = 8): List<VnResult> {
        val payload = buildJsonObject {
            putFilter("search", query.trim())
            put("fields", "id,title,alttitle,released,votecount")
            put("sort", "searchrank")
            put("results", limit.coerceIn(1, 8))
        }
        return parseResults(request("/vn", payload))
    }

    /**
     * Searches visual novels by title with what the matcher and a picker need,
     * best first. [platform] is a VNDB platform code such as "swi"; with
     * [japaneseOnly] only visual novels with a Japanese release come back.
     */
    fun search(query: String, platform: String? = null, japaneseOnly: Boolean = false, limit: Int = 10): List<VnResult> {
        val filters = buildList {
            add(filter("search", query.trim()))
            if (platform != null) add(filter("platform", platform))
            if (japaneseOnly) add(filter("lang", "ja"))
        }
        val payload = buildJsonObject {
            put("filters", if (filters.size == 1) filters[0] else JsonArray(listOf(JsonPrimitive("and")) + filters))
            put("fields", SEARCH_FIELDS)
            put("sort", "searchrank")
            put("results", limit.coerceIn(1, 100))
        }
        return parseResults(request("/vn", payload))
    }

    /** The visual novels with a release linked to this Steam app, each once, in VNDB's order. */
    fun vnsForSteam(appId: Long): List<String> {
        val payload = buildJsonObject {
            put("filters", buildJsonArray { add("extlink"); add("="); addJsonArray { add("steam"); add(appId) } })
            put("fields", "vns.id")
            put("results", 100)
        }
        return results(request("/release", payload), "VNDB's list of releases could not be read.")
            .flatMap { (it as? JsonObject)?.array("vns").orEmpty() }
            .mapNotNull { (it as? JsonObject)?.str("id")?.takeIf(String::isNotEmpty) }
            .distinct()
    }

    /** Sends the request, waiting and retrying up to three times while VNDB says it is busy (HTTP 429). */
    private fun request(path: String, payload: JsonObject): JsonObject {
        val body = payload.toString()
        var delayMs = 1000L
        for (attempt in 0..MAX_RETRIES) {
            val reply = try {
                transport.post(path, body)
            } catch (e: IOException) {
                throw VndbException(downloadProblem(e, "Couldn't reach VNDB."))
            }
            if (reply.status == 429) {
                if (attempt == MAX_RETRIES) break
                val wait = reply.retryAfterSeconds?.let { it.coerceIn(0, 10) * 1000 } ?: delayMs
                sleep(wait)
                delayMs *= 2
                continue
            }
            if (reply.status != 200) throw VndbException("VNDB answered with an error (HTTP ${reply.status}). Try again later.")
            return try {
                Json.parseToJsonElement(reply.body) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw VndbException("VNDB's answer could not be read.")
        }
        throw VndbException("VNDB is busy. Try again in a minute.")
    }

    companion object {
        const val API = "https://api.vndb.org/kana"
        private const val MAX_RETRIES = 3
        private const val PAGE_GAP_MS = 200L

        /** The reference builder's field list, kept identical so both builders see the same data. */
        const val CHARACTER_FIELDS =
            "id,name,original,image.url,sex,birthday,age,blood_type,height,weight,description,aliases,vns.role,vns.id,traits.name,traits.group_name,traits.spoiler"

        const val SEARCH_FIELDS =
            "id,title,alttitle,aliases,titles.title,titles.latin,titles.lang,titles.official,titles.main,released,platforms,languages,olang,votecount"

        /** "17", "v17" and "V17" all become "v17". */
        fun normalizeId(id: String): String {
            val t = id.trim()
            return if (t.startsWith("v", ignoreCase = true)) "v${t.substring(1)}" else "v$t"
        }

        /** A VN ID from "17", "v17" or a vndb.org address such as "https://vndb.org/v17/chars", as "v17". Throws [VndbException] otherwise. */
        fun parseVnId(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) throw VndbException("Enter a VNDB ID, such as v17.")
            fun vNumber(s: String) = s.length > 1 && s[0].lowercaseChar() == 'v' && s.substring(1).all { it in '0'..'9' }
            if (t.contains("vndb.org/")) {
                val segment = t.substring(t.lastIndexOf("vndb.org/") + "vndb.org/".length)
                    .split('/', '?', '#').first().trim()
                if (vNumber(segment)) return "v${segment.substring(1)}"
                throw VndbException("That vndb.org address is not a visual novel's page.")
            }
            if (vNumber(t)) return "v${t.substring(1)}"
            if (t.all { it in '0'..'9' }) return "v$t"
            throw VndbException("\"$t\" is not a VNDB ID. Use a number, an ID such as v17, or a vndb.org address.")
        }

        /** One raw VNDB character made a [Character], with its role in [targetVn]. Null when it has no `vns` list. */
        fun processCharacter(data: JsonObject, targetVn: String, voiceActors: Map<String, String> = emptyMap()): Character? {
            val vns = data["vns"] as? JsonArray ?: return null
            val role = vns.firstNotNullOfOrNull { v -> (v as? JsonObject)?.takeIf { it.str("id") == targetVn } }
                ?.str("role") ?: "side"
            val sex = (data["sex"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

            val personality = mutableListOf<CharacterTrait>()
            val roles = mutableListOf<CharacterTrait>()
            val engagesIn = mutableListOf<CharacterTrait>()
            val subjectOf = mutableListOf<CharacterTrait>()
            for (t in data.array("traits")) {
                val o = t as? JsonObject ?: continue
                val name = o.str("name").orEmpty()
                if (name.isEmpty()) continue
                val trait = CharacterTrait(name, o.unsigned("spoiler")?.toInt() ?: 0)
                when (o.str("group_name")) {
                    "Personality" -> personality += trait
                    "Role" -> roles += trait
                    "Engages in" -> engagesIn += trait
                    "Subject of" -> subjectOf += trait
                }
            }

            val birthday = (data["birthday"] as? JsonArray)?.takeIf { it.size >= 2 }?.let { arr ->
                val month = (arr[0] as? JsonPrimitive)?.let(::unsignedOf)
                val day = (arr[1] as? JsonPrimitive)?.let(::unsignedOf)
                if (month != null && day != null) listOf(month.toInt(), day.toInt()) else null
            }
            val aliases = data.array("aliases").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .filter { it.isNotEmpty() }

            // When the original name is empty or romanised, a Japanese alias stands in for it.
            var nameOriginal = data.str("original").orEmpty()
            if (!containsJapanese(nameOriginal)) aliases.firstOrNull(::containsJapanese)?.let { nameOriginal = it }

            val id = data.str("id").orEmpty()
            return Character(
                id = id,
                name = data.str("name").orEmpty(),
                nameOriginal = nameOriginal,
                role = role,
                sex = sex,
                age = data.unsigned("age")?.toString(),
                height = data.unsigned("height")?.toInt(),
                weight = data.unsigned("weight")?.toInt(),
                bloodType = data.str("blood_type"),
                birthday = birthday,
                description = data.str("description"),
                aliases = aliases,
                personality = personality,
                roles = roles,
                engagesIn = engagesIn,
                subjectOf = subjectOf,
                imageUrl = data.obj("image")?.str("url"),
                seiyuu = voiceActors[id],
            )
        }

        /** Search results made [VnResult]s, skipping any without an ID or without both titles, as parse_vn_search_results does. */
        internal fun parseResults(data: JsonObject): List<VnResult> =
            results(data, "VNDB's search results could not be read.").mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o.str("id")?.trim().orEmpty()
                if (id.isEmpty()) return@mapNotNull null
                val title = o.str("title")?.trim()?.takeIf { it.isNotEmpty() }
                val alttitle = o.str("alttitle")?.trim()?.takeIf { it.isNotEmpty() }
                if (title == null && alttitle == null) return@mapNotNull null
                VnResult(
                    id = id,
                    title = title ?: alttitle!!,
                    alttitle = alttitle,
                    aliases = o.strings("aliases"),
                    titles = o.array("titles").mapNotNull { t ->
                        val to = t as? JsonObject ?: return@mapNotNull null
                        val text = to.str("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        VnTitle(
                            text, to.str("latin")?.takeIf { it.isNotEmpty() }, to.str("lang").orEmpty(),
                            (to["official"] as? JsonPrimitive)?.booleanOrNull == true, (to["main"] as? JsonPrimitive)?.booleanOrNull == true,
                        )
                    },
                    year = o.str("released")?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()?.takeIf { it > 0 },
                    platforms = o.strings("platforms"),
                    languages = o.strings("languages"),
                    olang = o.str("olang"),
                    votecount = (o["votecount"] as? JsonPrimitive)?.intOrNull,
                )
            }

        private fun results(data: JsonObject, otherwise: String): JsonArray =
            data["results"] as? JsonArray ?: throw VndbException(otherwise)

        private fun filter(name: String, value: String): JsonArray = buildJsonArray { add(name); add("="); add(value) }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putFilter(name: String, value: String) {
            put("filters", filter(name, value))
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
        private fun JsonObject.strings(key: String): List<String> =
            array(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        private fun JsonObject.unsigned(key: String): Long? = (this[key] as? JsonPrimitive)?.let(::unsignedOf)

        /** serde's as_u64: a non-negative whole number, and nothing else. */
        private fun unsignedOf(p: JsonPrimitive): Long? =
            if (p is JsonNull || p.isString) null else p.longOrNull?.takeIf { it >= 0 }

        internal fun containsJapanese(text: String): Boolean = Kana.containsJapanese(text)
    }
}

/** The real network: HttpURLConnection to [Vndb.API], with the timeouts the dictionary downloads use. */
class HttpVndbTransport(private val base: String = Vndb.API) : VndbTransport {
    override fun post(path: String, body: String): VndbReply {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LearnFromGames")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return VndbReply(status, text, connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        } finally {
            connection.disconnect()
        }
    }
}
