// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.atan2

/** The headings the tiles are listed under where a wheel is edited. */
enum class WheelGroup(val label: String) {
    WHEELS("Wheels"), READ("Read"), AI("AI"), CARDS("Cards and sound"), DIARY("Diary"), LFG("LFG")
}

/**
 * What a slice of the wheel does: a tile. [id] names it in a saved wheel and
 * names its icon ([WheelIcons]); [about] is said in the wheel's middle, and
 * [more] where the tiles are listed to choose from.
 */
enum class WheelAction(val id: String, val label: String, val about: String, val group: WheelGroup, val more: String = about) {
    /** A slice that opens another wheel of the set ([WheelSlice.wheel]). Listed as "New wheel", which makes an empty one. */
    WHEEL("newwheel", "New wheel", "An empty wheel inside this one", WheelGroup.WHEELS, "An empty wheel inside this one. Name it, then fill it with tiles like any other."),

    /** Only where tiles are listed: it adds a [WHEEL] slice that opens the AI wheel, made anew if it was removed. */
    AI("ai", "AI", "A wheel of Translate, Explain, Recap and Ask", WheelGroup.WHEELS, "A ready-made wheel of Translate, Explain, Recap and Ask. It can be changed like any wheel."),
    BOXES("boxes", "Boxes", "A wheel of the game’s boxes", WheelGroup.WHEELS, "A wheel that fills itself with the game’s boxes, plus New box."),

    READ_ONCE("readonce", "Read once", "Two corners, read at once", WheelGroup.READ, "Two corners, read at once, nothing kept."),
    BOX("box", "Box", "Read one of the game’s boxes", WheelGroup.READ, "Read one of the game’s boxes: box 1 unless another number is set. Its icon shows the number. In a game without that box, nothing happens."),
    NEW_BOX("newbox", "New box", "Draw another box", WheelGroup.READ, "Draw another box for this game."),
    READ_SCREEN("screen", "Read screen", "Read the whole screen now", WheelGroup.READ, "Read the whole screen now, as the quick settings tile does."),
    READ_REGION("region", "Read region", "Draw a box and keep reading it", WheelGroup.READ, "Draw a box and keep reading it, for subtitles."),
    LOOKUP("lookup", "Lookup", "Start lookup on the line", WheelGroup.READ, "Start lookup on the line, as a tap of the shortcut does."),
    FURIGANA("furigana", "Furigana", "Readings over the game’s kanji, on or off", WheelGroup.READ, "Readings drawn over the kanji in the game’s text, on or off. Stays as set until picked again."),
    KNOWN("known", "Known words", "Words coloured by how well Anki knows them, on or off", WheelGroup.READ, "Words in the game’s text marked by their Anki card: mature, still learning, or not in Anki yet. On or off."),
    RECENT("recent", "Recent lines", "Pick a recent line for the next tile to act on", WheelGroup.READ, "Choose one of the last lines read. The next tile acts on it instead of the newest."),

    TRANSLATE("translate", "Translate", "This line in your language", WheelGroup.AI, "This line in your language."),
    EXPLAIN("explain", "Explain", "The line, part by part", WheelGroup.AI, "The line, part by part, with questions after."),
    RECAP("recap", "Recap", "The scene, or the story so far", WheelGroup.AI, "The scene, the last session or the story so far."),
    ASK("ask", "Ask", "A question about the game", WheelGroup.AI, "A question about the game, answered from the web."),
    NUANCE("nuance", "Nuance", "Tone, politeness and feeling in the line", WheelGroup.AI, "Tone, politeness and feeling: what the line says between the words, and how it would land on a native speaker."),
    CULTURE("culture", "Culture", "References, customs and wordplay", WheelGroup.AI, "References, customs, idioms and wordplay in the line that a learner might miss."),
    GRAMMAR("grammar", "Grammar", "The grammar points in the line", WheelGroup.AI, "The grammar points in the line, one by one, with what each does here."),
    READINGS("readings", "Readings", "Every kanji with its reading", WheelGroup.AI, "Every kanji in the line with its reading in context."),
    SIMPLER("simpler", "Simpler", "The line again in easier words", WheelGroup.AI, "The line again in easier words, in the language being learnt."),
    OTHER_WAYS("otherways", "Other ways", "How else it could be said", WheelGroup.AI, "How else the line could be said, and how each would differ."),
    SPEAKER("speaker", "Speaker", "Who is talking, and how they speak", WheelGroup.AI, "Who is talking, and how they speak: dialect, gender, age, character quirks."),
    PROMPT("prompt", "Prompt", "Your own question, asked of any line", WheelGroup.AI, "A question you write once and ask of any line. Each Prompt tile keeps its own question."),

