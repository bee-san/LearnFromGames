// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the second dictionary comes from: Bee's Ultimate Dictionary, as Rubi's
 * public releases ship it (`Rubi-<version>.zip`, holding `plaintext.vjdict` and
 * its attribution). Each release lists its files' SHA-256 in `SHA256SUMS`, which
 * the download must match.
 */
object RubiDictionarySource {
    const val RELEASES_URL = "https://api.github.com/repos/bee-san/Rubi_for_vita/releases?per_page=10"
    const val TITLE = "Bee's Ultimate Dictionary"
    const val ENTRY = "plaintext.vjdict"

    /** The release zip is about 67 MB, the dictionary 50 MB of it. */
    const val MIN_ZIP_BYTES = 30L * 1024 * 1024

    /** It has 486,529 entries; far fewer means something else was read. */
    const val MIN_TERMS = 300_000L

    /** The normal build's zip; the debug build's (`-debug.zip`) has the same dictionary but more besides. */
    private val ZIP_NAME = Regex("""Rubi-\d[0-9A-Za-z.]*\.zip""")

    data class Asset(val release: String, val name: String, val url: String, val size: Long, val sumsUrl: String)

    /** Release zips with their checksum lists, newest first. */
    fun candidates(releasesJson: String): List<Asset> {
        val root = try {
            DictJson.parseToJsonElement(releasesJson)
        } catch (_: Exception) {
            return emptyList()
        }
        if (root !is JsonArray) return emptyList()
        return root.mapNotNull { release ->
            if (release !is JsonObject) return@mapNotNull null
            if (release.text("draft") == "true" || release.text("prerelease") == "true") return@mapNotNull null
            val assets = (release["assets"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return@mapNotNull null
            val zip = assets.firstOrNull { ZIP_NAME.matches(it.text("name")) } ?: return@mapNotNull null
            val sums = assets.firstOrNull { it.text("name") == "SHA256SUMS" } ?: return@mapNotNull null
            val size = zip.text("size").toLongOrNull() ?: return@mapNotNull null
            val url = zip.text("browser_download_url")
            val sumsUrl = sums.text("browser_download_url")
            if (size < MIN_ZIP_BYTES || !url.startsWith("https://") || !sumsUrl.startsWith("https://")) return@mapNotNull null
            Asset(release.text("tag_name"), zip.text("name"), url, size, sumsUrl)
        }
    }

    /** The SHA-256 that `sha256sum`'s output gives [name], in lower case; null when it lists none. */
    fun checksum(sums: String, name: String): String? = sums.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("""\s+"""), limit = 2)
        if (parts.size == 2 && parts[1].removePrefix("*") == name && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) parts[0].lowercase() else null
    }.firstOrNull()

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content ?: ""
}
