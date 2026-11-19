// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.Context
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.ReadingGoals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few choices the app has. Every one has a default, so none is ever asked for. */
data class Prefs(
    /** [SHORTCUT_L3_R3] or [SHORTCUT_L1_R1]. */
    val shortcut: String = SHORTCUT_L3_R3,
    /** Swap which face button adds a card and which closes, for pads printed the other way round. */
    val swapConfirm: Boolean = false,
    /** [ENGINE_AUTO], [ENGINE_SCREENAI] or [ENGINE_MEIKI]. */
    val engine: String = ENGINE_AUTO,
    /** On a two-screen handheld, show the entry on the screen the game is not on. */
    val secondScreenPanel: Boolean = true,
    /** Sentence audio from a rolling buffer of game sound. Needs screen recording consent each session. */
    val sentenceAudio: Boolean = false,
    /** Android's voice reads the line onto a card the game gave no voice. */
    val spokenLines: Boolean = true,
    /** An animated picture of the moments before the card was made, instead of a still. */
    val animatedPicture: Boolean = false,
    /** Daily reading goals on the stats screen; 0 is none. */
    val goalMinutes: Int = 60,
    val goalChars: Int = 5_000,
    /** Cards a day, a ring on the Today widget; 0 is none. Not part of the reading goals' streak. */
    val goalCards: Int = 10,
    /** One of [Palette.SCHEMES]. */
    val colours: String = Palette.PINK,
    /** [Palette.THEME_SYSTEM], [Palette.THEME_LIGHT] or [Palette.THEME_DARK]. */
    val theme: String = Palette.THEME_SYSTEM,
    /** [TRANSLATE_OFF], [TRANSLATE_ASK] or [TRANSLATE_EVERY]. */
    val translate: String = TRANSLATE_OFF,
    /** The OpenRouter key translations and recaps use. Kept apart from the rest, so a backup never carries it. */
    val openRouterKey: String = "",
    /** The OpenRouter model that translates and writes recaps. */
    val model: String = "openai/gpt-oss-120b",
    /** The language translations and recaps are written in. */
    val translateInto: String = "English",
    /** How many lines before a line are sent with it, so its translation knows who is speaking. */
    val translateContext: Int = 10,
    /** The folder automatic backups and the lines file go to: a document tree's link, or empty for none. */
    val backupFolder: String = "",
    /** [BACKUP_OFF], [BACKUP_DAILY] or [BACKUP_WEEKLY]. */
    val backupEvery: String = BACKUP_OFF,
    /** How many automatic backups are kept; 0 keeps all. */
    val backupKeep: Int = 7,
    /** Whether backups carry the pictures of lines and notes. */
    val backupPictures: Boolean = true,
    /** Each automatic backup also writes lfg-lines.csv, every line in the CSV GameSentenceMiner's ExStatic import reads. */
    val gsmFile: Boolean = false,
    /** When the last automatic backup was made, in milliseconds since 1970, and its size in bytes; 0 for none yet. */
    val backupLast: Long = 0,
    val backupLastSize: Long = 0,
    /** Folders the game inside an emulator is named from (Settings › Game names): document trees' links, or empty. */
    val cocoonFolder: String = "",
    val esdeFolder: String = "",
    val retroArchFolder: String = "",
    val edenFolder: String = "",
    /** The Steam profile asked what is being played: a SteamID64, a profile link or a custom name. */
    val steamAccount: String = "",
    /** The Steam Web API key. Kept apart from the rest, like the OpenRouter key. */
    val steamKey: String = "",
) {
    val goals: ReadingGoals get() = ReadingGoals(goalMinutes, goalChars)

    companion object {
        const val SHORTCUT_L3_R3 = "l3r3"
        const val SHORTCUT_L1_R1 = "l1r1"
        const val ENGINE_AUTO = "auto"
        const val ENGINE_SCREENAI = "screenai"
        const val ENGINE_MEIKI = "meiki"
        const val TRANSLATE_OFF = "off"
        const val TRANSLATE_ASK = "ask"
        const val TRANSLATE_EVERY = "every"
        const val BACKUP_OFF = "off"
        const val BACKUP_DAILY = "daily"
        const val BACKUP_WEEKLY = "weekly"
        val CONTEXT_STEPS = listOf(0, 3, 5, 10, 15, 20)
    }
}

