# Character names for visual novels, built on the device

Status: steps 1 to 8 built on 2026-11-22; the parity check against the Rust builder (step 4) and the measurements on the Thor (step 9) are still to do. Written 2026-11-22.

## Goal

When LFG is reading a visual novel, tapping a character's name shows that character's card (name, reading, portrait, role) instead of a guess from JMnedict or nothing. LFG finds the visual novel on VNDB, builds the name dictionary itself, and uses it only for that game.

Decided already:

- VNDB only. No AniList, so no anime, manga or ordinary console games.
- Built entirely on the device. LFG talks to `api.vndb.org` and VNDB's image server and to nothing else. It never calls characterdictionary.tokyo.
- The Rust builder in `bee-san/Japanese_Character_Name_Dictionary` (`yomitan-dict-builder/src/`) stays the reference. The Kotlin here is a port of its VNDB path, and a parity check keeps the two from drifting.

## What was measured before planning

These shaped the design, so they are recorded here.

- **LFG's engine already takes these dictionaries.** The site's zip for Ever17 (13 characters, 28,220 entries, 82 MB of JSON unzipped) imported through `host-build/lfg_dict_host` in 0.18 s on the desktop and took 5 MB on disk. `倉成さんは` matched `倉成さん` as one word with the honorific note.
- **Honorifics are most of the size.** About 320 suffixes times about 10 name forms gives roughly 2,170 entries per character. A 150-character cast would be about 325,000 entries and nearly 1 GB of JSON passing through the zip. It has to be streamed, never held in memory.
- **Steam games match exactly.** `POST /kana/release` with `["extlink","=",["steam",412830]]` returned STEINS;GATE (v2002). LFG already holds the Steam app ID in `Named.steamAppId`.
- **Title search alone is not safe.** Searching VNDB for "Journey" and "Inside" returns visual novels with exactly those titles, and "Hades" on Switch returns an unrelated one. Adding `["platform","=","swi"]` removed the Celeste false match. Automatic matching needs an exact title, the right platform and a Japanese release together.
- **Dictionary titles must be unique and slash-free.** hoshidicts uses the title as the folder name and refuses `/` and `\`. The site titles every dictionary "Bee's Character Dictionary", which would make each new one replace the last.
- **VNDB gives no kana.** A character has `name` (romanised, family first) and `original` (kanji). The reading is derived from the romanised name, which is what `kana.rs` and `name_parser.rs` do.
- **The site's README is out of date on spoilers.** The server takes `spoilers=false`, `description=false` and so on, all defaulting to on. `spoiler_level` is ignored. Use the real switches when running the parity check.

## How it works

1. **Link the game to a VNDB entry.** The link is stored on the game's row.
2. **Build.** A background job fetches the title, voice actors and characters, downloads and shrinks the portraits, and streams a Yomitan zip into the cache.
3. **Import.** The existing `DictionaryRepository.addFromZip` path imports it. The dictionary is marked with its VNDB ID.
4. **Look up.** A lookup names the game it is for. The character dictionary for that game's VN is searched first, then the ordinary dictionaries. No other game sees it.

### Linking a game to VNDB

The link is automatic only when one of these holds:

- **Steam:** the game has a Steam app ID and exactly one VN has a release linked to it.
- **Title and platform:** after `GameTitle.key` normalisation the game's title equals the VN's title, alternative title, one of its aliases or one of its `titles` entries; the VN has a release on the platform the game is running on; the VN has a Japanese release; and exactly one VN passes all three.

Anything weaker becomes a suggestion that needs one tap. The game's page also has a search box to pick or change the VN by hand, and "None" to stop LFG asking. A link made by hand is never changed automatically.

#### Asking in the popup

A suggestion that only waits on the game's page is rarely seen, so LFG also asks once where the player already is. The first time a word is looked up in a game that has an unanswered suggestion, the popup gets one extra line at the bottom: "Is this the visual novel *Title*? Yes · No".

- It is shown only inside a popup the player opened. Nothing appears over the game by itself.
- It is shown once per game. Yes links the VN (`vndb_how` = `suggested`) and starts the build; No sets `vndb` to `none`. Closing the popup without answering counts as neither, and the line does not come back; the suggestion stays on the game's page.
- Games with no suggestion, which is nearly every ordinary game, never see the line.

Rows with `app = true` (ordinary apps) and rows with reading turned off are never matched.

The platform comes from the app the game runs in:

| App | VNDB platform |
|---|---|
| Eden, yuzu, Citron | `swi` |
| PPSSPP | `psp` |
| Vita3K | `psv` |
| AetherSX2 | `ps2` |
| DuckStation | `ps1` |
| aPS3e, RPCSX | `ps3` |
| DraStic, melonDS | `nds` |
| Citra, Lime3DS, Azahar, Mandarine, Panda3DS | `n3d` |
| Dolphin | `wii` |
| Cemu | `wiu` |
| Kirikiroid2, ONScripter, Winlator, DroidDeck, GameNative, GameHub | `win` |
| The game is its own Android app | `and` |
| RetroArch, ScummVM, Ren'Py and the rest | unknown: suggestion only |

Every code in the table was checked against `GET https://api.vndb.org/kana/schema` on 2026-11-22. VNDB has no GameCube platform, so Dolphin maps to `wii` only.

