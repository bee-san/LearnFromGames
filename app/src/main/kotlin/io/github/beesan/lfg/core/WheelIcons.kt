// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.text.BreakIterator

/**
 * The wheel's icons, as the design draws them: strokes on a 24-unit square,
 * each a path by its name. [LFG] are the tiles' own, by the tile's id, and
 * [MORE] are others a slice can be given.
 */
object WheelIcons {
    /** A box's corners, with room for its number in the middle. */
    const val BOX = "M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16"

    val LFG: Map<String, String> = linkedMapOf(
        "wheel" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7zM12 3v5.5M12 15.5V21M3 12h5.5M15.5 12H21",
        "ai" to "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z",
        "boxes" to "M3.5 4.5h11v8h-11zM9.5 11.5h11v8h-11z",
        "recent" to "M10 6h10M10 12h10M10 18h10M3.5 9.5L6 12l-2.5 2.5",
        "back" to "M9.5 6L4 11.5 9.5 17M4 11.5h10.5a5.5 5.5 0 0 1 5.5 5.5v1",
        "translate" to "M4 5h8M8 3v2M5.5 5c.7 2.9 2.7 5 5.5 6M10.5 5c-.7 2.9-2.7 5-5.5 6M13 21l4-10 4 10M14.4 18h5.2",
        "explain" to "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.1 2.1V16h5v-.1c0-.8.4-1.6 1.1-2.1A6 6 0 0 0 12 3z",
        "recap" to "M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3 4v5h5M12 7.5V12l3 2",
        "ask" to "M20.5 12a8.5 8.5 0 0 1-12.3 7.6L3.5 20.5l1-4.4A8.5 8.5 0 1 1 20.5 12zM9.7 9.6a2.4 2.4 0 0 1 4.6.8c0 1.6-2.3 2-2.3 3.3M12 16.4h.01",
        "nuance" to "M3 12c2-4 4-4 6 0s4 4 6 0 4-4 6 0",
        "culture" to "M3 5.5c6 1 12 1 18 0M5 9h14M7 5.8V21M17 5.8V21M12 9v3",
        "grammar" to "M12 4v5M12 9l-6 5M12 9l6 5M6 14v4M18 14v4M4 20h4M16 20h4",
        "readings" to "M4 20l3.5-9 3.5 9M5.3 17h4.4M14 11h6M17 11v9M14.5 5h5",
        "simpler" to "M4 6h5v5h5v5h6",
        "otherways" to "M4 12h6l4-6h6M10 12l4 6h6M17 3l3 3-3 3M17 15l3 3-3 3",
        "speaker" to "M9 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 0 0 0 7zM3 20c.6-3.5 3-5.5 6-5.5M14 10h7v5h-3l-2 2v-2h-2z",
        "prompt" to "M4 20h4L18 10l-4-4L4 16zM18 3l.8 2.2L21 6l-2.2.8L18 9l-.8-2.2L15 6l2.2-.8z",
        "furigana" to "M8 3.5h8M5 8h14v12H5zM5 14h14M12 8v12",
        "known" to "M9.5 14.5l-3 3V20H9l3-3M9.5 14.5l7-9 3.5 3.5-9 7zM14 20h6",
        "readonce" to BOX + "M8 12h8",
        "box" to BOX + "M10.5 9.5L12 8.5v7",
        "newbox" to BOX + "M12 8.5v7M8.5 12h7",
        "screen" to BOX + "M8 10h8M8 14h5",
        "region" to "M4 8V4h4M10 4h4M16 4h4v4M20 10v4M20 16v4h-4M14 20h-4M8 20H4v-4M4 14v-4",
        "lookup" to "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13zM15.5 15.5L20 20",
        "card" to "M3.5 6.5h17v11h-17zM7 10.5h6M7 13.5h10",
        "lastcard" to "M3.5 5h17v14h-17zM3.5 15l5-5 4 4 3-3 4.5 4.5M15.5 8.5h.01",
        "hear" to "M4 9.5h3.5L12 6v12l-4.5-3.5H4zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11",
        "aloud" to "M5 19c0-3 2.5-5 5.5-5s5.5 2 5.5 5M10.5 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7zM18 7.5a5 5 0 0 1 0 6M20.5 5a8.5 8.5 0 0 1 0 11",
        "copy" to "M9 8.5h10.5v12H9zM5 15.5v-11A1 1 0 0 1 6 3.5h9.5",
        "star" to "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9 6.8 19.6l1-5.8L3.5 9.7l5.9-.9z",
        "entry" to "M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4",
        "keep" to "M6.5 3.5h11v17L12 16.5l-5.5 4z",
        "diary" to "M4 5.5C6.5 4 9.5 4 12 6c2.5-2 5.5-2 8-.5v13c-2.5-1.5-5.5-1.5-8 .5-2.5-2-5.5-2-8-.5zM12 6v13",
        "lines" to "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
        "stats" to "M5 20V11M10 20V5M15 20v-7M20 20V9",
        "names" to "M12 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8zM4.5 20.5c.8-3.8 3.9-6 7.5-6s6.7 2.2 7.5 6",
        "search" to "M5 4.5h10a3 3 0 0 1 3 3v12H8a3 3 0 0 1-3-3zM5 16.5a3 3 0 0 1 3-3h10",
        "screens" to "M4 3.5h16v8H4zM6 14.5h12v6H6z",
        "pause" to "M8 5v14M16 5v14",
        "hide" to "M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 8.5 4.5 9.5 6-.5.8-1.6 2.3-3.2 3.6M6.6 7.6C4.8 8.9 3.2 10.8 2.5 12c1 1.5 4.5 6 9.5 6 1.4 0 2.7-.3 3.9-.9M9.9 9.9a3 3 0 0 0 4.2 4.2",
        "settings" to "M4 7h9M17 7h3M15 5v4M4 17h3M11 17h9M9 15v4",
    )

