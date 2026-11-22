# Notes for coding agents

What has gone wrong before, so it does not again. Read this before building, installing or releasing.

## A fresh checkout or worktree does not build as it is

- `git submodule update --init --recursive`. Without `--recursive`, hoshidicts' own submodules (glaze, zstd, libdeflate…) are empty and CMake fails.
- Copy `local.properties` from the main checkout (it points at the SDK in `~/.local/share/chimahon-android-sdk`).
- **ScreenAI is not in git.** `app/src/main/jniLibs/<abi>/libchromescreenai.so` is ignored and made by `tools/build_screenai.sh`. Without it the APK builds fine but has no ScreenAI, and the app has no reader. Copy it from the main checkout, take it out of the release APK of the same tag, or run the script.
- **Check every APK before installing it:** `unzip -l <apk> | grep '\.so'` must list `libchromescreenai.so`, `liblfg.so` and `libandroidx.graphics.path.so`.

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

## Before pushing

- The pre-push hook runs `tools/check.sh` (unit tests, lint, both APKs). Switch it on in a new clone with `git config core.hooksPath .githooks`.
- A Room schema change adds an `AutoMigration` and its exported schema in `app/schemas`.
- Commit messages from an agent end with its `Co-Authored-By:` line.

## Talking to the user

- Short and to the point. Do the thing, say what was done in a line or two, and do not explain what was not asked.
