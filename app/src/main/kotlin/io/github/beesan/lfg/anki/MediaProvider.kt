// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Hands pictures and sounds to AnkiDroid. AnkiDroid names a file after the
 * type this provider reports for it, and Android's own table calls `.m4a`
 * "audio/mpeg", which would store AAC sound under an `.mp3` name. `.avif`
 * is said outright rather than left to that table.
 */
class MediaProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment.orEmpty()
        return when {
            name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            name.endsWith(".avif", ignoreCase = true) -> "image/avif"
            else -> super.getType(uri)
        }
    }
}
