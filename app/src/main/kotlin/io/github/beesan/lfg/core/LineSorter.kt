// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/** A line kept in the store, as [LineSorter] needs it: its row, its text and when it was saved. */
data class KeptLine(val id: String, val text: String, val savedAtMs: Long = 0L)

/**
 * A part of a piece on screen, `[from, to)` of the piece's text, and the row
 * it is kept in. [text] is the row's own text, which is more than the screen
 * showed when the line was partly hidden or cut short.
 */
data class Held(val from: Int, val to: Int, val id: String, val text: String, val interfaceText: Boolean = false)

/** What a part of a piece, `[from, to)` of its text, turned out to be. */
sealed interface Part {
    val from: Int
    val to: Int

    /** The row [id] again: still on screen, or read before and shown again. */
    data class Same(override val from: Int, override val to: Int, val id: String) : Part

    /**
     * The row [id], which was caught half typed, or which this goes on from: its text is now [head]
     * and then this part. [head] is what the row had that is no longer on screen: the row a text box
     * scrolled up and out of sight, or the box before this one.
     */
    data class Grown(override val from: Int, override val to: Int, val id: String, val head: String = "") : Part

    /** Text not kept yet. [interfaceText]: it came as a menu's text does, and is no line. */
    data class New(override val from: Int, override val to: Int, val interfaceText: Boolean = false) : Part
}

/** A reading sorted: the parts of each piece, in the pieces' order, and whether it was a menu's screen. */
class Sorted(val parts: List<List<Part>>, val menu: Boolean)

/**
 * Tells what each piece of a reading is, so that a line is kept once.
 *
 * The story arrives a piece or two at a time; a menu, a backlog and a save
 * screen bring many pieces at once; and a line already kept that shows again
 * is the same line, not a new one. So a piece is, in this order:
 *
 * - still on screen: on the reading before, give or take a character, and it
 *   keeps its rows. A long piece that was a menu's is still one only while
 *   [BUSY] or more of the menu's pieces are, since a save slot may show the
 *   very line its save opens on;
 * - back: on the last screen that was no menu, as a line is when a menu
 *   opened over it is closed; or one of the game's last [RECENT] lines (a
 *   backlog, a save slot, the line a loaded save opens on), unless the story
 *   is moving on and the piece arrives alone: then it is that line said
 *   again, which is a new line. A line of [LONG]
 *   characters or more may differ by a character, and by two from twice that,
 *   as long as it is not one number for another; a shorter one must be exact,
 *   and is taken for an old line only when [BUSY] or more pieces arrive
 *   together, because 「……」 and 「うん。」 are said again and again. A piece
 *   of [SNIPPET] characters or more that is the beginning of such a line, as
 *   a save slot cuts it, is that line; and so is what is left showing of the
 *   line that was on screen when a toast or a prompt hides part of it
 *   ([LEFT] characters or more, at least half of it, in its order);
 * - read differently: in the place of a piece of the reading before, while
 *   a line of [LONG] characters or more is new on this one. So are the words
 *   of a sign or a book in the picture, which a reader takes differently at
 *   each look, and a name plate or a counter that changes with the line. A
 *   piece shorter than [LONG] is that piece whatever was read of it; a
 *   longer one must keep two thirds of its characters. The story's line is
 *   the piece that changed;
 * - grown: it starts with a piece of [BASE] characters or more that was on
 *   screen. If that piece ended a sentence outside any open 「 『 or （, the
 *   page has grown by a sentence, as a box that fills click by click does:
 *   the old part keeps its row and only what was added is new. If it did
 *   not, the line was caught half typed, and its row grows to the whole of it;
 * - going on: one piece new on this reading, which goes on from a line of the
 *   reading before. Either it starts with that line's end, [BASE] characters
 *   or more, as a text box that scrolls up a row at a time does; or it is in
 *   the line's place, the line ended no sentence, and nothing on either screen
 *   is interface text, as a sentence that runs on into the next box does.
 *   The line's row grows to hold it; after a sentence, only what was added is
 *   new;
 * - a menu's: [InterfaceText.CROWD] or more pieces arriving together that
 *   are none of the above. None of them is a line, short or long. Text
 *   already known as interface text does not count towards the crowd, so a
 *   line that arrives with a row of button labels is still a line; but a long
 *   piece that was a menu's before is a menu's again when it comes back in
 *   such company;
 * - or new.
 *
 * When in doubt a piece is new: a line kept twice can be seen and removed, a
 * line left out cannot. So a menu's help text that changes while the menu
 * stays up is kept as a line: it arrives alone among labels that stay, just
 * as a line does over a row of buttons.
 */
