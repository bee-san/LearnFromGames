// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * A profile as it is kept: its [id], the quick settings tile that is its own
 * ([slot], from 1 to [ProfileRules.MAX]), and the [name] it was given, empty
 * while it goes by its language's.
 */
data class KeptProfile(val id: String, val slot: Int, val name: String = "")

/** What is true of profiles whatever holds them: how they are named, numbered and kept. No Android in it, so tests can run it. */
object ProfileRules {
    /** As many profiles as there are quick settings tiles for them. */
    const val MAX = 6
    const val NAME_LENGTH = 24

    /** The one profile there is before any other is made. */
    val FIRST = KeptProfile("1", 1)

    /**
     * What each profile is called, given its name and its language's: the name
     * it was given, else its language, numbered from the second that shares it
     * ("Spanish", "Spanish 2").
     */
    fun shown(profiles: List<Pair<String, String>>): List<String> {
        val met = HashMap<String, Int>()
        return profiles.map { (name, language) ->
            if (name.isNotBlank()) return@map name
            val count = (met[language] ?: 0) + 1
            met[language] = count
            if (count == 1) language else "$language $count"
        }
    }

    /** [name] as a profile keeps it: trimmed, and no longer than fits a tile. */
    fun named(name: String): String = name.trim().take(NAME_LENGTH).trim()

    /** One more profile after [profiles], on the first tile free; null when every tile is taken. */
    fun added(profiles: List<KeptProfile>): KeptProfile? {
        val slot = (1..MAX).firstOrNull { slot -> profiles.none { it.slot == slot } } ?: return null
        val id = (profiles.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
        return KeptProfile(id.toString(), slot)
    }

    /** The profile after [id], round to the first again; null when there is no other. */
    fun after(profiles: List<KeptProfile>, id: String): KeptProfile? {
        if (profiles.size < 2) return null
        val at = profiles.indexOfFirst { it.id == id }
        return profiles[(at + 1) % profiles.size]
    }

    fun encode(profiles: List<KeptProfile>): String = buildJsonArray {
        for (p in profiles) addJsonObject {
            put("id", p.id)
            put("slot", p.slot)
            put("name", p.name)
        }
    }.toString()

    /**
     * The profiles [text] holds, or null when it holds none: one with no id, a
     * tile out of range or a tile or id another already has is left out.
     */
    fun decode(text: String?): List<KeptProfile>? = try {
        val found = ArrayList<KeptProfile>()
        for (item in Json.parseToJsonElement(text.orEmpty()).jsonArray) {
            val o = item as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.content.orEmpty()
            val slot = (o["slot"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
            if (id.isEmpty() || slot !in 1..MAX || found.any { it.id == id || it.slot == slot }) continue
            found += KeptProfile(id, slot, named((o["name"] as? JsonPrimitive)?.content.orEmpty()))
        }
        found.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
