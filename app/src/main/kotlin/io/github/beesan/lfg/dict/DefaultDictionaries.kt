// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

/**
 * The dictionaries a new install fetches by itself: Hachidori's starter set
 * (extension/recommended-dictionaries.js in bee-san/hachidori), in its order,
 * which is the order their entries show in. Each URL is its publisher's
 * "latest" link, so no GitHub API call (and no rate limit) is needed. A release
 * is sometimes a stub (yomidevs/jmdict-yomitan had two 2 KB ones in four days
 * in November 2026), so the size and the import's counts are checked.
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

    val ALL = listOf(
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

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
