package io.github.beesan.lfg

import ai.onnxruntime.OrtEnvironment
import io.github.beesan.lfg.core.TextLine
import io.github.beesan.lfg.ocr.Frame
import io.github.beesan.lfg.ocr.MeikiOcr
import io.github.beesan.lfg.ocr.ScreenAi
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Not a pass-or-fail test: runs the readers over a corpus made by
 * tools/make_ocr_corpus.py and writes what each read, for tools/score_ocr.py.
 * Runs only when LFG_CORPUS names the corpus directory.
 *
 * meikiocr runs here, through the app's own port. ScreenAI's library has no
 * JVM build, so its results are read from NAME.screenai.pb files made by the
 * host `run_ocr`, through the app's own decoder.
 */
class OcrCorpusReport {
    private fun frame(file: File): Frame = DataInputStream(GZIPInputStream(file.inputStream()).buffered()).use { input ->
        val width = input.readInt()
        val height = input.readInt()
        Frame(width, height, IntArray(width * height) { input.readInt() })
    }

    private fun meiki(): MeikiOcr? {
        val hub = File(System.getProperty("user.home"), ".cache/huggingface/hub")
        fun model(repo: String, name: String) =
            File(hub, "models--rtr46--$repo/snapshots").listFiles()?.map { File(it, name) }?.firstOrNull { it.isFile }
        val det = model("meiki.text.detect.v0", "meiki.text.detect.v0.1.960x544.onnx") ?: return null
        val rec = model("meiki.txt.recognition.v0", "meiki.text.rec.v0.960x32.onnx") ?: return null
        val vrec = model("meiki.txt.recognition.v0", "meiki.text.rec.v0.vertical.32x480.onnx")
        val env = OrtEnvironment.getEnvironment()
        return MeikiOcr(env, env.createSession(det.path), env.createSession(rec.path), vrec?.let { env.createSession(it.path) })
    }

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun dump(lines: List<TextLine>): String = lines.joinToString(",", "[", "]") { line ->
        "{\"text\":${json(line.text)},\"box\":[${line.box.left},${line.box.top},${line.box.right},${line.box.bottom}]," +
            "\"vertical\":${line.vertical},\"rows\":${line.rows.size},\"chars\":${line.chars.size}}"
    }

    @Test
    fun writeReport() {
        val dir = System.getenv("LFG_CORPUS")?.let(::File)
        assumeTrue("LFG_CORPUS is not set", dir != null && dir.isDirectory)
        val ocr = meiki()
        val out = StringBuilder("{")
        var first = true
        for (file in dir!!.listFiles { f -> f.name.endsWith(".argb.gz") }!!.sortedBy { it.name }) {
            val name = file.name.removeSuffix(".argb.gz")
            val picture = frame(file)
            val raw = LinkedHashMap<String, List<TextLine>>()
            if (ocr != null) {
                val start = System.nanoTime()
                raw["meiki"] = ocr.read(picture)
                raw["meiki"]!!.also { System.err.println("meiki $name ${(System.nanoTime() - start) / 1_000_000} ms") }
            }
            File(dir, "$name.screenai.pb").takeIf { it.isFile }?.let { raw["screenai"] = ScreenAi.decode(it.readBytes()) }
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
