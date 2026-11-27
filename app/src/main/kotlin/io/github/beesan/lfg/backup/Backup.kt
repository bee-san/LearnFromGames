// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.Profiles
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.LfgDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup is one zip: the database (lines, so the stats, notes, recaps,
 * cards waiting for AnkiDroid), the settings (never the OpenRouter key,
 * which is kept apart), and, if wanted, the pictures of lines and notes.
 * Automatic backups go to a folder picked in Settings, the newest few kept;
 * each also writes [LINES_FILE], every line in the CSV GameSentenceMiner's
 * ExStatic import reads, so a sync app can carry it to a desktop.
 */
object Backup {
    const val PREFIX = "lfg-backup-"
    const val LINES_FILE = "lfg-lines.csv"
    private const val DB = "lfg.db"
    private const val SETTINGS = "settings.xml"

    /** The folders under the app's files that hold pictures: the screen's, each line's own, and notes'. */
    private val PICTURES = listOf("frames", "crops", "notes")

    /** The words the person knows, a list for each language ([io.github.beesan.lfg.KnownWords]): always carried, pictures or not. */
    private const val KNOWN = "known-words"

    /** Whether a settings file is the profiles' list, or the settings of a profile not in use ([Profiles]). */
    internal fun profiles(name: String) = name == Profiles.STORE + ".xml" || PROFILE.matches(name)

    private val PROFILE = Regex(Regex.escape(Profiles.FILE_PREFIX) + "\\d+\\.xml")

    fun fileName(now: Date = Date()) = PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(now) + ".zip"

