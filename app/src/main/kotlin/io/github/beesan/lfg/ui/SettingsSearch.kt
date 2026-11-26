// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import java.util.Locale

/**
 * One thing Settings' search can find: a setting by the [name] its page shows,
 * a line of [detail], the [page] it is on with that page's title, and [words]
 * someone might type for it that the rest does not say. A page itself is a hit
 * too, its [name] and [pageName] the same.
 */
data class SettingHit(val name: String, val detail: String, val page: Page, val pageName: String, val words: String = "")

/**
 * Every setting, for Settings' search. Kept by hand: a setting added to a page,
 * renamed or moved is added, renamed or moved here too. No Android in it, so
 * tests can run it.
 */
object SettingsIndex {
    /** A page of Settings, then what is on it. */
    private fun page(page: Page, title: String, detail: String, words: String = "", settings: Section.() -> Unit = {}): List<SettingHit> =
        listOf(SettingHit(title, detail, page, title, words)) + Section(page, title).apply(settings).hits

    private class Section(val page: Page, val title: String) {
        val hits = mutableListOf<SettingHit>()

        fun setting(name: String, detail: String, words: String = "") {
            hits += SettingHit(name, detail, page, title, words)
        }
    }

    /**
     * In the order of the Settings list, and of each page. Only pages that open
     * with nothing chosen first: what is on a page inside one of them (a game's
     * boxes, the wheel's tiles, the sources) points at the page it is reached from.
     */
    val ALL: List<SettingHit> = listOf(
        page(Page.LANGUAGE, "Language", "The language you are learning", "japanese chinese korean spanish french german study learn"),
        page(Page.PROFILES, "Profiles", "A language and settings of its own for each", "second language two languages switch change user account person share") {
            setting("New profile", "Another language, starting with the settings you have now", "add create make")
            setting("Add to quick settings", "A tile for each profile, to change to it from anywhere", "tile shade notification quick switch")
            setting("Rename", "Call a profile what you like", "name")
        },
        page(Page.APPEARANCE, "Appearance", "Colours, light or dark, and sounds", "look style display") {
            setting("Theme", "Light, dark, or the same as the system", "dark mode night black white")
            setting("Colours", "Pink, mono, amber, teal or purple", "colors color colour scheme palette accent")
            setting("Button sounds", "Buttons click when you press them", "click audio mute sfx")
            setting("Wheel and lookup sounds", "A tick as you move, a thock when you pick, a chime for a new card", "audio sfx mute tick click chime radial ring menu")
            setting("Sound volume", "How loud LFG’s own sounds are", "audio loud quiet level sfx")
        },
        page(Page.BACKUP, "Backup", "Back up, restore, and back up on a schedule", "save copy move new device") {
            setting("Back up now", "Lines, stats, the diary, recaps, waiting cards and settings, in one file", "backup save export")
            setting("Restore", "Replaces what is here with a backup", "import load")
            setting("Export lines", "A spreadsheet of every line: time, game, text and characters", "csv export excel sheet")
            setting("Lines file for GSM", "Keeps every line in a file GameSentenceMiner can import", "gamesentenceminer exstatic csv")
            setting("How often", "Automatic backup: off, daily or weekly", "auto schedule every day week")
            setting("Folder", "Where automatic backups go", "auto directory location cloud sync drive")
            setting("Keep", "Older automatic backups are deleted", "auto how many delete old last")
            setting("Pictures", "Backups with or without the pictures of lines and diary entries", "images screenshots size include")
        },
        page(Page.TRANSLATION, "Translation", "Translate and explain lines with your OpenRouter key", "ai llm translate") {
            setting("Translate", "When asked, or every line as it is read", "ai automatic auto explain")
            setting("OpenRouter key", "Kept on this device, and never in a backup", "api key ai openrouter token password")
            setting("Ask", "Questions about a game go to OpenRouter, which searches the web", "ai question chat web search")
            setting("Model", "Translates and explains lines, writes recaps and answers questions", "ai llm gpt claude gemini")
            setting("Into", "The language translations, explanations, recaps and answers are in", "english target")
            setting("Lines before", "Sent with the line, so the answer knows who is speaking", "context history ai")
            setting("Test the key", "Checks that your OpenRouter key works", "api ai check try")
        },
        page(Page.DIARY_SETTINGS, "Diary", "When the model writes each day you played", "journal ai") {
            setting("Write each day", "A title and a few sentences about each day you played, written by the model", "journal ai recap automatic")
        },
        page(Page.DICTIONARIES, "Dictionaries", "Add, order and remove dictionaries", "dictionary yomitan yomichan jmdict jitendex") {
            setting("Add dictionary…", "Add a dictionary from a file on this device", "import install zip")
            setting("Starter set", "Hachidori's five: meanings, names, kanji, frequencies and grammar", "download default first")
            setting("Move up, Move down", "Entries show in the dictionaries' order", "sort priority first")
            setting("Remove", "Takes a dictionary off this device", "delete uninstall")
            setting("Kanji", "The dictionary a tapped kanji is looked up in", "character hanzi")
            setting("Show sources", "Who made each dictionary, and where it comes from", "credits attribution licence license about")
            setting("Updates", "A dictionary's next release replaces it, and keeps its place", "automatic auto update check now new version")
            setting("Word audio", "Words said by people, from the Local Audio Server add-on for Anki", "pronunciation sound voice android.db yomichan")
        },
        page(Page.ANKI, "Anki", "The note type and deck cards go to", "flashcards ankidroid srs mining") {
            setting("AnkiDroid", "Connect LFG to AnkiDroid, where cards go", "connect install permission flashcards")
            setting("Note type", "The kind of note a card is made as", "model template fields kiku lapis senren")
            setting("Deck", "The deck cards go to", "flashcards deck")
            setting("Find again", "Looks for a Kiku, Lapis or Senren note type and the deck with most of its notes", "detect search refresh")
        },
        page(Page.CONTROLS, "Controls", "The lookup shortcut, whole words or characters, and where the entry shows", "controller gamepad pad buttons") {
            setting("Lookup shortcut", "One button starts and stops lookup", "hotkey button l3 r3 select start bind")
            setting("Or two buttons together", "A pair of buttons for lookup, which the game gets too", "hotkey shortcut combo l1 r1 l2 r2 l3 r3 select start")
            setting("Stick and D-pad", "Which goes word by word, and which a character at a time", "dpad joystick analog cursor move")
            setting("Tap a word", "When a tap on a game's text looks the word up", "touch touchscreen finger")
            setting("Add card button", "Which face button adds the card; the other one closes", "a b swap confirm cancel nintendo xbox")
            setting("Selection", "A lookup marks the whole word, or one character", "highlight cursor")
            setting("Japanese characters only", "The cursor and a tap land only on kana and kanji", "skip digits numbers latin letters english")
            setting("Lines on the other screen", "On a two-screen handheld, the other screen lists the game's lines", "dual second display two screens")
            setting("LFG on the second screen", "On a two-screen handheld, LFG's own pages open on the second screen", "dual display two screens")
            setting("Dictionary entry", "A popup beside the word over the game, or on the lines' screen", "definition where place dual two screens")
            setting("Popup see-through", "How much of the game shows through the popup over it", "transparent transparency opacity")
        },
        page(Page.WHEEL, "Wheel", "Its tiles, the wheels inside it, and how it opens and is pointed at", "radial menu ring") {
            setting("Wheel for", "A game can have its own wheel. Others use the shared one.", "each per game all shared")
            setting("Whose wheel", "The game whose wheel you are changing", "choose game shared")
            setting("Edit wheel…", "Its tiles, and the wheels inside it", "slices actions add remove change furigana known words")
            setting("Copy wheel", "Save the wheel as a file, or load one saved before", "export import share save load")
            setting("Reset wheel", "Back to the wheel LFG starts with", "default restore undo")
            setting("Lookup shortcut", "Tap for lookup, hold for the wheel. Set in Controls.", "hotkey button open")
            setting("Hold for the wheel", "How long the shortcut is held before the wheel opens", "delay time long press")
            setting("Open wheels while held", "Pointing at a wheel tile opens it at once", "inside nested")
            setting("D-pad", "Point at a slice, or step round the wheel", "dpad direction")
            setting("Stick reach", "How far the stick is pushed before it points", "dead zone deadzone joystick analog sensitivity")
            setting("Vibrate on each slice", "A tick is felt for each slice pointed at", "vibration haptic rumble buzz tick")
            setting("Start on the last pick", "Each wheel opens pointing at the slice picked last", "remember")
            setting("Wheel after a pick", "Close, or stay open to pick again", "keep open")
            setting("Slices", "Names and icons, or icons only", "labels text")
            setting("Wheel size", "Makes the whole wheel bigger or smaller", "scale big small")
            setting("Dim the game", "How dark the game is behind the wheel", "darken background")
        },
        page(Page.RECOGNITION, "Text recognition", "ScreenAI, which reads the screen", "ocr reader") {
            setting("ScreenAI", "Reads the text on the screen. Downloaded here.", "ocr download google")
            setting("Leave out menus and labels", "They stay tappable, but out of Lines, the stats and recaps", "ocr interface hud filter hide")
        },
        page(Page.GAMES, "Games and apps", "Rename one, choose when it is read", "game app list") {
            setting("Rename", "Give a game or an app another name", "title name")
            setting("Read this app", "Automatic, on demand, always or never", "stop ignore off disable block")
            setting("Boxes", "Draw the dialogue box, and only what is in it is read", "dialogue box draw region area text ocr")
            setting("Character names", "Link a game to its visual novel on VNDB", "vn cast search")
        },
        page(Page.GAME_NAMES, "Game names", "Which game is open in an emulator: Cocoon, ES-DE, RetroArch, Eden and Steam", "emulator launcher front-end frontend") {
            setting("Cocoon", "The folder with Cocoon's files", "emulator launcher")
            setting("ES-DE", "The ES-DE folder", "esde emulationstation emulator launcher")
            setting("RetroArch", "The RetroArch folder, for games started from RetroArch itself", "emulator libretro")
            setting("Eden", "For Switch games started from Eden itself", "emulator yuzu")
            setting("Steam profile", "Your SteamID64, profile link, or custom profile name", "account droiddeck gamenative winlator pc")
            setting("Web API key", "From steamcommunity.com/dev/apikey. Kept on this device, and never in a backup.", "steam password droiddeck gamenative winlator")
            setting("Ask Steam", "Asks Steam what you are playing now", "test check try")
        },
        page(Page.CHARACTER_NAMES, "Character names", "A visual novel's cast from VNDB, so its names look up as its characters", "vn people") {
            setting("Find visual novels by themselves", "Links a game when its Steam ID matches, or its title, platform and a Japanese release all do", "automatic auto link")
            setting("Names with honorifics", "さん, くん and about 270 more endings look up as one word", "san kun chan sama suffix")
            setting("Show spoilers", "Descriptions and traits VNDB marks as spoilers", "hide")
        },
        page(Page.MEDIA, "Sound and pictures", "Sentence audio, spoken lines and animated pictures", "media image screenshot") {
            setting("Sentence audio", "The voice in the game's sound, added to the card", "record recording voice microphone")
            setting("This session", "Whether LFG is listening to game sound, and a button to start", "record recording audio")
            setting("Spoken line", "Android's voice reads the line onto cards with no voice from the game", "tts text to speech audio")
            setting("Animated picture", "A short moving picture on the card, instead of a still", "gif video animation screenshot")
            setting("Trim black bars", "A card's picture is cut to the game, without the black round it", "crop letterbox borders screenshot")
        },
        page(Page.APPS, "Other apps", "Read browsers, chat and ebooks when you ask", "browser chat ebook book manga video subtitles") {
            setting("LFG button", "A small button over other apps. Tap it to read the screen.", "floating bubble overlay")
            setting("Keep lines from apps", "Keeps the lines read in other apps, as a game's are", "privacy private history save chat mail")
            setting("Apps in reading time", "Lines kept from apps count towards reading time, the goals and the widgets", "stats count")
        },
        page(Page.PRACTICE, "Tutorial", "Practise a lookup, a card and the wheel without a game", "practice practise help how onboarding setup"),
        page(Page.STORAGE, "Storage", "What LFG keeps, and how long screens are kept", "space disk size memory full") {
            setting("Keep screens", "How long the whole screen saved with each line is kept", "screenshots pictures delete days week month always")
            setting("Clean up now", "Deletes the screens kept longer than that", "free space delete clear")
        },
        page(Page.DIALOGUE, "Help improve LFG", "Keep screens of dialogue, and send them to train a better reader", "contribute training samples ocr") {
            setting("Save full dialogue", "Every new line of a game's dialogue: its whole screen, and the text LFG read from it", "samples training keep")
            setting("Send…", "Opens your mail app with every saved screen in one zip", "email share")
            setting("Join the Discord", "Where to send a zip too big for mail", "community chat")
            setting("Delete all", "Deletes every saved screen of dialogue", "clear remove")
        },
        page(Page.HELP, "Help and diagnostics", "A report to send with a bug, or after a first run on a new device", "problem issue crash log debug support") {
            setting("Copy", "Copies the report, to paste with what happened", "bug log clipboard")
            setting("Share…", "Sends the report with another app", "bug log send")
        },
        page(Page.GOALS, "Reading goals", "How much to read, and how many cards to make, each day", "daily goal target streak") {
            setting("Time per day", "Reading time, as Stats counts it", "minutes hours daily goal")
            setting("Characters per day", "Characters read, as Stats counts them: no punctuation or spaces", "daily goal")
            setting("Cards per day", "Cards made, added or waiting for AnkiDroid", "daily goal anki mining")
            setting("Today widget", "The widget's layout: auto, small, wide or large", "home screen launcher size")
        },
    ).flatten()

    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** Without capitals or accents: "Thème" is "theme". */
    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)

    /** A hit as search reads it: its name, and everything about it, without capitals or accents. */
    private class Entry(val hit: SettingHit) {
        val name = fold(hit.name)
        val all = fold(listOf(hit.name, hit.detail, hit.pageName, hit.words).joinToString(" "))
    }

    // After [marks], which reading a hit needs.
    private val entries = ALL.map(::Entry)

    /**
     * The settings with every word of [query] somewhere in their name, detail,
     * page or extra words. First those whose name starts with the query, then
     * those whose name has it, then the rest, each in [ALL]'s order. Nothing
     * for a query of nothing.
     */
    fun search(query: String): List<SettingHit> {
        val words = fold(query).split(spaces).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        return entries.filter { entry -> words.all { it in entry.all } }
            .sortedBy { entry ->
                when {
                    entry.name.startsWith(whole) -> 0
                    whole in entry.name -> 1
                    else -> 2
                }
            }
            .map { it.hit }
    }
}

