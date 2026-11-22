// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

/*
 * A visual novel's cast as VNDB gives it, ported from models.rs in
 * bee-san/Japanese_Character_Name_Dictionary (yomitan-dict-builder), the
 * reference builder. Only what the VNDB path uses is here.
 */

/** A trait, with VNDB's spoiler level: 0 none, 1 minor, 2 major. */
data class CharacterTrait(val name: String, val spoiler: Int)

/** One character. [name] is romanised in Western order ("Given Family"); [nameOriginal] is Japanese, family first. */
data class Character(
    val id: String,
    val name: String,
    val nameOriginal: String,
    /** main, primary, side or appears. */
    val role: String,
    val sex: String? = null,
    val age: String? = null,
    val height: Int? = null,
    val weight: Int? = null,
    val bloodType: String? = null,
    /** Month and day. */
    val birthday: List<Int>? = null,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val personality: List<CharacterTrait> = emptyList(),
    val roles: List<CharacterTrait> = emptyList(),
    val engagesIn: List<CharacterTrait> = emptyList(),
    val subjectOf: List<CharacterTrait> = emptyList(),
    val imageUrl: String? = null,
    /** The portrait after resizing, with its file extension and size; null when there is none or images are off. */
    val image: Portrait? = null,
    val seiyuu: String? = null,
)

class Portrait(val bytes: ByteArray, val ext: String, val width: Int, val height: Int)

/** What the cards show, as DictSettings in content_builder.rs. LFG's defaults differ from the site's on spoilers. */
data class DictSettings(
    val showImage: Boolean = true,
    val showTag: Boolean = true,
    val showDescription: Boolean = true,
    val showTraits: Boolean = true,
    val showSpoilers: Boolean = false,
    val honorifics: Boolean = true,
    val showSeiyuu: Boolean = true,
)

/** A visual novel's own details, as VnInfo in vndb_client.rs: [title] romanised, [alttitle] Japanese, and each character's voice actor by character ID. */
data class VnInfo(val title: String, val alttitle: String, val voiceActors: Map<String, String> = emptyMap())