    CARD("card", "Card", "An Anki card of the whole line", WheelGroup.CARDS, "An Anki card of the whole line, with the picture and voice, without picking a word."),
    LAST_CARD("lastcard", "Picture to last card", "This screen on the card made last", WheelGroup.CARDS, "This screen on the card made last, for when the right moment comes after the line."),
    HEAR("hear", "Hear", "The line’s voice again", WheelGroup.CARDS, "The line’s voice clip again."),
    ALOUD("aloud", "Read aloud", "The line spoken by the device", WheelGroup.CARDS, "The line spoken by the device, for lines with no voice."),
    COPY("copy", "Copy", "This line to the clipboard", WheelGroup.CARDS, "This line to the clipboard."),

    NOTE("entry", "Entry", "A diary entry on this moment", WheelGroup.DIARY, "A diary entry on this moment."),
    KEEP("keep", "Keep", "This screen in today’s diary", WheelGroup.DIARY, "This screen in today’s diary, with nothing to write."),
    DIARY("diary", "Diary", "Today’s page", WheelGroup.DIARY, "Open today’s page for this game."),

    LINES("lines", "Lines", "What was read lately", WheelGroup.LFG, "What was read lately."),
    STATS("stats", "Stats", "Reading time and goals", WheelGroup.LFG, "Reading time and goals."),
    NAMES("names", "Names", "This game’s characters", WheelGroup.LFG, "This game’s characters, from VNDB."),
    SEARCH("search", "Search", "Look up a word by typing it", WheelGroup.LFG, "Look up a word by typing it."),
    OTHER_SCREEN("screens", "Other screen", "Lines on the other screen, on or off", WheelGroup.LFG, "Lines on the other screen, on or off."),
    PAUSE("pause", "Pause reading", "Stop reading this game for now", WheelGroup.LFG, "Stop reading this game until the tile is picked again."),
    HIDE("hide", "Hide LFG", "Nothing over the game until the shortcut", WheelGroup.LFG, "Nothing over the game until the shortcut is pressed."),
    SETTINGS("settings", "Settings", "LFG’s settings", WheelGroup.LFG, "LFG’s settings, on the other screen where there is one."),
    BACK("back", "Back", "Up to the wheel this one is in", WheelGroup.LFG, "Up to the wheel this one is in. B already does this; the tile is for touch."),
    ;

    /** Asked of the model about one line, and answered in a card: the tiles that all work the same way. */
    val asksOfLine: Boolean
        get() = this == NUANCE || this == CULTURE || this == GRAMMAR || this == READINGS || this == SIMPLER ||
            this == OTHER_WAYS || this == SPEAKER || this == PROMPT

    /** Done at once with nothing opened over the game, so a wheel set to stay open can stay for it. */
    val quick: Boolean
        get() = this == KEEP || this == COPY || this == HEAR || this == ALOUD || this == CARD || this == LAST_CARD ||
            this == FURIGANA || this == KNOWN || this == OTHER_SCREEN

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): WheelAction? = byId[id]
    }
}

/**
 * One slice of a wheel: what it does and, for [WheelAction.BOX], which of the
 * game's boxes it reads, counted from 1, the dialogue box; for
 * [WheelAction.WHEEL], the wheel it opens and, once looked up in its set
 * ([WheelSet.level]), that wheel's [name] and what is [inside] it; for [WheelAction.PROMPT], the
 * question it asks. [icon] (a name in [WheelIcons]) or [glyph] (one emoji or
 * character) is the player's own, drawn in place of the tile's.
 */
