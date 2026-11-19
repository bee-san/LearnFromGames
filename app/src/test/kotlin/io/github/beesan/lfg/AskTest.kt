package io.github.beesan.lfg

import io.github.beesan.lfg.ask.Moment
import io.github.beesan.lfg.ask.OpenRouter
import io.github.beesan.lfg.ask.Recap
import io.github.beesan.lfg.ask.Recapper
import io.github.beesan.lfg.data.GameLine
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
        assertTrue(prompt.contains("<day date=\"2 November 2026\">
[1] 灯台の明かりが消えた。
</day>"))
        assertTrue(prompt.contains("<day date=\"3 November 2026\">
[2] 新しい剣を手に入れた！"))
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
}
