// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.provider.Settings as AndroidSettings
import io.github.beesan.lfg.capture.Readers
import io.github.beesan.lfg.capture.VocrProbe
import io.github.beesan.lfg.dict.DictState
import io.github.beesan.lfg.ocr.ModelState
import io.github.beesan.lfg.ocr.ScreenAi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text report for a bug report or a first run on a new handheld: the
 * device, what LFG has installed and running, how much it keeps, and the
 * app's own recent log, where the reading pipeline writes its step timings
 * (LfgPerf) and any warnings. Nothing leaves the device unless the person
 * copies or shares it, and it holds no key and no line that was read.
 */
object Diagnostics {
    suspend fun report(app: App): String = buildString {
        val prefs = app.settings.prefs.value
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val metrics = app.resources.displayMetrics
        line("LFG", "${info.versionName} (${info.longVersionCode})" + if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) ", debug build" else "")
        line("When", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()))
        line("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
        line("Screen", "${metrics.widthPixels}x${metrics.heightPixels}, ${metrics.densityDpi} dpi")
        line("Memory", "${Runtime.getRuntime().maxMemory() / 1_048_576} MB heap limit")
        append('\n')

        val enabled = AndroidSettings.Secure.getString(app.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        line("Screen reading", if (app.packageName in enabled) "on" else "off")
        line("Text recognition", "ScreenAI " + when {
            !ScreenAi.available() -> "not in this build"
            else -> when (val s = app.models.screenAi.value) {
                ModelState.Ready -> "installed"
                ModelState.Missing -> "not downloaded"
                is ModelState.Downloading -> "downloading, ${s.bytes / 1_048_576} of ${s.total / 1_048_576} MB"
                is ModelState.Failed -> "failed: ${s.reason}"
            }
        })
        line("Reader open", Readers.reader?.name ?: "none")
        line("Change probe", if (Readers.probe is VocrProbe) "VOCR" else "pixels (VOCR did not load)")
        line("Dictionaries", when (val d = app.dictionaries.state.value) {
            is DictState.Ready -> d.dictionaries.joinToString { it.title }.ifEmpty { "none" }
            is DictState.Downloading -> "downloading ${d.name}"
            is DictState.Installing -> "installing ${d.name}"
            is DictState.Failed -> "failed: ${d.reason}"
            DictState.Missing -> "none"
        } + if (app.dictionaries.defaultsPending()) " (starter set still downloading)" else "")
        line("AnkiDroid", if (runCatching { app.packageManager.getPackageInfo("com.ichi2.anki", 0) }.isSuccess) "installed" else "not installed")
        line(
            "Settings",
            "shortcut ${prefs.shortcut}, second screen ${prefs.secondScreenPanel}, sentence audio ${prefs.sentenceAudio}, " +
                "animated pictures ${prefs.animatedPicture}, screens kept ${if (prefs.keepScreensDays == 0) "always" else "${prefs.keepScreensDays} days"}, " +
                "translation ${if (prefs.aiReady) "on" else "off"}",
        )
        val usage = app.pictures.usage(app.getDatabasePath("lfg.db"))
        line(
            "Storage",
            "${mb(usage.total)} MB: screens ${mb(usage.screens)} (${usage.screenCount}, ${usage.keptScreens} kept for good), " +
                "crops ${mb(usage.crops)}, cards ${mb(usage.cards)}, notes ${mb(usage.notes)}, dictionaries ${mb(usage.dictionaries)}, " +
                "text recognition ${mb(usage.reader)}, database ${mb(usage.database)}",
        )
        line("Free space", "${mb(app.filesDir.usableSpace)} MB")
        append("\nRecent log (this app only):\n")
        append(log())
    }

    private fun StringBuilder.line(name: String, value: String) = append(name).append(": ").append(value).append('\n')

    private fun mb(bytes: Long) = (bytes + 524_288) / 1_048_576

    /** The last lines this process wrote to the system log. An app may read its own without any permission. */
    private fun log(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-t", "400", "--pid", Process.myPid().toString(), "-v", "time")
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.destroy() }.ifBlank { "(empty)\n" }
    } catch (e: Exception) {
        "(could not read the log: ${e.message})\n"
    }
}