### What is stored

- **`games` table, version 6** (auto-migration from 5, new schema file in `app/schemas`):
  - `vndb` text, default empty: empty means not looked for yet, `none` means the user declined, otherwise an ID such as `v17`.
  - `vndb_how` text, default empty: `steam`, `title`, `suggested` or `hand`.
  - `vndb_suggested` text, default empty: the VN ID offered when the match was not confident.
  - `vndb_asked` boolean, default false: the popup has asked once.
- **`InstalledDictionary.vndb: String? = null`**: set on a character dictionary. The default keeps existing saved lists readable.
- **Dictionary title:** `<VN title> (VNDB v17)`, with `/` and `\` swapped for their full-width forms as `VjdictImport` already does. One dictionary per VN, shared by every game row linked to it.

The database is in backups and the dictionaries are not. A game whose `vndb` is set but whose dictionary is missing gets it rebuilt the next time the game is seen.

### Scoping lookups to the game

`DictionaryRepository.openSession` adds every installed dictionary to one session today (`DictionaryRepository.kt:103`). Change it so that:

- Dictionaries with `vndb` set are left out of the ordinary session.
- `lookup`, `matchLength`, `styles` and `mediaBlocking` take an optional VNDB ID. A session for "ordinary dictionaries plus this VN" is opened on first use, with the character dictionary added first so its entries lead.
- At most two sessions stay open, because the Thor can show a game on one screen and LFG's lines list for a different game on the other.

Callers to update: the overlay's two lambdas (`LfgAccessibilityService.kt:110-111`, using the game in front) and `MainViewModel.lineLookup` (`MainViewModel.kt:149`, using the line's game). `SelectionActivity` and the practice sentence (`MainViewModel.lookupPractice`) pass nothing.

Measure how long opening a session takes on the Thor. If switching between two games costs more than about 50 ms, keep the two-session cache; if it is negligible, a single session reopened on change is simpler.

### Building the dictionary

New package `io.github.beesan.lfg.names`. Everything except the portrait code is plain Kotlin with no Android imports, so it runs in unit tests.

| Kotlin file | Ported from | Notes |
|---|---|---|
| `Kana.kt` | `kana.rs` (397 lines) | Whole file. |
| `NameReadings.kt` | `name_parser.rs` | Only `split_japanese_name` and `generate_mixed_name_readings`, plus the whitespace strip in `generate_name_readings`. The hint-based splitting is AniList-only and stays out. |
| `Honorifics.kt` | `name_parser.rs` lines 25-348 | The suffix table, copied in order. Order matters: the first of two duplicate suffixes wins. |
| `CharacterCard.kt` | `content_builder.rs` | `build_content`, `build_honorific_content`, `strip_spoilers`, the VNDB markup and BBCode parsing, stats and traits. Leave out `build_merged_content` and the AniList spoiler pattern. |
| `CharacterDictionary.kt` | `dict_builder.rs` | `generate_entries_for_merged` for a single VN, the index, the tag bank and the zip. Leave out cross-title merging and `parse_alt_name_format`'s AniList case. |
| `Vndb.kt` | `vndb_client.rs` | `fetch_vn_info`, `fetch_characters`, `process_character`, `search_vns`, retry on 429. New: release lookup by Steam ID, and search with platform and language filters. Leave out user lists. |
| `Portraits.kt` | `image_handler.rs` | Android only. Decode, fit inside 160 by 200, write JPEG. |
| `VnMatcher.kt` | new | The linking rules above, as a pure function over search results. |
| `CharacterNames.kt`, `CharacterNamesWorker.kt` | new | Orchestration and the WorkManager job, modelled on `DictionaryWorker`. |

Rules for the port:

- **Iterate code points, not `Char`s.** Rust's `char` is a whole code point. `kana.rs` tests kanji in the supplementary planes (Extension B onwards), which are two `Char`s in Kotlin.
- **Keep the entry order and the dedupe order.** Which form of a name wins when two collide depends on it.
- **Keep the VNDB field list identical** to the Rust request, so both builders see the same data.
- **Compare JSON as parsed values, not bytes.** serde writes object keys alphabetically; key order is not part of the contract.
- **Stream the zip.** Serialise each character's card once. An honorific entry is the same card with one banner element in front, so write it as prefix, banner, saved card bytes. Use the fastest deflate level. Lift the private `Json` and `ZipWriter` out of `VjdictImport.kt` so both importers share them.
- **Network:** `HttpURLConnection` as elsewhere in the app, user agent `LearnFromGames`, 200 ms between character pages, at most three retries on 429, and the Wi-Fi-only setting respected.

LFG's own defaults, where they differ from the site:

- **Spoilers off.** Spoiler-tagged description text and spoiler traits are stripped unless the user turns them on. The site defaults to showing them.
- **Honorifics on**, pending the Thor measurement in step 9.
- **Index:** author and attribution name VNDB and the reference builder, so the Sources screen credits them. Confirm the wording VNDB asks for at vndb.org/d17 before release.

## Steps

Each step ends with `tools/check.sh` passing. Start on a branch once the uncommitted dialogue-samples work on `main` is committed, since it touches `Settings.kt`, `LfgAccessibilityService.kt`, `MainViewModel.kt` and `SettingsScreens.kt`, which this plan also edits.

1. **Kana and readings.** `Kana.kt`, `NameReadings.kt`, `Honorifics.kt`, with the Rust tests for `kana.rs` and the VNDB-relevant ones from `name_parser.rs` ported to JUnit. About 600 lines plus tests.
2. **Cards and entries.** `CharacterCard.kt` and `CharacterDictionary.kt`, fed characters built in the test. Tests port the relevant Rust ones, then import a built zip with `lfg_dict_host` and look a name up. About 900 lines plus tests.
3. **VNDB client.** `Vndb.kt` behind a small transport interface so tests can answer with canned JSON. Fixtures are made-up characters, not copied VNDB records. About 350 lines plus tests.
4. **Parity with the Rust builder.** See the next section. Fix every difference or write down why it is intended.
5. **Scoped sessions and install by ID.** The `InstalledDictionary.vndb` field, the session changes, `Portraits.kt`, the worker, and a debug-only way to build a given VN ID. Check on the emulator that a name looks up inside the fake game and not in another app.
6. **The game link and choosing by hand.** Database version 6, a "Character names" row on the game's settings page (near `SettingsScreens.kt:474`) showing the linked VN, build progress or failure, and Search, Update, Remove and None. This is the first point where the feature is usable end to end.
7. **Automatic matching.** `VnMatcher.kt` and the trigger where a game is first named (`LfgAccessibilityService.namedInside`, line 844) and where an app's own row is first read. Tests cover the Journey, Inside and Hades cases above so they stay suggestions. The popup's one-time "Is this the visual novel…?" line, in both the overlay popup and the Lines screen's, and only once per game.
8. **Settings and the dictionaries list.** Switches for automatic matching, honorifics and spoilers; changing the last two marks built dictionaries for rebuilding. Character dictionaries get their own section on the Dictionaries screen, removable but not reorderable. Removing one sets the game to `none` so it does not come back.
9. **On the Thor.** Build a small cast and the largest cast available. Record build time, import time, disk size and session-open time, and how the card looks in the popup.

## Checking the port against the Rust builder

- Clone the reference repository beside this one and run it locally (`cargo run --release` in `yomitan-dict-builder`), so the hosted site is not loaded and the Rust version compared against is known.
- A report-style test in the manner of `VjdictReport.kt`, run only when an environment variable names the local server, builds the same VN with the Kotlin code and fetches `/api/yomitan-dict?source=vndb&id=…&image=false` plus the matching switches from the Rust server.
- Compare term, reading, tags, score and card for every entry, as sets, for about five VNs: a small cast, a large cast, one with kana-only names, one with single-word names and one with many aliases.
- Portraits are compared by eye on the device. Resized dimensions may differ by a pixel between the two image libraries, so run the comparison with images off.
- Nothing fetched from VNDB is committed.

## Decisions to confirm

Each has the default this plan assumes.

1. **Spoilers off by default.** The site defaults to on.
2. **All of the roughly 320 honorifics.** If step 9 shows a large cast taking more than about a minute to build or more than about 100 MB on disk, cut to a short list of common ones, or to main and primary characters only.
3. **Automatic only when confident**, a one-tap suggestion otherwise. The alternative is installing the top search hit, which would put a stranger's cast into non-VN games with common titles.
4. **Updates by hand** in the first version, with an Update button. Rebuilding automatically when a dictionary is more than a month old can follow.

## Left out

- AniList, user lists, frequency dictionaries, and merging one character across several titles.
- A fully offline build from VNDB's database dump.
- Linking the Rust crate into the app. It carries the web server, Parquet, Arrow and SQLite, and LFG's build has no Rust toolchain.
