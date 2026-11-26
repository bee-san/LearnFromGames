// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg

import android.content.Context
import android.content.SharedPreferences
import io.github.beesan.lfg.core.Language
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.ReadingGoals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few choices the app has. Every one has a default, so none is ever asked for. */
data class Prefs(
    /**
     * One of the SHORTCUT_ values: a pair of buttons pressed together, or one button alone. Or the
     * buttons pressed at set-up, as [io.github.beesan.lfg.core.Shortcut] saves them.
     */
    val shortcut: String = SHORTCUT_L3_R3,
    /** Swap which face button adds a card and which closes, for pads printed the other way round. */
    val swapConfirm: Boolean = false,
    /**
     * The left stick steps by word and the D-pad by character, not the other way
     * round: for a handheld whose stick is where the thumb rests, as the AYN Thor's is.
     */
    val stickWords: Boolean = false,
    /**
     * When a tap on a game's text looks the word up: [TAP_ALWAYS], [TAP_SHORTCUT], only between
     * two presses of the shortcut, or [TAP_AUTO], which is the second on a device with a controller.
     */
    val tapLookup: String = TAP_AUTO,
    /** Days a line's screen is kept, unless a card or a recap took it; 0 keeps every one. */
    val keepScreensDays: Int = 14,
    /** The first run's downloads wait for an unmetered network. */
    val wifiOnly: Boolean = false,
    /** Dictionaries that name where their next release is are brought up to date once a day. */
    val updateDictionaries: Boolean = true,
    /** The title of the dictionary a tapped kanji is looked up in; empty for Bee's Ultimate Kanji Dictionary. */
    val kanjiDictionary: String = "",
    /** On a two-screen handheld, the screen the game is not on shows the game's lines as they are read. */
    val secondScreenPanel: Boolean = true,
    /** On a two-screen handheld, the app's own pages open on the second screen, wherever they were started from. */
    val appOnSecondScreen: Boolean = true,
    /**
     * Where a looked-up word's entry shows on a two-screen handheld: [ENTRY_OVER_GAME], a
     * popup beside the word, or [ENTRY_OTHER_SCREEN], on the lines' screen. One screen always has the popup.
     */
    val entryPlace: String = ENTRY_OVER_GAME,
    /** How much of the game shows through the popup over it, as a percentage: 0 is solid. */
    val popupSeeThrough: Int = 12,
    /** [SELECT_WORD] or [SELECT_CHARACTER]: what a lookup marks, and how far the D-pad's Right goes. Rubi for Vita's Selection mode. */
    val selection: String = SELECT_WORD,
    /** The cursor and a tap land only on kana and kanji. Off, on Latin letters and digits too; punctuation is always passed over. */
    val japaneseOnly: Boolean = false,
    /** Sentence audio from a rolling buffer of game sound. Needs screen recording consent each session. */
    val sentenceAudio: Boolean = true,
    /** Android's voice reads the line onto a card the game gave no voice. */
    val spokenLines: Boolean = true,
    /** An animated picture of the moments before the card was made, instead of a still. */
    val animatedPicture: Boolean = false,
    /** A card's picture loses the black bars an emulator puts round the game. */
    val trimBars: Boolean = true,
    /** Daily reading goals on the stats screen; 0 is none. */
    val goalMinutes: Int = 60,
    val goalChars: Int = 5_000,
    /** Cards a day, a ring on the Today widget; 0 is none. Not part of the reading goals' streak. */
    val goalCards: Int = 10,
    /**
     * The Today widget's layout: "auto" lets the launcher's size pick it; "small",
     * "wide" or "large" fix it, for launchers (Cocoon) that never pass the size on.
     */
    val widgetSize: String = "auto",
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
    /** Whether Ask may send questions about a game, with its last lines, to OpenRouter, which searches the web for them. */
    val ask: Boolean = false,
    /** The folder automatic backups and the lines file go to: a document tree's link, or empty for none. */
    val backupFolder: String = "",
    /** [BACKUP_OFF], [BACKUP_DAILY] or [BACKUP_WEEKLY]. */
    val backupEvery: String = BACKUP_OFF,
    /**
     * When the diary's days are written by the model: [DIARY_WITH_RECAP], only
     * when a recap is, or [DIARY_EACH_DAY], also once each day is over.
     */
    val diaryWrite: String = DIARY_EACH_DAY,
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
    /**
     * Keep the lines read from ordinary apps, as a game's are. Off: chats and
     * mail can be private, so a lookup there leaves nothing behind but the
     * line a card is made from.
     */
    val keepAppLines: Boolean = false,
    /** A small LFG button over ordinary apps: a tap reads the screen, a long press reads a region. */
    val readButton: Boolean = false,
    /** Kept lines from apps count towards reading time and the goals, beside games. */
    val appsInReadingTime: Boolean = true,
    /**
     * Menu labels, a HUD's words and name plates are told apart from lines
     * ([io.github.beesan.lfg.core.InterfaceText]) and left out of the lines
     * list, the stats and the lines file. Turned off, every line is back.
     */
    val interfaceApart: Boolean = true,
    /** A game is linked to its visual novel on VNDB by itself when the match is certain, for its character names. */
    val namesAuto: Boolean = true,
    /** Character names also match with each honorific after them (七瀬さん). */
    val namesHonorifics: Boolean = true,
    /** Character cards keep what VNDB marks as spoilers. */
    val namesSpoilers: Boolean = false,
    /**
     * Keep the whole screen of every new line of game dialogue for good, with
     * what was read from it, for the player to send to train a better reader.
     * Asked once at set-up ([keepDialogueAsked]); nothing is sent by itself.
     */
    val keepDialogue: Boolean = false,
    val keepDialogueAsked: Boolean = false,
    /**
     * The tag of the language being learnt ([io.github.beesan.lfg.core.Language]), asked at set-up;
     * empty until it is answered.
     */
    val language: String = "",
    /** Readings are drawn over the kanji of a game's text: Furigana on the wheel. */
    val furigana: Boolean = false,
    /** Words of a game's text are marked by their Anki card: Known words on the wheel. */
    val knownWords: Boolean = false,
    /** A game can have a wheel of its own ([Wheels]); the others use the shared one. */
    val wheelPerGame: Boolean = false,
    /** How long the shortcut is held before the wheel opens, in milliseconds. */
    val wheelHoldMs: Int = 350,
    /** With the shortcut still held, pointing at a slice that opens a wheel opens it at once, so letting go picks inside it. */
    val wheelOpenHeld: Boolean = true,
    /** [WHEEL_POINT]: each way of the D-pad is a slice, up to 8. [WHEEL_STEP]: left and right go round a wheel of any size. */
    val wheelDpad: String = WHEEL_POINT,
    /** How far a stick is pushed before it points at a slice, as a percentage of all the way. */
    val wheelReach: Int = 75,
    /** A tick is felt for each slice pointed at. */
    val wheelTick: Boolean = true,
    /** Each wheel opens pointing at the slice picked in it last. */
    val wheelStartLast: Boolean = false,
    /** The wheel stays open after a pick that opens nothing over the game: Keep or Copy several times. */
    val wheelStay: Boolean = false,
    /** Slices show their icons without their names. */
    val wheelIconsOnly: Boolean = false,
    /** The whole wheel scaled, as a percentage. */
    val wheelSize: Int = 100,
    /** How dark the game is behind the wheel, as a percentage. */
    val wheelDim: Int = 60,
    /** The app's buttons click as they are pressed and let go. */
    val buttonSounds: Boolean = true,
    /** Over a game the wheel ticks from slice to slice and thocks at a pick, lookup ticks from word to word, and a card made chimes. */
    val gameSounds: Boolean = true,
    /** How loud the app's own sounds are, as a percentage: one of [SOUND_VOLUME_STEPS]. */
    val soundVolume: Int = 100,
    /** Set-up has been gone through to its end; until then the app opens on it, at [setupStep] (a [io.github.beesan.lfg.core.SetupStep]'s name). */
    val setupDone: Boolean = false,
    val setupStep: String = "",
    /** Said at set-up: a controller is used, so the tutorial is the controller's and not the finger's. */
    val usesController: Boolean = false,
) {
    val goals: ReadingGoals get() = ReadingGoals(goalMinutes, goalChars)

    /** Translate and Explain are offered wherever a line shows, once there is a key to ask with. */
    val aiReady: Boolean get() = openRouterKey.isNotBlank()

    /** The language being learnt; Japanese until the question is answered. */
    val learning: Language get() = Language.orJapanese(language)

    /**
     * These settings, a profile's, with what every profile shares taken from [from], the settings in use:
     * set-up, the keys, backups, the game folders, storage and downloads, and the widget's size.
     * A setting not named here is each profile's own.
     */
    fun withShared(from: Prefs): Prefs = copy(
        setupDone = from.setupDone, setupStep = from.setupStep,
        keepDialogue = from.keepDialogue, keepDialogueAsked = from.keepDialogueAsked,
        openRouterKey = from.openRouterKey, steamKey = from.steamKey, steamAccount = from.steamAccount,
        backupFolder = from.backupFolder, backupEvery = from.backupEvery, backupKeep = from.backupKeep,
        backupPictures = from.backupPictures, backupLast = from.backupLast, backupLastSize = from.backupLastSize, gsmFile = from.gsmFile,
        cocoonFolder = from.cocoonFolder, esdeFolder = from.esdeFolder, retroArchFolder = from.retroArchFolder, edenFolder = from.edenFolder,
        keepScreensDays = from.keepScreensDays, wifiOnly = from.wifiOnly, updateDictionaries = from.updateDictionaries,
        widgetSize = from.widgetSize,
    )

    val picking: io.github.beesan.lfg.core.Selection
        get() = io.github.beesan.lfg.core.Selection(selection != SELECT_CHARACTER, japaneseOnly && learning.japanese, learning.wordStart)

    companion object {
        const val SHORTCUT_L3_R3 = "l3r3"
        const val SHORTCUT_L1_R1 = "l1r1"

        /** The triggers: on pads that send them as buttons too, as the Thor's does. */
        const val SHORTCUT_L2_R2 = "l2r2"
        const val TAP_AUTO = io.github.beesan.lfg.core.TapGate.AUTO
        const val TAP_ALWAYS = io.github.beesan.lfg.core.TapGate.ALWAYS
        const val TAP_SHORTCUT = io.github.beesan.lfg.core.TapGate.SHORTCUT
        const val SHORTCUT_SELECT_START = "selectstart"

        /** One button alone, which the game then never receives. */
        const val SHORTCUT_L3 = "l3"
        const val SHORTCUT_R3 = "r3"
        const val SHORTCUT_SELECT = "select"
        const val SHORTCUT_START = "start"
        const val ENTRY_OVER_GAME = "game"
        const val ENTRY_OTHER_SCREEN = "other"
        const val SELECT_WORD = "word"
        const val SELECT_CHARACTER = "character"
        val SEE_THROUGH_STEPS = listOf(0, 6, 12, 20, 30, 40)
        const val TRANSLATE_OFF = "off"
        const val TRANSLATE_ASK = "ask"
        const val TRANSLATE_EVERY = "every"
        const val DIARY_WITH_RECAP = "recap"
        const val DIARY_EACH_DAY = "day"
        const val BACKUP_OFF = "off"
        const val BACKUP_DAILY = "daily"
        const val BACKUP_WEEKLY = "weekly"
        val CONTEXT_STEPS = listOf(0, 3, 5, 10, 15, 20)
        const val WHEEL_POINT = "point"
        const val WHEEL_STEP = "step"
        val WHEEL_HOLD_STEPS = listOf(250, 350, 500, 700)
        val WHEEL_REACH_STEPS = listOf(50, 75, 90)
        val WHEEL_SIZE_STEPS = listOf(80, 100, 120)
        val WHEEL_DIM_STEPS = listOf(40, 60, 80)
        val SOUND_VOLUME_STEPS = listOf(50, 100, 180)
    }
}

