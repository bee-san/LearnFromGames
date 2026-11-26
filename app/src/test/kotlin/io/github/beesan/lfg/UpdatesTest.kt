package io.github.beesan.lfg

import io.github.beesan.lfg.core.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdatesTest {
    private val release = """
        {"tag_name": "v0.1.7", "html_url": "https://github.com/bee-san/LearnFromGames/releases/tag/v0.1.7",
         "assets": [
           {"name": "LearnFromGames-v0.1.7-arm64-v8a.apk", "browser_download_url": "https://example.com/arm.apk"},
           {"name": "LearnFromGames-v0.1.7-x86_64.apk", "browser_download_url": "https://example.com/x86.apk"}
         ]}
    """

    @Test
    fun theApkIsTheOneForThisPhone() {
        assertEquals(Updates.Release("0.1.7", "https://example.com/arm.apk"), Updates.parse(release, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("https://example.com/x86.apk", Updates.parse(release, listOf("x86_64", "arm64-v8a"))?.url)
    }

    @Test
    fun withNoApkForThisPhoneTheReleasePageOpens() {
        assertEquals("https://github.com/bee-san/LearnFromGames/releases/tag/v0.1.7", Updates.parse(release, listOf("riscv64"))?.url)
    }

    /** GitHub's answer as it is now: each asset with its size and its digest. */
    private val described = """
        {"tag_name": "v0.1.17", "html_url": "https://github.com/bee-san/LearnFromGames/releases/tag/v0.1.17",
         "assets": [
           {"name": "LearnFromGames-v0.1.17-arm64-v8a.apk", "size": 3, "digest": "sha256:$ABC",
            "browser_download_url": "https://github.com/bee-san/LearnFromGames/releases/download/v0.1.17/LearnFromGames-v0.1.17-arm64-v8a.apk"},
           {"name": "LearnFromGames-v0.1.17-x86_64.apk", "size": 70000000, "digest": null,
            "browser_download_url": "https://github.com/bee-san/LearnFromGames/releases/download/v0.1.17/LearnFromGames-v0.1.17-x86_64.apk"}
         ]}
    """

    private fun file(text: String): File = Files.createTempFile("lfg-update", ".apk").toFile().apply {
        deleteOnExit()
        writeText(text)
    }

    @Test
    fun theApkComesWithItsSizeAndDigest() {
        val arm = Updates.parse(described, listOf("arm64-v8a"))!!
        assertEquals("0.1.17", arm.version)
        assertEquals(3L, arm.size)
        assertEquals("sha256:$ABC", arm.digest)
        assertTrue(arm.apk)
        // A digest GitHub left empty is no digest.
        val x86 = Updates.parse(described, listOf("x86_64"))!!
        assertEquals(70_000_000L, x86.size)
        assertNull(x86.digest)
        // Assets from before GitHub described them: the APK, and nothing to hold it to.
        val old = Updates.parse(release, listOf("arm64-v8a"))!!
        assertEquals(0L, old.size)
        assertNull(old.digest)
    }

    @Test
    fun aReleasePageIsNotAnApk() {
        assertFalse(Updates.parse(described, listOf("riscv64"))!!.apk)
        assertTrue(Updates.Release("0.1.17", "https://example.com/a.APK?download=1").apk)
    }

    @Test
    fun aDownloadIsHeldToItsDigest() {
        val abc = file("abc")
        assertEquals(ABC, Updates.sha256(abc))
        assertTrue(Updates.matches(abc, "sha256:$ABC"))
        assertTrue(Updates.matches(abc, " SHA256:${ABC.uppercase()} "))
        assertFalse(Updates.matches(file("abd"), "sha256:$ABC"))
        assertFalse(Updates.matches(abc, "sha256:"))
        // No digest, or one of a kind LFG does not know: nothing to hold it to.
        assertTrue(Updates.matches(abc, null))
        assertTrue(Updates.matches(abc, "md5:900150983cd24fb0d6963f7d28e17f72"))
    }

    @Test
    fun aFileOfTheWrongSizeIsNotTheWholeApk() {
        val sized = Updates.Release("0.1.17", "https://example.com/a.apk", size = 3)
        assertTrue(Updates.whole(3, sized))
        assertFalse(Updates.whole(2, sized))
        assertFalse(Updates.whole(0, sized))
        // GitHub gave no size: anything that is not empty.
        val unsized = Updates.Release("0.1.17", "https://example.com/a.apk")
        assertTrue(Updates.whole(2, unsized))
        assertFalse(Updates.whole(0, unsized))
    }

    @Test
    fun anApkAlreadyDownloadedIsReady() {
        val newer = Updates.Release("0.1.17", "https://example.com/a.apk")
        val apk = File("LearnFromGames-0.1.17.apk")
        assertEquals(Updates.State.Ready(newer, apk), Updates.resting(newer, "0.1.16", apk))
        assertEquals(Updates.State.Available(newer), Updates.resting(newer, "0.1.16", null))
        // Installed, or older than what is installed: nothing, downloaded or not.
        assertEquals(Updates.State.None, Updates.resting(newer, "0.1.17", apk))
        assertEquals(Updates.State.None, Updates.resting(newer, "0.2.0", null))
        assertEquals(Updates.State.None, Updates.resting(null, "0.1.16", null))
    }

    @Test
    fun onlyTheWantedDownloadIsKept() {
        val names = listOf("LearnFromGames-0.1.16.apk", "LearnFromGames-0.1.17.apk", "LearnFromGames-0.1.17.apk.part", "LearnFromGames-0.1.16.apk.part")
        assertEquals(
            listOf("LearnFromGames-0.1.16.apk", "LearnFromGames-0.1.16.apk.part"),
            Updates.stale(names, "LearnFromGames-0.1.17.apk"),
        )
        // Nothing wanted, as once it is installed: all of it goes.
        assertEquals(names, Updates.stale(names, null))
    }

    @Test
    fun aVersionNamesItsFileAndNothingElse() {
        assertEquals("LearnFromGames-0.1.17.apk", Updates.fileName("0.1.17"))
        assertEquals("LearnFromGames-.._.._x.apk", Updates.fileName("../../x"))
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Updates.newer("0.1.10", "0.1.9"))
        assertTrue(Updates.newer("v0.2.0", "0.1.7"))
        assertTrue(Updates.newer("0.1.7.1", "0.1.7"))
        assertFalse(Updates.newer("0.1.7", "0.1.7"))
        assertFalse(Updates.newer("0.1.6", "0.1.7"))
    }

    private companion object {
        /** SHA-256 of "abc". */
        const val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
