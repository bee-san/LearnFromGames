// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * The app's colours, as ARGB ints, for one colour scheme in light or dark.
 * Dark is true black, for AMOLED screens. Every surface draws from one of
 * these: the app's screens, the views over a game, the entry pages and the
 * widgets. The pitch-accent colours are not here: they mean something, so
 * they stay the same in every scheme.
 */
data class Palette(
    val dark: Boolean,
    val bg: Int,
    /** Cards and other raised areas. */
    val surface: Int,
    /** Wells below the background, such as a text field. */
    val sunken: Int,
    val border: Int,
    val borderStrong: Int,
    val text: Int,
    val dim: Int,
    /** Links, the marked word, outlined buttons. */
    val accent: Int,
    /** A primary button, and the text on it. */
    val fill: Int,
    val onFill: Int,
    /** A disabled button, and the card button's ground. */
    val soft: Int,
    val ok: Int,
    val okSoft: Int,
    val warn: Int,
    val error: Int,
    /** The Today widget's rings, outside in: time, characters, cards. */
    val ringTime: Int,
    val ringChars: Int,
    val ringCards: Int,
    /** Small grey chips: the audio button, a frequency's value. */
    val chip: Int,
    /** A frequency dictionary's name chip. */
    val freqName: Int,
    val freqNameText: Int,
) {
    /** The accent at a quarter strength: behind the marked word. */
    val wash: Int get() = (accent and 0x00FFFFFF) or (0x3D shl 24)

    companion object {
        const val PINK = "pink"
        const val MONO = "mono"
        const val AMBER = "amber"
        const val TEAL = "teal"
        const val PURPLE = "purple"

        /** The schemes in the order Settings lists them, with their names. */
        val SCHEMES = listOf(PINK to "Pink", MONO to "Mono", AMBER to "Amber", TEAL to "Teal", PURPLE to "Purple")

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** [theme] is one of the THEME_ values; [systemDark] is what the system is set to, for [THEME_SYSTEM]. */
        fun of(scheme: String, theme: String, systemDark: Boolean): Palette {
            val dark = when (theme) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> systemDark
            }
            return (if (dark) DARK else LIGHT)[scheme] ?: (if (dark) DARK else LIGHT).getValue(PINK)
        }

        private fun c(hex: Long) = (hex or 0xFF000000).toInt()

        private val DARK = mapOf(
            PINK to Palette(
                true, c(0x000000), c(0x0F0D0E), c(0x000000), c(0x2E2A2C), c(0x4A4347), c(0xF5EFF2), c(0xA39AA0),
                c(0xFF85B8), c(0xFF85B8), c(0x2A0614), c(0x2A0E1B), c(0x5FD38D), c(0x0E2117), c(0xE0B46A), c(0xF08A8C),
                c(0xFF85B8), c(0xFFC46B), c(0x7AB8FF), c(0x1C181A), c(0x3A1426), c(0xFFD1E6),
            ),
            MONO to Palette(
                true, c(0x000000), c(0x0C0C0D), c(0x000000), c(0x2A2A2D), c(0x48484C), c(0xF2F2F2), c(0x9A9AA0),
                c(0xF2F2F2), c(0xF2F2F2), c(0x000000), c(0x1C1C1E), c(0x5FD38D), c(0x0E2117), c(0xE0B46A), c(0xF08A8C),
                c(0xF2F2F2), c(0xA1A1A6), c(0x5C5C61), c(0x1C1C1E), c(0x2A2A2D), c(0xF2F2F2),
            ),
            AMBER to Palette(
                true, c(0x000000), c(0x0E0E0F), c(0x000000), c(0x2A2A2E), c(0x48474A), c(0xF0EEE9), c(0x9D9B96),
                c(0xF5A524), c(0xF5A524), c(0x1A1100), c(0x241906), c(0x4CC38A), c(0x0C2117), c(0xE0B46A), c(0xF08A8C),
                c(0xF5A524), c(0x2EC4B6), c(0x6AA6FF), c(0x1A1A1C), c(0x0F3A35), c(0xC9F5EF),
            ),
            TEAL to Palette(
                true, c(0x000000), c(0x0D0F10), c(0x000000), c(0x263033), c(0x41504F), c(0xEEF2F2), c(0x95A0A2),
                c(0x3DD6C6), c(0x3DD6C6), c(0x00201D), c(0x072421), c(0x9BE37A), c(0x13220C), c(0xE0B46A), c(0xF08A8C),
                c(0x3DD6C6), c(0xF5A524), c(0xFF7A6B), c(0x16191A), c(0x3A2A0C), c(0xFFD79A),
            ),
            // The colours the app had before schemes: Hachidori's default theme.
            PURPLE to Palette(
                true, c(0x22232C), c(0x2B2B36), c(0x1D1E26), c(0x454653), c(0x747586), c(0xE8E5EB), c(0xAAA8B2),
                c(0xB1A5EE), c(0xA89CE4), c(0x211D30), c(0x353247), c(0x8BB79B), c(0x2B3530), c(0xD1AA71), c(0xD28A8C),
                c(0xB1A5EE), c(0x8BB79B), c(0x6CB8F5), c(0x2F303B), c(0x347D35), c(0xFFFFFF),
            ),
        )

        private val LIGHT = mapOf(
            PINK to Palette(
                false, c(0xF8F2F5), c(0xFFFFFF), c(0xEFE6EB), c(0xEADDE3), c(0xC9B8C0), c(0x1F1A1D), c(0x675E63),
                c(0xC2185B), c(0xC2185B), c(0xFFFFFF), c(0xFDE7F0), c(0x1D8048), c(0xE5F4EA), c(0x8A5A00), c(0xB3261E),
                c(0xE6508F), c(0xD99000), c(0x2F7FE0), c(0xF6EEF2), c(0xFCD9E8), c(0x7A1240),
            ),
            MONO to Palette(
                false, c(0xF2F2F2), c(0xFFFFFF), c(0xE8E8E8), c(0xDEDEDE), c(0xB8B8BC), c(0x111111), c(0x5C5C63),
                c(0x111111), c(0x111111), c(0xFFFFFF), c(0xF0F0F0), c(0x1D8048), c(0xE5F4EA), c(0x8A5A00), c(0xB3261E),
                c(0x111111), c(0x6E6E73), c(0xB4B4B8), c(0xF0F0F0), c(0xE2E2E4), c(0x111111),
            ),
            AMBER to Palette(
                false, c(0xF4F2EE), c(0xFFFFFF), c(0xEAE6DF), c(0xE2DFD8), c(0xBDB8AE), c(0x1D1B16), c(0x66625A),
                c(0xA86300), c(0xA86300), c(0xFFFFFF), c(0xFFF3DF), c(0x1D8048), c(0xE3F5EA), c(0x8A5A00), c(0xB3261E),
                c(0xE08E00), c(0x0F9488), c(0x2F6FE0), c(0xF3F1EC), c(0xD5F2EE), c(0x0B4F48),
            ),
            TEAL to Palette(
                false, c(0xF0F4F4), c(0xFFFFFF), c(0xE3EAEA), c(0xD9E1E2), c(0xB2BEC0), c(0x121718), c(0x5B6567),
                c(0x0B7A70), c(0x0B7A70), c(0xFFFFFF), c(0xE0F5F2), c(0x4A7F1C), c(0xEBF5E3), c(0x8A5A00), c(0xB3261E),
                c(0x14A89A), c(0xD98A00), c(0xE0533F), c(0xEEF2F2), c(0xFBE7C4), c(0x5A3D00),
            ),
            PURPLE to Palette(
                false, c(0xF4F3F8), c(0xFFFFFF), c(0xE9E7F1), c(0xDCDAE6), c(0xB9B6CA), c(0x1E1D26), c(0x5F5D6B),
                c(0x5B4BC4), c(0x5B4BC4), c(0xFFFFFF), c(0xEEEBFB), c(0x2A7A4B), c(0xE6F4EC), c(0x8A5A00), c(0xB3261E),
                c(0x6B5BD2), c(0x2F8F5B), c(0x2A7BD4), c(0xF0EFF5), c(0x2F7D4F), c(0xFFFFFF),
            ),
        )
    }
}
