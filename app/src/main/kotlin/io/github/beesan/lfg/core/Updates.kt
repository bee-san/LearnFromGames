// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import io.github.beesan.lfg.update.InstallReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * LFG's own newer versions, from GitHub's releases. Three steps, each callable alone:
 * [check] asks GitHub (once a day as the app opens, and daily from
 * [io.github.beesan.lfg.update.UpdateWorker]), [download] fetches the APK for this device into
 * the cache, and [install] hands it to Android. Installing closes LFG and stops a game being
 * read, so it only ever follows a tap, never the worker. [state] is what the screens show.
 */
object Updates {
    private const val TAG = "LfgUpdates"

    /** The one place GitHub is asked: point it at a stand-in server to try the updater out. */
    private const val LATEST = "https://api.github.com/repos/bee-san/LearnFromGames/releases/latest"
    private const val PREFS = "updates"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** How long "installing" is believed with no word from Android: its question left unanswered would otherwise hold the button for good. */
    private const val INSTALL_MS = 60_000L

    /** The least Android's question is up for: LFG's pages in front again any sooner are only what was behind it as it opened. */
    private const val QUESTION_MS = 2_000L

    /**
     * A release: its version without the "v", and the link to its APK for this device, or to
     * its page when it has none. With the APK come GitHub's [digest] of it ("sha256:…", null
     * when GitHub gave none) and its [size] in bytes (0 when not given).
     */
    data class Release(val version: String, val url: String, val digest: String? = null, val size: Long = 0) {
        /** Whether [url] is an APK LFG can fetch and install; otherwise it is a page for the browser. */
        val apk: Boolean get() = url.substringBefore('?').endsWith(".apk", ignoreCase = true)
    }

    sealed interface State {
        val release: Release?

        /** Nothing newer than what is installed. */
        data object None : State {
            override val release: Release? get() = null
        }

        /** A newer release that is not here yet: waiting to be fetched, or one with no APK for this device. */
        data class Available(override val release: Release) : State

        /** Being fetched; [progress] goes from 0 to 1. */
        data class Downloading(override val release: Release, val progress: Float) : State

        /** Fetched and checked: [file] waits for a tap on Install. */
        data class Ready(override val release: Release, val file: File) : State

        /** Handed to Android, which ends LFG when it is done. */
        data class Installing(override val release: Release) : State

        /** Fetching or installing went wrong; [reason] is a few plain words. The browser can still get it. */
        data class Failed(override val release: Release, val reason: String) : State
    }

    /** What [check] found. */
    enum class Checked {
        /** There is a newer release; [state] has it. */
        NEWER,
        UP_TO_DATE,

        /** GitHub could not be reached. */
        OFFLINE,

        /** GitHub answered, but not with a release. */
        FAILED,

        /** A debug build never asks. */
        SKIPPED,
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state

    /** Held while a download runs, so the worker and the app never fetch the same file twice at once. */
    private val lock = Mutex()

    @Volatile
    private var installStarted = 0L

    /** How long after LFG put a version of itself in its pages are still opened again. */
    private const val REOPEN_MS = 120_000L

    /** Installs given up to begin another: what Android says of them is not about the one going in. */
    private val abandoned: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    /** Its pages were opened again already, in this run of the app. */
    @Volatile
    private var reopened = false

