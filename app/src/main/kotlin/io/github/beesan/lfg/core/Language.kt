// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * A language to learn from games. The player picks one at set-up; the reader
 * is told which, so it reads every line as that language instead of guessing
 * each line's script first, and the dictionary takes words apart by that
 * language's rules.
 *
 * One for each language pack of hoshidicts (third_party/hoshidicts/languages),
 * which is where Hachidori's rules for inflected words live. What is said of
 * each here is the pack's own: where a lookup starts, how words are divided,
 * and the practice sentence set-up ends on. LanguageTest holds the two together.
 * The best known come first, then the rest by name.
 */
enum class Language(
    /** The tag saved in the settings, given to ScreenAI and naming the pack. */
    val tag: String,
    /** Its name in English, and in the language itself. */
    val title: String,
    val ownName: String,
    /** What watches the screen for the text changing. */
    val probe: Probe,
    /** A lookup starts at the start of the word touched, not at the character: the pack's `scanStart`. */
    val wordStart: Boolean = false,
    /** Spaces divide its words: the pack's `segmentation`. */
    val spaced: Boolean = false,
    val rightToLeft: Boolean = false,
    /** The voice Android reads a line in: a BCP 47 tag. */
    val voice: String,
    /** The line the tutorial shows, and the word in it looked up first. */
    val practice: String,
    val practiceWord: String,
) {
    JAPANESE("ja", "Japanese", "日本語", Probe.VOCR, voice = "ja-JP", practice = "昨日の夜、森の奥で不思議な光を見たんだ。", practiceWord = "不思議"),
    CHINESE("zh", "Chinese", "中文", Probe.READER, voice = "zh-CN", practice = "我想先洗个澡，然后好好睡一觉。", practiceWord = "洗个澡"),
    KOREAN("ko", "Korean", "한국어", Probe.READER, spaced = true, voice = "ko-KR", practice = "어제 친구와 같이 영화를 봤어요.", practiceWord = "봤어요"),
    ENGLISH("en", "English", "English", Probe.READER, wordStart = true, spaced = true, voice = "en-US", practice = "The children were running across the playground.", practiceWord = "running"),
    SPANISH("es", "Spanish", "Español", Probe.READER, wordStart = true, spaced = true, voice = "es-ES", practice = "Ayer comimos en un restaurante pequeño cerca del puerto.", practiceWord = "comimos"),
    FRENCH("fr", "French", "Français", Probe.READER, wordStart = true, spaced = true, voice = "fr-FR", practice = "Elles finissaient leurs devoirs avant le dîner.", practiceWord = "finissaient"),
    GERMAN("de", "German", "Deutsch", Probe.READER, spaced = true, voice = "de-DE", practice = "Sie ist heute früh aufgestanden und hat ihre Freunde angerufen.", practiceWord = "aufgestanden"),
    ITALIAN("it", "Italian", "Italiano", Probe.READER, wordStart = true, spaced = true, voice = "it-IT", practice = "Ieri abbiamo mangiato in una piccola trattoria vicino al porto.", practiceWord = "mangiato"),
    PORTUGUESE("pt", "Portuguese", "Português", Probe.READER, wordStart = true, spaced = true, voice = "pt-BR", practice = "Ela falou com o professor depois da aula.", practiceWord = "falou"),
    RUSSIAN("ru", "Russian", "Русский", Probe.READER, wordStart = true, spaced = true, voice = "ru-RU", practice = "Вчера мы долго гуляли по старому городу.", practiceWord = "гуляли"),
    ALBANIAN("sq", "Albanian", "Shqip", Probe.READER, wordStart = true, spaced = true, voice = "sq-AL", practice = "Fëmijët lexonin libra në bibliotekën e qytetit.", practiceWord = "lexonin"),
    ANCIENT_GREEK("grc", "Ancient Greek", "Ἑλληνική", Probe.READER, wordStart = true, spaced = true, voice = "el-GR", practice = "ἐν ἀρχῇ ἦν ὁ λόγος.", practiceWord = "ἀρχῇ"),
    ARABIC("ar", "Arabic", "العربية", Probe.READER, wordStart = true, spaced = true, rightToLeft = true, voice = "ar-SA", practice = "ذهبتُ إلى المكتبة واستعرتُ كتابين.", practiceWord = "واستعرت"),
    ASSYRIAN_NEO_ARAMAIC("aii", "Assyrian Neo-Aramaic", "ܣܘܪܝܬ", Probe.READER, wordStart = true, spaced = true, rightToLeft = true, voice = "aii", practice = "ܟܲܠܒܹܗ ܕܡܲܠܟܵܐ ܐ݇ܙܝܼܠ ܠܹܗ ܠܛܘܼܪܵܐ.", practiceWord = "ܐ݇ܙܝܼܠ"),
    BASQUE("eu", "Basque", "euskara", Probe.READER, wordStart = true, spaced = true, voice = "eu-ES", practice = "Gaur goizean etxetik irten naiz eta lagunekin hitz egin dut.", practiceWord = "etxetik"),
    ESPERANTO("eo", "Esperanto", "Esperanto", Probe.READER, wordStart = true, spaced = true, voice = "eo", practice = "Hieraŭ ni manĝis en malgranda restoracio apud la haveno.", practiceWord = "manĝis"),
    GEORGIAN("ka", "Georgian", "ქართული", Probe.READER, wordStart = true, spaced = true, voice = "ka-GE", practice = "ბავშვები ყოველ დილით სკოლაში მიდიან.", practiceWord = "სკოლაში"),
    GREEK("el", "Greek", "Ελληνικά", Probe.READER, wordStart = true, spaced = true, voice = "el-GR", practice = "Χθες γράψαμε ένα μεγάλο γράμμα στη γιαγιά μας.", practiceWord = "γράψαμε"),
    IRISH("ga", "Irish", "Gaeilge", Probe.READER, wordStart = true, spaced = true, voice = "ga-IE", practice = "Cheannaigh sí bróga nua sa siopa inné.", practiceWord = "Cheannaigh"),
    LATIN("la", "Latin", "Lingua Latina", Probe.READER, wordStart = true, spaced = true, voice = "la", practice = "Arma virumque canō, Trōiae quī prīmus ab ōrīs Ītaliam vēnit.", practiceWord = "virumque"),
    OLD_IRISH("sga", "Old Irish", "Goídelc", Probe.READER, wordStart = true, spaced = true, voice = "sga", practice = "Ní·carat ind ḟir in n-ech.", practiceWord = "Ní·carat"),
    SERBO_CROATIAN("sh", "Serbo-Croatian", "Srpskohrvatski / Српскохрватски", Probe.READER, wordStart = true, spaced = true, voice = "hr-HR", practice = "Jučer smo išli kod bake na ručak.", practiceWord = "išli"),
    TAGALOG("tl", "Tagalog", "Tagalog", Probe.READER, wordStart = true, spaced = true, voice = "fil-PH", practice = "Nagluluto ang nanay ko ng adobo para sa hapunan.", practiceWord = "Nagluluto"),
    UKRAINIAN("uk", "Ukrainian", "Українська", Probe.READER, wordStart = true, spaced = true, voice = "uk-UA", practice = "Учора ми довго гуляли містом і читали старі вивіски.", practiceWord = "гуляли"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt", Probe.READER, wordStart = true, spaced = true, voice = "vi-VN", practice = "Chiếc áo màu đo đỏ treo trên tường.", practiceWord = "đo đỏ"),
    YIDDISH("yi", "Yiddish", "ייִדיש", Probe.READER, wordStart = true, spaced = true, rightToLeft = true, voice = "yi", practice = "נעכטן האָב איך געשריבן אַ בריוו צו מײַן חבֿר.", practiceWord = "געשריבן");

    enum class Probe {
        /** VOCR, a small reader that knows only Japanese: quick enough to look at every picture. */
        VOCR,

        /** The reader itself, looking twice: for a language VOCR cannot read. */
        READER,
    }

    /** Kana repair, furigana, pitch accent, VNDB's character names and the text clean-up are Japanese's alone. */
    val japanese: Boolean get() = this == JAPANESE

    /** A tapped character has an entry of its own: kanji and hanzi. */
    val characters: Boolean get() = this == JAPANESE || this == CHINESE

    companion object {
        /** The language with [tag]; null for an empty tag (not chosen yet) or one this build does not know. */
        fun of(tag: String): Language? = entries.firstOrNull { it.tag == tag }

        /** The language with [tag], or Japanese: for what has to read something before the question is answered. */
        fun orJapanese(tag: String): Language = of(tag) ?: JAPANESE

        /** How many lead the list at set-up; the rest are behind "More". */
        const val COMMON = 10
    }
}
