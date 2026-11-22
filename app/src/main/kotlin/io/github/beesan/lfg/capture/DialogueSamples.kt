// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import io.github.beesan.lfg.core.TextLine
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Whole screens of game dialogue, kept for good when the player agreed to it
 * at set-up, to train a better reader on. Each is a picture and, beside it,
 * what LFG read from it. Nothing leaves the device until the player sends the
 * zip themselves from Settings › Help improve LFG.
 */
object DialogueSamples {
    const val DIR = "dialogue"
    const val EMAIL = "data@skerritt.blog"
    const val DISCORD = "https://discord.com/invite/zYTM3rZM4T"

    fun dir(files: File) = File(files, DIR)

    /** What was read from one screen, with each line's box on the picture itself. */
    fun labels(game: String, engine: String, lines: List<TextLine>, offsetX: Int, offsetY: Int, atMs: Long): String =
        buildJsonObject {
            put("game", game)
            put("engine", engine)
            put("at", atMs)
            putJsonArray("lines") {
                for (line in lines) addJsonObject {
                    put("text", line.text)
                    put("vertical", line.vertical)
                    putJsonArray("box") {
                        add(line.box.left - offsetX); add(line.box.top - offsetY)
                        add(line.box.right - offsetX); add(line.box.bottom - offsetY)
                    }
                }
            }
        }.toString()

    /** Writes [name].webp through [picture], then its labels; a picture whose labels fail goes too. */
    fun save(dir: File, name: String, labels: String, picture: (File) -> Unit) {
        dir.mkdirs()
        val image = File(dir, "$name.webp")
        try {
            picture(image)
            File(dir, "$name.json").writeText(labels)
        } catch (e: Exception) {
            image.delete()
            throw e
        }
    }

    data class Kept(val screens: Int, val bytes: Long)

    fun kept(files: File): Kept {
        val all = dir(files).listFiles().orEmpty()
        return Kept(all.count { it.extension == "webp" }, all.sumOf { it.length() })
    }

    /**
     * Every screen and its labels in one zip in [outDir], replacing the last one
     * made. Stored rather than compressed: the pictures are WebP already.
     */
    fun zip(files: File, outDir: File, now: Date = Date()): File? {
        val all = dir(files).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        if (all.none { it.extension == "webp" }) return null
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val out = File(outDir, "lfg-dialogue-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(now) + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            for (file in all) {
                zip.putNextEntry(ZipEntry(file.name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /** Lets go of every kept screen. Returns how many went. */
    fun clear(files: File): Int = dir(files).listFiles().orEmpty().count { it.extension == "webp" && it.delete() }
        .also { dir(files).listFiles()?.forEach { it.delete() } }
}
