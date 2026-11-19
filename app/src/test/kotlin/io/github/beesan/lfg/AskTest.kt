package io.github.beesan.lfg

import io.github.beesan.lfg.ask.OpenRouter
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
}