class Settings(context: Context) {
    private val app = context.applicationContext
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The OpenRouter and Steam keys, in a file of their own so a backup of the settings never holds them. */
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read(store))
    val prefs: StateFlow<Prefs> = _prefs

    /** The settings [store] holds: the ones in use, or those of a profile not in use, which is the same names in a file of its own. The two keys come from their own file either way. */
    private fun read(store: SharedPreferences) = Prefs().let { d ->
        Prefs(
            shortcut = store.getString("shortcut", d.shortcut) ?: d.shortcut,
            swapConfirm = store.getBoolean("swapConfirm", d.swapConfirm),
            tapLookup = store.getString("tapLookup", d.tapLookup) ?: d.tapLookup,
            stickWords = store.getBoolean("stickWords", d.stickWords),
            keepScreensDays = store.getInt("keepScreensDays", d.keepScreensDays),
            wifiOnly = store.getBoolean("wifiOnly", d.wifiOnly),
            updateDictionaries = store.getBoolean("updateDictionaries", d.updateDictionaries),
            kanjiDictionary = store.getString("kanjiDictionary", d.kanjiDictionary) ?: d.kanjiDictionary,
            secondScreenPanel = store.getBoolean("secondScreenPanel", d.secondScreenPanel),
            appOnSecondScreen = store.getBoolean("appOnSecondScreen", d.appOnSecondScreen),
            entryPlace = store.getString("entryPlace", d.entryPlace) ?: d.entryPlace,
            popupSeeThrough = store.getInt("popupSeeThrough", d.popupSeeThrough),
            selection = store.getString("selection", d.selection) ?: d.selection,
            japaneseOnly = store.getBoolean("japaneseOnly", d.japaneseOnly),
            sentenceAudio = store.getBoolean("sentenceAudio", d.sentenceAudio),
            spokenLines = store.getBoolean("spokenLines", d.spokenLines),
            animatedPicture = store.getBoolean("animatedPicture", d.animatedPicture),
            trimBars = store.getBoolean("trimBars", d.trimBars),
            goalMinutes = store.getInt("goalMinutes", d.goalMinutes),
            goalChars = store.getInt("goalChars", d.goalChars),
            goalCards = store.getInt("goalCards", d.goalCards),
            widgetSize = store.getString("widgetSize", d.widgetSize) ?: d.widgetSize,
            colours = store.getString("colours", d.colours) ?: d.colours,
            theme = store.getString("theme", d.theme) ?: d.theme,
            translate = store.getString("translate", d.translate) ?: d.translate,
            openRouterKey = secrets.getString("openRouterKey", d.openRouterKey) ?: d.openRouterKey,
            model = store.getString("model", d.model) ?: d.model,
            translateInto = store.getString("translateInto", d.translateInto) ?: d.translateInto,
            translateContext = store.getInt("translateContext", d.translateContext),
            ask = store.getBoolean("ask", d.ask),
            backupFolder = store.getString("backupFolder", d.backupFolder) ?: d.backupFolder,
            backupEvery = store.getString("backupEvery", d.backupEvery) ?: d.backupEvery,
            diaryWrite = store.getString("diaryWrite", d.diaryWrite) ?: d.diaryWrite,
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
            keepAppLines = store.getBoolean("keepAppLines", d.keepAppLines),
            readButton = store.getBoolean("readButton", d.readButton),
            appsInReadingTime = store.getBoolean("appsInReadingTime", d.appsInReadingTime),
            interfaceApart = store.getBoolean("interfaceApart", d.interfaceApart),
            namesAuto = store.getBoolean("namesAuto", d.namesAuto),
            namesHonorifics = store.getBoolean("namesHonorifics", d.namesHonorifics),
            namesSpoilers = store.getBoolean("namesSpoilers", d.namesSpoilers),
            keepDialogue = store.getBoolean("keepDialogue", d.keepDialogue),
            keepDialogueAsked = store.getBoolean("keepDialogueAsked", d.keepDialogueAsked),
            // Set up before the question existed, when there was only Japanese: not asked again.
            language = store.getString("language", null) ?: if (store.getBoolean("keepDialogueAsked", false)) Language.JAPANESE.tag else d.language,
            furigana = store.getBoolean("furigana", d.furigana),
            knownWords = store.getBoolean("knownWords", d.knownWords),
            wheelPerGame = store.getBoolean("wheelPerGame", d.wheelPerGame),
            wheelHoldMs = store.getInt("wheelHoldMs", d.wheelHoldMs),
            wheelOpenHeld = store.getBoolean("wheelOpenHeld", d.wheelOpenHeld),
            wheelDpad = store.getString("wheelDpad", d.wheelDpad) ?: d.wheelDpad,
            wheelReach = store.getInt("wheelReach", d.wheelReach),
            wheelTick = store.getBoolean("wheelTick", d.wheelTick),
            wheelStartLast = store.getBoolean("wheelStartLast", d.wheelStartLast),
            wheelStay = store.getBoolean("wheelStay", d.wheelStay),
            wheelIconsOnly = store.getBoolean("wheelIconsOnly", d.wheelIconsOnly),
            wheelSize = store.getInt("wheelSize", d.wheelSize),
            wheelDim = store.getInt("wheelDim", d.wheelDim),
            buttonSounds = store.getBoolean("buttonSounds", d.buttonSounds),
            gameSounds = store.getBoolean("gameSounds", d.gameSounds),
            soundVolume = store.getInt("soundVolume", d.soundVolume),
            // Set up before set-up had steps: not gone through again.
            setupDone = store.getBoolean("setupDone", store.getBoolean("keepDialogueAsked", false)),
            setupStep = store.getString("setupStep", d.setupStep) ?: d.setupStep,
            usesController = store.getBoolean("usesController", d.usesController),
        )
    }

    fun update(change: (Prefs) -> Prefs) {
        val next = change(_prefs.value)
        write(store.edit(), next).apply()
        secrets.edit().putString("openRouterKey", next.openRouterKey).putString("steamKey", next.steamKey).apply()
        _prefs.value = next
    }

    /** The settings a profile not in use was left with ([io.github.beesan.lfg.Profiles]), from its own [file]; null when it holds none. */
    fun saved(file: String): Prefs? = app.getSharedPreferences(file, Context.MODE_PRIVATE).takeIf { it.all.isNotEmpty() }?.let(::read)

    /** [prefs] kept in [file] for a profile no longer in use. Written before it returns: the settings in use change straight after. */
    fun save(file: String, prefs: Prefs) {
        write(app.getSharedPreferences(file, Context.MODE_PRIVATE).edit(), prefs).commit()
    }

    private fun write(to: SharedPreferences.Editor, next: Prefs): SharedPreferences.Editor =
        to.putString("shortcut", next.shortcut)
            .putBoolean("swapConfirm", next.swapConfirm)
            .putString("tapLookup", next.tapLookup)
            .putBoolean("stickWords", next.stickWords)
            .putInt("keepScreensDays", next.keepScreensDays)
            .putBoolean("wifiOnly", next.wifiOnly)
            .putBoolean("updateDictionaries", next.updateDictionaries)
            .putString("kanjiDictionary", next.kanjiDictionary)
            .putBoolean("secondScreenPanel", next.secondScreenPanel)
            .putBoolean("appOnSecondScreen", next.appOnSecondScreen)
            .putString("entryPlace", next.entryPlace)
            .putInt("popupSeeThrough", next.popupSeeThrough)
            .putString("selection", next.selection)
            .putBoolean("japaneseOnly", next.japaneseOnly)
            .putBoolean("sentenceAudio", next.sentenceAudio)
            .putBoolean("spokenLines", next.spokenLines)
            .putBoolean("animatedPicture", next.animatedPicture)
            .putBoolean("trimBars", next.trimBars)
            .putInt("goalMinutes", next.goalMinutes)
            .putInt("goalChars", next.goalChars)
            .putInt("goalCards", next.goalCards)
            .putString("widgetSize", next.widgetSize)
            .putString("colours", next.colours)
            .putString("theme", next.theme)
            .putString("translate", next.translate)
            .putString("model", next.model)
            .putString("translateInto", next.translateInto)
            .putInt("translateContext", next.translateContext)
            .putBoolean("ask", next.ask)
            .putString("backupFolder", next.backupFolder)
            .putString("backupEvery", next.backupEvery)
            .putString("diaryWrite", next.diaryWrite)
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
            .putBoolean("keepAppLines", next.keepAppLines)
            .putBoolean("readButton", next.readButton)
            .putBoolean("appsInReadingTime", next.appsInReadingTime)
            .putBoolean("interfaceApart", next.interfaceApart)
            .putBoolean("namesAuto", next.namesAuto)
            .putBoolean("namesHonorifics", next.namesHonorifics)
            .putBoolean("namesSpoilers", next.namesSpoilers)
            .putBoolean("keepDialogue", next.keepDialogue)
            .putBoolean("keepDialogueAsked", next.keepDialogueAsked)
            .putString("language", next.language)
            .putBoolean("furigana", next.furigana)
            .putBoolean("knownWords", next.knownWords)
            .putBoolean("wheelPerGame", next.wheelPerGame)
            .putInt("wheelHoldMs", next.wheelHoldMs)
            .putBoolean("wheelOpenHeld", next.wheelOpenHeld)
            .putString("wheelDpad", next.wheelDpad)
            .putInt("wheelReach", next.wheelReach)
            .putBoolean("wheelTick", next.wheelTick)
            .putBoolean("wheelStartLast", next.wheelStartLast)
            .putBoolean("wheelStay", next.wheelStay)
            .putBoolean("wheelIconsOnly", next.wheelIconsOnly)
            .putInt("wheelSize", next.wheelSize)
            .putInt("wheelDim", next.wheelDim)
            .putBoolean("buttonSounds", next.buttonSounds)
            .putBoolean("gameSounds", next.gameSounds)
            .putInt("soundVolume", next.soundVolume)
            .putBoolean("setupDone", next.setupDone)
            .putString("setupStep", next.setupStep)
            .putBoolean("usesController", next.usesController)
}
