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

## Hungarian, 16 Nov 2026

Synthetic swipes on plain QWERTZ with held-out sentence context (FineWeb-2 `hun_Latn` test split),
with English as second language, beam 200:

| Set | top-1 | top-3 |
|---|---|---|
| Hungarian, QWERTZ (hu + en), 1500 | 80.5 | 89.7 |
| Hungarian words with á é í ó ö ő ú ü ű (hu + en), 580 | 79.7 | 86.7 |

Most errors are targets the encoder never proposed (7.7 %, 12.1 % for words with accents, which
are longer on average); accents alone are wrong in under 2 %.

## Neural language model in the swipe ranking, 16 Nov 2026

The final decode now reranks the 40 best candidates with the neural next-word models of the swipe
languages (`fuse_neural` in `native/droneswipe/neural_lm.cpp`, log-linear):

score + w × log P_nn(word | text before the cursor) − 0.015 × frequency − 0.05 × n-gram context term

w is 0.2 for a clear gesture and grows towards 0.4 as the score gap of the two best candidates
shrinks (w = 0.2 + 0.2 × exp(−gap / 4)). A word outside the model's vocabulary gets
log P(<unk> | context) + its unigram log probability in the dictionary + 3. Without context (start
of the text) w is 0.1 and the n-gram weights stay. The models are mixed by the language posterior,
like the next-word prediction; a language without a loaded model switches the fusion off.

Sets: synthetic swipes with sentence context at three levels of sloppiness (`synth.py --sloppy`):
s0 is the precise default, s1 sloppy (aim error 0.30 key widths, the word drifting, corners cut,
letters passed by, faster), s2 very sloppy. Two held-out sources: FineWeb-2 test split (FineWeb for
English) with any word as target (`synth.py --tokens`), and the LM test split
(`tools/lm/make_lm_eval_set.py`, targets only from the model's vocabulary). Dictionaries: the
language + English (Slovak + English for English). Tuned on separate dev sets with
`--dump`; "missed" = the target is among the candidates but not first.

| Set | s0 top-1 / top-3, missed | s1 top-1 / top-3, missed | s2 top-1 / top-3 |
|---|---|---|---|
| sk, FineWeb-2 | 86.8 / 94.6, 9.1 → **90.0** / 95.1, 5.9 | 63.2 / 77.8, 23.9 → **75.2** / 83.5, 11.9 | 20.9 / 29.2 → 30.9 / 37.3 |
| en, FineWeb | 89.3 / 95.7, 7.7 → **93.5** / 96.1, 3.4 | 75.0 / 85.9, 18.7 → **84.9** / 91.1, 8.7 | 36.9 / 48.3 → 51.0 / 59.9 |
| cs, FineWeb-2 | 87.1 / 94.4, 9.7 → **90.6** / 95.3, 6.2 | 65.6 / 77.9, 20.0 → **73.9** / 82.1, 11.7 | 24.9 / 32.9 → 32.8 / 38.0 |
| de, FineWeb-2 | 83.5 / 91.4, 9.7 → **88.3** / 92.5, 4.9 | 61.1 / 77.1, 23.0 → **72.3** / 81.4, 11.8 | 24.9 / 36.9 → 37.5 / 45.1 |
| hu, FineWeb-2 | 79.1 / 87.8, 12.6 → **83.4** / 89.5, 8.3 | 57.1 / 69.2, 21.3 → **66.7** / 74.1, 11.7 | 18.6 / 26.5 → 27.5 / 32.5 |
| sk, LM test split | 87.2 / 94.4 → **91.9** / 95.7 | 64.8 / 78.4 → **79.8** / 87.5 | 29.0 / 38.8 → 39.7 / 49.1 |
| en, LM test split | 87.7 / 94.8 → **91.8** / 96.7 | 69.2 / 83.0 → **80.8** / 89.4 | 34.1 / 46.5 → 47.9 / 56.7 |
| cs, LM test split | 90.4 / 97.4 → **93.2** / 97.9 | 70.3 / 83.1 → **78.1** / 86.9 | 30.7 / 39.5 → 38.3 / 45.0 |

Guards:

| Set | before top-1 / top-3 | after |
|---|---|---|
| recordings, 106 (sk + en, no context) | 82.1 / 94.3 | 84.9 / 97.2 |
| sink-or-swipe Czech, 1872 (cs, no context) | 78.6 / 92.6 | 78.7 / 92.4 |
| older synthetic sk / en / cs (3 context words), top-1 only | 85.1 / 93.5 / 89.5 | 88.6 / 96.5 / 89.6 |
| contractions (sk + en) | 77.2 / 99.1 | 82.4 / 100 |
| hyphenated (sk + en) | 51.6 / 84.7 | 48.4 / 86.8 |

The best weight grows with sloppiness (0.2-0.3 on s0, 0.4-0.6 on s1, 0.8-1.0 on s2), hence the
weight that follows the score gap: +0.8 pp top-1 on s1 against a fixed weight, the same on s0. A
weight of its own per language gained at most 0.3 pp. Retuning gamma did not help (+0.1 helps s1,
costs s0). Without context the model only knows the start of a text: on the Czech word list a
weight of 0.2 cost 0.8 pp, hence 0.1. The hyphenated loss is "e-mailom" (now "emailom") and Czech
"chcete-li" in the Slovak set: the model sees a hyphenated word as two tokens, so it is out of
vocabulary.

Time on the Mac: 2.6-3.4 ms per swipe for two models when the context is new (up to 31 tokens), about
0.0 ms when the distribution is cached (the same text as the last next-word prediction). The app
reuses the key/value cache, so a swipe after a swipe runs one model step per language.

```sh
# the app's fusion is on whenever --neural is given; --no-fuse for the old ranking
build/swipe-replay/swipe-replay --encoder $A/honorable_sturgeon/model_int8.pte \
  --dict sk:$A/sk.kpd --dict en:$A/en.kpd --neural sk:$A --neural en:$A ... set.jsonl
python3 -I tools/swipe-replay/synth.py fineweb2-slk-test.txt sk 1500 s1.jsonl --tokens --sloppy 1
```