    /** The version name of the LFG installed. */
    fun installed(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** A debug build never asks GitHub: it is signed with another key, so no release could go over it. */
    private fun asks(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun folder(context: Context) = File(context.cacheDir, "update")

    /**
     * Looks for a newer release and brings [state] up to date. GitHub is asked at most once a
     * day, unless [force] says to ask now ("Check now" in Settings, and the daily worker).
     */
    suspend fun check(context: Context, force: Boolean = false): Checked = withContext(Dispatchers.IO) {
        if (!asks(context)) return@withContext Checked.SKIPPED
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        var problem: Checked? = null
        if (force || now - prefs.getLong("checked", 0) >= DAY_MS) {
            try {
                val latest = fetch()
                if (latest == null) {
                    problem = Checked.FAILED
                } else {
                    prefs.edit {
                        putLong("checked", now)
                        putString("version", latest.version)
                        putString("url", latest.url)
                        putString("digest", latest.digest)
                        putLong("size", latest.size)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No word of a newer release: ${e.message}")
                problem = if (e is IOException) Checked.OFFLINE else Checked.FAILED
            }
        }
        settle(context)
        if (_state.value !is State.None) Checked.NEWER else problem ?: Checked.UP_TO_DATE
    }

    /** GitHub's latest release, or null when it answered with something else. Throws when it cannot be reached. */
    private fun fetch(): Release? {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != 200) return null
            return parse(connection.inputStream.use { it.readBytes().decodeToString() }, Build.SUPPORTED_ABIS.toList())
        } finally {
            connection.disconnect()
        }
    }

    /** The release GitHub last told of, newer or not. */
    private fun known(context: Context): Release? {
        val prefs = prefs(context)
        val version = prefs.getString("version", null) ?: return null
        val url = prefs.getString("url", null) ?: return null
        return Release(version, url, prefs.getString("digest", null), prefs.getLong("size", 0))
    }

    /** The release to fetch: newer than what is installed, with an APK for this device. */
    private fun wanted(context: Context): Release? = known(context)?.takeIf { it.apk && newer(it.version, installed(context)) }

    /** [state] with nothing under way. A download is left alone, and so is an install just begun. */
    private fun settle(context: Context) {
        if (lock.isLocked) return
        if (_state.value is State.Installing && System.currentTimeMillis() - installStarted < INSTALL_MS) return
        _state.value = rest(context)
    }

    /** The state from what GitHub last said and what the cache holds; downloads no longer wanted are deleted on the way. */
    private fun rest(context: Context): State {
        val wanted = wanted(context)
        val file = wanted?.let { File(folder(context), fileName(it.version)) }
        tidy(folder(context), file)
        val downloaded = if (wanted != null && file != null && file.isFile && whole(file.length(), wanted)) file else null
        return resting(known(context), installed(context), downloaded)
    }

    private fun tidy(folder: File, keep: File?) {
        for (name in stale(folder.list()?.toList().orEmpty(), keep?.name)) File(folder, name).delete()
    }

    /**
     * Fetches the newer release's APK into the cache, unless it is already there whole. The
     * file is written beside its final name and renamed once it is complete and matches
     * GitHub's digest. False only when it was tried and failed, so trying again is worth it.
     */
    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (_state.value is State.Installing && System.currentTimeMillis() - installStarted < INSTALL_MS) return@withContext true
            val release = wanted(context)
            if (release == null) {
                _state.value = rest(context)
                return@withContext true
            }
            val dir = folder(context)
            val file = File(dir, fileName(release.version))
            tidy(dir, file)
            if (file.isFile && whole(file.length(), release)) {
                _state.value = State.Ready(release, file)
                return@withContext true
            }
            val part = File(dir, file.name + ".part")
            _state.value = State.Downloading(release, 0f)
            try {
                dir.mkdirs()
                var shown = 0
                fetchTo(release.url, part) { got, length ->
                    // Stopped by WorkManager, as when the network goes: no more is read.
                    ensureActive()
                    val total = if (length > 0) length else release.size
                    val percent = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0
                    if (percent != shown) {
                        shown = percent
                        _state.value = State.Downloading(release, percent / 100f)
                    }
                }
                if (!whole(part.length(), release) || !matches(part, release.digest)) throw IOException("The file is not the one GitHub describes")
                if (!part.renameTo(file)) throw IOException("The file could not be renamed")
                _state.value = State.Ready(release, file)
                true
            } catch (e: CancellationException) {
                part.delete()
                _state.value = State.Available(release)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LFG ${release.version} was not fetched: $e")
                part.delete()
                file.delete()
                _state.value = State.Failed(release, "Could not get LFG ${release.version}")
                false
            }
        }
    }

