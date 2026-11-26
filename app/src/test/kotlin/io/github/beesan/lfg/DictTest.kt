package io.github.beesan.lfg

import io.github.beesan.lfg.dict.DictJson
import io.github.beesan.lfg.dict.GlossaryText
import io.github.beesan.lfg.dict.ImportOutcome
import io.github.beesan.lfg.dict.DefaultDictionaries
import io.github.beesan.lfg.dict.InstalledDictionary
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

    private fun source(id: String) = DefaultDictionaries.byId(id)!!
    private fun installed(title: String) = InstalledDictionary(title, "/x", terms = 1, installedAt = 0)

    @Test
    fun theStarterSetIsHachidorisInItsOrder() {
        assertEquals(
            listOf("jitendex", "jmnedict", "bees-ultimate-kanji-dictionary", "jiten", "bees-ultimate-grammar-dictionary"),
            DefaultDictionaries.of(io.github.beesan.lfg.core.Language.JAPANESE).map { it.id },
        )
        assertTrue(DefaultDictionaries.of(io.github.beesan.lfg.core.Language.JAPANESE).all { it.url.startsWith("https://") })
    }

    @Test
    fun anInstalledDictionaryIsKnownByItsTitle() {
        assertTrue(source("jitendex").matches(installed("Jitendex.org [2026-08-11]")))
        assertFalse(source("jitendex").matches(installed("JMdict [2026-11-17]")))
        assertTrue(source("jmnedict").matches(installed("JMnedict [2026-09-04]")))
        assertTrue(source("jiten").matches(installed("Jiten")))
        assertFalse(source("jiten").matches(installed("Jiten Kanji")))
    }

    @Test
    fun anImportIsCheckedByWhatItHolds() {
        assertTrue(source("jitendex").accepts(ImportOutcome(success = true, title = "Jitendex.org [2026-08-11]", terms = 300_000)))
        assertFalse(source("jitendex").accepts(ImportOutcome(success = true, title = "Jitendex.org [2026-08-11]", terms = 12)))
        assertFalse(source("jitendex").accepts(ImportOutcome(success = false, terms = 300_000)))
        // Jiten holds frequencies, not terms.
        assertTrue(source("jiten").accepts(ImportOutcome(success = true, title = "Jiten", frequencies = 500_000)))
        assertFalse(source("jiten").accepts(ImportOutcome(success = true, title = "Jiten", terms = 500_000)))
        assertTrue(source("bees-ultimate-grammar-dictionary").accepts(ImportOutcome(success = true, terms = 6_371)))
    }
}
