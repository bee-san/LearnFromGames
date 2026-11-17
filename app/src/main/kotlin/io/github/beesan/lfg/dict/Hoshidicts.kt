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

    fun lookup(handle: Long, text: String, maxResults: Int, scanLength: Int): String =
        nativeLookup(handle, text.toByteArray(), maxResults, scanLength).decodeToString()

    fun styles(handle: Long): String = nativeStyles(handle).decodeToString()

    fun media(handle: Long, dictionary: String, path: String): ByteArray = nativeMedia(handle, dictionary.toByteArray(), path.toByteArray())

    private external fun nativeStyles(handle: Long): ByteArray
    private external fun nativeMedia(handle: Long, dictionary: ByteArray, path: ByteArray): ByteArray
    private external fun nativeImport(zip: ByteArray, outDir: ByteArray, lowRam: Boolean): ByteArray
    private external fun nativeOpen(): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeAddDictionary(handle: Long, path: ByteArray, kind: Int): Boolean
    private external fun nativeLookup(handle: Long, text: ByteArray, maxResults: Int, scanLength: Int): ByteArray
}
