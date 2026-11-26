// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * The lookup shortcut as it is saved: one of the named ones Settings has
 * always offered, or `keys:` and the key codes of whatever was pressed at
 * set-up, one button or two held together.
 */
object Shortcut {
    // android.view.KeyEvent's codes, written out so this runs in a unit test.
    const val A = 96
    const val B = 97
    const val X = 99
    const val Y = 100
    const val L1 = 102
    const val R1 = 103
    const val L2 = 104
    const val R2 = 105
    const val L3 = 106
    const val R3 = 107
    const val START = 108
    const val SELECT = 109
    const val MODE = 110

    private const val PREFIX = "keys:"

    private val NAMED = mapOf(
        "l3r3" to (L3 to R3), "l1r1" to (L1 to R1), "l2r2" to (L2 to R2), "selectstart" to (SELECT to START),
        "l3" to (L3 to L3), "r3" to (R3 to R3), "select" to (SELECT to SELECT), "start" to (START to START),
    )

    private val NAMES = mapOf(
        A to "A", B to "B", X to "X", Y to "Y", L1 to "L1", R1 to "R1", L2 to "L2", R2 to "R2", L3 to "L3", R3 to "R3",
        START to "Start", SELECT to "Select", MODE to "Home",
        // The extra buttons some handhelds have (M1, M2, back paddles) arrive as the numbered ones.
        188 to "Button 1", 189 to "Button 2", 190 to "Button 3", 191 to "Button 4", 192 to "Button 5", 193 to "Button 6",
        194 to "Button 7", 195 to "Button 8", 196 to "Button 9", 197 to "Button 10", 198 to "Button 11", 199 to "Button 12",
        200 to "Button 13", 201 to "Button 14", 202 to "Button 15", 203 to "Button 16", 98 to "C", 101 to "Z",
    )

    /** The buttons lookup's own controls need: A and B, X and Y, the D-pad. None can be the shortcut. */
    private val TAKEN = setOf(A, B, X, Y, 19, 20, 21, 22, 23)

    /** The two buttons of [saved], the same one twice for a shortcut of one button. Anything unknown is L3 + R3. */
    fun keys(saved: String): Pair<Int, Int> {
        NAMED[saved]?.let { return it }
        if (saved.startsWith(PREFIX)) {
            val codes = saved.removePrefix(PREFIX).split('+').mapNotNull { it.toIntOrNull() }
            if (codes.size == 1) return codes[0] to codes[0]
            if (codes.size == 2 && codes[0] != codes[1]) return codes[0] to codes[1]
        }
        return L3 to R3
    }

    /** What to save for the buttons pressed, in any order; a named shortcut where there is one, so Settings shows it chosen. */
    fun of(codes: Collection<Int>): String? {
        val pressed = codes.distinct().sorted()
        if (pressed.isEmpty() || pressed.size > 2 || pressed.any { it in TAKEN }) return null
        val pair = pressed.first() to pressed.last()
        NAMED.entries.firstOrNull { (_, keys) -> minOf(keys.first, keys.second) == pair.first && maxOf(keys.first, keys.second) == pair.second }?.let { return it.key }
        return PREFIX + pressed.joinToString("+")
    }

    /** Whether [code] can be one of the shortcut's buttons. */
    fun allowed(code: Int): Boolean = code !in TAKEN

    fun label(saved: String): String = keys(saved).let { (first, second) -> if (first == second) name(first) else "${name(first)} + ${name(second)}" }

    fun name(code: Int): String = NAMES[code] ?: "Button $code"
}