data class WheelSlice(
    val action: WheelAction,
    val box: Int = 0,
    val wheel: String = "",
    val icon: String = "",
    val glyph: String = "",
    val prompt: String = "",
    val name: String = "",
    /** For a wheel slice looked up in its set: the names of the first tiles in its wheel, said in the middle. */
    val inside: String = "",
) {
    val label: String
        get() = when (action) {
            WheelAction.BOX -> "Box $box"
            WheelAction.WHEEL -> name.ifBlank { "Wheel" }
            else -> action.label
        }
    val about: String
        get() = when {
            action == WheelAction.BOX && box == 1 -> "Read the dialogue box"
            action == WheelAction.BOX -> "Read box $box"
            action == WheelAction.PROMPT && prompt.isNotBlank() -> prompt
            action == WheelAction.WHEEL -> inside.ifBlank { "An empty wheel" }
            else -> action.about
        }

    /** Whether the player gave it an icon of their own. */
    val custom: Boolean get() = icon.isNotEmpty() || glyph.isNotEmpty()

    /** The icon drawn, as a path on a 24-unit square ([WheelIcons]); a [glyph] is drawn in place of it. */
    val iconPath: String
        get() = WheelIcons.ALL[icon] ?: when (action) {
            WheelAction.WHEEL -> if (wheel == WheelSet.AI) WheelIcons.ALL.getValue("ai") else WheelIcons.ALL.getValue("wheel")
            WheelAction.BOX -> WheelIcons.BOX
            else -> WheelIcons.ALL.getValue(action.id)
        }

    /** The number drawn in a box's frame: not under an icon of the player's own. */
    val number: String get() = if (action == WheelAction.BOX && !custom) box.toString() else ""
}

/** A wheel of a [WheelSet]: its name, and its slices clockwise from the top. */
data class Wheel(val name: String, val slices: List<WheelSlice> = emptyList())

/**
 * The player's wheels, by id: [TOP], which the shortcut opens, and every wheel
 * a slice of another opens. Changed where the wheel is edited, each change
 * giving a new set; kept as JSON ([encode]), which is also the file a wheel is
 * copied to another device in.
 */
data class WheelSet(val wheels: Map<String, Wheel> = DEFAULT.wheels) {
    val top: Wheel get() = wheels[TOP] ?: Wheel("Wheel")

    operator fun get(id: String): Wheel? = wheels[id]

    /** The slices of wheel [id] as the wheel over a game shows them: each wheel slice with its wheel's name. */
    fun level(id: String): List<WheelSlice> = wheels[id]?.slices.orEmpty().map { named(it) }

    fun named(slice: WheelSlice): WheelSlice {
        if (slice.action != WheelAction.WHEEL) return slice
        val wheel = wheels[slice.wheel]
        val tiles = wheel?.slices.orEmpty().map { if (it.action == WheelAction.WHEEL) wheels[it.wheel]?.name ?: "Wheel" else it.label }
        return slice.copy(name = wheel?.name ?: "Wheel", inside = tiles.take(INSIDE_SAID).joinToString(", ") + if (tiles.size > INSIDE_SAID) "…" else "")
    }

    /**
     * Wheel [id] with [action] added at [at], or at its end: the set, and where
     * the new slice is. "New wheel" makes an empty wheel for its slice to open;
     * AI opens the AI wheel, made anew if it has gone.
     */
    fun add(id: String, action: WheelAction, at: Int? = null): Pair<WheelSet, Int> {
        val wheel = wheels[id] ?: return this to -1
        val pos = (at ?: wheel.slices.size).coerceIn(0, wheel.slices.size)
        var all = wheels
        val slice = when (action) {
            WheelAction.WHEEL -> {
                val made = generateSequence(1) { it + 1 }.map { "w$it" }.first { it !in all }
                val count = all.keys.count { it != TOP && it != AI }
                all = all + (made to Wheel("Wheel ${count + 2}"))
                WheelSlice(WheelAction.WHEEL, wheel = made)
            }
            WheelAction.AI -> {
                if (AI !in all) all = all + (AI to DEFAULT.wheels.getValue(AI))
                WheelSlice(WheelAction.WHEEL, wheel = AI)
            }
            WheelAction.BOX -> WheelSlice(WheelAction.BOX, box = 1)
            else -> WheelSlice(action)
        }
        val slices = wheel.slices.toMutableList().apply { add(pos, slice) }
        return WheelSet(all + (id to wheel.copy(slices = slices))) to pos
    }

    /** Wheel [id] without its slice at [index]. A wheel no slice opens any more goes with it. */
    fun remove(id: String, index: Int): WheelSet = change(id) { it.filterIndexed { i, _ -> i != index } }.pruned()

