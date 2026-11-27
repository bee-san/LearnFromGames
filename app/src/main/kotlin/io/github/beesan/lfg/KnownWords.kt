// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.Context
import io.github.beesan.lfg.core.KnownWordList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Your own list of known words, one for each language learnt: a file of one
 * word a line in `files/known-words`, named by the language's tag. It is the
 * language's and not a profile's, as the words a person knows do not change
 * with their settings: two profiles of one language share it.
 */
class KnownWords(context: Context) {
    private val dir = File(context.filesDir, "known-words")

    /** Each language's list as [KnownWordList.key] makes its words, read once. */
    private val byLanguage = HashMap<String, Set<String>>()

    private val _changed = MutableStateFlow(0)

    /** Goes up each time a list is changed, so what is marked is worked out again. */
    val changed: StateFlow<Int> = _changed

    private fun file(language: String) = File(dir, language.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "none" } + ".txt")

    /** The words of [language]'s list, in the order they were given. */
    fun words(language: String): List<String> = synchronized(this) {
        file(language).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    }

    /** [language]'s list, each word as [KnownWordList.key] makes it, for [KnownWordList.knows]. */
    fun keys(language: String): Set<String> = synchronized(this) {
        byLanguage.getOrPut(language) { words(language).mapTo(HashSet(), KnownWordList::key) }
    }

    /** [language]'s list is [words] from now on; none deletes it. */
    fun replace(language: String, words: List<String>) {
        synchronized(this) {
            val list = words.distinctBy(KnownWordList::key)
            if (list.isEmpty()) {
                file(language).delete()
            } else {
                dir.mkdirs()
                file(language).writeText(KnownWordList.format(list) + "\n")
            }
            byLanguage[language] = list.mapTo(HashSet(), KnownWordList::key)
        }
        _changed.value++
    }
}
