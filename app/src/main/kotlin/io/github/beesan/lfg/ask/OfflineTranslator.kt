// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ask

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.beesan.lfg.core.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.nl.translate.Translator as MlKitTranslator

/**
 * A line translated on the device, for when there is no internet: ML Kit's on-device
 * translation (https://developers.google.com/ml-kit/language/translation), which runs the
 * models of Google Translate's offline mode.
 *
 * Every language but English has one model, of 30 to 50 MB, which Android's download
 * manager fetches into the app's own storage; English needs none. Between two languages
 * that are not English it goes by way of English, so it needs both models and loses a
 * little twice. Google calls it fit for "casual and simple translations": it is given the
 * line alone, with nothing of the game or the lines before it, and stands in for the model
 * Settings picked without being its equal.
 *
 * Google's guidelines ask that a translation of its be shown as Google's
 * (https://developers.google.com/ml-kit/language/translation/translation-terms).
 *
 * One translator is kept open, for the two languages last translated between, and closed
 * when another two are asked for: opening one loads its models, too slow to do for each line.
 */
class OfflineTranslator {
    private val lock = Mutex()

    /** The translator kept open and the languages it is for, as `ja>en`. Only touched under [lock]. */
    private var open: Pair<String, MlKitTranslator>? = null

    /** ML Kit's language for one of the app's; null where it has none, which then cannot be translated here. */
    fun code(language: Language): String? = OfflineLanguages.tag(language)?.let { TranslateLanguage.fromLanguageTag(it) }

    /** ML Kit's language for what Settings › Translation › Into says: a language's name, in English or its own, or its tag. */
    fun code(into: String): String? = OfflineLanguages.tag(into)?.let { TranslateLanguage.fromLanguageTag(it) }

