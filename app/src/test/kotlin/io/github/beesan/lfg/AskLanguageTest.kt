package io.github.beesan.lfg

import io.github.beesan.lfg.ask.Asker
import io.github.beesan.lfg.ask.Explainer
import io.github.beesan.lfg.ask.LineAsker
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.ask.Translator
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.data.GameLine
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompts name the language being learnt, and say of Japanese exactly what they said when it was the only one. */
class AskLanguageTest {
    private val asking = WheelAction.entries.filter { it.asksOfLine }

    private fun line(text: String) = GameLine(
        id = "a", gameId = "g", gameName = "Quest", lineText = text,
        timestamp = 1_793_620_800.0, createdAt = 0, lastModified = 0, source = "ocr", charCount = text.length,
    )

    /** Every prompt the app sends about a line or a game in [language]: twelve, then one for each tile that asks of a line. */
    private fun prompts(language: Language): List<String> = listOf(
        Translator.system("English", language), Translator.prompt("", emptyList(), "x", language),
        Explainer.system("English", language), Explainer.followUp("English", language), Explainer.prompt("", emptyList(), "x", language),
        LineAsker.prompt("", emptyList(), "x", language),
        Asker.system("English", language), Asker.prompt("", emptyList(), emptyList(), "x", withScreen = false, language = language),
        Recapper.system(language), Recapper.sceneSystem(language),
        Recapper.scenePrompt("Quest", listOf("x"), "English", language), Recapper.prompt("Quest", listOf(line("x")), null, "English", ZoneOffset.UTC, language),
    ) + asking.map { LineAsker.system("English", LineAsker.task(it, "English", "Is this rude?", language)!!, language) }

    // The strings below are the prompts as they were before any other language could be learnt.
    @Test
    fun japanesePromptsAreWhatTheyAlwaysWere() {
        assertEquals(
            "You translate lines of Japanese games into English for someone learning Japanese. Keep the tone and who is " +
                "speaking. Answer with the translation alone: no notes, no quotation marks, no romaji.",
            Translator.system("English"),
        )
        assertTrue(
            Explainer.system("English").startsWith(
                "You explain lines of Japanese games to someone learning Japanese, in English. Answer in plain text, no Markdown, " +
                    "in three parts. First the line's meaning in natural English, on its own line. Then the line taken apart in " +
                    "order, one part a line, each as: the Japanese, its reading in hiragana in brackets where it has kanji, a dash, " +
                    "and what it means or does there (the particle's job, the verb's dictionary form and how it is conjugated). " +
                    "Then, only if there is one, a single short note on a nuance, an idiom, the register or who it implies. " +
                    "Be brief: no greeting, no romaji, nothing about lines other than the one asked about. After that, on a line of its own, write ",
            ),
        )
        assertTrue(
            Explainer.followUp("English").startsWith(
                "You are helping someone learning Japanese with one line of a Japanese game, in English. You have explained the line; " +
                    "now answer their question about it in plain text, no Markdown, in two to four sentences, with a Japanese example " +
                    "where one helps and no romaji. Stay on this line: its words, its grammar, its tone and who is speaking. If the " +
                    "question is about the game and not the line",
            ),
        )
        assertEquals(
            "You help someone learning Japanese with one line of a Japanese game, answering in English. " +
                "List every word of the line that has kanji, in order, one a line: the word as written, then its reading " +
                "in hiragana as it is read in this line, in brackets. Nothing else. " +
                "Plain text, no Markdown, no romaji, no greeting. Be brief, and stay on the line asked about.",
            LineAsker.system("English", LineAsker.task(WheelAction.READINGS, "English")!!),
        )
        assertEquals(
            "Write the line again in easier Japanese: common words, short sentences, the same meaning and the same speaker's " +
                "voice. Give the easier Japanese alone on the first line, then one sentence in English on what was changed.",
            LineAsker.task(WheelAction.SIMPLER, "English"),
        )
        assertTrue(
            Asker.system("English").startsWith(
                "You help someone who is playing a Japanese game, and learning Japanese, with a question about the game. " +
                    "Search the web for walkthroughs, guides and wikis when that helps. ",
            ),
        )
        assertEquals(
            "You write recaps of Japanese games for someone learning Japanese who is coming back to one: the story so far, " +
                "like a TV recap, from only what they have read. You answer with JSON alone.",
            Recapper.system(),
        )
        assertEquals(
            "You tell someone learning Japanese what is happening in the scene of a Japanese game they are reading, from only " +
                "what they have read. You answer with the telling alone, in plain text.",
            Recapper.sceneSystem(),
        )
        assertTrue(Recapper.scenePrompt("Quest", listOf("x"), "English").startsWith("I’m playing Quest, a Japanese game. Here are the lines I read last, oldest first:\n<lines>\n"))
        assertTrue(
            Recapper.prompt("", listOf(line("x")), null, "English", ZoneOffset.UTC)
                .startsWith("I’m playing a game, a Japanese game. Here is its Japanese text as I read it, by day, each line numbered:\n"),
        )
        assertEquals("A line from a Japanese game.\n\nTranslate this line:\n<line>\nx\n</line>", Translator.prompt("", emptyList(), "x"))
        assertEquals("A line from a Japanese game.\n\nExplain this line:\n<line>\nx\n</line>", Explainer.prompt("", emptyList(), "x"))
        assertEquals("A line from a Japanese game.\n\nThe line:\n<line>\nx\n</line>", LineAsker.prompt("", emptyList(), "x"))
        // Left out, the language is Japanese.
        assertEquals(prompts(Language.JAPANESE).drop(12), asking.map { LineAsker.system("English", LineAsker.task(it, "English", "Is this rude?")!!) })
    }

