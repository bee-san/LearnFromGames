<div align="center">

# LearnFromGames

**Learn Japanese from the games you are already playing.**

Tap a word in a game, or pick it with the controller. Read the entry. Press once for an Anki card with the screenshot.

![Android 13+](https://img.shields.io/badge/Android-13%2B-3ddc84?logo=android&logoColor=white)
![Licence GPL-3.0-or-later](https://img.shields.io/badge/licence-GPL--3.0--or--later-b1a5ee)
![Status: not yet run on a handheld](https://img.shields.io/badge/status-not%20yet%20run%20on%20a%20handheld-e0a84e)

<img src="docs/screens.png" width="720" alt="A game's dialogue box on the top screen with the word 景色 marked across a line break, and its dictionary entry with an Add card button on the bottom screen">

<sub>The stand-in game from this repo on an emulator laid out like the AYN Thor's two screens. Dictionary: Jitendex.</sub>

</div>

LearnFromGames (LFG) is [GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner) for Android handhelds, designed for the AYN Thor first. It sits over any game or emulator, reads the Japanese on screen, and turns it into something you can look up and keep.

> [!WARNING]
> **Nothing here has run on a real handheld yet.** Every part is built and has been run on Android emulators (Android 13 and 15). What only a device can answer is listed under [Not done, or not known](#not-done-or-not-known).

## What it does

- **Reads the game for you.** An accessibility service pictures the screen, notices when new text has stopped moving, and reads it. No screen-recording prompt, no setup per game.
- **Tap a word on the game.** Each line of text becomes tappable where it is drawn. A wrapped sentence is one sentence, and a word cut by the line break is still one word.
- **Or never let go of the controller.** L3 + R3 turns the pad into a text cursor: by word, by line, by character. The game receives nothing until you leave. The shortcut can be one button instead (L3, R3, Select or Start), which is then kept from the game, and the left stick can go word by word where the stick is under your thumb. A tap that misses the words does not reach the game either.
- **Uses the second screen.** On a two-screen handheld the entry opens on the screen the game is not on. On one screen it is a popup.
- **One press to Anki.** Cards go to AnkiDroid with the sentence, the word marked, the definition and a picture of the game. Kiku, Lapis and Senren note types are found by themselves; without one, the app makes its own.
- **Sentence audio**: the game's own voice for the line, with the music around it cut away. Android has to ask before LFG may listen, so its question comes up by itself when you open a game: one tap, once a session. Say no and it is not asked again; Start on the Lines page begins it later. A line the game does not speak is read by Android's voice instead.
- **Moving pictures**, if you switch them on: an animated picture of the line appearing.
- **Your dictionaries.** Hachidori's starter set is fetched at first run: Jitendex, JMnedict, Bee's Ultimate Kanji Dictionary, Jiten's frequencies and Bee's Ultimate Grammar Dictionary. Add any Yomitan dictionary zip and it is shown with its own styling. Pitch accent dictionaries draw the accent over the reading, frequency dictionaries show their ranks, and local-audio-yomichan's `android.db` lets you hear the word.
- **Every line is kept**, so you can mine after the session and see reading time, characters, speed and your streak.

## How it works

```mermaid
flowchart LR
    A[Game on screen] --> B[VOCR<br/>has the text changed<br/>and stopped moving?]
    B --> C[ScreenAI<br/>reads it, with a box<br/>for every character]
    C --> D[Rows grouped into<br/>sentences, furigana dropped]
    D --> E[Tap strips on the game<br/>Controller cursor]
    D --> F[(Lines saved)]
    E --> G[hoshidicts lookup]
    G --> H[Entry on the panel]
    H --> I[AnkiDroid card]
    F --> J[Stats]
```

Reading and lookup run on the device. The network is used for the first-run downloads (the starter dictionaries from GitHub, jitendex.org's releases and api.jiten.moe; ScreenAI's files from Chromium's package store) and, only when you turn them on, for translation and recaps through OpenRouter and for game names from Steam's Web API.

## Where it stands

| Stage | What works on the emulator |
| --- | --- |
| Tap, read, mine | First run from a clean install. Background reading. Tap strips, the second-screen panel, the one-screen popup. Add card with the picture. Lines saved. |
| Controller | L3 + R3 lookup mode: word, line and character steps, held repeat, entry change, add, close. A one-button shortcut the game never receives. The stick and the D-pad changing places. |
| Dictionaries and lines | Add, order and remove Yomitan dictionaries; Rubi's .vjdict files can be added too. Full entry rendering with pitch accent and frequency. Word audio from local-audio-yomichan's `android.db`: a play button, Y in a game, and the card's ExpressionAudio field. Cards from a stored line after the session. |
| Sound, then motion | Sentence audio added to a card when the next line appears, trimmed to the voice by Silero VAD. Android's voice for a card without one. Animated WebP of the line appearing. |
| Stats | Reading time, characters, speed and streak over 7 days, 30 days, 12 months or all time, against the period before, and a bar for each day or month. Daily goals for hours and characters: today against each, the days that met them, a goal streak, and a goal line on the chart. Two home screen widgets: today against the goals as rings (time, characters and, with a cards-a-day goal, cards; at 4 × 4 the week as small rings too), and the last seven days as bars ([picture](docs/widgets.png)). On [Rubi](https://github.com/bee-san/Rubi_for_vita)'s reading-time rule. |

### Against real apps

- **PPSSPP 1.20.4** (its Japanese menus; no game was run in it): read, tap
  strips placed, tap lookup and the panel all work. It showed two faults, now
  fixed: a moving background made the first pass read slightly different text
  on every frame, so the screen never counted as still and was never read
  (text now settles when only a character in twenty differs, and is read
  anyway after six seconds of never holding still); and with dozens of pieces
  of text on screen the panel listed them all and pushed the entry off it.
  Still wrong there: icons beside labels are read as stray characters
  ("[1) オーディオ"). Every menu label was saved as a line; menus and labels
  are now told apart (`core/InterfaceText.kt`) and kept out of Lines and the
  stats, but this has not been run against PPSSPP again.
- **Lapis 1.7.0 and Senren 5.1.0**, imported into AnkiDroid from their release
  decks: a card made from the game lands in the right fields of each. Kiku
  2.1.0's fields are checked against the mapping but no card was made in it.
  While cards are going to the app's own note type it looks again each time,
  so one of the three installed later is picked up without "Find again".
- **The release build** on the Android 15 emulator: reading with ScreenAI,
  lookup, the styled entry and a card all work after shrinking.
- **Sentence audio on the AYN Thor** (Android 13), with the stand-in game on
  the top screen: Android's question comes up over the game as it opens, and
  after one tap a card made from a line gets the voice, cut from the music and
  added to its AnkiDroid note (SentenceAudio in Kiku). This holds with the
  Thor's volume at nothing too. A game on the Thor's lower screen is another
  matter: the Thor plays no sound from an app there, and a sound it starts
  after LFG began listening is recorded as silence. Not run with a real game.

### Not done, or not known

- The plan's "Prove first" questions that need the device are all still open:
  whether the controller stays with the game after a touch on the Thor's
  bottom screen, AYN firmware and quickly changing overlay windows, AnkiDroid
  answering after a cold start there, and what background reading costs in
  battery and heat. The emulator answered the others (see below).
- No speed figures for ScreenAI on a handheld: the OCR bench below
  ran on the x86_64 emulator.
- A text hook as a second source of lines (the plan's "after that, if wanted")
  is not built. The intent is for the app to be the texthooker itself, not to
  listen to another one.

### How fast it is

`tools/bench.sh` runs the stand-in game through three phases and prints the
app's CPU use and its own step timings (logcat tag `LfgPerf`). These figures
are from the release build on a 4-core x86_64 emulator with software graphics,
at 1080p; they say where the time goes, not what a handheld will show.

| Phase | Before | After |
| --- | --- | --- |
| The same line sitting on screen | 31.5% of a core | 7.6% |
| Advancing a line every 4 s | 48.2% | 40.0% |
| Advancing and looking a word up | 53.1% | 45.2% |
| From text read to text tappable | read + 180 ms for the picture | read + 8 ms |

What changed: a picture identical to the last one is not copied or probed
again (the probe was 110 ms of every look); after ten seconds unchanged the
screen is looked at half as often; the screenshot is copied off the main
thread; and the card's picture is written behind the reading instead of in
front of it. What is left, per look: copying the screenshot out of graphics
memory (40 ms here), the probe on a changing screen (90 ms), and a full
reading per new line (320 ms with ScreenAI).

A second round, on the debug build with its native code now optimised as in a
release (it was built at -O0, which made VOCR and libwebp several times slower
there than for anyone using the app), before and after on the same emulator:

| | Before | After |
| --- | --- | --- |
| Is this the last picture again? (every look) | 15 ms | 1.2 ms |
| Writing a line's screen (behind the reading) | 235 ms | 110 ms |
| CPU, the same line on screen | 8.8% | 7.0% |
| CPU, advancing a line every 4 s | 44.9% | 39.4% |
| CPU, advancing and looking up | 48.8% | 44.4% |
| Sentence audio: finding speech in 30 s | 1.06 s | 0.80 s |

The check fingerprints every fourth row where Android keeps the pixels instead
of copying them into Java (`lfg_pixels.cpp`); pictures go through libwebp at a
quick setting (127 ms and 128 KB a 1080p screen, against Android's 337 ms and
123 KB: `PixelsBench`); and the voice detector's spectrum is an FFT. Reading
only around the text VOCR found was tried and left out: 17% quicker, but 0.8
points fewer lines read exactly on the OCR bench, and text VOCR misses (a
choice menu) would go unread.

```sh
adb shell am instrument -w -e count 40 io.github.beesan.lfg/.PixelsBench   # debug build, after the OCR bench's pages are pushed
```

A tap, with the five starter dictionaries: the lookup is 2–5 ms (finding it
and reading its answer), building the entries 3–8 ms, and the popup shows them
in 28–45 ms. The popup's page and every dictionary's stylesheet (a grammar
dictionary's alone is 48 KB) load once; each lookup then swaps in only its
entries, where loading a whole new page took 100–280 ms. Only the first tap
after the service starts pays for that load. The controller's word steps ask
hoshidicts only how long each word is (`match_length`), not for its entries.

`host-build/lfg_dict_host bench <directory of imported dictionaries>` times
lookups on the desktop over every dictionary there: with the five, 0.3–0.8 ms
a lookup and about 6 ms for the word steps of a whole line.

<details>
<summary><b>How well it reads real games</b> (1,570 hooked lines from 167 Vita and PSP games)</summary>

`tools/ocr_bench.py` scores the readers on real game screens whose text is
known: Vita and PSP pages captured with the game's own text hooked
(psandvita's paired data, Rubi's training data), each page letterboxed to
1920x1080 as a handheld shows it. The readers run on the device through the
debug-only `OcrBench` instrumentation, with the app's own clean-up. On the
x86_64 emulator, 931 screens:

| Reader | Character errors | Lines exactly right | Time per screen (median) |
| --- | --- | --- | --- |
| ScreenAI (the reader) | 3.6% | 88% | 240–500 ms |
| VOCR (the change probe) | 4.4% | 86% | 100 ms |

VOCR6 was fine-tuned on these games; on the two held out from it, it misses
5.5% of characters, ScreenAI 6.7%. VOCR reads no vertical text, which is why
it only watches for changes. meikiocr, LFG's first reader, scored 5.1% (75%
exact, 545 ms) and missed lines that are only "……"; ScreenAI replaced it, and
its 45 MB download and ONNX models are gone. ScreenAI read ellipses as any number
of dots; it now counts them by the room they take, which halved its errors.
Every reader's text then goes through `ocr/TextClean.kt`, Rubi's rules for
OCR text (one form of each mark, ー or 一 and ッ or ヶ by context, a text box's
border read as text dropped) and two from the bench (a 「 or 」 read as a
letter; a line of English or numbers with nothing Japanese dropped), which
removes 43% of what ScreenAI read off the dialogue (menus, scene art).

```sh
tools/ocr_bench.py prepare ~/path/to/paired-data OUT     # then push, run and score: see the script's header
```

</details>

<details>
<summary><b>How well it reads</b> (scores on a drawn test set)</summary>

`tools/make_ocr_corpus.py` draws twelve harder screens with their true text
(wrapped sentences, furigana, busy backgrounds, no text box, 720p, letterboxed,
choices, dark-on-light, novel pages, vertical, status text, name plates), and
`tools/score_ocr.py` scores what the readers made of them. These are drawn
screens, not real games.

| Reader | Whole pieces read exactly | Mean character accuracy | Extra pieces |
| --- | --- | --- | --- |
| ScreenAI, rows as read | 11 of 20 | 0.875 | 11 |
| ScreenAI, grouped | 18 of 20 | 0.995 | 0 |
| meikiocr (since removed), rows as read | 10 of 20 | 0.873 | 10 |
| meikiocr (since removed), grouped | 17 of 20 | 0.992 | 0 |

"Grouped" is what the app does with a reading (`core/Blocks.kt`): rows that
sit like the lines of one text box become one piece, so a wrapped sentence is
one line in the store and on a card, and a word cut by the line break can be
tapped and is marked on both rows; furigana is dropped. What is still wrong:
ScreenAI took 頷 for 領; meikiocr, the reader before it, dropped a digit and a comma in status text;
and one case is the corpus's own fault (its vertical punctuation is drawn where
no game puts it). A small kana-only name plate set as close to its line as
furigana, over a kanji, is taken for furigana.

</details>

<details>
<summary><b>Where this differs from the plan</b></summary>

- **What counts as a game.** The plan reads whatever app is in front. In
  testing that put tap strips over Android's file picker and swallowed taps on
  its labels. An app is now read automatically only while it is fullscreen (no
  status or navigation bar on its screen) and shows few text items of its own
  (a phone held sideways hides the bars for every app; a game draws its text
  into one picture), or if it declares itself a game or is a known emulator. Settings › Games and apps has Automatic, On demand, Always and Never for each.
- **Pictures of the game.** From Android 14 the service pictures the game's
  own window, so the app's mark and popup are never in a frame. On Android 13
  it pictures the whole screen: reading pauses while a word is marked on the
  game, and text under other windows (a system dialog) is ignored.
- **Note type ties.** Hachidori stops and asks when two supported note types
  hold the same number of notes, or none has any. This app never asks: a tie
  goes to the older note type and an unused one is still used.
- **`cards.payload`** is an extra column: the card's content, so a card made
  without AnkiDroid can be sent later.

</details>

<details>
<summary><b>What the emulator answered from the plan's "Prove first"</b></summary>

- A service is handed real gamepad buttons (not ones injected with `adb shell
  input`), and a D-pad that arrives as an axis reaches a focused overlay
  window. The game sees the shortcut's own buttons; nothing after that.
- bee-san/hoshidicts builds with NDK r29 and needs CMake 3.31. JMdict (528,000
  entries) imports in well under a second on a desktop core.
- screenai_ocr builds with the NDK and reads the test screens exactly as on the
  desktop. On a 4-core x86_64 emulator a 1080p frame takes 115–130 ms.
- VOCR reads a 1080p frame in 20–30 ms on a desktop core.
- AnkiDroid answers while closed, creates the note type and deck, and takes a
  field update on an existing note.
- The accessibility switch survives a reboot and the service rebinds by itself.
- arm64, under `qemu-aarch64`: hoshidicts imports JMdict and answers lookups
  byte for byte as on x86_64; VOCR (its NEON path) reads the test screens the
  same; ScreenAI reads the same characters, with character boxes up to 9 px
  from the x86_64 ones.

</details>

## Install

Releases are on GitHub, one APK for each kind of device (arm64 for handhelds, x86_64 for emulators), and [Obtainium](https://github.com/ImranR98/Obtainium) can follow them. To build it yourself:

## Build

Needs JDK 17, the Android SDK (platform 36), NDK 29.0.14206865, and CMake 3.31
or later on `PATH` (the SDK's own CMake packages are too old for hoshidicts).

```sh
git submodule update --init --recursive
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

`tools/check.sh` runs everything the GitHub check does: unit tests, lint and
both APKs. To run it before every push:

```sh
git config core.hooksPath .githooks   # once per clone; skip a push's check with --no-verify
```

A release is one shrunk APK per kind of device (about 15 MB for arm64, 17 MB
for x86_64):

```sh
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-arm64-v8a-release.apk, app-x86_64-release.apk
```

It is signed with your own key if `keystore.properties` (not kept in git) is
beside `settings.gradle.kts`, and with the debug key otherwise, which installs
but can never be updated by a properly signed build:

```sh
keytool -genkeypair -v -keystore release.jks -alias lfg -keyalg RSA -keysize 4096 -validity 10000
printf 'storeFile=release.jks\nstorePassword=…\nkeyAlias=lfg\nkeyPassword=…\n' > keystore.properties
```

`.github/workflows/release.yml` does the same on GitHub when a tag like
`v0.2.0` is pushed, and attaches the APKs to a release for Obtainium; its
header lists the four secrets it needs. `check.yml` runs the tests and lint on
every push.

ScreenAI's library is built separately, once, and is not kept in git. Without
it the app still builds, but nothing reads the screen.

```sh
ANDROID_NDK=/path/to/ndk/29.0.14206865 tools/build_screenai.sh   # about 8 minutes; fills app/src/main/jniLibs
```

The dictionary and VOCR boundaries also build and run on the build machine:

```sh
cmake -S app/src/main/cpp -B host-build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build host-build
./host-build/lfg_dict_host import JMdict_english.zip dicts
./host-build/lfg_dict_host lookup "dicts/JMdict [2026-11-17]" 見たんだ
gunzip -c app/src/test/resources/screens/dialogue.argb.gz > dialogue.argb
./host-build/lfg_vocr_host app/src/main/assets/vocr/VOCR6.2_N_w8g2.vocr app/src/main/assets/vocr/textspot8_t64n8c16_w8.vtsp dialogue.argb
```

The same tests build for arm64 and run under `qemu-aarch64`:

```sh
cmake -S app/src/main/cpp -B arm64-tests -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-33 -DANDROID_STL=c++_static -DLFG_HOST_TESTS=ON -DCMAKE_EXE_LINKER_FLAGS=-static
cmake --build arm64-tests
qemu-aarch64 arm64-tests/lfg_dict_host lookup "dicts/JMdict [2026-11-17]" 見たんだ
```

## Trying it without a handheld

```sh
./gradlew :app:assembleDebug :fakegame:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
adb install fakegame/build/outputs/apk/debug/fakegame-debug.apk
```

To score ScreenAI on the corpus (through the host `run_ocr` that
`tools/build_screenai.sh`'s sources also build):

```sh
python3 tools/make_ocr_corpus.py corpus
LFG_CORPUS=$PWD/corpus ./gradlew :app:testDebugUnitTest --tests '*OcrCorpusReport*'
tools/score_ocr.py corpus --show
```

`fakegame` is a dialogue box whose text types itself out and advances on a tap
or a button, with a tone as its "voice". Started with `--ez music true` it
plays music throughout instead, and speaks `voice.s16le` (16 kHz, 16-bit, mono;
the tests' own is `app/src/test/resources/sound/voice.s16le`) from its external
files folder on two lines in three, which is how voice trimming is checked.
`tools/virtual_pad.py` plays a gamepad on the emulator. Do not use `uiautomator dump` while testing: it suspends
accessibility services while it runs.

## Layout

- `app/src/main/cpp` — `lfg_dict` (hoshidicts to JSON), `lfg_vocr`, `lfg_screenai`, `lfg_webp`, `lfg_pixels` (frame fingerprints, quick WebP), `lfg_jni`, and host tests
- `app/src/main/kotlin/io/github/beesan/lfg`
  - `core` — the line record, grouping rows into text blocks, tap hit-testing, settle detection, the text cursor, reading-time rules
  - `ocr` — VOCR, ScreenAI, its download, and TextClean
  - `dict` — the engine's JNI face, entries as text and as HTML, dictionary management
  - `lookup` — lookup from a character, by touch or controller
  - `anki` — note type detection and field filling, AnkiDroid calls, cards, sentence audio
  - `data` — the Room store
  - `capture` — the accessibility service, the reading pipeline, sound capture
  - `overlay` — tap strips, mark, popup, panel, controller input
  - `ui` — set-up, lines, settings, stats
  - `widget` — the home screen widgets
  - `ask` — translation and recaps through OpenRouter
  - `backup` — backup and restore, by hand and on a schedule
  - `games` — naming the game inside an emulator, and covers
  - `Diagnostics.kt` — the report in Settings › Help and diagnostics
- `fakegame` — the stand-in game
- `third_party/hoshidicts`, `third_party/libwebp` — submodules; `third_party/vocr` — VOCR's runtime, copied
- `tools` — `build_screenai.sh`, `make_test_screens.py`, `make_ocr_corpus.py`, `score_ocr.py`, `ocr_bench.py`, `bench.sh`, `check.sh`, `export_silero.py`, `virtual_pad.py`

## Licences

The app is GPL-3.0-or-later, as hoshidicts is.

- VOCR's runtime (bee-san/vita-vn-ocr) is GPL-3.0-or-later. **Its weights, in
  `app/src/main/assets/vocr`, are for non-commercial use only**; the attribution
  files beside them say why.
- screenai_ocr (AuroraWright) is MPL-2.0. The model files it runs are Google's,
  published for Chromium; no licence for them was found, so the app downloads
  them from Chromium's package store and never ships them.
- libwebp is BSD-3-Clause. AnkiDroid's API is LGPL-3.0.
- Silero VAD is MIT. Its model's weights ship in `app/src/main/assets/vad`, run by the app's own port of it.

## Thanks

LFG stands on [GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner), [hoshidicts](https://github.com/bee-san/hoshidicts), [VOCR](https://github.com/bee-san/vita-vn-ocr), [screenai_ocr](https://github.com/AuroraWright/screenai_ocr), [AnkiDroid](https://github.com/ankidroid/Anki-Android), [Silero VAD](https://github.com/snakers4/silero-vad), [Jitendex](https://jitendex.org), JMdict, JMnedict, [Jiten](https://jiten.moe), [Bee's Ultimate Kanji Dictionary](https://github.com/bee-san/bees-ultimate-kanji-dictionary), [Bee's Ultimate Grammar Dictionary](https://github.com/bee-san/bees-ultimate-grammar-dictionary) and [Hachidori](https://github.com/bee-san/hachidori), and on the Kiku, Lapis and Senren note types.

The plan and screen designs this was built from: <https://claude.ai/artifact/PahaxprErjPmLGocbz6pPW>
