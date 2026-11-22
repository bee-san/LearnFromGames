// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.names

import io.github.beesan.lfg.dict.JsonBytes
import io.github.beesan.lfg.dict.YomitanZip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.OutputStream

/**
 * A visual novel's cast as a Yomitan dictionary, ported from dict_builder.rs for a
 * single VNDB title. Every form of each name gets an entry (as written, without its
 * space, family and given names alone, in hiragana and katakana, and its Japanese
 * aliases), and each of those forms again with every honorific. The entries are
 * written in the Rust builder's order and the zip is streamed bank by bank: a large
 * cast with honorifics is around a gigabyte of JSON.
 */
object CharacterDictionary {
    /** Entries per term bank, as the Rust builder, JMdict and Jitendex use. */
    const val TERM_BANK_LIMIT = 2_000

    /** [entries] written for [characters] characters; [skipped] had no Japanese name. */
    class Written(val entries: Int, val characters: Int, val skipped: Int = 0)

    fun getScore(role: String): Int = when (role) {
        "main" -> 100
        "primary" -> 75
        "side" -> 50
        "appears" -> 25
        else -> 0
    }

    fun roleImportance(role: String): Int = when (role) {
        "main" -> 0
        "primary" -> 1
        "side" -> 2
        "appears" -> 3
        else -> 4
    }

