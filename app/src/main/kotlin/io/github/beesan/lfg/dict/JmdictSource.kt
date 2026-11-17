// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the first dictionary comes from. yomidevs/jmdict-yomitan releases
 * daily, and a release is sometimes a 2 KB stub (on 17 November 2026 two of the
 * last four were), so "latest" is never trusted: the newest release whose zip is
 * a plausible size wins, and the import's entry count is checked afterwards.
 */
object JmdictSource {
    const val RELEASES_URL = "https://api.github.com/repos/yomidevs/jmdict-yomitan/releases?per_page=10"
    const val ASSET_NAME = "JMdict_english.zip"

    /** Real releases are about 15 MB. */
    const val MIN_ZIP_BYTES = 5L * 1024 * 1024

    /** JMdict has over 200,000 entries; fewer than this means a broken build. */
    const val MIN_TERMS = 100_000L

    data class Asset(val release: String, val url: String, val size: Long)

    /** Usable zips from GitHub's release list, newest first. */
    fun candidates(releasesJson: String, assetName: String = ASSET_NAME): List<Asset> {
        val root = try {
            DictJson.parseToJsonElement(releasesJson)
        } catch (_: Exception) {
            return emptyList()
        }
        if (root !is JsonArray) return emptyList()
        return root.mapNotNull { release ->
            if (release !is JsonObject) return@mapNotNull null
            if (release.text("draft") == "true" || release.text("prerelease") == "true") return@mapNotNull null
            val asset = (release["assets"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.text("name") == assetName } ?: return@mapNotNull null
            val size = asset.text("size").toLongOrNull() ?: return@mapNotNull null
            val url = asset.text("browser_download_url")
            if (size < MIN_ZIP_BYTES || !url.startsWith("https://")) return@mapNotNull null
            Asset(release.text("tag_name"), url, size)
        }
    }

    fun plausible(outcome: ImportOutcome) = outcome.success && outcome.terms >= MIN_TERMS

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content ?: ""
}
