package io.github.beesan.lfg

import com.google.mlkit.nl.translate.TranslateLanguage
import io.github.beesan.lfg.ask.OfflineLanguages
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.core.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTranslatorTest {
    @Test
    fun theLanguagesListedAreMlKitsOwn() {
        assertEquals(TranslateLanguage.getAllLanguages().toSet(), OfflineLanguages.NAMES.keys)
        OfflineLanguages.NAMES.keys.forEach { assertEquals(it, TranslateLanguage.fromLanguageTag(it)) }
    }

    @Test
    fun anAppLanguageGoesByItsTagAndSerboCroatianAsCroatian() {
        assertEquals("ja", OfflineLanguages.tag(Language.JAPANESE))
        assertEquals("zh", OfflineLanguages.tag(Language.CHINESE))
        assertEquals("tl", OfflineLanguages.tag(Language.TAGALOG))
        assertEquals("hr", OfflineLanguages.tag(Language.SERBO_CROATIAN))
        // The ones ML Kit has not got cannot be translated with no internet.
        assertEquals(
            setOf(Language.ANCIENT_GREEK, Language.ASSYRIAN_NEO_ARAMAIC, Language.BASQUE, Language.LATIN, Language.OLD_IRISH, Language.YIDDISH),
            Language.entries.filter { OfflineLanguages.tag(it) == null }.toSet(),
        )
    }

    @Test
    fun theLanguageToTranslateIntoIsFoundByItsNameInEnglishOrItselfOrItsTag() {
        assertEquals("en", OfflineLanguages.tag("English"))
        assertEquals("en", OfflineLanguages.tag("  english "))
        assertEquals("es", OfflineLanguages.tag("SPANISH"))
        assertEquals("es", OfflineLanguages.tag("Español"))
        assertEquals("ja", OfflineLanguages.tag("日本語"))
        assertEquals("de", OfflineLanguages.tag("deutsch"))
        assertEquals("nl", OfflineLanguages.tag("Nederlands"))
        assertEquals("hr", OfflineLanguages.tag("Serbo-Croatian"))
        assertEquals("fa", OfflineLanguages.tag("Farsi"))
        assertEquals("bn", OfflineLanguages.tag("Bengali"))
        assertEquals("tl", OfflineLanguages.tag("Filipino"))
        assertEquals("pt", OfflineLanguages.tag("pt"))
        assertEquals("pt", OfflineLanguages.tag("pt-BR"))
        assertEquals("en", OfflineLanguages.tag("en_GB"))
        assertEquals("zh", OfflineLanguages.tag("zh-Hans-CN"))
        assertEquals("he", OfflineLanguages.tag("iw"))
    }

    @Test
    fun oneLanguageNamedAmongOtherWordsIsIt() {
        assertEquals("pt", OfflineLanguages.tag("Brazilian Portuguese"))
        assertEquals("zh", OfflineLanguages.tag("Simplified Chinese"))
        // "it" and "is" are tags too, and are not read as Italian and Icelandic here.
        assertEquals("en", OfflineLanguages.tag("It is English"))
        assertNull(OfflineLanguages.tag("English or German"))
    }

    @Test
    fun aLanguageMlKitHasNotGotIsNone() {
        assertNull(OfflineLanguages.tag(""))
        assertNull(OfflineLanguages.tag("Klingon"))
        assertNull(OfflineLanguages.tag("xx"))
        assertNull(OfflineLanguages.tag("Latin"))
        assertNull(OfflineLanguages.tag("Basque"))
        assertNull(OfflineLanguages.tag("no-nonsense"))
        // Not Irish and not Greek.
        assertNull(OfflineLanguages.tag("Old Irish"))
        assertNull(OfflineLanguages.tag("Ancient Greek"))
    }

    @Test
    fun aLinesRowsAreJoinedAsTheLanguageWritesThem() {
        assertEquals("昨日の夜、森の奥で不思議な光を見たんだ。", OfflineLanguages.oneLine("昨日の夜、森の奥で\n不思議な光を見たんだ。", Language.JAPANESE))
        assertEquals("The children were running.", OfflineLanguages.oneLine("The children \n were running.\n", Language.ENGLISH))
        assertEquals("", OfflineLanguages.oneLine(" \n ", Language.ENGLISH))
    }

    @Test
    fun onlyAnOpenRouterThatWasNeverHeardFromCountsAsNoInternet() {
        assertFalse(OpenRouter.Failure(OpenRouter.failure(401)).offline)
        assertFalse(OpenRouter.Failure(OpenRouter.failure(402)).offline)
        assertTrue(OpenRouter.Failure("OpenRouter isn’t reachable.", offline = true).offline)
    }
}
