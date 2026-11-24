package io.github.beesan.lfg

import io.github.beesan.lfg.core.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Updates.newer("0.1.10", "0.1.9"))
        assertTrue(Updates.newer("v0.2.0", "0.1.7"))
        assertTrue(Updates.newer("0.1.7.1", "0.1.7"))
        assertFalse(Updates.newer("0.1.7", "0.1.7"))
        assertFalse(Updates.newer("0.1.6", "0.1.7"))
    }
}
