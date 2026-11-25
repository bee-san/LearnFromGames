// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenRouter (https://openrouter.ai), with the key from Settings: its
 * gpt-oss translates lines and writes recaps. Asked as Telly asks it
 * (bee-san/Telly, src-tauri/src/openrouter.rs): one chat completion, its
 * reasoning kept low and left out of the answer.
 *
 *     POST https://openrouter.ai/api/v1/chat/completions   Authorization: Bearer <key>
 *     {"model": "openai/gpt-oss-120b", "messages": [{"role", "content"}], "reasoning": {"effort": "low", "exclude": true}}
 *     →  {"choices": [{"message": {"content": "…"}}]}
 *
 * Ask also turns on OpenRouter's web search server tool for its request alone
 * (`"tools": [{"type": "openrouter:web_search"}]`, which replaces the
 * deprecated `:online` suffix and `web` plugin), and may send the screen as
 * an `image_url` part; the pages it read come back as `url_citation`
 * annotations on the message.
 */
object OpenRouter {
    const val API = "https://openrouter.ai/api/v1"

    class Failure(message: String) : Exception(message)

    /** A page the web search read, from the answer's annotations. */
    data class Source(val url: String, val title: String)

    /** Pages one web search request may bring back. */
    const val SEARCH_RESULTS = 5

    /**
     * The request's body. [webSearch] lets the model search the web for this request alone;
     * [image] is a `data:` URL of a picture sent after the prompt. [talk] is what was said after
     * the prompt, the model's answer and the user's next question in turn, ending on a question.
     */
    fun body(
        model: String, system: String, prompt: String, webSearch: Boolean = false, image: String? = null, talk: List<String> = emptyList(),
    ): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                if (image == null) {
                    put("content", prompt)
                } else {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", image) }
                        }
                    }
                }
            }
            talk.forEachIndexed { i, said ->
                addJsonObject {
                    put("role", if (i % 2 == 0) "assistant" else "user")
                    put("content", said)
                }
            }
        }
        if (webSearch) {
            putJsonArray("tools") {
                addJsonObject {
                    put("type", "openrouter:web_search")
                    putJsonObject("parameters") { put("max_results", SEARCH_RESULTS) }
                }
            }
        }
        putJsonObject("reasoning") {
            put("effort", "low")
            put("exclude", true)
        }
    }.toString()

    /** The pages the answer cites, each once, in the order cited. */
    fun sources(response: String): List<Source> = try {
        val message = Json.parseToJsonElement(response).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
        (message?.get("annotations") as? JsonArray).orEmpty().mapNotNull { a ->
            val cite = (a as? JsonObject)?.get("url_citation") as? JsonObject ?: return@mapNotNull null
            val url = cite.text("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Source(url, cite.text("title"))
        }.distinctBy { it.url }
    } catch (_: Exception) {
        emptyList()
    }

    /** The answer's text, from the response's body. */
    fun answer(response: String): String {
        val content = try {
            Json.parseToJsonElement(response).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
        } catch (_: Exception) {
            null
        }
        val text = (content as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (text.isNullOrEmpty()) throw Failure("OpenRouter sent no answer. Try again.")
        return text
    }

    /** Asks [model] with [system] as its instructions. Throws [Failure] with a message to show. */
    suspend fun ask(key: String, model: String, system: String, prompt: String): String = answer(request(key, body(model, system, prompt)))

    /** Sends [body] and gives back the response's body. Throws [Failure] with a message to show. */
    suspend fun request(key: String, body: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw Failure("Add an OpenRouter key in Settings › Translation.")
        val connection = try {
            (URL("$API/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        } catch (_: IOException) {
            throw Failure("OpenRouter isn’t reachable. Check the connection and try again.")
        }
        try {
            val code = connection.responseCode
            if (code != 200) throw Failure(failure(code))
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            throw Failure("OpenRouter isn’t reachable. Check the connection and try again.")
        } finally {
            connection.disconnect()
        }
    }

    /** What went wrong, by the response's status, and what to do. */
    fun failure(code: Int): String = when (code) {
        401 -> "OpenRouter refused the key. Check it in Settings › Translation."
        402 -> "The OpenRouter account is out of credit. Add some at openrouter.ai."
        404 -> "OpenRouter has no such model. Check the model in Settings › Translation."
        429 -> "OpenRouter is busy. Try again in a minute."
        else -> "OpenRouter answered with an error ($code). Try again."
    }

    /** The first JSON object in [text]: a model may wrap one in a code fence. */
    fun jsonIn(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw Failure("The answer wasn’t in the expected form. Try again.")
        return try {
            Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (_: Exception) {
            throw Failure("The answer wasn’t in the expected form. Try again.")
        }
    }

    internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
}