    /**
     * Writes the dictionary for [vn]'s [characters] to [out] as a zip, and does not
     * close it. [title] is the index's title. [progress] hears how many characters
     * are fully written out of how many have a Japanese name.
     */
    fun write(
        vnId: String,
        vn: VnInfo,
        characters: List<Character>,
        settings: DictSettings,
        title: String,
        revision: String,
        out: OutputStream,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Written {
        val gameTitle = gameTitle(vn)
        val zip = YomitanZip(out)
        zip.add("index.json", index(vnId, gameTitle, title, revision))
        zip.add("tag_bank_1.json", tagBank(settings))
        val bank = JsonBytes()
        var inBank = 0
        var banks = 0
        var entries = 0
        fun flush() {
            if (inBank == 0) return
            bank.raw(']')
            zip.add("term_bank_${++banks}.json", bank.bytes, bank.size)
            bank.clear()
            inBank = 0
        }
        val banners = HashMap<Honorific, ByteArray>()
        val images = ArrayList<Pair<String, Portrait>>()
        val built = generate(vn, characters, settings, images, progress) { term, reading, role, score, card, honorific ->
            bank.raw(if (inBank++ == 0) '[' else ',')
            bank.raw('[').string(term).raw(',').string(reading).raw(',').string(CharacterCard.definitionTags(role)).raw(",\"\",").number(score).raw(",[")
            bank.raw(CARD_PREFIX)
            if (honorific == null) {
                bank.raw(card.content)
            } else {
                bank.raw('[').raw(banners.getOrPut(honorific) { encode(CharacterCard.honorificBanner(honorific.suffix, honorific.description)) })
                if (card.content.size > 2) bank.raw(',')
                bank.raw(card.content, from = 1)
            }
            bank.raw("}],0,\"\"]")
            entries++
            if (inBank >= TERM_BANK_LIMIT) flush()
        }
        flush()
        for ((name, portrait) in images) zip.add("img/$name", portrait.bytes)
        zip.finish()
        return Written(entries, built.characters, built.skipped)
    }

    /** The card's title: the Japanese one when VNDB has it. */
    fun gameTitle(vn: VnInfo): String = vn.alttitle.ifEmpty { vn.title }

    /** Every entry as a Yomitan term, in the order [write] writes them. For tests: it holds the whole dictionary. */
    internal fun entries(vn: VnInfo, characters: List<Character>, settings: DictSettings): List<JsonArray> {
        val all = ArrayList<JsonArray>()
        generate(vn, characters, settings, ArrayList(), { _, _ -> }) { term, reading, role, score, card, honorific ->
            val content = if (honorific == null) card.json else CharacterCard.buildHonorificContent(card.json, honorific.suffix, honorific.description)
            all += CharacterCard.createTermEntry(term, reading, role, score, content)
        }
        return all
    }

    internal class SavedCard(val json: JsonObject) {
        /** The card's content list as JSON; the card is [CARD_PREFIX], this, and a closing brace. */
        val content: ByteArray = encode(json["content"] as JsonArray)
    }

    private fun interface Sink {
        fun entry(term: String, reading: String, role: String, score: Int, card: SavedCard, honorific: Honorific?)
    }

    private class Merged(var character: Character, val appearances: MutableList<Pair<String, String>>, var bestScore: Int, var bestRole: String)

    private class HonorificSource(
        val bases: List<Pair<String, String>>,
        val card: SavedCard,
        val tagRole: String,
        val score: Int,
        val addedTerms: Set<String>,
    )

    private class Generated(val characters: Int, val skipped: Int)

    private fun generate(
        vn: VnInfo,
        characters: List<Character>,
        settings: DictSettings,
        images: MutableList<Pair<String, Portrait>>,
        progress: (Int, Int) -> Unit,
        sink: Sink,
    ): Generated {
        val gameTitle = gameTitle(vn)
        var skipped = 0
        val merged = ArrayList<Merged>()
        val seenById = HashMap<String, Int>()
        val seenByName = HashMap<String, Int>()
        // VNDB's client files the cast under main, primary, side and appears, anything else under side, and adds them in that order.
        for (listed in characters.sortedBy { if (it.role in KNOWN_ROLES) roleImportance(it.role) else roleImportance("side") }) {
            // The voice actors on the visual novel's own record win, as in the Rust server.
            val char = vn.voiceActors[listed.id]?.let { listed.copy(seiyuu = it) } ?: listed
            if (char.nameOriginal.isEmpty()) {
                skipped++
                continue
            }
            val normalized = char.nameOriginal.filterNot { it.isWhitespace() }
            val score = getScore(char.role)
            val appearance = char.role to gameTitle
            val existing = seenById[char.id] ?: seenByName[normalized]
            if (existing != null) {
                val m = merged[existing]
                m.appearances += appearance
                if (score > m.bestScore) {
                    m.bestScore = score
                    m.bestRole = char.role
                }
                m.character = mergeFields(m.character, char)
                if (char.id.isNotEmpty()) seenById[char.id] = existing
                continue
            }
            if (char.id.isNotEmpty()) seenById[char.id] = merged.size
            seenByName[normalized] = merged.size
            merged += Merged(char, mutableListOf(appearance), score, char.role)
        }

        val addedImages = HashSet<String>()
        val sources = ArrayList<HonorificSource>()
        for ((i, m) in merged.withIndex()) {
            m.appearances.sortBy { roleImportance(it.first) }
            generateBase(m, settings, images, addedImages, sink)?.let(sources::add)
            if (!settings.honorifics) progress(i + 1, merged.size)
        }
        if (settings.honorifics) {
            // A term already given as an honorific, for this character or one before it, is not given again.
            val dedup = HashSet<String>()
            var done = 0
            for (source in sources) {
                for ((baseName, baseReading) in source.bases) {
                    for (h in HONORIFIC_SUFFIXES) {
                        val term = baseName + h.suffix
                        if (term in source.addedTerms || !dedup.add(term)) continue
                        sink.entry(term, baseReading + h.reading, source.tagRole, source.score, source.card, h)
                    }
                }
                progress(++done, merged.size)
            }
            for (i in done until merged.size) progress(i + 1, merged.size)
        }
        return Generated(merged.size, skipped)
    }

    /** One character's entries other than honorifics, written to [sink]; what its honorifics need, when they are on. */
    private fun generateBase(
        merged: Merged,
        settings: DictSettings,
        images: MutableList<Pair<String, Portrait>>,
        addedImages: MutableSet<String>,
        sink: Sink,
    ): HonorificSource? {
        val char = merged.character
        val nameOriginal = char.nameOriginal
        val score = merged.bestScore
        val tagRole = if (settings.showTag) merged.bestRole else ""
        val readings = NameReadingsParser.generateNameReadings(nameOriginal, char.name)

        var imagePath: String? = null
        val portrait = char.image
        if (settings.showImage && portrait != null) {
            val filename = "c${char.id}.${portrait.ext}"
            imagePath = "img/$filename"
            if (addedImages.add(filename)) images += filename to portrait
        }
        val card = SavedCard(
            CharacterCard(settings).buildContent(
                char, imagePath, portrait?.let { it.width to it.height }, merged.appearances.firstOrNull()?.second.orEmpty(),
            ),
        )

        val added = HashSet<String>()
        fun entry(term: String, reading: String) = sink.entry(term, reading, tagRole, score, card, null)
        fun addIfNew(term: String, reading: String) {
            if (term.isNotEmpty() && added.add(term)) entry(term, reading)
        }

        val parts = NameReadingsParser.splitJapaneseName(nameOriginal)
        val hasSplit = parts.hasSpace || parts.family != null
        val kataFamily = Kana.hiraToKata(readings.family)
        val kataGiven = Kana.hiraToKata(readings.given)
        val kataFull = Kana.hiraToKata(readings.full)
        if (hasSplit) {
            addIfNew(parts.original, readings.full)
            addIfNew(parts.combined, readings.full)
            parts.family?.let { addIfNew(it, readings.family) }
            parts.given?.let { addIfNew(it, readings.given) }
            addIfNew(readings.family + readings.given, readings.full)
            val hiraSpaced = "${readings.family} ${readings.given}"
            if (added.add(hiraSpaced)) entry(hiraSpaced, readings.full)
            addIfNew(readings.family, readings.family)
            addIfNew(readings.given, readings.given)
            addIfNew(kataFamily + kataGiven, readings.full)
            val kataSpaced = "$kataFamily $kataGiven"
            if (added.add(kataSpaced)) entry(kataSpaced, readings.full)
            addIfNew(kataFamily, readings.family)
            addIfNew(kataGiven, readings.given)
        } else {
            addIfNew(nameOriginal, readings.full)
            addIfNew(readings.full, readings.full)
            addIfNew(kataFull, readings.full)
        }

        val bases = ArrayList<Pair<String, String>>()
        fun base(term: String, reading: String) {
            if (term.isNotEmpty()) bases += term to reading
        }
        if (hasSplit) {
            parts.family?.let { base(it, readings.family) }
            parts.given?.let { base(it, readings.given) }
            base(parts.combined, readings.full)
            base(parts.original, readings.full)
            base(readings.family, readings.family)
            base(readings.given, readings.given)
            base(readings.family + readings.given, readings.full)
            base(kataFamily, readings.family)
            base(kataGiven, readings.given)
            base(kataFamily + kataGiven, readings.full)
        } else if (nameOriginal.isNotEmpty()) {
            bases += nameOriginal to readings.full
            base(readings.full, readings.full)
            base(kataFull, readings.full)
        }

        for (alias in char.aliases) {
            if (alias.isEmpty() || !Kana.containsJapanese(alias)) continue
            if (added.add(alias)) {
                entry(alias, readings.full)
                if (settings.honorifics) bases += alias to readings.full
            }
        }

        return if (settings.honorifics && bases.isNotEmpty()) HonorificSource(bases, card, tagRole, score, added) else null
    }

    /** [base] with its gaps filled from [other]: empty fields and lists take [other]'s, and aliases are joined. */
    private fun mergeFields(base: Character, other: Character): Character {
        val aliases = base.aliases.toMutableList()
        val existing = base.aliases.toMutableSet()
        for (alias in other.aliases) if (alias.isNotEmpty() && existing.add(alias)) aliases += alias
        return base.copy(
            sex = base.sex ?: other.sex,
            age = base.age ?: other.age,
            height = base.height ?: other.height,
            weight = base.weight ?: other.weight,
            bloodType = base.bloodType ?: other.bloodType,
            birthday = base.birthday ?: other.birthday,
            description = base.description ?: other.description,
            imageUrl = base.imageUrl ?: other.imageUrl,
            image = base.image ?: other.image,
            seiyuu = base.seiyuu ?: other.seiyuu,
            name = base.name.ifEmpty { other.name },
            personality = base.personality.ifEmpty { other.personality },
            roles = base.roles.ifEmpty { other.roles },
            engagesIn = base.engagesIn.ifEmpty { other.engagesIn },
            subjectOf = base.subjectOf.ifEmpty { other.subjectOf },
            aliases = aliases,
        )
    }

    internal fun index(vnId: String, gameTitle: String, title: String, revision: String): ByteArray {
        val description = (if (gameTitle.isEmpty()) "Character names dictionary" else "Character names from $gameTitle") +
            " ($vnId on VNDB), built on this device from VNDB's data by a port of bee-san's Japanese Character Name Dictionary builder."
        val json = JsonBytes().raw("{\"title\":").string(title).raw(",\"revision\":").string(revision).raw(",\"format\":3")
            .raw(",\"author\":").string(AUTHOR)
            .raw(",\"attribution\":").string(ATTRIBUTION)
            .raw(",\"description\":").string(description)
            .raw('}')
        return json.bytes.copyOf(json.size)
    }

    internal fun tagBank(settings: DictSettings): ByteArray {
        val json = JsonBytes().raw("[[\"name\",\"partOfSpeech\",0,\"Character name\",0]")
        if (settings.showTag) {
            json.raw(",[\"main\",\"name\",0,\"Protagonist\",0]")
                .raw(",[\"primary\",\"name\",0,\"Main character\",0]")
                .raw(",[\"side\",\"name\",0,\"Side character\",0]")
                .raw(",[\"appears\",\"name\",0,\"Minor appearance\",0]")
        }
        json.raw(']')
        return json.bytes.copyOf(json.size)
    }

    const val AUTHOR = "VNDB contributors (vndb.org); built with a port of bee-san/Japanese_Character_Name_Dictionary"
    const val ATTRIBUTION = "Character data and portraits from VNDB (https://vndb.org), used under its data licence " +
        "(https://vndb.org/d17). Built on this device by LearnFromGames with a port of the dictionary builder in " +
        "bee-san/Japanese_Character_Name_Dictionary (https://github.com/bee-san/Japanese_Character_Name_Dictionary)."

    private val KNOWN_ROLES = setOf("main", "primary", "side", "appears")
    private val CARD_PREFIX = "{\"type\":\"structured-content\",\"content\":".toByteArray()

    private fun encode(element: JsonElement): ByteArray = Json.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)
}
