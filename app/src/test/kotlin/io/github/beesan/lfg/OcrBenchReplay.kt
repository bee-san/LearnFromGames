package io.github.beesan.lfg

import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.CharBox
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.TextClean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a pass-or-fail test: runs TextClean over an OCR bench's results.jsonl
 * (tools/ocr_bench.py) taken before the cleaning, so a change to the rules is
 * scored without reading the screens again. Runs only when LFG_OCR_RESULTS
 * names the file; writes results.clean.jsonl beside it.
 */
class OcrBenchReplay {
    @Test
    fun cleanResults() {
        val input = System.getenv("LFG_OCR_RESULTS")?.let(::File)
        assumeTrue("LFG_OCR_RESULTS is not set", input != null && input.isFile)
        val out = File(input!!.parentFile, "results.clean.jsonl").bufferedWriter()
        input.forEachLine { row ->
            val o = Json.parseToJsonElement(row).jsonObject
            val lines = o["lines"]!!.jsonArray.map { l ->
                val j = l.jsonObject
                val b = j["box"]!!.jsonArray.map { it.jsonPrimitive.int }
                val chars = j["chars"]!!.jsonArray.map { c ->
                    val n = c.jsonArray.map { it.jsonPrimitive.int }
                    CharBox(n[0], n[1], Box(n[2], n[3], n[4], n[5]))
                }
                TextLine(j["text"]!!.jsonPrimitive.content, Box(b[0], b[1], b[2], b[3]), chars, j["vertical"]!!.jsonPrimitive.boolean)
            }.mapNotNull(TextClean::line)
            val cleaned = buildJsonObject {
                o.forEach { (k, v) -> if (k != "lines") put(k, v) }
                put("lines", buildJsonArray {
                    lines.forEach { line ->
                        add(buildJsonObject {
                            put("text", line.text)
                            put("box", JsonArray(listOf(line.box.left, line.box.top, line.box.right, line.box.bottom).map(::JsonPrimitive)))
                            put("vertical", line.vertical)
                            put("chars", JsonArray(line.chars.map { c ->
                                JsonArray(listOf(c.start, c.end, c.box.left, c.box.top, c.box.right, c.box.bottom).map(::JsonPrimitive))
                            }))
                        })
                    }
                })
            }
            out.write(cleaned.toString())
            out.newLine()
        }
        out.close()
    }
}
