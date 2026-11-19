// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 */
object OpenRouter {
    const val API = "https://openrouter.ai/api/v1"

    class Failure(message: String) : Exception(message)

    /** The request's body. */
    fun body(model: String, system: String, prompt: String): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
        putJsonObject("reasoning") {
            put("effort", "low")
            put("exclude", true)
        }
    }.toString()

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
    suspend fun ask(key: String, model: String, system: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw Failure("Add an OpenRouter key in Settings › Translation.")
        val connection = try {
            (URL("$API/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body(model, system, prompt).toByteArray()) }
            }
        } catch (_: IOException) {
            throw Failure("OpenRouter isn’t reachable. Check the connection and try again.")
        }
        try {
            val code = connection.responseCode
            if (code != 200) throw Failure(failure(code))
            answer(connection.inputStream.bufferedReader().use { it.readText() })
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
