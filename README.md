<p align="center"><img src="art/droneboard-icon-512.png" width="128" alt="Droneboard icon"></p>

# Droneboard

Droneboard is an open-source Android keyboard that works entirely offline and feels like Gboard. It has no `INTERNET` permission. It types in two languages at once, swipes with an open swipe engine, and turns speech into text without switching keyboards.

> **Status: beta (0.1.x).** It is good enough for daily use by people who like to report bugs. Expect rough edges.

The name comes from drone ambient music. A swipe across the keys feels like someone sliding their hand along a synth keyboard and letting the note hang.

## Languages

Czech, Slovak, English, German, Spanish, Dutch and Hungarian, plus Slovak and Czech without diacritics (for people who type "dakujem" and "muze"). Each has swipe typing, neural next-word prediction and emoji search, and any of them can be mixed in one keyboard. German and Hungarian use QWERTZ, Spanish has the ñ key, as in Gboard. HeliBoard's layouts and tap-typing dictionaries for other languages are still there.

## What works today

- **Swipe typing in several languages at once,** e.g. Slovak, German and English without switching. It runs on the [FUTO Swipe](https://gitlab.futo.org/keyboard/swipe-library) encoder with our own decoding:
  - the dictionary is searched by base letters and the accents are added afterwards;
  - the previous word re-ranks the candidates;
  - swiping over a key with an accent (ñ, ü) counts as its base letter;
  - a live preview runs while you swipe.
- **One-tap dictation** with a microphone at the right end of the suggestion strip. The keyboard records the audio itself and hands it to [Offline Speech to Text](https://zapstore.dev/apps/app.offlinespeechtotext) for transcription on the phone.
  - It records from Bluetooth headsets, including LE Audio and hearing aids, and falls back to the phone mic if the headset stays silent.
  - Long dictation is split into pieces, so you can keep talking past OSTT's 60-second limit.
- **The Gboard look and defaults:**
  - Material You colors, rounded keys, pill-shaped enter and `?123` keys;
  - Gboard's order of long-press characters;
  - a number row in password fields;
  - the word you typed in the middle of the strip;
  - suggestions without silent autocorrection.
- **Next-word prediction with a small neural network on the phone,** for every language above. The model sees the text before the cursor and predicts only in the languages you enabled; words you have typed before also count. Trigram (word-triple) statistics back it up, and completion ignores accents ("dak" offers "ďakujem").
- **Emoji:** search the emoji panel by any part of a word in any of these languages ("ocket" finds 🚀). A matching emoji also appears in the strip while you type.
- **A compact keyboard on tablets and unfolded foldables** that you resize and move with handles.
- **Space bar long-press** moves the cursor like a trackpad, or switches the language if you prefer.
- **Clipboard history, split and one-handed layouts, themes, custom layouts and a physical keyboard,** all inherited from HeliBoard.
- **Swipe recording for tuning:** an optional screen shows words for you to swipe and saves the gestures to a local file. The `tools/swipe-replay` tool replays them through the exact decoder.

## How good is the swipe?

| Test | Top-1 | Top-3 |
|---|---|---|
| Czech gestures, Google gesture library (as in HeliBoard) | 82.4 % | 96.3 % |
| Czech gestures, Droneboard with its own Czech dictionary | 78.6 % | 92.6 % |
| Real phone, 106 prompted Slovak and English words | 82.1 % | 94.3 % |

These numbers come from the offline gesture set "sink-or-swipe" (1,872 Czech gestures, QWERTZ and QWERTY) and from one person's recordings. The gesture words were drawn from the dictionary the Google row uses; 2.3 % of them are rare words that Droneboard's open-data Czech list lacks. On the words it knows, Droneboard gets 79.8 % / 94.0 %. None of these gestures has sentence context, which is where the trigrams help most. Treat the numbers as a direction, not a promise.

## Planned

- More languages, with the same swipe dictionaries and neural prediction.
- A clipboard chip for fresh links, e-mails and one-time codes, like Gboard's.
- Hold A/X/C/V/Z/Y for select all, cut, copy, paste, undo and redo.
- A "fix sentence" button backed by a small on-device model.
- A geek mode with a terminal row and snippets for AI agents. It will be off by default.

## Install

Install Droneboard from [Zapstore](https://zapstore.dev/apps/today.cypherpunk.droneboard) (preferred), or take the APK from [GitHub releases](https://github.com/jooray/droneboard/releases/latest), or track those releases with Obtainium. Dictation needs [Offline Speech to Text](https://zapstore.dev/apps/app.offlinespeechtotext) installed next to it.

## Building

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 17+
./gradlew assembleDebug
```

You need the Android SDK and NDK 28. The native swipe library is prebuilt in `app/src/main/jniLibs`. Rebuild it with `tools/build-swipe-lib.sh`. The APK ships the ARM libraries only; for an emulator add `x86_64` to `abiFilters` in `app/build.gradle.kts`. A release is prepared with `tools/release.sh`. The Droneboard dictionaries (Slovak, Czech, English, German, Spanish, Dutch, Hungarian) are rebuilt from open data with the scripts in `tools/dicts`.

## Privacy

- No `INTERNET` permission, no analytics, no crash reporting.
- The keyboard downloads nothing. Extra dictionaries are imported from a file, see [docs/dictionaries.md](docs/dictionaries.md).
- Dictation audio goes to Offline Speech to Text on the same phone and nowhere else.
- Contacts in suggestions are optional and off by default.

## Contributing

Droneboard is built with AI assistance, and AI-assisted contributions are welcome. Issues and pull requests too. Anyone may take any part of it under the license.

## Credits

- Droneboard started as a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard), which is based on [OpenBoard](https://github.com/openboard-team/openboard) and AOSP LatinIME. Thank you to everyone who built them.
- Swipe typing is powered by FUTO Swipe ([swipe-library](https://gitlab.futo.org/keyboard/swipe-library), GPL-3.0). The model weights are under the FUTO Model Weights License.
- The Bluetooth and LE Audio recording comes from [Third Ear](https://github.com/jooray/third-ear) ([website](https://cypherpunk.today/third-ear/), [Zapstore](https://zapstore.dev/apps/app.thirdear)) by the same author, relicensed under GPL-3.0 for Droneboard.
- The word lists come from [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) (ODC-By), and for Czech also from [Wikipedia](https://huggingface.co/datasets/wikimedia/wikipedia) (CC BY-SA), filtered through Hunspell dictionaries.
- The emoji dictionaries come from [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries). They are built from Unicode CLDR annotations (Unicode License v3) and Signal's emoji search data (AGPL-3.0).

## License

GPL-3.0, see [LICENSE](LICENSE). Some files are under Apache-2.0 or CC-BY-SA-4.0, as their headers say (`LICENSE-Apache-2.0`, `LICENSE-CC-BY-SA-4.0`). Third-party model weights keep their own licenses.
