package io.github.beesan.lfg

import io.github.beesan.lfg.backup.Backup
import io.github.beesan.lfg.backup.BackupWorker
import io.github.beesan.lfg.data.GameLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

class BackupTest {
    private fun line(id: String, text: String) = GameLine(
        id = id, gameId = "game-1", gameName = "Quest, part 2", lineText = text,
        timestamp = 1_793_620_800.5, createdAt = 0, lastModified = 0, source = "ocr", charCount = text.length,
    )

    @Test
    fun theLinesFileIsTheCsvGsmsExStaticImportReads() {
        val out = StringWriter()
        Backup.exStaticCsv(listOf(line("l1", "「行こう」と言った。"), line("l2", "He said \"go\"")), out)
        assertEquals(
            "uuid,given_identifier,name,line,time\n" +
                "game-1,l1,\"Quest, part 2\",「行こう」と言った。,1.7936208005E9\n" +
                "game-1,l2,\"Quest, part 2\",\"He said \"\"go\"\"\",1.7936208005E9\n",
            out.toString(),
        )
    }

    @Test
    fun theExportIsASpreadsheetOfTimeGameTextAndCharacters() {
        val out = StringWriter()
        Backup.linesCsv(listOf(line("l1", "新しい剣")), out, ZoneOffset.UTC)
        assertEquals("time,game,text,characters\n2026-11-02 12:00:00,\"Quest, part 2\",新しい剣,4\n", out.toString())
    }

    @Test
    fun automaticBackupsStartAtTheNextFourOClock() {
        val zone = ZoneOffset.UTC
        assertEquals(Duration.ofHours(2), BackupWorker.untilFour(ZonedDateTime.of(2026, 11, 19, 2, 0, 0, 0, zone)))
        assertEquals(Duration.ofHours(23), BackupWorker.untilFour(ZonedDateTime.of(2026, 11, 19, 5, 0, 0, 0, zone)))
        assertEquals(Duration.ofHours(24), BackupWorker.untilFour(ZonedDateTime.of(2026, 11, 19, 4, 0, 0, 0, zone)))
        assertEquals("lfg-backup-2026-11-19-0400.zip".length, Backup.fileName().length)
    }
}