    /** The slice at [index] of wheel [id] changes places with the one [by] round from it; the set, and where the slice is now. */
    fun move(id: String, index: Int, by: Int): Pair<WheelSet, Int> {
        val slices = wheels[id]?.slices ?: return this to index
        if (index !in slices.indices || slices.size < 2) return this to index
        val to = Math.floorMod(index + by, slices.size)
        return change(id) { list -> list.toMutableList().apply { this[index] = list[to]; this[to] = list[index] } } to to
    }

    /** The slice at [index] of wheel [id], changed. */
    fun edit(id: String, index: Int, change: (WheelSlice) -> WheelSlice): WheelSet =
        change(id) { list -> list.mapIndexed { i, s -> if (i == index) change(s) else s } }

    fun rename(id: String, name: String): WheelSet = wheels[id]?.let { WheelSet(wheels + (id to it.copy(name = name.take(NAME_LENGTH)))) } ?: this

    private fun change(id: String, slices: (List<WheelSlice>) -> List<WheelSlice>): WheelSet {
        val wheel = wheels[id] ?: return this
        return WheelSet(wheels + (id to wheel.copy(slices = slices(wheel.slices))))
    }

    /** Only the wheels that can be reached from [TOP]. */
    fun pruned(): WheelSet {
        val kept = LinkedHashSet<String>()
        val todo = ArrayDeque(listOf(TOP))
        while (todo.isNotEmpty()) {
            val id = todo.removeFirst()
            if (!kept.add(id)) continue
            wheels[id]?.slices?.forEach { if (it.action == WheelAction.WHEEL) todo += it.wheel }
        }
        return WheelSet(wheels.filterKeys { it in kept })
    }

    fun encode(): String = buildJsonObject {
        put("v", 1)
        putJsonObject("wheels") {
            for ((id, wheel) in wheels) putJsonObject(id) {
                put("name", wheel.name)
                putJsonArray("slices") {
                    for (s in wheel.slices) addJsonObject {
                        if (s.action == WheelAction.WHEEL) put("wheel", s.wheel) else put("tile", s.action.id)
                        if (s.action == WheelAction.BOX) put("box", s.box)
                        if (s.icon.isNotEmpty()) put("icon", s.icon)
                        if (s.glyph.isNotEmpty()) put("glyph", s.glyph)
                        if (s.prompt.isNotEmpty()) put("prompt", s.prompt)
                    }
                }
            }
        }
    }.toString()

    companion object {
        const val TOP = "top"
        const val AI = "ai"
        const val NAME_LENGTH = 24

        /** How many of a wheel's tiles are named where its slice is pointed at. */
        const val INSIDE_SAID = 4
        const val PROMPT_LENGTH = 300

        val DEFAULT = WheelSet(
            mapOf(
                TOP to Wheel(
                    "Wheel",
                    listOf(
                        WheelSlice(WheelAction.WHEEL, wheel = AI), WheelSlice(WheelAction.NOTE), WheelSlice(WheelAction.KEEP),
                        WheelSlice(WheelAction.READ_ONCE), WheelSlice(WheelAction.BOXES), WheelSlice(WheelAction.LINES),
                    ),
                ),
                AI to Wheel("AI", listOf(WheelAction.TRANSLATE, WheelAction.EXPLAIN, WheelAction.RECAP, WheelAction.ASK).map { WheelSlice(it) }),
            ),
        )

        /**
         * The set [text] holds, or null when it is not a wheel. A tile this
         * version does not know is left out, and a slice that opens a wheel
         * the file does not have; a file with no top wheel is not a wheel.
         */
        fun decode(text: String): WheelSet? = try {
            val root = Json.parseToJsonElement(text).jsonObject
            val found = root.getValue("wheels").jsonObject
            val wheels = found.mapValues { (_, value) ->
                val wheel = value.jsonObject
                fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
                Wheel(
                    wheel.text("name").take(NAME_LENGTH),
                    (wheel["slices"] as? JsonArray).orEmpty().mapNotNull { item ->
                        val s = item as? JsonObject ?: return@mapNotNull null
                        val opens = s.text("wheel").ifEmpty { s.text("tile").takeIf { it == AI }.orEmpty() }
                        val action = if (opens.isNotEmpty()) WheelAction.WHEEL else WheelAction.of(s.text("tile"))
                        when {
                            action == null || action == WheelAction.AI -> null
                            action == WheelAction.WHEEL && opens !in found -> null
                            else -> WheelSlice(
                                action,
                                box = if (action == WheelAction.BOX) (s.text("box").toIntOrNull() ?: 1).coerceIn(1, MAX_BOX) else 0,
                                wheel = opens,
                                icon = s.text("icon").takeIf { it in WheelIcons.ALL }.orEmpty(),
                                glyph = WheelIcons.lastGlyph(s.text("glyph")),
                                prompt = s.text("prompt").take(PROMPT_LENGTH),
                            )
                        }
                    },
                )
            }
            if (TOP in wheels) WheelSet(wheels).pruned() else null
        } catch (_: Exception) {
            null
        }

        /** The highest box a Box tile can be set to. */
        const val MAX_BOX = 9
    }
}