class Settings(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The OpenRouter and Steam keys, in a file of their own so a backup of the settings never holds them. */
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = _prefs

    private fun read() = Prefs().let { d ->
        Prefs(
            shortcut = store.getString("shortcut", d.shortcut) ?: d.shortcut,
            swapConfirm = store.getBoolean("swapConfirm", d.swapConfirm),
            engine = store.getString("engine", d.engine) ?: d.engine,
            secondScreenPanel = store.getBoolean("secondScreenPanel", d.secondScreenPanel),
            sentenceAudio = store.getBoolean("sentenceAudio", d.sentenceAudio),
            spokenLines = store.getBoolean("spokenLines", d.spokenLines),
            animatedPicture = store.getBoolean("animatedPicture", d.animatedPicture),
            goalMinutes = store.getInt("goalMinutes", d.goalMinutes),
            goalChars = store.getInt("goalChars", d.goalChars),
            goalCards = store.getInt("goalCards", d.goalCards),
            colours = store.getString("colours", d.colours) ?: d.colours,
            theme = store.getString("theme", d.theme) ?: d.theme,
            translate = store.getString("translate", d.translate) ?: d.translate,
            openRouterKey = secrets.getString("openRouterKey", d.openRouterKey) ?: d.openRouterKey,
            model = store.getString("model", d.model) ?: d.model,
            translateInto = store.getString("translateInto", d.translateInto) ?: d.translateInto,
            translateContext = store.getInt("translateContext", d.translateContext),
            backupFolder = store.getString("backupFolder", d.backupFolder) ?: d.backupFolder,
            backupEvery = store.getString("backupEvery", d.backupEvery) ?: d.backupEvery,
            backupKeep = store.getInt("backupKeep", d.backupKeep),
            backupPictures = store.getBoolean("backupPictures", d.backupPictures),
            gsmFile = store.getBoolean("gsmFile", d.gsmFile),
            backupLast = store.getLong("backupLast", d.backupLast),
            backupLastSize = store.getLong("backupLastSize", d.backupLastSize),
            cocoonFolder = store.getString("cocoonFolder", d.cocoonFolder) ?: d.cocoonFolder,
            esdeFolder = store.getString("esdeFolder", d.esdeFolder) ?: d.esdeFolder,
            retroArchFolder = store.getString("retroArchFolder", d.retroArchFolder) ?: d.retroArchFolder,
            edenFolder = store.getString("edenFolder", d.edenFolder) ?: d.edenFolder,
            steamAccount = store.getString("steamAccount", d.steamAccount) ?: d.steamAccount,
            steamKey = secrets.getString("steamKey", d.steamKey) ?: d.steamKey,
        )
    }

    fun update(change: (Prefs) -> Prefs) {
        val next = change(_prefs.value)
        store.edit()
            .putString("shortcut", next.shortcut)
            .putBoolean("swapConfirm", next.swapConfirm)
            .putString("engine", next.engine)
            .putBoolean("secondScreenPanel", next.secondScreenPanel)
            .putBoolean("sentenceAudio", next.sentenceAudio)
            .putBoolean("spokenLines", next.spokenLines)
            .putBoolean("animatedPicture", next.animatedPicture)
            .putInt("goalMinutes", next.goalMinutes)
            .putInt("goalChars", next.goalChars)
            .putInt("goalCards", next.goalCards)
            .putString("colours", next.colours)
            .putString("theme", next.theme)
            .putString("translate", next.translate)
            .putString("model", next.model)
            .putString("translateInto", next.translateInto)
            .putInt("translateContext", next.translateContext)
            .putString("backupFolder", next.backupFolder)
            .putString("backupEvery", next.backupEvery)
            .putInt("backupKeep", next.backupKeep)
            .putBoolean("backupPictures", next.backupPictures)
            .putBoolean("gsmFile", next.gsmFile)
            .putLong("backupLast", next.backupLast)
            .putLong("backupLastSize", next.backupLastSize)
            .putString("cocoonFolder", next.cocoonFolder)
            .putString("esdeFolder", next.esdeFolder)
            .putString("retroArchFolder", next.retroArchFolder)
            .putString("edenFolder", next.edenFolder)
            .putString("steamAccount", next.steamAccount)
            .apply()
        secrets.edit().putString("openRouterKey", next.openRouterKey).putString("steamKey", next.steamKey).apply()
        _prefs.value = next
    }
}