    /** Both languages' models are on the device, so [translate] will answer. */
    suspend fun ready(from: Language, into: String): Boolean {
        val (source, target) = pair(from, into) ?: return false
        return try {
            downloaded(source) && downloaded(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The models on the device could not be listed", e)
            false
        }
    }

    /**
     * Fetches the models the two languages need and the device lacks, so that [translate] works
     * later with no internet. With [wifiOnly] Android waits for Wi-Fi; with no network at all it
     * waits for one, and this comes back when the models have arrived. Models of other languages, from a
     * language learnt or translated into before, are deleted then: each is tens of megabytes, and nothing
     * else in the app would ever take them away. Never throws: a failure is logged, and false returned.
     */
    suspend fun prepare(from: Language, into: String, wifiOnly: Boolean): Boolean {
        val (source, target) = pair(from, into) ?: return true
        return try {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            val missing = listOf(source, target).filterNot { downloaded(it) }
            if (missing.isNotEmpty()) Log.i(TAG, "Fetching the models for $missing")
            // Both are asked for before either is waited on, so one failing does not hold the other back.
            missing.map { models.download(model(it), conditions) }.forEach { it.await() }
            // English is built in and cannot go.
            val keep = setOf(source, target, TranslateLanguage.ENGLISH)
            models.getDownloadedModels(TranslateRemoteModel::class.java).await().filter { it.language !in keep }.forEach {
                runCatching { models.deleteDownloadedModel(it).await() }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The models for $source to $target were not fetched", e)
            false
        }
    }

    /**
     * [text] in the language of [into]; null when that cannot be done here: ML Kit has not one of
     * the languages, they are the same one, or a model is not on the device. It never fetches one:
     * that is [prepare]'s to do, while there is internet.
     */
    suspend fun translate(text: String, from: Language, into: String): String? {
        val (source, target) = pair(from, into) ?: return null
        val line = OfflineLanguages.oneLine(text, from).ifEmpty { return null }
        return lock.withLock {
            try {
                // ML Kit fails a translation whose model is missing rather than fetch it; asking first opens no translator for nothing.
                if (downloaded(source) && downloaded(target)) translator(source, target).translate(line).await()?.trim()?.ifEmpty { null } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No translation from $source to $target on the device", e)
                // Whatever went wrong may have been the translator's own: the next line opens a fresh one.
                open?.second?.close()
                open = null
                null
            }
        }
    }

    /** The two ML Kit languages to translate between; null when either is not ML Kit's or there is nothing to translate. */
    private fun pair(from: Language, into: String): Pair<String, String>? {
        val source = code(from) ?: return null
        val target = code(into) ?: return null
        return if (source == target) null else source to target
    }

    private fun translator(source: String, target: String): MlKitTranslator {
        val languages = "$source>$target"
        open?.let { (kept, translator) -> if (kept == languages) return translator else translator.close() }
        val options = TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        return Translation.getClient(options).also { open = languages to it }
    }

    // Asked for at each use, not when this is made: ML Kit must have started by then, and a failure is the caller's to catch.
    private val models: RemoteModelManager get() = RemoteModelManager.getInstance()

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    private suspend fun downloaded(code: String): Boolean = models.isModelDownloaded(model(code)).await()

    private companion object {
        const val TAG = "LfgOfflineTranslator"
    }
}

/**
 * A Play services task's result, waited for: what kotlinx-coroutines-play-services does, without
 * the dependency. The listener runs on the thread that finishes the task, not the main thread.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { waiting ->
    addOnCompleteListener(Executor { it.run() }) { task ->
        val failure = task.exception
        when {
            failure != null -> waiting.resumeWithException(failure)
            task.isCanceled -> waiting.cancel()
            else -> waiting.resume(task.result)
        }
    }
}

/**
 * Which of ML Kit's languages is meant, worked out without ML Kit or Android so it can be tested
 * on the build machine. OfflineTranslatorTest holds [NAMES] to ML Kit's own list.
 */
internal object OfflineLanguages {
    /**
     * The languages ML Kit translates, by tag, with the name its documentation gives each
     * (https://developers.google.com/ml-kit/language/translation/translation-language-support).
     */
    val NAMES: Map<String, String> = mapOf(
        "af" to "Afrikaans", "ar" to "Arabic", "be" to "Belarusian", "bg" to "Bulgarian", "bn" to "Bengali",
        "ca" to "Catalan", "cs" to "Czech", "cy" to "Welsh", "da" to "Danish", "de" to "German",
        "el" to "Greek", "en" to "English", "eo" to "Esperanto", "es" to "Spanish", "et" to "Estonian",
        "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "ga" to "Irish", "gl" to "Galician",
        "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian", "ht" to "Haitian",
        "hu" to "Hungarian", "id" to "Indonesian", "is" to "Icelandic", "it" to "Italian", "ja" to "Japanese",
        "ka" to "Georgian", "kn" to "Kannada", "ko" to "Korean", "lt" to "Lithuanian", "lv" to "Latvian",
        "mk" to "Macedonian", "mr" to "Marathi", "ms" to "Malay", "mt" to "Maltese", "nl" to "Dutch",
        "no" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian",
        "sk" to "Slovak", "sl" to "Slovenian", "sq" to "Albanian", "sv" to "Swedish", "sw" to "Swahili",
        "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tl" to "Tagalog", "tr" to "Turkish",
        "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese", "zh" to "Chinese",
    )

    /** Tags that mean one of ML Kit's: older ones, and Serbo-Croatian, which it has as Croatian. */
    private val OTHER_TAGS = mapOf("sh" to "hr", "iw" to "he", "in" to "id", "nb" to "no", "fil" to "tl")

    /** Names people use that are neither the documentation's nor Java's. */
    private val OTHER_NAMES = mapOf("bangla" to "bn", "farsi" to "fa", "filipino" to "tl", "haitian creole" to "ht", "mandarin" to "zh")

    /** A tag with a script or a region after the language: `pt-BR`, `en_GB`, `zh-Hans-CN`. */
    private val LONGER_TAG = Regex("([a-z]{2,3})(?:[-_](?:[a-z]{4}|[a-z]{2}|[0-9]{3})){1,2}")

    private val NOT_LETTERS = Regex("[^\\p{L}]+")

    /** Every name a language goes by, in lower case, to its tag. */
    private val named: Map<String, String> by lazy {
        val names = HashMap<String, String>()
        fun add(name: String, tag: String) {
            val key = name.trim().lowercase(Locale.ROOT)
            // Java names a language it does not know by its tag, which is no name. The first to claim a name keeps it.
            if (key.isNotEmpty() && key != tag && key !in names) names[key] = tag
        }
        NAMES.forEach { (tag, name) -> add(name, tag) }
        // The names the app itself shows for the languages it teaches.
        Language.entries.forEach { language ->
            val ours = tag(language) ?: return@forEach
            add(language.title, ours)
            add(language.ownName, ours)
        }
        NAMES.keys.forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            add(locale.getDisplayLanguage(Locale.ENGLISH), tag)
            add(locale.getDisplayLanguage(locale), tag)
        }
        OTHER_NAMES.forEach { (name, tag) -> add(name, tag) }
        names
    }

    /** The app's languages ML Kit has not got, by the names the app shows: "Old Irish" is not to be read as Irish. */
    private val missing: Set<String> by lazy {
        Language.entries.filter { tag(it) == null }.flatMap { listOf(it.title, it.ownName) }.map { it.lowercase(Locale.ROOT) }.toSet()
    }

    /** ML Kit's tag for one of the app's languages: its own, but that Serbo-Croatian is read as Croatian. */
    fun tag(language: Language): String? = tagged(language.tag)

    /**
     * ML Kit's tag for a language as someone typed it: its name in English ("Spanish") or in
     * itself ("español"), or its tag ("es", "es-MX"), in any case. Failing those, the one
     * language named among the words ("Brazilian Portuguese", "plain English"); a tag is not
     * looked for there, since "it", "no" and "is" are tags. Null when it is none of ML Kit's.
     */
    fun tag(into: String): String? {
        val asked = into.trim().lowercase(Locale.ROOT)
        named[asked]?.let { return it }
        tagged(asked)?.let { return it }
        LONGER_TAG.matchEntire(asked)?.let { return tagged(it.groupValues[1]) }
        if (asked in missing) return null
        return asked.split(NOT_LETTERS).mapNotNull { named[it] }.distinct().singleOrNull()
    }

    private fun tagged(tag: String): String? = (OTHER_TAGS[tag] ?: tag).takeIf { it in NAMES }

    /**
     * A line's rows as one sentence: the reader keeps the rows a text box broke a line into,
     * and ML Kit is to translate the sentence, not each row.
     */
    fun oneLine(text: String, language: Language): String =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(if (language.spaced) " " else "")
}
