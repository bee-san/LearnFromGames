// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.anki

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Hands pictures and sounds to AnkiDroid. AnkiDroid names a file after the
 * type this provider reports for it, and Android's own table calls `.m4a`
 * "audio/mpeg", which would store AAC sound under an `.mp3` name.
 */
class MediaProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".m4a", ignoreCase = true) == true) "audio/mp4" else super.getType(uri)
}
