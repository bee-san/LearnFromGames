// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ocr

/** A picture as packed ARGB, free of android.graphics so the readers run in unit tests. */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size >= width * height)
    }
}
