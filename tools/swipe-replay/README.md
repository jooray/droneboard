# Swipe replay

Runs recorded swipes through exactly the keyboard's decoder: the FUTO Swipe encoder, Droneboard's key-path dictionaries and the shared ranking in `native/droneswipe/ranker.cpp`. Use it to tune swipe typing on real gestures without a phone.

## Recording on the phone

Settings → Gesture typing → **Record swipes for tuning**. The screen shows a word; swipe it into the field. Each swipe is appended to a JSONL file in the app's external files directory. Nothing is recorded outside that screen, and nothing is uploaded.

```sh
adb pull /sdcard/Android/data/today.cypherpunk.droneboard/files/swipe-recordings/ .
```

Every line holds:

- the target word, the context words and the normalized points (`x`, `y`, `t`) and letter layout exactly as fed to the decoder;
- the keyboard's own top predictions (`pred`);
- the raw pointer coordinates (`raw`) and the key boxes (`keyboard`), for re-normalizing later.

## Building

You need a [swipe-library](https://gitlab.futo.org/keyboard/swipe-library) checkout at the revision in `tools/build-swipe-lib.sh`, with ExecuTorch built for the host (`make host` in it).

```sh
cmake -S tools/swipe-replay -B build/swipe-replay -DSWIPE_LIBRARY_DIR=<swipe-library>
cmake --build build/swipe-replay -j
```

## Running

```sh
A=app/src/main/assets/swipe
build/swipe-replay/swipe-replay --encoder $A/honorable_sturgeon/model_int8.pte \
  --dict en:$A/en.kpd --dict sk:$A/sk.kpd \
  --params en=0.5,0.9866,0.025,-2,0.1,0 --params sk=0.5,0.9866,0.025,-2,0.1,0 \
  --out pred.jsonl swipes-*.jsonl
```

`--history words.tsv` (word, tab, weight 0–255) adds a user dictionary like the app's typing history; `--history-params lambda,bonus` sets its weights (app: `0.025,1.0`).

The dictionary order and the `--params` must match `SwipeGestureDecoder` in the app. Then the replay reproduces the keyboard's top-1 exactly (checked on 20 emulator recordings).

The tool prints top-1 and top-3 accuracy per `lang` field. It also splits the errors into four kinds:

- **diacritics:** right letters, wrong accents;
- **wrong language:** a word from the other dictionary won;
- **not in beam:** the encoder never proposed the target's key path;
- **other.**

Options:

- `--kappa`, `--cap`, `--prior` set the context language bonus;
- `--beam` sets the beam width;
- `--context N` sets how many words before the cursor are used.

## Synthetic swipes

`synth.py` writes synthetic swipes with real sentence context from a text corpus, in the same format. Use it when no recordings are at hand:

```sh
python3 -I tools/swipe-replay/synth.py corpus-sk.txt sk 1500 eval-sk.jsonl --require "ľôäĺŕ" --forbid "ěřůőűöüß"
```

`--layout qwertz-de` (ü ö ä keys) and `--layout qwerty-es` (ñ key) swipe over those keys instead of the
base letter; `--cased` also takes capitalized words inside a sentence (German nouns).

## Results, 13 Nov 2026

Synthetic swipes, QWERTY, with sentence context: 1500 SK, 1000 EN and 1000 code-switched (an EN word in an SK sentence and the other way round).

| Ranking | SK | EN | code-switched | all |
|---|---|---|---|---|
| Before: EN main-language bonus +0.5 | 87.1 | 91.3 | 88.2 | 88.6 |
| No main-language bonus (now) | 89.1 | 91.0 | 88.6 | 89.5 |
| Context language prior κ=0.3, cap 4 | 89.8 | 92.1 | 72.8 | 85.6 |

Truncating the beam to the engine's own top 32 before our ranking made no difference.

HeliBoard's point sampling (one point per 1/6 key width) costs about 1.5 pp compared with dense points.

## Contractions and hyphenated words, 15 Nov 2026

`contractions-en.tsv` lists 47 English contractions with typical words before them (I'll, I'm,
don't, we're, it's, …) plus 11 plain words with the same key path as controls (were, well, its,
ill, …). `hyphenated.tsv` lists Slovak, Czech and English hyphenated words. Each line gets ten
synthetic swipes:

```sh
python3 -I tools/swipe-replay/synth.py --words tools/swipe-replay/contractions-en.tsv en 10 contr.jsonl
python3 -I tools/swipe-replay/synth.py --words tools/swipe-replay/hyphenated.tsv x 10 hyph.jsonl
```

| Set (dictionaries) | 0.1.11 top-1 / top-3 | 0.1.12 top-1 / top-3 |
|---|---|---|
| contractions (en) | 62.1 / 93.2 | 79.4 / 99.8 |
| contractions (sk + en) | 60.0 / 90.0 | 77.4 / 99.1 |
| same-key-path controls (en) | 78.2 / 100 | 78.2 / 100 |
| hyphenated (sk + en) | 0 / 0 | 51.6 / 84.7 |
| recordings, 106 (sk + en) | 82.1 / 92.5 | 82.1 / 92.5 |
| sink-or-swipe Czech, 1872 (cs) | 78.2 / 92.0 | 78.2 / 92.1 |

Most of the gain comes from dropping the capital-letter penalty for "I'll", "I'm", "I've" and
"I'd". The remaining errors are mostly words that the n-gram counts prefer. "well" beats "we'll"
and "were" beats "we're" even after "so" or "and", and "email" beats "e-mail".

## German, Spanish, Dutch and beam 200, 15 Nov 2026

Synthetic swipes with held-out sentence context (FineWeb-2 test split, which the dictionaries never
saw), with English as second language; beam 200 as in the app:

| Set | top-1 | top-3 |
|---|---|---|
| German, QWERTZ, nouns included (de + en) | 83.8 | 91.4 |
| German words with ü ö ä, swiped over umlaut keys (de) | 77.6 | 87.6 |
| Spanish, QWERTY with ñ (es + en) | 86.6 | 96.3 |
| Spanish words with ñ, swiped over the ñ key (es) | 90.4 | 96.0 |
| Dutch, QWERTY (nl + en) | 84.6 | 94.3 |

Before the twin trie nodes for keys with diacritics (`set_layout` in `kp_dictionary.cpp`), swipes over
the ñ key or the umlaut keys reached the word in 28.4 % and 33.6 % of cases. About 8 % of the German
targets are compounds that the 300,000-word list lacks.

Beam 100 → 200: recordings (106, sk + en) top-3 92.5 → 94.3, sink-or-swipe Czech 78.2 / 92.1 →
78.6 / 92.6.

## Tap typing end to end, 16 Nov 2026

`lm-eval` scores the model's own top 3, which is not what the suggestion strip shows. The strip has
three slots for the next word; while typing, the centre echoes the typed word, so two slots are left
for words, and one when an emoji takes the right slot. `TapPredictionEval` (app unit test, skipped
unless `TAP_EVAL_DIR` is set) replays held-out text through what the app does: the AOSP dictionaries
(the real native library, built for the host), `LanguageModelSuggestions`, the facilitator's emoji and
word rules and `Suggest`'s strip order. It counts a hit when the target is in a visible slot.

```sh
# the AOSP library for the host: compile app/src/main/jni (NativeFileList.mk: LATIN_IME_JNI_SRC_FILES
# and src/ + LATIN_IME_CORE_SRC_FILES) with clang++ -std=c++17 -shared -fPIC -Isrc -I$JDK/include{,/darwin}
# into $T/libjni_latinime.dylib
.venv/bin/python -I tools/lm/make_lm_eval_set.py --work $LM --langs sk --split test --n 1000 --out $T/items-sk.jsonl
build/swipe-replay/lm-eval --dict sk:$A/sk.kpd --dict en:$A/en.kpd --neural sk:$A --neural en:$A --mode interp \
  --tokens --dump $T/dump-sk.jsonl --max-typed 12 $T/items-sk.jsonl          # add --fold for dump-sk-fold.jsonl
TAP_EVAL_DIR=$T ./gradlew :app:testDebugUnitTest --tests 'helium314.keyboard.latin.lm.TapPredictionEval'
```

`--dump` writes the app's model candidates (neural and n-gram mixed, 8 words) for every prefix;
`--fold` types the letters without accents. `TAP_EVAL_CONFIGS="base:rerank=0,modelnext=0,emoji=0;now:"`
compares settings in one run (`aosp=`, `gap=`, `emojimin=` set the weights). The `swipePromotion`
test measures `preferNextWordSuggestion` on `swipe-replay --out` results.

Visible keystroke savings (KSS), 1000 held-out FineWeb-2 items per language, sk and cs with en as
second language, en with sk; "fold" types without accents:

| Step | sk | sk fold | en | cs | cs fold |
|---|---|---|---|---|---|
| 0.1.14 | 37.5 | 34.4 | 39.1 | 40.9 | 38.0 |
| model ranks completions (accent-folded) | 46.0 | 44.8 | 47.6 | 49.5 | 47.9 |
| + emoji only on a keyword, 3+ letters | 51.2 | 50.7 | 52.0 | 53.6 | 52.3 |
| + next word in the model's order | 51.5 | 50.9 | 52.8 | 54.3 | 52.9 |
| model alone, top 3 (lm-eval) | 54.4 | 53.6 | 55.8 | 57.3 | 56.4 |

In 0.1.14 an emoji took the right slot in 82–89 % of typing states, now in 6–9 %. The target was
visible after one letter in 22.0 / 20.8 / 25.0 % (sk / en / cs) of items, now 39.2 / 49.0 / 43.8 %.
The dictionaries' rank weight 0.35 was best among 0, 0.25, 0.35, 0.5, 1 and 2 (0.25–0.5 within
0.3 pp); it keeps their spatial evidence for typos, which this eval (exact letters) does not test.

`preferNextWordSuggestion` (a next-word prediction among the swipe candidates replaces the first) on
FUTO Swipe results, top-1: recordings 82.1 → 82.1, synthetic sk 84.9 → 84.2, en 93.5 → 92.0,
cs 89.0 → 87.1, sink-or-swipe cs 78.3 → 77.9. It is off for the FUTO decoder now.