/** Where a setting is searched for: one line, with a button that empties it once something is typed. */
@Composable
fun SettingsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val keyboard = LocalSoftwareKeyboardController.current
    val input = LocalInputModeManager.current
    val field = remember { FocusRequester() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = Lfg.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Lfg.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.weight(1f).focusRequester(field).padField(shape, singleLine = true).clip(shape).background(Lfg.Sunken).border(1.dp, Lfg.BorderStrong, shape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search settings", color = Lfg.Dim, fontSize = 16.sp)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            LfgButton(
                "✕",
                {
                    onQuery("")
                    // The button goes with the text, and the pad's focus with it: the pad is put on the field. Touch never left it.
                    if (input.inputMode == InputMode.Keyboard) runCatching { field.requestFocus() }
                },
                modifier = Modifier.semantics { contentDescription = "Clear" },
            )
        }
    }
}

/** What a search found, each a row that opens its page; or a line saying nothing was found. */
@Composable
fun SettingsSearchResults(hits: List<SettingHit>, onOpen: (Page) -> Unit) {
    if (hits.isEmpty()) {
        Text("No setting matches.", color = Lfg.Dim, fontSize = 15.sp)
        return
    }
    for (hit in hits) {
        // A page says what is on it; a setting says which page it is on first.
        val detail = if (hit.name == hit.pageName) hit.detail else listOf(hit.pageName, hit.detail).filter { it.isNotEmpty() }.joinToString(" · ")
        Card(hit.name, detail, onClick = { onOpen(hit.page) })
    }
}
