// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * A newer LFG on GitHub's releases, asked about at most once a day. When there
 * is one, Home suggests it; the button downloads the APK for this phone.
 */
object Updates {
    private const val TAG = "LfgUpdates"
    private const val LATEST = "https://api.github.com/repos/bee-san/LearnFromGames/releases/latest"
    private const val PREFS = "updates"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A release newer than this build: its version without the "v", and the link to download. */
    data class Release(val version: String, val url: String)

    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available

    /** Looks for a newer release, once a day; a debug build never asks. */
    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return@withContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked", 0) >= DAY_MS) {
            fetch()?.let { latest ->
                prefs.edit {
                    putLong("checked", now)
                    putString("version", latest.version)
                    putString("url", latest.url)
                }
            }
        }
        val version = prefs.getString("version", null) ?: return@withContext
        val url = prefs.getString("url", null) ?: return@withContext
        _available.value = Release(version, url).takeIf { newer(version, installed) }
    }

    private fun fetch(): Release? = try {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != 200) null
            else parse(connection.inputStream.use { it.readBytes().decodeToString() }, Build.SUPPORTED_ABIS.toList())
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "No word of a newer release: ${e.message}")
        null
    }

    /** The release in GitHub's answer: the APK for the first ABI this phone runs, or the release page. */
    fun parse(json: String, abis: List<String>): Release? {
        val release = Json.parseToJsonElement(json).jsonObject
        val tag = release["tag_name"]?.jsonPrimitive?.content ?: return null
        val page = release["html_url"]?.jsonPrimitive?.content ?: return null
        val apks = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.name().endsWith(".apk") }
        val apk = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name().endsWith("-$abi.apk") } }
        return Release(tag.removePrefix("v"), apk?.get("browser_download_url")?.jsonPrimitive?.content ?: page)
    }

    private fun JsonObject.name() = this["name"]?.jsonPrimitive?.content ?: ""

    /** Whether [latest] is a later version than [installed], number by number: 0.1.10 is after 0.1.9. */
    fun newer(latest: String, installed: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