    private fun fetchTo(url: String, to: File, progress: (got: Long, length: Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        got += read
                        progress(got, length)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hands the fetched APK to Android. Only ever called for a tap: the install ends LFG, and
     * with it the reading of a game. Android asks the person the first time; once LFG has
     * installed itself, later versions go in without a question. What comes of it reaches
     * [InstallReceiver], and from there [installEnded].
     */
    suspend fun install(context: Context): Unit = withContext(Dispatchers.IO) {
        val ready = _state.value as? State.Ready ?: return@withContext
        installStarted = System.currentTimeMillis()
        _state.value = State.Installing(ready.release)
        try {
            val installer = context.packageManager.packageInstaller
            // One left behind by a question nobody answered. Android reports each as a failure, which is not this install's.
            for (old in installer.mySessions) {
                abandoned += old.sessionId
                runCatching { installer.abandonSession(old.sessionId) }
            }
            val length = ready.file.length()
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(length)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            val session = installer.openSession(id)
            try {
                session.openWrite("lfg.apk", 0, length).use { output ->
                    ready.file.inputStream().use { it.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                // Android fills in what happened, so the intent is mutable, and names its receiver, as Android 14 asks of a mutable one.
                val said = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                // Written before Android is told to go ahead: LFG is ended soon after, and this must outlast it.
                prefs(context).edit(commit = true) { putString("installing", ready.release.version) }
                session.commit(said.intentSender)
            } catch (e: Exception) {
                runCatching { session.abandon() }
                throw e
            } finally {
                runCatching { session.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LFG ${ready.release.version} was not installed: $e")
            prefs(context).edit { remove("installing") }
            installStarted = 0
            _state.value = State.Failed(ready.release, "Could not install LFG ${ready.release.version}")
            return@withContext
        }
        // Android says nothing of a question left unanswered: the button comes back by itself.
        delay(INSTALL_MS)
        settle(context)
    }

    /**
     * LFG's pages are in front again. An install that was going in would have ended LFG by now:
     * Android's question was backed out of, and Android does not say so. The button comes back.
     */
    fun inFront(context: Context) {
        if (_state.value is State.Installing && System.currentTimeMillis() - installStarted >= QUESTION_MS && !lock.isLocked) _state.value = rest(context)
    }

    /**
     * What Android said of an install that did not go through ([PackageInstaller]'s [status] and
     * its own [message], which is kept in the log: it is not words for a person). [session] is the
     * install it speaks of: one [install] gave up itself, to begin again, is not heard.
     */
    fun installEnded(context: Context, status: Int, message: String?, session: Int = -1) {
        if (status == PackageInstaller.STATUS_SUCCESS || abandoned.remove(session)) return
        Log.w(TAG, "Not installed ($status): $message")
        prefs(context).edit { remove("installing") }
        installStarted = 0
        val release = _state.value.release ?: wanted(context) ?: return
        // Backed out of Android's question: the file is still there, and so is the button.
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            if (!lock.isLocked) _state.value = rest(context)
            return
        }
        // A file Android cannot read is fetched again another time.
        if (status == PackageInstaller.STATUS_FAILURE_INVALID) File(folder(context), fileName(release.version)).delete()
        _state.value = State.Failed(release, installProblem(status))
    }

    /** A failed install in a few plain words. */
    fun installProblem(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not for this device"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked it"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Does not fit over this LFG"
        else -> "Could not install"
    }

    /**
     * LFG was replaced by another build: what was downloaded is deleted. True when the build
     * now installed is the one [install] asked for, and not one put on from a desk.
     */
    fun updated(context: Context): Boolean {
        val prefs = prefs(context)
        val asked = prefs.getString("installing", null)
        val own = asked != null && !newer(asked, installed(context))
        prefs.edit {
            remove("installing")
            // For the reading service, should it start after this: it is what may open LFG's pages again.
            if (own) putLong("updated", System.currentTimeMillis())
        }
        tidy(folder(context), null)
        return own
    }

    /**
     * Whether LFG has just put a newer version of itself in, and its pages are to be opened again
     * where the person left them: true once. Asked by the reading service as it starts, since only
     * an app with such a service may open a page by itself; with the service off, the notification is all.
     */
    fun reopens(context: Context): Boolean {
        val prefs = prefs(context)
        val asked = prefs.getString("installing", null)
        val at = prefs.getLong("updated", 0)
        if (at != 0L) prefs.edit { remove("updated") }
        val just = (asked != null && !newer(asked, installed(context))) || System.currentTimeMillis() - at in 0..REOPEN_MS
        if (!just || reopened) return false
        reopened = true
        return true
    }

    /** The release in GitHub's answer: the APK for the first ABI this phone runs, or the release page. */
    fun parse(json: String, abis: List<String>): Release? {
        val release = Json.parseToJsonElement(json).jsonObject
        val tag = release["tag_name"]?.jsonPrimitive?.content ?: return null
        val page = release["html_url"]?.jsonPrimitive?.content ?: return null
        val apks = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.name().endsWith(".apk") }
        val apk = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name().endsWith("-$abi.apk") } }
        val version = tag.removePrefix("v")
        if (apk == null) return Release(version, page)
        val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return Release(version, page)
        return Release(
            version, url,
            digest = apk["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0,
        )
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

    /** The name a version's APK is kept under. Only what is safe in a file's name is taken from the version. */
    fun fileName(version: String) = "LearnFromGames-" + version.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

    /** Whether a file of [length] bytes can be all of [release]'s APK: GitHub's size, when it gave one. */
    fun whole(length: Long, release: Release) = length > 0 && (release.size <= 0 || length == release.size)

    /** Of the [names] in the download folder, those to delete: all but the APK to [keep] and its unfinished copy. */
    fun stale(names: List<String>, keep: String?): List<String> = names.filter { keep == null || (it != keep && it != "$keep.part") }

    /**
     * The state with nothing under way: nothing when [release] is no newer than [installed],
     * ready when its APK is already [downloaded], and otherwise there to be fetched.
     */
    fun resting(release: Release?, installed: String, downloaded: File?): State = when {
        release == null || !newer(release.version, installed) -> State.None
        downloaded != null -> State.Ready(release, downloaded)
        else -> State.Available(release)
    }

    /** [file]'s SHA-256, in lower-case hex. */
    fun sha256(file: File): String {
        val sum = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sum.update(buffer, 0, read)
            }
        }
        return sum.digest().joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    }

    /**
     * Whether [file] is the one GitHub's [digest] ("sha256:…") describes. With no digest, or
     * one of another kind, there is nothing to hold it to: Android still refuses an APK signed
     * with another key.
     */
    fun matches(file: File, digest: String?): Boolean {
        val wanted = digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: return true
        return sha256(file).equals(wanted, ignoreCase = true)
    }
}
