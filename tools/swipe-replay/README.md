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

## Results, 13 Nov 2026

Synthetic swipes, QWERTY, with sentence context: 1500 SK, 1000 EN and 1000 code-switched (an EN word in an SK sentence and the other way round).

| Ranking | SK | EN | code-switched | all |
|---|---|---|---|---|
| Before: EN main-language bonus +0.5 | 87.1 | 91.3 | 88.2 | 88.6 |
| No main-language bonus (now) | 89.1 | 91.0 | 88.6 | 89.5 |
| Context language prior κ=0.3, cap 4 | 89.8 | 92.1 | 72.8 | 85.6 |

Truncating the beam to the engine's own top 32 before our ranking made no difference.

HeliBoard's point sampling (one point per 1/6 key width) costs about 1.5 pp compared with dense points.
