// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.dict

/** The JNI face of bee-san/hoshidicts. JSON in UTF-8 bytes crosses the boundary; see lfg_dict.h. */
internal object Hoshidicts {
    init {
        System.loadLibrary("lfg")
    }

    const val KIND_TERM = 0
    const val KIND_FREQUENCY = 1
    const val KIND_PITCH = 2
    const val KIND_KANJI = 3

    fun import(zipPath: String, outputDir: String, lowRam: Boolean): String =
        nativeImport(zipPath.toByteArray(), outputDir.toByteArray(), lowRam).decodeToString()

    fun open(): Long = nativeOpen()

    fun close(handle: Long) = nativeClose(handle)

    fun addDictionary(handle: Long, path: String, kind: Int): Boolean =
        nativeAddDictionary(handle, path.toByteArray(), kind)

    /**
     * Gives the session a language pack: its language.json, and its transforms.json, the rules that take an
     * inflected word back to its dictionary form. Japanese is built in. Returns what was wrong with the pack, or "".
     */
    fun addLanguage(handle: Long, languageJson: ByteArray, transformsJson: ByteArray): String =
        nativeAddLanguage(handle, languageJson, transformsJson).decodeToString()

    /** [language] is the pack's code: "ja", or one given with [addLanguage]. */
    fun lookup(handle: Long, text: String, maxResults: Int, scanLength: Int, language: String = "ja"): String =
        nativeLookup(handle, text.toByteArray(), maxResults, scanLength, language.toByteArray()).decodeToString()

    fun matchLength(handle: Long, text: String, scanLength: Int, language: String = "ja"): Int =
        nativeMatchLength(handle, text.toByteArray(), scanLength, language.toByteArray())

    fun styles(handle: Long): String = nativeStyles(handle).decodeToString()

    fun media(handle: Long, dictionary: String, path: String): ByteArray = nativeMedia(handle, dictionary.toByteArray(), path.toByteArray())

    private external fun nativeMatchLength(handle: Long, text: ByteArray, scanLength: Int, language: ByteArray): Int
    private external fun nativeAddLanguage(handle: Long, language: ByteArray, transforms: ByteArray): ByteArray
    private external fun nativeStyles(handle: Long): ByteArray
    private external fun nativeMedia(handle: Long, dictionary: ByteArray, path: ByteArray): ByteArray
    private external fun nativeImport(zip: ByteArray, outDir: ByteArray, lowRam: Boolean): ByteArray
    private external fun nativeOpen(): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeAddDictionary(handle: Long, path: ByteArray, kind: Int): Boolean
    private external fun nativeLookup(handle: Long, text: ByteArray, maxResults: Int, scanLength: Int, language: ByteArray): ByteArray
}