    /** Writes a backup to [out]. */
    fun write(context: Context, db: LfgDatabase, out: OutputStream, pictures: Boolean) {
        // Everything still in the write-ahead log goes into the database file first, so the copy is whole.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        ZipOutputStream(out.buffered()).use { zip ->
            add(zip, DB, context.getDatabasePath(DB))
            val stores = File(context.applicationInfo.dataDir, "shared_prefs")
            add(zip, SETTINGS, File(stores, SETTINGS))
            // The profiles not in use, each in its own file, and the list of them.
            stores.listFiles()?.filter { profiles(it.name) }?.forEach { add(zip, it.name, it) }
            File(context.filesDir, KNOWN).listFiles()?.forEach { add(zip, "files/$KNOWN/${it.name}", it) }
            if (pictures) {
                for (dir in PICTURES) File(context.filesDir, dir).listFiles()?.forEach { add(zip, "files/$dir/${it.name}", it) }
            }
        }
    }

    private fun add(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Replaces the database, settings and pictures with those in [input]. The
     * database is closed, so the app must start again straight after.
     */
    fun restore(context: Context, db: LfgDatabase, input: InputStream) {
        val dir = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        val root = dir.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(dir, entry.name)
                // A zip from elsewhere must not write outside its own folder ("zip slip").
                if (!target.canonicalPath.startsWith(root)) throw IOException("This file isn’t an LFG backup.")
                if (entry.isDirectory) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        val database = File(dir, DB)
        if (!database.isFile) {
            dir.deleteRecursively()
            throw IOException("This file isn’t an LFG backup.")
        }
        db.close()
        val live = context.getDatabasePath(DB)
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        database.copyTo(live, overwrite = true)
        val stores = File(context.applicationInfo.dataDir, "shared_prefs")
        File(dir, SETTINGS).takeIf { it.isFile }?.copyTo(File(stores, SETTINGS), overwrite = true)
        // The backup's profiles take the place of the ones here; a backup from before profiles leaves the one its settings are.
        stores.listFiles()?.filter { profiles(it.name) }?.forEach { it.delete() }
        dir.listFiles()?.filter { it.isFile && profiles(it.name) }?.forEach { it.copyTo(File(stores, it.name), overwrite = true) }
        // A backup from before the lists leaves the ones here as they are.
        File(dir, "files/$KNOWN").listFiles()?.let { lists ->
            val into = File(context.filesDir, KNOWN).apply { mkdirs() }
            lists.forEach { it.copyTo(File(into, it.name), overwrite = true) }
        }
        for (name in PICTURES) {
            val into = File(context.filesDir, name).apply { mkdirs() }
            File(dir, "files/$name").listFiles()?.forEach { it.copyTo(File(into, it.name), overwrite = true) }
        }
        dir.deleteRecursively()
    }

    /** An automatic backup into [Prefs.backupFolder], the oldest past [Prefs.backupKeep] deleted, then the lines file. Returns the backup's size. */
    suspend fun toFolder(context: Context, db: LfgDatabase, prefs: Prefs): Long {
        val resolver = context.contentResolver
        val name = fileName()
        val tree = Uri.parse(prefs.backupFolder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("Android would not make a file in the backup folder. Choose the folder again.")
        (resolver.openOutputStream(doc) ?: throw IOException("The backup folder can’t be written to.")).use { write(context, db, it, prefs.backupPictures) }
        val children = children(resolver, tree)
        if (prefs.backupKeep > 0) {
            children.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".zip") }.sortedBy { it.name }
                .dropLast(prefs.backupKeep).forEach { DocumentsContract.deleteDocument(resolver, it.uri) }
        }
        if (prefs.gsmFile) {
            val lines = children.firstOrNull { it.name == LINES_FILE }?.uri
                ?: DocumentsContract.createDocument(resolver, parent, "text/csv", LINES_FILE)
                ?: throw IOException("Android would not make the lines file. Choose the folder again.")
            // "wt": written whole each time; GSM's import adds only the lines it has not seen.
            val all = db.lines().all()
            (resolver.openOutputStream(lines, "wt") ?: throw IOException("The lines file can’t be written to.")).bufferedWriter().use { exStaticCsv(all, it) }
        }
        return children.firstOrNull { it.name == name }?.size ?: 0
    }

    private class Child(val uri: Uri, val name: String, val size: Long)

    private fun children(resolver: ContentResolver, tree: Uri): List<Child> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        val out = ArrayList<Child>()
        resolver.query(list, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1).orEmpty(), if (c.isNull(2)) 0 else c.getLong(2))
            }
        }
        return out
    }

    /** "Documents › LFG backups" for a picked folder's link. */
    fun folderName(folder: String): String {
        if (folder.isEmpty()) return ""
        val id = try {
            DocumentsContract.getTreeDocumentId(Uri.parse(folder))
        } catch (_: Exception) {
            return folder
        }
        return id.substringAfter(':').split('/').filter { it.isNotEmpty() }.joinToString(" › ").ifEmpty { id.substringBefore(':') }
    }

    /** Every line as a spreadsheet: when it was read, its game, its text and its characters. */
    fun linesCsv(lines: List<GameLine>, out: Writer, zone: ZoneId = ZoneId.systemDefault()) {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        out.write("time,game,text,characters\n")
        for (line in lines) {
            out.write(time.format(Instant.ofEpochMilli((line.timestamp * 1000).toLong())) + "," + field(line.gameName) + "," + field(line.lineText) + "," + line.charCount + "\n")
        }
    }

    /**
     * Every line in the CSV GameSentenceMiner's ExStatic import reads
     * (GameSentenceMiner/web/import_api.py): the game's id, the line's id, the
     * game's name, the text, and the time in seconds since 1970. It skips the
     * lines it already has by game and line id.
     */
    fun exStaticCsv(lines: List<GameLine>, out: Writer) {
        out.write("uuid,given_identifier,name,line,time\n")
        for (line in lines) {
            out.write(field(line.gameId) + "," + field(line.id) + "," + field(line.gameName) + "," + field(line.lineText) + "," + line.timestamp + "\n")
        }
    }

    /** A CSV field, quoted when it holds a comma, a quote or a line break. */
    fun field(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}