    @Test
    fun aSpanishPromptNamesSpanishAndNothingOfJapanese() {
        for (prompt in prompts(Language.SPANISH)) {
            listOf("Japanese", "hiragana", "kanji", "romaji", "pinyin").forEach { assertFalse("$it in: $prompt", prompt.contains(it)) }
        }
        prompts(Language.SPANISH).forEach { assertTrue(it, it.contains("Spanish")) }
        assertEquals(
            "You translate lines of games in Spanish into English for someone learning Spanish. Keep the tone and who is " +
                "speaking. Answer with the translation alone: no notes, no quotation marks.",
            Translator.system("English", Language.SPANISH),
        )
        assertTrue(Explainer.system("English", Language.SPANISH).contains("each as: the Spanish, a dash, and what it means or does there"))
        assertTrue(Explainer.followUp("English", Language.SPANISH).contains("with an example in Spanish where one helps. Stay on this line"))
        assertEquals(
            "List every word of the line, in order, one a line: the word as written, then how it is said in this line, in brackets. Nothing else.",
            LineAsker.task(WheelAction.READINGS, "English", language = Language.SPANISH),
        )
        assertTrue(LineAsker.task(WheelAction.SIMPLER, "English", language = Language.SPANISH)!!.startsWith("Write the line again in easier Spanish:"))
        assertEquals("I’m playing a game in Spanish.\n\nMy question:\n<question>\nx\n</question>", Asker.prompt("", emptyList(), emptyList(), "x", false, Language.SPANISH))
        assertTrue(Recapper.scenePrompt("Quest", listOf("x"), "English", Language.SPANISH).startsWith("I’m playing Quest, a game in Spanish. Here are the lines"))
        assertTrue(
            Recapper.prompt("Quest", listOf(line("x")), null, "English", ZoneOffset.UTC, Language.SPANISH)
                .startsWith("I’m playing Quest, a game in Spanish. Here is its Spanish text as I read it, by day"),
        )
    }

    @Test
    fun aChineseExplanationGivesReadingsInPinyin() {
        val system = Explainer.system("English", Language.CHINESE)
        assertTrue(system.contains("each as: the Chinese, its reading in pinyin in brackets, a dash, and what it means"))
        assertEquals(
            "List every word of the line, in order, one a line: the word as written, then its reading in pinyin as it is read in this line, " +
                "in brackets. Nothing else.",
            LineAsker.task(WheelAction.READINGS, "English", language = Language.CHINESE),
        )
        for (prompt in prompts(Language.CHINESE)) {
            listOf("Japanese", "hiragana", "kanji", "romaji").forEach { assertFalse("$it in: $prompt", prompt.contains(it)) }
        }
    }

    @Test
    fun everyLanguagesPromptsReadAsSentences() {
        for (language in Language.entries) {
            val all = prompts(language)
            assertEquals(12 + asking.size, all.size)
            // A clause left out leaves no gap behind it.
            for (prompt in all) listOf("  ", ",,", " ,", " .", ",.", "null").forEach { assertFalse("'$it' in: $prompt", prompt.contains(it)) }
            if (!language.japanese) all.forEach { assertFalse(it, it.contains("Japanese") || it.contains("romaji")) }
        }
    }
}
