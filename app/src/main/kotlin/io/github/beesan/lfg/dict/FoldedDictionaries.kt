// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

import android.content.Context

/**
 * The dictionaries folded away in the lists of entries: only their names
 * show, until a name is tapped again. One set for the popup over a game, the
 * lines' screen and the app's own pages, kept across restarts.
 */
object FoldedDictionaries {
    private const val STORE = "entries"
    private const val KEY = "folded"

    @Volatile
    private var names: Set<String>? = null

    fun get(context: Context): Set<String> = names ?: synchronized(this) {
        names ?: context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet().also { names = it }
    }

    fun set(context: Context, dictionary: String, folded: Boolean) = synchronized(this) {
        val next = if (folded) get(context) + dictionary else get(context) - dictionary
        names = next
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }
}
