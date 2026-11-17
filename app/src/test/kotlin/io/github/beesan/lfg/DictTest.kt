package io.github.beesan.lfg

import io.github.beesan.lfg.dict.DictJson
import io.github.beesan.lfg.dict.GlossaryText
import io.github.beesan.lfg.dict.ImportOutcome
import io.github.beesan.lfg.dict.JmdictSource
import io.github.beesan.lfg.dict.LookupResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictTest {
    // Copied from a lookup of 見た in JMdict [2026-11-17] through lfg_dict_host.
    private val jmdictSense = """[{"content":{"content":[{"content":"to see","tag":"li"},{"content":"to look","tag":"li"},{"content":"to watch","tag":"li"}],"data":{"content":"glossary"},"lang":"en","style":{"listStyleType":"circle"},"tag":"ul"},"type":"structured-content"}]"""
    private val jmdictSingle = """[{"content":{"content":{"content":"deep in the forest","tag":"li"},"data":{"content":"glossary"},"lang":"en","style":{"listStyleType":"circle"},"tag":"ul"},"type":"structured-content"}]"""
    private val jmdictRedirect = """[{"content":{"content":["⟶",{"content":"叱り","href":"?query=叱り&wildcards=off","lang":"ja","tag":"a"}],"style":{"fontSize":"130%"},"tag":"span"},"type":"structured-content"}]"""

    @Test
    fun aJmdictRowIsOneSenseWithItsGlossesJoined() {
        assertEquals(listOf("to see; to look; to watch"), GlossaryText.senses(jmdictSense))
        assertEquals(listOf("deep in the forest"), GlossaryText.senses(jmdictSingle))
        assertEquals(listOf("⟶叱り"), GlossaryText.senses(jmdictRedirect))
    }

    @Test
    fun notesAndReferencesAreNotPartOfTheMeaning() {
        val json = """[{"type":"structured-content","content":[
            {"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"to meet"}]},
            {"tag":"ul","data":{"content":"notes"},"content":[{"tag":"li","content":"usually kana"}]},
            {"tag":"ul","data":{"content":"references"},"content":[{"tag":"li","content":[{"tag":"a","content":"会う"},{"tag":"span","data":{"content":"refGlosses"},"content":"to meet"}]}]}
        ]}]"""
        assertEquals(listOf("to meet"), GlossaryText.senses(json))
    }

    @Test
    fun jmdictsTableOfWrittenFormsIsNotASense() {
        // Copied from a lookup of 近づいたら in JMdict [2026-11-17].
        val forms = """[{"content":{"content":[{"content":[{"content":"","tag":"th"},{"content":"近づく","style":{"textAlign":"center"},"tag":"th"}],"tag":"tr"},{"content":[{"content":"ちかづく","tag":"th"},{"content":"★","tag":"td"}],"tag":"tr"}],"tag":"table"},"type":"structured-content"}]"""
        assertEquals(emptyList<String>(), GlossaryText.senses(forms))
    }

    @Test
    fun plainStringGlossariesAreJoined() {
        assertEquals(listOf("to eat; to consume"), GlossaryText.senses("""["to eat","to consume"]"""))
        assertEquals(listOf("to eat"), GlossaryText.senses("""[{"type":"text","text":"to eat"},{"type":"image","path":"a.png"}]"""))
    }

    @Test
    fun aNumberedListIsAListOfSenses() {
        val json = """[{"type":"structured-content","content":{"tag":"ol","content":[
            {"tag":"li","content":[{"tag":"span","content":"strange"},{"tag":"ul","content":[{"tag":"li","content":"odd"},{"tag":"li","content":"curious"}]}]},
            {"tag":"li","content":["wonder",{"tag":"br"},{"tag":"ruby","content":["謎",{"tag":"rt","content":"なぞ"}]}]}
        ]}}]"""
        assertEquals(listOf("strange odd; curious", "wonder 謎"), GlossaryText.senses(json))
    }

    @Test
    fun brokenOrEmptyGlossariesDoNotThrow() {
        assertEquals(emptyList<String>(), GlossaryText.senses(""))
        assertEquals(listOf("not json"), GlossaryText.senses("not json"))
        assertEquals(emptyList<String>(), GlossaryText.senses("[]"))
    }

    @Test
    fun lookupJsonFromTheEngineParses() {
        val json = """{"results":[{"matched":"見た","deinflected":"見る","trace":[{"name":"-た","description":"past"}],"expression":"見る","reading":"みる","rules":"v1","score":1,"glossaries":[{"dictionary":"JMdict [2026-11-17]","glossary":${quote(jmdictSense)},"definitionTags":"1 v1 vt","termTags":"⭐ ichi"},{"dictionary":"JMdict [2026-11-17]","glossary":${quote(jmdictSingle)},"definitionTags":"","termTags":""}],"frequencies":[]}]}"""
        val entry = DictJson.decodeFromString<LookupResponse>(json).results.single()
        assertEquals("見る", entry.expression)
        assertEquals("見た → 見る · -た", entry.path)
        assertEquals(listOf("to see; to look; to watch", "deep in the forest"), entry.senses())
        assertEquals(listOf("JMdict [2026-11-17]"), entry.sources)
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // The shape and the sizes GitHub returned for yomidevs/jmdict-yomitan on 17 November 2026.
    private val releases = """[
      {"tag_name":"2026-11-17","draft":false,"prerelease":false,"assets":[
        {"name":"JMnedict.zip","size":11434497,"browser_download_url":"https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-11-17/JMnedict.zip"},
        {"name":"JMdict_english.zip","size":15637366,"browser_download_url":"https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-11-17/JMdict_english.zip"}]},
      {"tag_name":"2026-11-16","draft":false,"prerelease":false,"assets":[
        {"name":"JMdict_english.zip","size":2303,"browser_download_url":"https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-11-16/JMdict_english.zip"}]},
      {"tag_name":"2026-11-15","draft":false,"prerelease":false,"assets":[
        {"name":"JMdict_english.zip","size":15631326,"browser_download_url":"https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-11-15/JMdict_english.zip"}]},
      {"tag_name":"2026-11-14","draft":true,"prerelease":false,"assets":[
        {"name":"JMdict_english.zip","size":15631326,"browser_download_url":"https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-11-14/JMdict_english.zip"}]}
    ]"""

    @Test
    fun stubReleasesAreSkipped() {
        val picked = JmdictSource.candidates(releases)
        assertEquals(listOf("2026-11-17", "2026-11-15"), picked.map { it.release })
        assertEquals(15637366L, picked[0].size)
        assertTrue(picked[0].url.endsWith("/2026-11-17/JMdict_english.zip"))
    }

    @Test
    fun aStubOnTopFallsBackToThePreviousDay() {
        val stubFirst = releases.replace("\"size\":15637366", "\"size\":2303")
        assertEquals(listOf("2026-11-15"), JmdictSource.candidates(stubFirst).map { it.release })
    }

    @Test
    fun unusableReleaseListsGiveNothing() {
        assertEquals(emptyList<JmdictSource.Asset>(), JmdictSource.candidates("""{"message":"API rate limit exceeded"}"""))
        assertEquals(emptyList<JmdictSource.Asset>(), JmdictSource.candidates("<html>"))
    }

    @Test
    fun anImportIsCheckedByItsEntryCount() {
        assertTrue(JmdictSource.plausible(ImportOutcome(success = true, title = "JMdict", terms = 527_999)))
        assertFalse(JmdictSource.plausible(ImportOutcome(success = true, title = "JMdict", terms = 12)))
        assertFalse(JmdictSource.plausible(ImportOutcome(success = false, terms = 527_999)))
    }
}