    val MORE: Map<String, String> = linkedMapOf(
        "heart" to "M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.3 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z",
        "starO" to "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9 6.8 19.6l1-5.8L3.5 9.7l5.9-.9z",
        "flag" to "M5 21V4M5 4h11l-2 4 2 4H5",
        "bolt" to "M13 3L5 13.5h6L10 21l8-10.5h-6z",
        "sun" to "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
        "moon" to "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z",
        "cloud" to "M7 18.5a4.5 4.5 0 0 1-.5-9 6 6 0 0 1 11.5 1.5 3.75 3.75 0 0 1 0 7.5z",
        "flame" to "M12 21a6.5 6.5 0 0 1-6.5-6.5c0-4 3-6 4-10 2.5 2 4 4 4.5 6 1-1 1.5-2 1.5-3 2 2 3 4.5 3 7A6.5 6.5 0 0 1 12 21z",
        "leaf" to "M5 19c0-8 5-14 15-14 0 10-6 15-14 15M5 19l7-7",
        "music" to "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z",
        "mic" to "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3zM6 11.5a6 6 0 0 0 12 0M12 17.5v3",
        "camera" to "M3.5 7.5h4l1.5-2.5h6l1.5 2.5h4v12h-17zM12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z",
        "film" to "M3.5 4.5h17v15h-17zM7.5 4.5v15M16.5 4.5v15M3.5 9h4M3.5 15h4M16.5 9h4M16.5 15h4",
        "eye" to "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "chat" to "M4 5h16v11H9l-5 4z",
        "smile" to "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17zM8.5 14a4.5 4.5 0 0 0 7 0M9 9.5h.01M15 9.5h.01",
        "gamepad" to "M7 8.5h10a4 4 0 0 1 4 4v1a3.5 3.5 0 0 1-6.3 2.1L14 15h-4l-.7.6A3.5 3.5 0 0 1 3 13.5v-1a4 4 0 0 1 4-4zM7.5 11v3M6 12.5h3M15.5 12h.01M17.5 13.5h.01",
        "trophy" to "M7.5 4h9v5a4.5 4.5 0 0 1-9 0zM7.5 6h-3a3 3 0 0 0 3 4M16.5 6h3a3 3 0 0 1-3 4M12 13.5V17M9.5 17h5v3.5h-5z",
        "sword" to "M14.5 3.5h6v6L10 20l-3-3L17.5 6.5M5 15.5l3.5 3.5M4 20l2-2",
        "shield" to "M12 3l7.5 3v5.5c0 4.5-3.2 8-7.5 9.5-4.3-1.5-7.5-5-7.5-9.5V6z",
        "crown" to "M4 18.5h16M4.5 16l-1-9 5 4 3.5-6 3.5 6 5-4-1 9z",
        "gem" to "M7 4h10l4 5-9 11L3 9zM3 9h18M10 4L8 9l4 11 4-11-2-5",
        "key" to "M8 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 12h8.5M17.5 12v3M20.5 12v2.5",
        "home" to "M4 11l8-7 8 7v9h-5v-6h-6v6H4z",
        "pin" to "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11zM12 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z",
        "map" to "M3.5 6L9 4l6 2 5.5-2v14L15 20l-6-2-5.5 2zM9 4v14M15 6v14",
        "globe" to "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17zM3.5 12h17M12 3.5c2.3 2.3 3.5 5.2 3.5 8.5s-1.2 6.2-3.5 8.5c-2.3-2.3-3.5-5.2-3.5-8.5s1.2-6.2 3.5-8.5z",
        "clock" to "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17zM12 7.5V12l3 2",
        "bell" to "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0",
        "tag" to "M3.5 12.5v-8h8l9 9-8 8zM8 8.5h.01",
        "link" to "M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1",
        "folder" to "M3.5 6.5a1 1 0 0 1 1-1h5l2 2.5h8a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z",
        "grid" to "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
        "target" to "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17zM12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z",
        "check" to "M4.5 12.5l5 5 10-11",
        "coffee" to "M4.5 9h12v6a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5zM16.5 10.5H18a2.5 2.5 0 0 1 0 5h-1.5M8 3.5V6M12 3.5V6",
        "cat" to "M5 20v-9L4 5l5 3h6l5-3-1 6v9zM9.5 13.5h.01M14.5 13.5h.01M10.5 17h3",
    )

    val ALL: Map<String, String> = LFG + MORE + ("newwheel" to LFG.getValue("wheel"))

    /** The last whole character of [text], an emoji of several code points counting as one: what a slice's own glyph is. */
    fun lastGlyph(text: String): String {
        if (text.isBlank()) return ""
        val trimmed = text.trim()
        val breaks = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
        val end = breaks.last()
        val start = breaks.previous()
        return if (start == BreakIterator.DONE) trimmed else trimmed.substring(start, end)
    }
}
