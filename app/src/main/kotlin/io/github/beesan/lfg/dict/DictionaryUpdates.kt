// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Whether a dictionary has a newer release, as Yomitan decides it: a
 * dictionary that says it can be updated names an index to ask (`indexUrl`),
 * and it is out of date when that index's `revision` is not its own. Any
 * difference counts, not only a later one: revisions are dates for some and
 * version numbers for others, and a publisher who withdraws a release means
 * the one before it.
 */
object DictionaryUpdates {
    /** What an index.json says about updating: the installed one's, or the publisher's. */
    data class Index(val revision: String, val updatable: Boolean, val indexUrl: String, val downloadUrl: String)

    /** Reads [json], an index.json; null when it is not one. */
    fun index(json: String): Index? {
        val o = try {
            DictJson.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        fun text(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.replace("\\/", "/")?.trim() ?: ""
        return Index(text("revision"), (o["isUpdatable"] as? JsonPrimitive)?.booleanOrNull == true, text("indexUrl"), text("downloadUrl"))
    }

    /** Whether [installed] can be asked about at all: it says so, and names an index over HTTPS. */
    fun asks(installed: Index): Boolean = installed.updatable && secure(installed.indexUrl)

    /**
     * Where the newer release is, or null when [installed] is up to date or [remote] names none.
     * The publisher's own index says where its zip is; the installed one's is used when it does not.
     */
    fun download(installed: Index, remote: Index): String? {
        if (remote.revision.isEmpty() || remote.revision == installed.revision) return null
        return listOf(remote.downloadUrl, installed.downloadUrl).firstOrNull(::secure)
    }

    /**
     * Whether what was imported can stand in for what was installed: it holds something, and
     * no kind of entry has fallen to under half. A release is sometimes a stub (see [DefaultDictionaries]).
     */
    fun replaces(old: InstalledDictionary, new: ImportOutcome): Boolean =
        new.success && new.title.isNotEmpty() && new.terms + new.frequencies + new.pitches + new.kanji > 0 &&
            new.terms >= old.terms / 2 && new.frequencies >= old.frequencies / 2 && new.pitches >= old.pitches / 2 && new.kanji >= old.kanji / 2

    private fun secure(url: String) = url.startsWith("https://")
}
