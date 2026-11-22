// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import io.github.beesan.lfg.capture.DialogueSamples
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.TextLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class DialogueSamplesTest {
    private val files = Files.createTempDirectory("lfg-dialogue").toFile()

    @Test
    fun labelsPlaceBoxesOnThePictureItself() {
        val line = TextLine("こんにちは", Box(110, 220, 310, 260), emptyList())
        val json = Json.parseToJsonElement(DialogueSamples.labels("Game", "screenai", listOf(line), 100, 200, 5)).jsonObject
        val first = json["lines"]!!.jsonArray[0].jsonObject
        assertEquals("こんにちは", first["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(10, 20, 210, 60), first["box"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun savesZipsAndClears() {
        val dir = DialogueSamples.dir(files)
        assertNull(DialogueSamples.zip(files, java.io.File(files, "out")))
        DialogueSamples.save(dir, "a", "{}") { it.writeBytes(ByteArray(10)) }
        DialogueSamples.save(dir, "b", "{}") { it.writeBytes(ByteArray(20)) }
        assertEquals(2, DialogueSamples.kept(files).screens)
        val zip = DialogueSamples.zip(files, java.io.File(files, "out"))!!
        ZipFile(zip).use { z -> assertEquals(listOf("a.json", "a.webp", "b.json", "b.webp"), z.entries().toList().map { it.name }) }
        assertEquals(2, DialogueSamples.clear(files))
        assertEquals(0, DialogueSamples.kept(files).screens)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun aPictureThatFailsLeavesNothingBehind() {
        val dir = DialogueSamples.dir(files)
        runCatching { DialogueSamples.save(dir, "c", "{}") { it.writeBytes(ByteArray(1)); error("disk full") } }
        assertEquals(0, dir.listFiles()!!.size)
    }
}
