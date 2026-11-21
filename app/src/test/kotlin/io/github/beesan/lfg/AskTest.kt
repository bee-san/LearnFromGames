package io.github.beesan.lfg

import io.github.beesan.lfg.ask.Explainer
import io.github.beesan.lfg.ask.Asker
import io.github.beesan.lfg.ask.Moment
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.ask.Recap
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.data.QuestionRow
import java.time.ZoneOffset
import io.github.beesan.lfg.ask.Translator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AskTest {
    @Test
    fun theRequestIsTellysChatCompletionWithLowReasoningLeftOut() {
        val body = Json.parseToJsonElement(OpenRouter.body("openai/gpt-oss-120b", "sys", "hi")).jsonObject
        assertEquals("openai/gpt-oss-120b", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("hi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", body["reasoning"]!!.jsonObject["exclude"]!!.jsonPrimitive.content)
    }

    @Test
    fun theAnswerIsTheFirstChoicesTextAndAnEmptyOneFails() {
        assertEquals("I got a new sword!", OpenRouter.answer("""{"choices":[{"message":{"content":" I got a new sword!\n"}}]}"""))
        assertThrows(OpenRouter.Failure::class.java) { OpenRouter.answer("""{"choices":[]}""") }
        assertTrue(OpenRouter.failure(402).contains("credit"))
        assertTrue(OpenRouter.failure(401).contains("key"))
        assertEquals("b", OpenRouter.jsonIn("```json\n{\"a\":\"b\"}\n```")["a"]!!.jsonPrimitive.content)
    }

    @Test
    fun aTranslationIsAskedForTheLineWithTheLinesBeforeAsContext() {
        val prompt = Translator.prompt("Quest", listOf("村の長老が待っている。", "橋を渡ろう。"), "新しい剣を手に入れた！")
        assertTrue(prompt.contains("<before>\n村の長老が待っている。\n橋を渡ろう。\n</before>"))
        assertTrue(prompt.trimEnd().endsWith("<line>\n新しい剣を手に入れた！\n</line>"))
        assertFalse(Translator.prompt("Quest", emptyList(), "a").contains("<before>"))
    }

    @Test
    fun anExplanationIsAskedForTheLineAloneInPlainTextInTheChosenLanguage() {
        val prompt = Explainer.prompt("Quest", listOf("橋を渡ろう。"), "新しい剣を手に入れた！")
        assertTrue(prompt.contains("<before>\n橋を渡ろう。\n</before>"))
        assertTrue(prompt.trimEnd().endsWith("Explain this line:\n<line>\n新しい剣を手に入れた！\n</line>"))
        assertFalse(Explainer.prompt("", emptyList(), "a").contains("<before>"))
        val system = Explainer.system("French")
        assertTrue(system.contains("in French") && system.contains("no Markdown") && system.contains("no romaji"))
    }

    private fun line(id: String, text: String, day: Int) = GameLine(
        id = id, gameId = "g", gameName = "Quest", lineText = text,
        // Noon on 2 or 3 November 2026, UTC, well clear of the 04:00 day turn.
        timestamp = 1_793_620_800.0 + day * 86_400.0, createdAt = 0, lastModified = 0, source = "ocr", charCount = text.length,
    )

    @Test
    fun aRecapNumbersTheLinesByDayAndCarriesOnFromTheLastOne() {
        val lines = listOf(line("a", "灯台の明かりが消えた。", 0), line("b", "新しい剣を手に入れた！", 1))
        val earlier = Recap(moments = listOf(Moment("x", "村の長老", "/gone.webp", "The elder")))
        val prompt = Recapper.prompt("Quest", lines, earlier, "English", ZoneOffset.UTC)
        assertTrue(prompt.contains("<day date=\"2 November 2026\">\n[1] 灯台の明かりが消えた。\n</day>"))
        assertTrue(prompt.contains("<day date=\"3 November 2026\">\n[2] 新しい剣を手に入れた！"))
        assertTrue(prompt.contains("<earlier>"))
        assertFalse(prompt.contains("/gone.webp"))   // no file paths go out

        val answer = """```json
            {"arcs":[{"title":"The sword","from":"2 November 2026","to":"3 November 2026","summary":"…"}],
             "last":"You got the sword.","moments":[{"line":2,"caption":"The sword"},{"earlier":0,"caption":""},{"line":9,"caption":"none"}]}
            ```"""
        val recap = Recapper.parse(answer, lines, earlier)
        assertEquals("The sword", recap.arcs.single().title)
        assertEquals("You got the sword.", recap.last)
        assertEquals(listOf("b", "x"), recap.moments.map { it.lineId })   // a number past the lines is dropped
        assertEquals("The elder", recap.moments[1].caption)              // an earlier moment keeps its caption
        assertThrows(OpenRouter.Failure::class.java) { Recapper.parse("{}", lines, null) }
    }

    @Test
    fun aQuestionGoesAfterTheGameTheLastLinesAndTheEarlierQuestions() {
        val earlier = listOf(QuestionRow(gameId = "g", question = "Where is the elder?", answer = "In the house by the well.", model = "m", createdAt = 0))
        val prompt = Asker.prompt("Quest", listOf("村の長老が待っている。", "橋を\n渡ろう。"), earlier, "  How do I get the sword?\n", withScreen = false)
        assertTrue(prompt.startsWith("I’m playing Quest."))
        assertTrue(prompt.contains("<lines>\n村の長老が待っている。\n橋を 渡ろう。\n</lines>"))   // a line stays one line
        assertTrue(prompt.contains("<asked>\nWhere is the elder?\n</asked>\n<answered>\nIn the house by the well.\n</answered>"))
        assertTrue(prompt.trimEnd().endsWith("<question>\nHow do I get the sword?\n</question>"))
        assertFalse(prompt.contains("picture"))

        val bare = Asker.prompt("", emptyList(), emptyList(), "Is there a save point?", withScreen = true)
        assertTrue(bare.startsWith("I’m playing a Japanese game."))
        assertFalse(bare.contains("<lines>"))
        assertFalse(bare.contains("<asked>"))
        assertTrue(bare.contains("The picture is my screen now."))
    }

    @Test
    fun theAskInstructionsKeepToTheQuestionWithoutSpoilersAndSayWhenUnsure() {
        val system = Asker.system("German")
        assertTrue(system.contains("in German"))
        assertTrue(system.contains("Answer only what was asked"))
        assertTrue(system.contains("Don't spoil the story past the lines they have read"))
        assertTrue(system.contains("If you aren't sure"))
        assertTrue(system.contains("short and practical"))
    }

    @Test
    fun anAskTurnsOnWebSearchForItsRequestAloneAndCanSendTheScreen() {
        val plain = Json.parseToJsonElement(OpenRouter.body("m", "sys", "hi")).jsonObject
        assertFalse(plain.containsKey("tools"))

        val body = Json.parseToJsonElement(OpenRouter.body("m", "sys", "hi", webSearch = true, image = "data:image/jpeg;base64,AA")).jsonObject
        assertEquals("m", body["model"]!!.jsonPrimitive.content)   // no :online suffix
        assertFalse(body.containsKey("plugins"))
        assertEquals("openrouter:web_search", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        val parts = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("hi", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AA", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSourcesAreTheAnswersUrlCitationsEachOnce() {
        val response = """{"choices":[{"message":{"content":"Go north.","annotations":[
            {"type":"url_citation","url_citation":{"url":"https://wiki.example/sword","title":"Sword"}},
            {"type":"url_citation","url_citation":{"url":"https://wiki.example/sword","title":"Sword again"}},
            {"type":"file","file":{}},
            {"type":"url_citation","url_citation":{"url":"javascript:alert(1)","title":"no"}}]}}]}"""
        val sources = OpenRouter.sources(response)
        assertEquals(listOf(OpenRouter.Source("https://wiki.example/sword", "Sword")), sources)
        assertEquals(emptyList<OpenRouter.Source>(), OpenRouter.sources("not json"))

        val row = QuestionRow(gameId = "g", question = "q", answer = "a", sources = "https://a.example\tA\nhttps://b.example", model = "m", createdAt = 0)
        assertEquals(listOf(OpenRouter.Source("https://a.example", "A"), OpenRouter.Source("https://b.example", "")), Asker.sources(row))
    }
}
