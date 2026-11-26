# Notes for coding agents

What has gone wrong before, so it does not again. Read this before building, installing or releasing.

## The Thor runs main, and main has everything

On 2026-11-22 the Thor was running a build of a local branch with 49 commits that were never pushed, so the v0.1.3 release from `main` silently took away the dialogue box, one-button lookup and more. To stop that happening again:

- **Push to `main` often.** Finished work goes to `main` as soon as `tools/check.sh` passes, not to a side branch that waits. The user has asked for this.
- **Never install on the Thor from a branch that is not `main`.** Merge it into `main` and push first, then build from `main`.
- **Before building for the Thor or tagging a release**, check that nothing is left behind:
  ```sh
  git fetch
  git log --oneline origin/main..HEAD        # must be empty: pushed
  git log --oneline HEAD..origin/main        # must be empty: up to date
  for b in $(git branch --format='%(refname:short)' --no-merged origin/main); do echo "$b: $(git rev-list --count origin/main..$b) unmerged"; done
  ```
  An unmerged branch with real work is merged first or named to the user; it is never dropped quietly.
- **Compare with what is installed:** `adb -s f34c1a3d shell dumpsys package io.github.beesan.lfg | grep -E 'versionCode|versionName'`. If the installed build has features `main` lacks, stop and find the branch it came from.

## A fresh checkout or worktree does not build as it is

