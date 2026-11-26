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

## Languages

- The rules that take an inflected word back to its dictionary form are hoshidicts' language packs (`third_party/hoshidicts/languages/<code>`), the same ones Hachidori ships. Gradle's `languagePacks` task copies each pack's two JSON files into the APK's assets; Japanese is built into the engine. `core/Language.kt` has one entry a pack and `SetupTest` fails when the two disagree, so a new pack upstream needs its line there.
- Moving the hoshidicts submodule forward: dictionaries people already imported must still open. Import one with the old `lfg_dict_host`, build the new one (`cmake -S app/src/main/cpp -B <dir> && cmake --build <dir> --target lfg_dict_host`), and compare `all <dir> <text>` from both.
- A dictionary belongs to the language it was added under (`InstalledDictionary.language`); only the language being learnt is listed, fetched and looked up in.

## Set-up and the tutorial

- Set-up is `core/SetupFlow.kt` (which steps there are) and `ui/SetupScreen.kt`; the tutorial is `ui/PracticeActivity.kt`, which the service reads as a game (`LfgAccessibilityService.practiseOn`). Words there follow the owner's rule: few, plain, every sentence worth reading.
- To see set-up again on the emulator: `am force-stop` the app first (a running app writes its settings back over yours), take `setupDone`, `keepDialogueAsked` and `language` out of `shared_prefs/settings.xml`, then switch the service on again, since a force-stop switches it off.

## Before pushing

- The pre-push hook runs `tools/check.sh` (unit tests, lint, both APKs). Switch it on in a new clone with `git config core.hooksPath .githooks`.
- A Room schema change adds an `AutoMigration` and its exported schema in `app/schemas`.
- Commit messages from an agent end with its `Co-Authored-By:` line.

## Talking to the user

- Short and to the point. Do the thing, say what was done in a line or two, and do not explain what was not asked.