class LineSorter {
    /** A single full stop ends a sentence: in a language other than Japanese, where dots are not typed one at a time for 「……」. */
    @Volatile
    var dotEnds = false

    private class Shown(val text: String, val parts: List<Held>, val place: Box? = null) {
        val squeezed = Squeezed(text)
        val flagged = parts.any { it.interfaceText }

        /** The row's text, when the piece is one row and showed it otherwise: hidden in part, cut short, or misread. */
        val kept = parts.singleOrNull()?.text?.takeIf { it != text }?.let(::Squeezed)
    }

    private class Row(val id: String, val text: Squeezed, val savedAtMs: Long = 0L)

    /** A piece's characters without its spaces, and where each of them is in the piece. */
    private class Squeezed(val raw: String) {
        val points: IntArray
        private val starts: IntArray

        init {
            val p = ArrayList<Int>(raw.length)
            val s = ArrayList<Int>(raw.length)
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                    p += cp
                    s += i
                }
                i += Character.charCount(cp)
            }
            points = p.toIntArray()
            starts = s.toIntArray()
        }

        val size get() = points.size

        /** Where a part starting at character [i] starts in the piece. */
        fun start(i: Int) = if (i <= 0) 0 else if (i >= size) raw.length else starts[i]

        /** Where a part ending before character [i] ends in the piece. */
        fun end(i: Int) = if (i >= size) raw.length else if (i <= 0) 0 else starts[i - 1] + Character.charCount(points[i - 1])
    }

    /** The reading before this one that had any text. */
    private var before: List<Shown>? = null

    /** The last reading that was no menu's screen: what is under a menu while it is open. */
    private var story: List<Shown> = emptyList()

    /** Whether the reading before this one was a menu's screen. */
    private var menuBefore = false

    /**
     * What each of [pieces] is. [recent] is the game's last [RECENT] lines,
     * newest first, and [known] its interface text. [apart]: whether interface
     * text is told from lines at all. [older] finds a line of exactly this
     * text however long ago it was kept; it is asked only about pieces that
     * would otherwise be taken for a menu's, so a page of an old backlog is
     * not. [places] is where each piece is on screen, when that is known.
     * [recheck]: this is the text of the reading before read again, more
     * clearly; a piece still on screen and read otherwise is corrected.
     * [drawn]: the pieces in a box the player drew beside box 1, which are
     * lines whatever else is on screen: they are never a menu's, and do not
     * count towards a crowd that would make the story's line one.
     */
    suspend fun sort(
        pieces: List<String>,
        recent: List<KeptLine>,
        known: Set<String> = emptySet(),
        apart: Boolean = true,
        places: List<Box>? = null,
        recheck: Boolean = false,
        drawn: Set<Int> = emptySet(),
        older: suspend (String) -> KeptLine? = { null },
    ): Sorted {
        val far = recent.map { Row(it.id, Squeezed(it.text), it.savedAtMs) }
        val newest = far.takeWhile { it.savedAtMs == far[0].savedAtMs }
        // With nothing remembered, the lines kept last are what was on screen last.
        val last = before ?: recent.take(newest.size).map { Shown(it.text, listOf(Held(0, it.text.length, it.id, it.text))) }
        val rows = (last + story).filter { !it.flagged }.flatMap { it.parts }.distinctBy { it.id }.map { Row(it.id, Squeezed(it.text)) }

        val parts = arrayOfNulls<List<Part>>(pieces.size)
        val texts = pieces.map(::Squeezed)
        val still = texts.map { pieceIn(last, it) }
        // A long piece that was a menu's stays one only while the menu does: a save slot may show the very line its save opens on.
        val menuStays = still.count { it?.flagged == true } >= BUSY
        /** Pieces nothing on screen lately explains, in the pieces' order. */
        val open = ArrayList<Int>()
        /** Pieces of [drawn] nothing explains: new lines, kept apart from the crowd. */
        val drawnOpen = ArrayList<Int>()
        var labels = 0
        for (i in pieces.indices) {
            val text = texts[i]
            parts[i] = still[i]?.takeIf { !it.flagged || menuStays || InterfaceText.short(pieces[i]) }
                ?.let { (if (recheck) corrected(it, text) else null) ?: again(it, text) }
                ?: last.firstNotNullOfOrNull { more(it, text, rows) } ?: pieceIn(story, text)?.let { again(it, text) }
            if (parts[i] != null) continue
            if (apart && i !in drawn && InterfaceText.short(pieces[i]) && pieces[i] in known) {
                parts[i] = listOf(Part.New(0, pieces[i].length, interfaceText = true))
                labels++
                continue
            }
            parts[i] = story.firstNotNullOfOrNull { more(it, text, rows) } ?: rest(text, 0, rows, emptySet()).takeIf { it.isNotEmpty() && it[0] !is Part.New }
            if (parts[i] == null) (if (i in drawn) drawnOpen else open) += i
        }

        // Words in the picture itself (a book's spine, a sign) and labels are read differently from one look
        // to the next, the small ones as quite other characters. When a line of the story is new on this
        // reading, a piece in the place of one on the reading before is that piece again: a short one
        // whatever was read of it, a long one with most of its characters. The story's line is the piece
        // that changed, and nothing else of the story changes with it in the place it had.
        if (places != null && open.size >= 2) {
            val again = HashMap<Int, Held>()
            for (i in open) {
                val text = texts[i]
                val limit = text.size / 3
                val was = last.firstOrNull { shown ->
                    shown.place != null && shown.parts.size == 1 && samePlace(places[i], shown.place) && (
                        text.size < LONG || apart(text.points, 0, text.size, shown.squeezed.points, 0, shown.squeezed.size, limit) <= limit
                        )
                } ?: continue
                again[i] = was.parts[0]
            }
            if (open.any { it !in again && texts[it].size >= LONG }) {
                for ((i, held) in again) parts[i] = listOf(Part.Same(0, pieces[i].length, held.id))
                open.removeAll { parts[it] != null }
            }
        }

        // One of the game's last lines, shown again. While the story moves on, the line kept last being the
        // one that was just on screen, a piece like an older line that arrives alone is that line said
        // again, and new: old lines come back after a menu, in a crowd, or when nothing is remembered.
        val busy = open.size >= BUSY
        val onStory = story.flatMapTo(HashSet()) { shown -> shown.parts.map { it.id } }
        val movingOn = before != null && !menuBefore && newest.any { it.id in onStory }
        for (list in listOf(open, drawnOpen)) list.removeAll { i ->
            val row = old(texts[i], far, newest, busy, movingOn) ?: return@removeAll false
            parts[i] = listOf(Part.Same(0, pieces[i].length, row.id))
            true
        }
        for (i in drawnOpen) parts[i] = listOf(Part.New(0, pieces[i].length))
        // A text box that scrolled up a row, or the next box of a sentence that ran on.
        if (open.size == 1 && labels == 0 && !menuBefore) {
            val i = open[0]
            goesOn(texts[i], places?.getOrNull(i), last, rows)?.let {
                parts[i] = it
                open.clear()
            }
        }
        // A crowd may be a page of lines read long ago: each is asked after before it is called a menu's.
        val menus = if (apart) open.filter { pieces[it] in known } else emptyList()
        if (open.size + labels >= InterfaceText.CROWD) {
            val asked = open.iterator()
            while (asked.hasNext()) {
                val i = asked.next()
                val row = older(pieces[i]) ?: continue
                parts[i] = listOf(Part.Same(0, pieces[i].length, row.id))
                asked.remove()
            }
        }
        val menu = open.size + labels >= InterfaceText.CROWD
        val crowd = open.size >= InterfaceText.CROWD
        for (i in open) {
            val interfaceText = apart && (crowd || (menu && i in menus))
            parts[i] = listOf(Part.New(0, pieces[i].length, interfaceText))
        }
        return Sorted(parts.map { it!! }, menu)
    }

    /** What [sort]'s answer came to: each piece's parts and the rows they are kept in now, and where each piece is. */
    fun shown(pieces: List<String>, held: List<List<Held>>, menu: Boolean, places: List<Box>? = null) {
        val now = pieces.indices.map { Shown(pieces[it], held[it], places?.getOrNull(it)) }
        before = now
        menuBefore = menu
        if (!menu) story = now
    }

    /** The rows [sort] may name without being given them: those of the last reading and of the last that was no menu. */
    fun held(): Set<String> = ((before ?: emptyList()) + story).flatMapTo(HashSet()) { shown -> shown.parts.map { it.id } }

    /** Forget what was on screen. */
    fun reset() {
        before = null
        menuBefore = false
        story = emptyList()
    }

    /** The one of [shown] that [text] is: the very text if it is among them, else one a character off. */
    private fun pieceIn(shown: List<Shown>, text: Squeezed): Shown? =
        shown.firstOrNull { it.text == text.raw } ?: shown.firstOrNull { same(text, 0, it.squeezed) }
            // A line that was partly hidden or cut short, and shows whole now.
            ?: shown.firstOrNull { it.kept != null && same(text, 0, it.kept) }

    /** The same, among rows. */
    private fun rowIn(rows: List<Row>, text: Squeezed): Row? =
        rows.firstOrNull { it.text.raw == text.raw } ?: rows.firstOrNull { same(text, 0, it.text) }

    /** [text] is [shown] again, give or take a character: its parts, laid over the new text. */
    private fun again(shown: Shown, text: Squeezed): List<Part> {
        if (shown.text == text.raw) return shown.parts.map { Part.Same(it.from, it.to, it.id) }
        return over(shown, text, text.size).map { (from, to, id) -> Part.Same(text.start(from), text.end(to), id) }
    }

    /** [text] is [shown] read again, more clearly: its row takes the new reading. Null when there is nothing to correct. */
    private fun corrected(shown: Shown, text: Squeezed): List<Part>? {
        if (shown.text == text.raw || shown.flagged) return null
        val held = shown.parts.singleOrNull() ?: return null
        val head = head(shown, held) ?: return null
        return listOf(Part.Grown(0, text.raw.length, held.id, head))
    }

    /**
     * What [held]'s row has before the part of [shown] it is: its text that is no longer on screen.
     * Null when the row is not what the screen showed of it (hidden in part, or misread and kept).
     */
    private fun head(shown: Shown, held: Held): String? {
        val showing = shown.text.substring(held.from, held.to)
        return if (held.text.endsWith(showing)) held.text.dropLast(showing.length) else null
    }

    /**
     * [text], new, goes on from a piece of [last]: it starts with that piece's end ([BASE] characters
     * or more), as a box that scrolls up a row does; or it took the piece's [place], the piece ended
     * no sentence, and no interface text was on screen then or is now.
     */
    private fun goesOn(text: Squeezed, place: Box?, last: List<Shown>, rows: List<Row>): List<Part>? {
        for (shown in last) {
            if (shown.flagged) continue
            val base = shown.squeezed
            val held = shown.parts.last()
            val overlap = overlap(base, text)
            val running = overlap == 0 && place != null && shown.place != null && samePlace(place, shown.place) &&
                !endsLine(base) && last.none { it.flagged }
            if (overlap == 0 && !running) continue
            // Where what is on screen still starts, in the piece before: none of it when the box was replaced.
            val cut = if (overlap > 0) base.start(base.size - overlap) else shown.text.length
            if (cut < held.from) continue
            val head = (head(shown, held) ?: continue) + shown.text.substring(held.from, cut)
            if (overlap > 0 && endsLine(Squeezed(held.text))) {
                return listOf(Part.Same(0, text.end(overlap), held.id)) + rest(text, overlap, rows, setOf(held.id))
            }
            return listOf(Part.Grown(0, text.raw.length, held.id, head))
        }
        return null
    }

    /** How many characters, [BASE] or more, that [text] starts with are the end of [base]; 0 for none. Neither is all of the other. */
    private fun overlap(base: Squeezed, text: Squeezed): Int {
        for (length in minOf(base.size, text.size) - 1 downTo BASE) {
            val allowed = allowed(length)
            if (apart(text.points, 0, length, base.points, base.size - length, base.size, allowed) <= allowed) return length
        }
        return 0
    }

    /**
     * [text] is [shown] with more after it. After a sentence the more is a
     * part of its own; after anything else it is the rest of a line that was
     * read half typed, and the last row grows to hold it.
     */
    private fun more(shown: Shown, text: Squeezed, rows: List<Row>): List<Part>? {
        val base = shown.squeezed
        if (shown.flagged || base.size < BASE || text.size <= base.size) return null
        val end = prefixEnd(text, 0, base)
        if (end < 0 || end >= text.size) return null
        val cuts = over(shown, text, end)
        val kept = cuts.dropLast(1).map { (from, to, id) -> Part.Same(text.start(from), text.end(to), id) }
        val (from, _, id) = cuts.last()
        return when {
            !endsLine(base) -> kept + Part.Grown(text.start(from), text.raw.length, id, shown.parts.first { it.id == id }.let { head(shown, it) } ?: "")
            // A closing bracket alone is no sentence of its own.
            !hasWord(text, end) -> kept + Part.Same(text.start(from), text.raw.length, id)
            else -> kept + Part.Same(text.start(from), text.end(end), id) + rest(text, end, rows, shown.parts.mapTo(HashSet()) { it.id })
        }
    }

    /** Where each part of [shown] lies in the first [end] characters of [text], which is [shown] read again. */
    private fun over(shown: Shown, text: Squeezed, end: Int): List<Triple<Int, Int, String>> {
        val cuts = ArrayList<Triple<Int, Int, String>>()
        var pos = 0
        for ((k, part) in shown.parts.withIndex()) {
            val length = if (k == shown.parts.lastIndex) -1 else prefixEnd(text, pos, Squeezed(part.text))
            // A part that cannot be found again takes what is left, with the parts after it.
            if (length <= 0 || pos + length >= end) {
                cuts += Triple(pos, end, part.id)
                break
            }
            cuts += Triple(pos, pos + length, part.id)
            pos += length
        }
        return cuts
    }

    /**
     * What is left of [text] from character [from]: rows that were on screen
     * lately for as far as they go, then whatever is new. [taken] rows are
     * already parts of this piece.
     */
    private fun rest(text: Squeezed, from: Int, rows: List<Row>, taken: Set<String>): List<Part> {
        val parts = ArrayList<Part>()
        val used = HashSet(taken)
        var pos = from
        while (pos < text.size) {
            val free = rows.filter { it.id !in used }
            val whole = if (pos > 0) free.firstOrNull { same(text, pos, it.text) } else null
            if (whole != null) return parts + Part.Same(text.start(pos), text.raw.length, whole.id)
            var first: Row? = null
            var length = 0
            for (row in free) {
                if (row.text.size < BASE) continue
                val covered = prefixEnd(text, pos, row.text)
                if (covered > length && pos + covered < text.size) {
                    first = row
                    length = covered
                }
            }
            if (first != null) {
                if (!endsLine(first.text)) return parts + Part.Grown(text.start(pos), text.raw.length, first.id)
                if (!hasWord(text, pos + length)) return parts + Part.Same(text.start(pos), text.raw.length, first.id)
                parts += Part.Same(text.start(pos), text.end(pos + length), first.id)
                used += first.id
                pos += length
                continue
            }
            val shown = free.firstOrNull { begins(text, pos, it.text) } ?: if (pos == 0) free.firstOrNull { hidden(text, it.text) } else null
            return parts + if (shown != null) Part.Same(text.start(pos), text.raw.length, shown.id) else Part.New(text.start(pos), text.raw.length)
        }
        return parts
    }

    /** The one of the game's last lines that [text] is, if it is one. */
    private fun old(text: Squeezed, far: List<Row>, newest: List<Row>, busy: Boolean, movingOn: Boolean): Row? {
        rowIn(newest, text)?.let { return it }
        if ((text.size >= LONG && !movingOn) || busy) rowIn(far, text)?.let { return it }
        return if (busy) far.firstOrNull { begins(text, 0, it.text) } else null
    }

    /** Whether [text] from character [from] is [other], give or take the characters its length allows. */
    private fun same(text: Squeezed, from: Int, other: Squeezed): Boolean {
        val length = text.size - from
        if (length <= 0 || other.size == 0) return false
        val allowed = allowed(minOf(length, other.size))
        return apart(text.points, from, text.size, other.points, 0, other.size, allowed) <= allowed
    }

    /**
     * How many characters of [text] from [from] are [other], give or take the
     * characters its length allows; -1 when [text] does not start with it there.
     */
    private fun prefixEnd(text: Squeezed, from: Int, other: Squeezed): Int {
        if (other.size == 0) return -1
        val allowed = allowed(other.size)
        var best = -1
        var least = allowed + 1
        // The same length first: a character read differently is likelier than one added or lost.
        for (extra in listOf(0, -1, 1, -2, 2)) {
            val length = other.size + extra
            if (kotlin.math.abs(extra) > allowed || length <= 0 || from + length > text.size) continue
            val d = apart(text.points, from, from + length, other.points, 0, other.size, allowed)
            if (d < least) {
                least = d
                best = length
            }
        }
        return best
    }

    /** Whether [text] from [from] is the beginning of [other] and no more: a line cut short, with or without an ellipsis put after it. */
    private fun begins(text: Squeezed, from: Int, other: Squeezed): Boolean {
        var end = text.size
        while (end > from && (text.points[end - 1] == '…'.code || text.points[end - 1] == '‥'.code)) end--
        val length = end - from
        if (length < SNIPPET || other.size <= length) return false
        val allowed = allowed(length)
        return apart(text.points, from, end, other.points, 0, length, allowed) <= allowed
    }

    /**
     * Whether [text] is [other] with part of it hidden, as a line is under a
     * toast: [LEFT] characters or more, every one of them in [other] and in
     * its order, and at least half of it. Only a line that was on screen just
     * before is asked this, so a short new line is not taken for an old one.
     */
    private fun hidden(text: Squeezed, other: Squeezed): Boolean {
        if (text.size < LEFT || text.size >= other.size || 2 * text.size < other.size) return false
        var found = 0
        for (point in other.points) if (found < text.size && text.points[found] == point) found++
        return found == text.size
    }

    /** Whether [text] ends a sentence and is not inside a quote still open, as 「そうか。 is while the rest is typed out. */
    private fun endsLine(text: Squeezed): Boolean {
        val last = text.points.lastOrNull() ?: return false
        // Outside Japanese a full stop ends a sentence too, but not dots trailing off, and so does a quote closed after one.
        val dot = dotEnds && (last == '.'.code && text.points.getOrNull(text.size - 2) != '.'.code ||
            last in listOf('"'.code, '»'.code) && text.points.getOrNull(text.size - 2) in listOf('.'.code, '!'.code, '?'.code))
        if (!dot && (last > 0xFFFF || SENTENCE_ENDS.indexOf(last.toChar()) < 0)) return false
        return QUOTES.chunked(2).all { pair -> text.points.count { it == pair[0].code } <= text.points.count { it == pair[1].code } }
    }

    private fun hasWord(text: Squeezed, from: Int) = (from until text.size).any { Character.isLetterOrDigit(text.points[it]) }

    companion object {
        /** How many of a game's last lines a piece is held against. */
        const val RECENT = 50

        /** From this many characters a line may be read a character differently and still be the same line. */
        const val LONG = 10

        /** Pieces arriving together that make a reading busy: a backlog, a save screen, a menu. */
        const val BUSY = 3

        /** The fewest characters of a line's beginning that are taken for the line. */
        const val SNIPPET = 8

        /** The fewest characters of a piece that the next one can be more of. */
        const val BASE = 6

        /** The fewest characters left showing of a line partly hidden that are still taken for it. */
        const val LEFT = 6

        /** Whether two pieces are in the same place: each covers at least half of the other. */
        private fun samePlace(a: Box, b: Box): Boolean {
            val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (width <= 0 || height <= 0) return false
            val shared = width.toLong() * height
            return 2 * shared >= a.width.toLong() * a.height && 2 * shared >= b.width.toLong() * b.height
        }

        private const val SENTENCE_ENDS = "。！？!?…‥」』）)】］〕〉》”’♪"

        /** Brackets a line is said in, each opening one followed by its closing one. */
        private const val QUOTES = "「」『』（）"

        /** How many characters two texts of this length may differ by and be the same. */
        private fun allowed(length: Int) = if (length < LONG) 0 else if (length < 2 * LONG) 1 else 2

        private const val NUMERALS = "0123456789０１２３４５６７８９〇零一二三四五六七八九十百千万億"

        private fun numeral(point: Int) = point <= 0xFFFF && NUMERALS.indexOf(point.toChar()) >= 0

        /**
         * How far apart two runs of characters are, as [distance] has it, but
         * more than [limit] when what differs is a number: a reader takes 動
         * for 勧 or 二 for ニ, never 三 for 四, so 三日目 and 四日目 are two
         * lines however alike the rest of them is.
         */
        private fun apart(a: IntArray, aFrom: Int, aTo: Int, b: IntArray, bFrom: Int, bTo: Int, limit: Int): Int {
            val d = distance(a, aFrom, aTo, b, bFrom, bTo, limit)
            if (d == 0 || d > limit) return d
            val counts = HashMap<Int, Int>()
            for (i in aFrom until aTo) counts[a[i]] = (counts[a[i]] ?: 0) + 1
            for (i in bFrom until bTo) counts[b[i]] = (counts[b[i]] ?: 0) - 1
            val inA = counts.any { it.value > 0 && numeral(it.key) }
            val inB = counts.any { it.value < 0 && numeral(it.key) }
            // One numeral for another; or, where a character was added or lost, a numeral come or gone.
            return if ((inA && inB) || (aTo - aFrom != bTo - bFrom && (inA || inB))) limit + 1 else d
        }

        /**
         * How many characters must be added, lost or changed to make one run
         * of characters the other, if that is [limit] or fewer; else more than
         * [limit].
         */
        private fun distance(a: IntArray, aFrom: Int, aTo: Int, b: IntArray, bFrom: Int, bTo: Int, limit: Int): Int {
            val n = aTo - aFrom
            val m = bTo - bFrom
            if (kotlin.math.abs(n - m) > limit) return limit + 1
            if (limit == 0) {
                for (i in 0 until n) if (a[aFrom + i] != b[bFrom + i]) return 1
                return 0
            }
            var above = IntArray(m + 1) { it }
            var row = IntArray(m + 1)
            for (i in 1..n) {
                row[0] = i
                var least = i
                for (j in 1..m) {
                    val change = if (a[aFrom + i - 1] == b[bFrom + j - 1]) 0 else 1
                    row[j] = minOf(above[j] + 1, row[j - 1] + 1, above[j - 1] + change)
                    if (row[j] < least) least = row[j]
                }
                if (least > limit) return limit + 1
                val swap = above
                above = row
                row = swap
            }
            return above[m]
        }
    }
}