- `git submodule update --init --recursive`. Without `--recursive`, hoshidicts' own submodules (glaze, zstd, libdeflate…) are empty and CMake fails.
- Copy `local.properties` from the main checkout (it points at the SDK in `~/.local/share/chimahon-android-sdk`).
- **ScreenAI is not in git.** `app/src/main/jniLibs/<abi>/libchromescreenai.so` is ignored and made by `tools/build_screenai.sh`. Without it the APK builds fine but has no ScreenAI, and the app has no reader. Copy it from the main checkout, take it out of the release APK of the same tag, or run the script.
- **Check every APK before installing it:** `unzip -l <apk> | grep '\.so'` must list `libchromescreenai.so`, `liblfg.so` and `libandroidx.graphics.path.so` (and ML Kit's `libtranslate_jni.so`, which Gradle brings by itself).

## Releases

- Push a `vX.Y.Z` tag on `main`. `.github/workflows/release.yml` builds both signed APKs and makes the GitHub release, with notes generated from pull requests only: work pushed straight to `main` needs the notes written by hand (`gh release edit vX.Y.Z --notes-file …`, with the user's go-ahead).
- Versions so far bump the last number, features included.
- The workflow takes about a quarter of an hour. `gh run watch` outlives a ten-minute command: ask `gh run view <id> --json status,conclusion` in a loop instead.
- Build the release at the desk once before tagging when a library was added (`./gradlew :app:assembleRelease`, signed with the debug key when there is no keystore): the shrinker only runs there, and the debug build says nothing about it.
- Four old branches show as unmerged in the check above and are not: `backup/local-main-526df5f`, `claude/determined-mccarthy-8a0eb3`, `recap-current-game` and `sentence-audio-lower-screen`. Their work is on `main` under other commits (checked 2026-11-26).
- The workflow's version code is its run number (v0.1.3 is 4).
- The release key lives in `~/.config/lfg-release/` and in the repository's secrets. Never make a new one or overwrite the secrets.

## The AYN Thor (adb serial `f34c1a3d`)

- **It runs desk builds with a higher version code than the releases**, so a GitHub APK is refused as a downgrade. Install a desk build of the same tag instead, signed with the release key, with a code above the one installed (`adb shell dumpsys package io.github.beesan.lfg | grep versionCode`):
  ```sh
  cp ~/.config/lfg-release/keystore.properties .
  LFG_VERSION_CODE=<installed + 1> LFG_VERSION_NAME=vX.Y.Z ./gradlew :app:assembleRelease
  rm keystore.properties
  ```
  Then check the `.so` list above, and `adb -s f34c1a3d install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk`.
- **Never uninstall LFG to get round a signature or version error:** it wipes the user's lines, cards and dictionaries.
- After `adb install -r` the reading service is off until it is switched off and on again. Keep AYN's Game Assistant in the list:
  ```sh
  GA=com.odin.gameassistant/com.ro.gameassistant.service.ForegroundAppMonitorV4Service
  adb -s f34c1a3d shell settings put secure enabled_accessibility_services $GA
  adb -s f34c1a3d shell settings put secure enabled_accessibility_services "$GA:io.github.beesan.lfg/io.github.beesan.lfg.capture.LfgAccessibilityService"
  ```
- "Install it" means the finished, working app: it is not done until the install succeeded, the service is running, and nothing crashed in `logcat`.

## The emulator

- `lfg_api33` (Android 13, as the Thor) on port 5560. Test there, not on API 35: an Android 14 API passed on 35 and crashed on 33.
- Never run `uiautomator dump` while testing: it suspends accessibility services.
- **Right after `adb install -r` the service is connected but sees no windows**, even once it is switched off and on: nothing is read, and `LfgPerf` in logcat shows only `front` at about 0.1 ms with no `shot`. Force-stop the app, switch the service off and on again, and test from the second start. A failure seen only on the first run after an install is this, not the code.
- `am force-stop` switches the service off. A running app writes its settings back over a `settings.xml` pushed under it, so stop it first.
- LFG opening on the second screen is `toSecondScreen()` in `MainActivity`: only on a start from out of sight. Moved while showing (the Thor's swap-screens), it stays where it is put.
- The app's pages are on the second screen (`input -d 2 tap`, `adb emu screenrecord screenshot --display 1`); games, and the tutorial, on the first. A row's place differs between the two: the second is narrow, so take a picture before tapping by coordinates.
- `tools/virtual_pad.py` talks to plain `adb`: set `ANDROID_SERIAL=emulator-5560`. The pad it makes counts as a connected controller, and goes when the script ends.
- Never read `shared_prefs/secrets.xml`. A test that needs an OpenRouter key types a made-up one into the app (`input text`) and deletes it in Settings › Translation afterwards.
- No internet for a test: `adb shell cmd connectivity airplane-mode enable`, and `disable` after.
- A new dependency needs one build without `--offline` before `--offline` works again.

## Languages

- The rules that take an inflected word back to its dictionary form are hoshidicts' language packs (`third_party/hoshidicts/languages/<code>`), the same ones Hachidori ships. Gradle's `languagePacks` task copies each pack's two JSON files into the APK's assets; Japanese is built into the engine. `core/Language.kt` has one entry a pack and `SetupTest` fails when the two disagree, so a new pack upstream needs its line there.
- Moving the hoshidicts submodule forward: dictionaries people already imported must still open. Import one with the old `lfg_dict_host`, build the new one (`cmake -S app/src/main/cpp -B <dir> && cmake --build <dir> --target lfg_dict_host`), and compare `all <dir> <text>` from both.
- A dictionary belongs to the language it was added under (`InstalledDictionary.language`); only the language being learnt is listed, fetched and looked up in.
- What is Japanese's alone asks `Language.japanese`: `TextClean`'s clean-up (which drops a line with nothing Japanese in it), `KanaRepair`, VOCR as the probe, readings in the AI prompts. Anything new that assumes kana or kanji goes behind it too.
- `TextClean` counts `…` and `。` as Japanese, so English with an ellipsis in it ("Reading the screen…") is kept as a line when Japanese is being learnt. Text of the app's own that a game's reading could see is kept out of the picture, not left to the clean-up.
- A language whose pack says `scanStart: word` is looked up from the start of the word touched (`Selection.wordStart`); Japanese, Chinese, Korean and German from the character.
- The AI prompts name the language (`Language.orJapanese(line.language)` for one line, `prefs().learning` for a game) and must come out unchanged for Japanese: `AskLanguageTest` pins them, so a prompt changed on purpose is changed there too.

## Set-up and the tutorial

- Set-up is `core/SetupFlow.kt` (which steps there are) and `ui/SetupScreen.kt`; the tutorial is `ui/PracticeActivity.kt`, which the service reads as a game (`LfgAccessibilityService.practiseOn`). Words there follow the owner's rule: few, plain, every sentence worth reading.
- **Android presses the focused button for a stick click, Start or Select that nothing took, and goes Back for B.** On a page of the app the shortcut itself (L3 + R3) therefore presses whatever has the pad's focus. A page that asks for controller buttons takes them in `dispatchKeyEvent` first, as `MainActivity` does for "Pick your LFG button" and `PracticeActivity` does throughout; and nothing there is focused that a stray press must not reach.
- Overriding `dispatchKeyEvent` in an activity fails lint with `RestrictedApi` (it mistakes it for androidx.core's): `@SuppressLint("RestrictedApi")` with a line saying why.
- The tutorial's guide is a strip along the top because the entry opens above the word, two thirds of the screen high: anything lower is covered while a word is looked up.
- The service reads the tutorial only inside the box `PracticeActivity` reports (`practiceBox`), and not before it has one: read whole, the guide's own words become the game's lines. Its lines go when it ends, but for those a card was made from.
- A word already in Anki counts as the tutorial's card (`alreadyInAnki`): the card button would otherwise open AnkiDroid and take the player out of it.
- On a two-screen handheld the tutorial opens on the game's screen and the pages stay on the other. It ends with `finish()` alone: starting `MainActivity` from there carries the app's pages onto the game's screen.
- To see set-up again on the emulator: `am force-stop` the app first (a running app writes its settings back over yours), take `setupDone`, `keepDialogueAsked` and `language` out of `shared_prefs/settings.xml`, then switch the service on again, since a force-stop switches it off.

## Settings, sounds and translation

- **Settings pages are built from `ui/SettingsKit.kt`**: rows (`Card`, `SettingText`, `Plain`) go in a `Group("Title")`; on/off is `OnOff` (a switch), a few options `Choice` (a segmented row), numbers `Stepper`. Names are a few words and details a short phrase, never a sentence; anything longer is a `Hint` under the group, or left out. A new page gets an entry in `SETTINGS_GROUPS` with its icon.
- **Settings › search is a list kept by hand** (`SettingsIndex.ALL` in `ui/SettingsSearch.kt`): a setting added, renamed or moved gets its row there, with the words people would type for it. `SettingsSearchTest` checks that every row points at a page Settings can open by itself.
- A new setting is a field of `Prefs` and a line in both `Settings.read()` and `Settings.update()`. One whose default must differ for people already set up is read from a setting they have (`setupDone` from `keepDialogueAsked`), not migrated.
- **Button sounds** play as `USAGE_ASSISTANCE_SONIFICATION`, which game-sound capture never records; a sound played as media would end up on cards. Material's `Button` ignores `LocalIndication`, so `LfgButton` clicks through `rememberClickSource()`, and only inside `ClickSounds { }`: the overlays over a game stay silent.
- **The wheel's and lookup's sounds** are `ui/GameSounds.kt`, one entry a sound, played as sonification like the buttons'. Over a game only what the player did is heard (`lookup.Heard`: a step, a card asked for, made or failed), never a line arriving by itself. The tick is one sound, the same every time. The files are made by `tools/sounds` (its README has the recipe and why each was chosen); the owner likes the Hi75 thock and a PSP menu's tick, and pure synthesised pops read as cheap.
- **Hearing a sound on the emulator, which has no ears:** `adb shell dumpsys audio | grep "new player"` gives each `SoundPool`'s `piid` (the app makes two: `GameSounds` first, the buttons' when a page opens), and `grep "piid:<n> state:started"` lists one line for each sound played, with its time.
- A field for a key is a password to the keyboard as well (`KeyboardType.Password`), or the keyboard suggests the key and learns it.
- **ML Kit** translates only when OpenRouter cannot be reached (`OpenRouter.Failure.offline`), never for a wrong key. Its models come by Android's download manager and work without Google Play services (seen on the emulator). Its translations are marked as Google's, which its terms ask for. It brought appcompat with it, and with appcompat lint's `AppCompatCustomView`, which is switched off in `app/build.gradle.kts`.

## Profiles

- The profile in use **is** the ordinary settings (`Settings`, the `settings` file); one not in use is the same keys in a file of its own (`profile-<id>`), with its Anki target beside it in `profiles`. Changing profile (`Profiles.use`) puts one away and takes the other out, so nothing else in the app knows of profiles.
- **A new setting is each profile's own** unless it is added to `Prefs.withShared` (set-up, keys, backups, game folders, storage and downloads, the widget's size). Wheels, boxes and dictionaries are not a profile's.
- A profile with no name goes by its language's (`ProfileRules.shown`), so it follows a change of language.
- **Tiles:** six `ProfileTile` services in the manifest, off until there are two profiles (`Profiles.syncTiles`). A tile redraws by watching `profiles.list` while it is listening: `TileService.requestListeningState` does nothing for them while the shade is open. Android 13 leaves the tile of a deleted profile greyed out in the shade until the user drags it away.
- Changing to another language over a game reads that game's text as the new language. That is the user's to choose, not a bug to guard.
- A backup carries `profiles.xml` and each `profile-*.xml`; one made before profiles puts back only `settings.xml`.

## Words the user reads

- The owner's rule (2026-11-26), for set-up and everywhere else: few words; each sentence holds only what is needed; no technical jargon; a sentence not worth reading is taken out. Say what happens for the person ("LFG spots a game by itself"), not how it works.
- A screen asks one thing. What LFG can see for itself (a controller already there, AnkiDroid installed, downloads done) is not asked.
- Say only what is true of every case: "nothing leaves your device" stopped being true the moment a line could be sent to be translated.

## Working with helpers

- Helpers sharing one checkout are each given their own files and told not to run Gradle: a build while another is mid-edit fails for both. Compile once they are all back, and expect a test or a caller to need mending.

## Before pushing

- The pre-push hook runs `tools/check.sh` (unit tests, lint, both APKs). Switch it on in a new clone with `git config core.hooksPath .githooks`.
- A Room schema change adds an `AutoMigration` and its exported schema in `app/schemas`.
- Commit messages from an agent end with its `Co-Authored-By:` line.

## Talking to the user

- Short and to the point. Do the thing, say what was done in a line or two, and do not explain what was not asked.
