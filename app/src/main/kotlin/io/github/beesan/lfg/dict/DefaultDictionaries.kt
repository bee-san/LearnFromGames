// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import io.github.beesan.lfg.core.Language

/**
 * The dictionaries a new install fetches by itself. For Japanese, Hachidori's starter set
 * (extension/recommended-dictionaries.js in bee-san/hachidori), in its order,
 * which is the order their entries show in. Each URL is its publisher's
 * "latest" link, so no GitHub API call (and no rate limit) is needed. A release
 * is sometimes a stub (yomidevs/jmdict-yomitan had two 2 KB ones in four days
 * in November 2026), so the size and the import's counts are checked.
 *
 * Every other language has one: its Wiktionary dictionary with English
 * meanings, made by wiktionary-to-yomitan, which each language pack of
 * hoshidicts recommends and whose inflected forms its rules are written for.
 */
object DefaultDictionaries {
    data class Source(
        val id: String,
        val name: String,
        val url: String,
        /** The title the dictionary gives itself, to tell whether it is already installed. */
        val title: Regex,
        /** Well below the real size, which is noted beside each. */
        val minBytes: Long,
        val plausible: (ImportOutcome) -> Boolean,
    ) {
        fun matches(installed: InstalledDictionary) = title.matches(installed.title)
        fun accepts(outcome: ImportOutcome) = outcome.success && plausible(outcome)
    }

    private const val MB = 1024L * 1024

    private val JAPANESE = listOf(
        // 38.8 MB, about 300,000 entries.
        Source(
            "jitendex", "Jitendex",
            "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            Regex("""Jitendex\.org \[\d{4}-\d{2}-\d{2}]"""), 20 * MB,
        ) { it.terms >= 100_000 },
        // 11.4 MB, over 700,000 names.
        Source(
            "jmnedict", "JMnedict",
            "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            Regex("""JMnedict \[\d{4}-\d{2}-\d{2}]"""), 5 * MB,
        ) { it.terms >= 100_000 },
        // 14.3 MB.
        Source(
            "bees-ultimate-kanji-dictionary", "Bee's Ultimate Kanji Dictionary",
            "https://github.com/bee-san/bees-ultimate-kanji-dictionary/releases/latest/download/bees-ultimate-kanji-dictionary.zip",
            Regex("Bee's Ultimate Kanji Dictionary"), 5 * MB,
        ) { it.terms >= 1_000 },
        // 8 MB, frequencies only.
        Source(
            "jiten", "Jiten frequencies",
            "https://api.jiten.moe/api/frequency-list/download?downloadType=yomitan",
            Regex("Jiten"), 2 * MB,
        ) { it.frequencies >= 10_000 },
        // 32 MB, about 6,400 grammar points.
        Source(
            "bees-ultimate-grammar-dictionary", "Bee's Ultimate Grammar Dictionary",
            "https://github.com/bee-san/bees-ultimate-grammar-dictionary/releases/latest/download/bees-ultimate-grammar-dictionary.zip",
            Regex("Bee's Ultimate Grammar Dictionary"), 5 * MB,
        ) { it.terms >= 1_000 },
    )

    /** All five Japanese downloads together, as noted beside each: about 105 MB. */
    private const val JAPANESE_BYTES = 104_600_000L

    /** How big each language's Wiktionary dictionary is to download, in bytes, in November 2026. */
    private val WIKTIONARY_BYTES = mapOf(
        "aii" to 1_725_959L, "ar" to 13_609_450L, "de" to 17_318_061L, "el" to 6_062_302L, "en" to 110_539_099L,
        "eo" to 4_024_933L, "es" to 21_857_656L, "eu" to 1_784_965L, "fr" to 11_171_590L, "ga" to 3_753_901L,
        "grc" to 15_176_889L, "it" to 17_357_566L, "ko" to 7_472_324L, "la" to 26_430_257L, "pt" to 11_427_166L,
        "ru" to 26_228_831L, "sga" to 618_762L, "sh" to 11_989_217L, "sq" to 2_641_684L, "tl" to 4_295_045L,
        "uk" to 9_826_557L, "vi" to 4_376_918L, "yi" to 1_394_068L, "zh" to 21_332_823L,
    )

    private fun wiktionary(language: Language): Source {
        val name = "wty-${language.tag}-en"
        val bytes = WIKTIONARY_BYTES[language.tag] ?: 2 * MB
        return Source(
            name, "${language.title} dictionary",
            "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict/${language.tag}/en/$name.zip",
            Regex(Regex.escape(name)), bytes / 3,
        ) { it.terms >= 1_000 }
    }

    /** The starter dictionaries of [language], in the order their entries show. */
    fun of(language: Language): List<Source> = if (language.japanese) JAPANESE else listOf(wiktionary(language))

    /** What [language]'s starter dictionaries are to download, in bytes. */
    fun bytes(language: Language): Long = if (language.japanese) JAPANESE_BYTES else WIKTIONARY_BYTES[language.tag] ?: 2 * MB

    fun byId(id: String) = JAPANESE.firstOrNull { it.id == id } ?: Language.entries.firstOrNull { "wty-${it.tag}-en" == id }?.let(::wiktionary)
}