/** The wheel's one level that fills itself: the game's boxes. */
object WheelLevels {
    /** A game's [count] boxes, box 1 always among them, drawn or not, and one more to draw. */
    fun boxes(count: Int): List<WheelSlice> = (1..maxOf(count, 1)).map { WheelSlice(WheelAction.BOX, it) } + WheelSlice(WheelAction.NEW_BOX)
}

/** How big a wheel of so many slices is drawn, in the design's units: it grows with its tiles. */
object WheelSize {
    fun outer(count: Int): Float = maxOf(170f, 80f + 17f * count)
    fun inner(count: Int): Float = maxOf(70f, Math.round(outer(count) * 0.42f).toFloat())
}

/** Which slice of the wheel a stick, the D-pad or a finger points at. */
object WheelAim {
    /**
     * How far from the middle a stick must be pushed to point: near its rim, so a
     * stick springing back to the middle, or let go one way at a time, points at
     * nothing on the way and leaves the slice it was pushed to.
     */
    const val DEAD = 0.75f

    /**
     * The slice of [count] that a push to ([x], [y]) points at, y growing
     * downwards, counted clockwise from straight up; null while it is nearer
     * the middle than [dead].
     */
    fun slice(x: Float, y: Float, count: Int, dead: Float = DEAD): Int? {
        if (x * x + y * y < dead * dead) return null
        val degrees = Math.toDegrees(atan2(x.toDouble(), -y.toDouble()))
        // Each slice is centred on its way, so a push half a slice either side of it is still that slice.
        val turned = (degrees + 360.0 + 180.0 / count) % 360.0
        return (turned / (360.0 / count)).toInt() % count
    }

    /** The slice [by] round from [current] of [count], clockwise for more: the D-pad stepping round. From nothing, the top slice. */
    fun step(current: Int?, by: Int, count: Int): Int? = when {
        count <= 0 -> null
        current == null -> 0
        else -> Math.floorMod(current + by, count)
    }

    /** The slice the D-pad's held directions point at: one alone, or two together for a slant. */
    fun pad(up: Boolean, down: Boolean, left: Boolean, right: Boolean, count: Int): Int? {
        val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
        val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        // The D-pad is pushed all the way or not at all, whatever reach a stick is set to.
        return slice(x, y, count, dead = 0.5f)
    }
}

/**
 * The lookup shortcut, pressed and let go. Let go soon, it is a tap: lookup
 * on or off, as always. Held past [holdMs], which Settings › Wheel sets, it
 * opens the wheel, and letting go picks the slice pointed at.
 */
class ShortcutHold(private val holdMs: () -> Long = { HOLD_MS }) {
    enum class Release { TAP, HOLD, NONE }

    private var downAt = -1L
    private var held = false

    /** Whether the shortcut is down and has not been let go. */
    val pressed: Boolean get() = downAt >= 0

    fun press(now: Long) {
        downAt = now
        held = false
    }

    /** True once, the first time it is asked after the shortcut has been down for [holdMs]. */
    fun holdReached(now: Long): Boolean {
        if (downAt < 0 || held || now - downAt < holdMs()) return false
        held = true
        return true
    }

    /** The hold came to nothing (there was no wheel to open): letting go is then a tap after all. */
    fun cancelHold() {
        held = false
    }

    fun release(): Release {
        if (downAt < 0) return Release.NONE
        val result = if (held) Release.HOLD else Release.TAP
        downAt = -1
        held = false
        return result
    }

    companion object {
        const val HOLD_MS = 350L
    }
}
