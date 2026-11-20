package io.github.beesan.lfg

import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.ScreenAi
import io.github.beesan.lfg.ocr.TextClean
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a pass-or-fail test: runs the readers over a corpus made by
 * tools/make_ocr_corpus.py and writes what each read, for tools/score_ocr.py.
 * Runs only when LFG_CORPUS names the corpus directory.
 *
 * ScreenAI's library has no JVM build, so its results are read from
 * NAME.screenai.pb files made by the host `run_ocr`, through the app's own
 * decoder, and then grouped and cleaned as the app does.
 */
class OcrCorpusReport {
    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun dump(lines: List<TextLine>): String = lines.joinToString(",", "[", "]") { line ->
        "{\"text\":${json(line.text)},\"box\":[${line.box.left},${line.box.top},${line.box.right},${line.box.bottom}]," +
            "\"vertical\":${line.vertical},\"rows\":${line.rows.size},\"chars\":${line.chars.size}}"
    }

    @Test
    fun writeReport() {
        val dir = System.getenv("LFG_CORPUS")?.let(::File)
        assumeTrue("LFG_CORPUS is not set", dir != null && dir.isDirectory)
        val out = StringBuilder("{")
        var first = true
        for (file in dir!!.listFiles { f -> f.name.endsWith(".argb.gz") }!!.sortedBy { it.name }) {
            val name = file.name.removeSuffix(".argb.gz")
            val raw = LinkedHashMap<String, List<TextLine>>()
            File(dir, "$name.screenai.pb").takeIf { it.isFile }?.let { raw["screenai"] = ScreenAi.decode(it.readBytes()).mapNotNull(TextClean::line) }
            if (!first) out.append(',')
            first = false
            out.append(json(name)).append(":{")
            out.append(raw.entries.joinToString(",") { (engine, lines) ->
                "${json(engine)}:{\"lines\":${dump(lines)},\"blocks\":${dump(io.github.beesan.lfg.core.Blocks.group(lines))}}"
            })
            out.append('}')
        }
        out.append('}')
        File(dir, "report.json").writeText(out.toString())
    }
}
