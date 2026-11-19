# Adding a language to Droneboard

This guide is written for an AI coding agent (or a person) with a fresh checkout of `main` and no
other context. Follow it top to bottom. Every command and file name below exists in the repository
unless the text says otherwise; section 11 lists what is missing or manual.

Placeholders used throughout, with the Hungarian values as the running example:

| Placeholder | Meaning | Hungarian |
|---|---|---|
| `LL` | language code of the locale, used in all asset names | `hu` |
| `FW2` | FineWeb-2 config name (ISO 639-3 + script) | `hun_Latn` |
| `HS` | Hunspell dictionary name | `hu_HU` |
| `$W` | a work directory **outside** the repository | `~/droneboard-work` |

Worked examples in the git history (read them with `git show --stat <hash>`):

| Commit | What it shows |
|---|---|
| `53ae4c4f` | Hungarian: all assets, swipe parameters, app language, docs. The cleanest single-language example |
| `00efdb4e` | Hungarian main (tap) dictionary rebuilt from open data |
| `23961b4f` | German, Spanish, Dutch: assets, layouts, long-press keys, a native change for accented keys |
| `714b02bc` | Slovak and Czech without diacritics: how a subtype that HeliBoard does not have is added |

## 1. What "supporting a language" consists of

| Artifact | File | Built by | Needed for |
|---|---|---|---|
| Swipe word list with bigrams and trigrams | `app/src/main/assets/swipe/LL.kpd` | `tools/dicts` (sections 4 and 5) | swipe typing, n-gram next-word prediction |
| Neural next-word model, body | `app/src/main/assets/swipe/LL.lmb` | `tools/lm` (section 7) | neural prediction, neural reranking of swipes |
| Neural model, vocabulary and embeddings | `app/src/main/assets/swipe/LL.lmv` | `tools/lm` (section 7) | same; both files must be present |
| Main (tap typing) dictionary | `app/src/main/assets/dicts/main_LL.dict` | `build_dicts.py` + `BuildDictionaryTool` (section 6) | tap suggestions, corrections, spell checker |
| Emoji dictionary | `app/src/main/assets/dicts/emoji_LL.dict` | downloaded, not built here (section 6) | emoji search and emoji suggestions |
| Subtype, layout, long-press keys | `res/xml/method.xml`, `assets/layouts/main/`, `assets/locale_key_texts/LL.txt` | by hand (section 8) | the language appears in the keyboard at all |
| Swipe scoring parameters | `SwipeGestureDecoder.LANGUAGE_PARAMS` | by hand, tuned with `tools/swipe-replay` | swipe accuracy (falls back to the English values) |

The app finds the engine assets by name at runtime: `SwipeGestureDecoder.languagesWithDictionary`
lists `swipe/*.kpd`, `NeuralLanguageModel` lists `swipe/*.lmb` that have a matching `.lmv`, and
`EngineBadges.kt` derives the "neural", "n-grams" and "swipe" badges of the language list from
them. The name is `LocaleUtils.engineKey`: the language code, plus `_` and the private-use subtag
if the locale has one (`sk-x-nd` loads `sk_nd.kpd`). No list of languages has to be edited for the
engines.

Partial support works:

- **Tap dictionary only** (`main_LL.dict`): typing with suggestions, no swipe. Users can also
  import such a file themselves (`docs/dictionaries.md`).
- **`.kpd` without a neural model**: swipe typing and n-gram prediction; the badge says "n-grams".
  The neural reranking of swipes is switched off while any active swipe language lacks a model
  (`tools/swipe-replay/README.md`, "Neural language model in the swipe ranking").
- **No emoji dictionary**: emoji search works only through the other enabled languages.

## 2. Prerequisites

- **Machine:** an Apple Silicon Mac for the neural model. `tools/lm/train.py`, the neural path of
  `evaluate.py`, `mix_eval.py` and `verify_keyboard_lm.py compare` import `mlx`; the repository has
  no PyTorch training script. Everything else (corpus fetch, dictionaries, n-grams, the KN baseline,
  `export_keyboard_lm.py`) is plain Python with numpy and runs on any OS. On a CUDA or CPU box you
  can therefore build `.kpd`, `main_LL.dict` and the emoji dictionary, but not train as the
  scripts stand. MLX itself publishes Linux builds; these scripts were never run on them, so treat
  that as an experiment and say so in the pull request.
- **Tools:** `uv`, `hunspell` on the `PATH` (`brew install hunspell`; `build_dicts.py` calls
  `hunspell -G`), JDK 17+ and the Android SDK for Gradle (see "Building" in `README.md`). NDK 28
  is needed only if you change `native/droneswipe`.
- **Disk and time:** observed for Hungarian: the token streams are about 270 MB, a checkpoint
  directory is under 100 MB, 100M words of raw FineWeb-2 text are 270 MB gzipped. The uncompressed
  and normalized text adds a few GB (an estimate; keep 15 GB free). Training took 89 minutes on an
  Apple M2 Max; the other steps were not timed.
- **Network:** only for the corpus fetch and for downloading the Hunspell and emoji dictionaries.

```sh
W=~/droneboard-work && mkdir -p $W/hunspell $W/norm $W/out $W/ngrams
uv venv $W/.venv
uv pip install --python $W/.venv/bin/python fsspec aiohttp requests pyarrow numpy mlx safetensors
PY=$W/.venv/bin/python
```

There is no `requirements.txt` or `pyproject.toml`; the list above is what the scripts import.
`sentencepiece` is needed only for `prep_sp.py`, `torch` and `executorch` only for `export_et.py`;
neither is part of the shipped pipeline. Never install Python packages outside the virtualenv, and
keep `$W` outside the checkout so that corpora and checkpoints cannot be committed by accident.

## 3. Step 0: check that the language is feasible

1. **Script.** The swipe engine reduces every word to a key path of the letters a to z
   (`keypath()` in `tools/dicts/build_dicts.py`, `key_path_of` in
   `native/droneswipe/kp_dictionary.cpp`): the first code point of the NFD decomposition must be
   a to z, plus the special cases ß → ss, æ → ae, œ → oe. A word with any other letter gets no key
   path and is dropped. Cyrillic, Greek, Arabic and other non-Latin scripts therefore cannot be
   swiped without engine work, and the swipe encoder (`swipe/honorable_sturgeon`, metadata layout
   `en_qwerty`) was trained on a Latin layout.
2. **Case mapping.** `lower_cp` / `upper_cp` in `kp_dictionary.cpp` (mirrored in
   `tools/dicts/pack_swipe_dict.py`) cover ASCII, Latin-1 and Latin Extended-A (U+0100 to U+017F)
   by a parity rule. Check every letter of your alphabet: letters outside these blocks (Romanian
   ș ț at U+0218 to U+021B, Vietnamese) and exceptions inside them (Turkish İ / ı) need a native
   change, a rebuild with `tools/build-swipe-lib.sh` and the same change in `pack_swipe_dict.py`.
3. **Letters with no NFD base** (ł, ø, đ, þ): add them to `SPECIAL` in `build_dicts.py` and to
   `key_path_of` (as `23961b4f` did for ß), or accept that words with them are dropped.
4. **Layout.** Check that HeliBoard already has the language:
   `grep -n 'languageTag="LL' app/src/main/res/xml/method.xml` and
   `ls app/src/main/assets/locale_key_texts/LL.txt`. Most European languages are there (Hungarian
   was). If the layout has extra letter keys (ñ, ü), swiping over them works through twin trie
   nodes (`set_layout`, at most 7 accented keys per base letter).
5. **Text.** Check that FineWeb-2 has the language (expect HTTP 200 or a redirect, not 404):

   ```sh
   curl -sIL https://huggingface.co/datasets/HuggingFaceFW/fineweb-2/resolve/main/data/FW2/train/000_00000.parquet | grep -i '^HTTP'
   ```

   About 100M words gave good results for German, Spanish, Dutch and Hungarian. With much less
   text, lower the `top` and n-gram thresholds and expect weaker prediction.
6. **Spelling filter.** Find `HS.dic` and `HS.aff` in
   [LibreOffice/dictionaries](https://github.com/LibreOffice/dictionaries) and put them in
   `$W/hunspell/` (the prefix passed to the scripts is `$W/hunspell/HS`, without extension). The
   files are only used to filter words and are never shipped. `build_dicts.py` cannot run without
   a Hunspell dictionary (see section 11).
7. **Vocabulary size.** The token streams are `uint16`, so a model vocabulary cannot exceed
   65,535 words. All shipped models use 50,000 (English 30,000).

Never use the Slovak or Czech national corpora (SNK, ČNK) or any other source whose licence
forbids redistribution of derived word lists.

## 4. Corpus and word list

Add the language to `LANG` in `tools/dicts/build_dicts.py` first (this edit is part of the pull
request). The Hungarian entry:

```python
"hu": dict(alphabet="aábcdeéfghiíjklmnoóöőpqrstuúüűvwxyz", top=500_000, one_letter="a s e", min_count=4,
           description="Magyar"),
```

- `alphabet`: every lowercase letter a word may contain; a token with any other letter is
  rejected. Include a to z in full, loanwords need q, w, x. Add `'` only if the language writes
  apostrophes inside words (Dutch does).
- `top`: maximum number of word forms. 160,000 for English, 240,000 to 320,000 for Spanish,
  Dutch, German and Slovak, 500,000 for heavily inflected or agglutinative languages (Czech,
  Hungarian).
- `one_letter`: the real one-letter words, always kept. `min_count`: 4 everywhere so far.
- `description`: the language's own name; it goes into the `.dict` header.

Fetch the text and build the lists (280 row groups of the first FineWeb-2 shard, about 100M words;
the script takes fewer if the shard is smaller):

```sh
$PY -I tools/dicts/fetch_corpus.py FW2 280 $W/corpus-LL        # hun_Latn -> $W/corpus-hu/corpus.txt
python3 -I tools/dicts/build_dicts.py LL $W/out $W/hunspell/HS $W/corpus-LL/corpus.txt
```

Outputs: `$W/out/LL.words.tsv` (surface, key path, frequency 0 to 255, count),
`LL.bigrams.tsv` and `LL.combined` (HeliBoard word list with bigrams).

Sanity checks before going on:

- `head -200 $W/out/LL.words.tsv`: the top must be ordinary function words. Spam shows here; the
  Czech shard ranked "sex" 27th. `tools/dicts/filter_spam.py <lang> <out.txt> <corpus.txt>` drops
  spam documents but only has word lists for `cs`, `sk` and `en`; add one for your language if
  needed, then rebuild from the filtered file.
- `wc -l`: Hungarian gave 493,505 words. A list far below `top` means little text or a strict
  Hunspell dictionary.
- Grep for a few words with every accented letter and check their key paths (column 2).
- Words of a neighbouring language leaking in (Czech forms in Slovak) are stopped by `alphabet`.

Optional steps that the shipped Slovak, Czech and English lists use and a new language does not
need: a second corpus (Czech adds Wikipedia through `clean_wiki.py`), extra vocabulary from your
own writing (`personal_vocab.py` with `vocab/tech-allowlist.txt`, then `--personal` in the next
step) and a local blocklist of words that must never ship (`blocklist.py`, see
`tools/dicts/README.md`). German, Spanish, Dutch and Hungarian were built without any of them.

## 5. N-grams and the `.kpd` swipe dictionary

To measure swipe accuracy on sentences the n-grams never saw, set aside evaluation sentences now
and exclude them from the counts (`synth.py` layouts: `qwerty`, `qwertz`, `qwertz-de`, `qwerty-es`):

```sh
python3 -I tools/swipe-replay/synth.py $W/corpus-LL/corpus.txt LL 1500 $W/eval-LL.jsonl --layout qwertz --tokens
$PY -I tools/dicts/count_ngrams.py LL $W/out/LL.words.tsv $W/ngrams --corpus $W/corpus-LL/corpus.txt \
  --exclude $W/eval-LL.jsonl --min-trigram 2
python3 -I tools/dicts/pack_swipe_dict.py $W/ngrams/LL.words.tsv $W/ngrams/LL.bigrams.tsv \
  app/src/main/assets/swipe/LL.kpd --min-bigram=10 --trigrams=$W/ngrams/LL.trigrams.tsv \
  --min-trigram=3 --trigram-bytes=4000000
```

(The Hungarian numbers in this guide were measured on the FineWeb-2 test split instead, without
`--exclude`; see section 11.) Thresholds that were shipped, and the resulting sizes:

| Language | `--min-bigram` | `--min-trigram` | `--trigram-bytes` | `.kpd` size |
|---|---|---|---|---|
| en | 10 | 3 | 2,000,000 | 3.1 MB |
| sk | 30 | 5 | 2,000,000 | 5.4 MB |
| es, nl | 10 | 3 | 4,000,000 | 6.6 MB, 7.7 MB |
| de | 15 | 3 | 3,000,000 | 7.7 MB |
| hu | 10 | 3 | 4,000,000 | 10.3 MB |
| cs | 5 | 2 | 8,000,000 | 18.9 MB |

Size matters little past a point: Hungarian top-1 on held-out synthetic swipes was 79.8 % at
7.0 MB, 80.5 % at 10.3 MB and 80.7 % at 11.9 MB. Start from the es/nl/hu values.

Check the result: `python3 -I tools/dicts/unpack_swipe_dict.py app/src/main/assets/swipe/LL.kpd $W/check`
must write `LL.words.tsv`, `LL.bigrams.tsv`, `LL.trigrams.tsv` and `LL.total` without an error.
The format is documented in `native/droneswipe/FORMAT.md`.

## 6. Main dictionary and emoji dictionary

`main_LL.dict` is the AOSP binary dictionary for tap typing. Build it from `LL.combined` with the
converter that lives in the unit tests (it runs only when both variables are set):

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 17+
DRONEBOARD_COMBINED=$W/out/LL.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/main_LL.dict \
  ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool --rerun
```

If HeliBoard already ships `app/src/main/assets/dicts/main_LL.dict`, replacing it is a choice, not
a requirement: German, Spanish and Dutch kept HeliBoard's, Hungarian replaced a 427 KB list from
2016 without bigrams by 493,505 words with 331,587 bigrams in 4.5 MB (`00efdb4e`; visible keystroke
savings with the main dictionary alone 13.1 % → 23.7 %). Replace it only with a measurement like
that in the pull request.

The emoji dictionary is **not generated in this repository**. Download `emoji_LL.dict` from
[aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries), folder
`emoji_cldr_signal_dictionaries/`, and put it in `app/src/main/assets/dicts/`. Those files are
built from Unicode CLDR annotations (Unicode License v3) and Signal's emoji search data
(AGPL-3.0); check the licence stated there. A line `cldr,LL` in
`app/src/main/assets/dictionaries_in_dict_repo.txt` tells you the file exists. Existing files are
280 to 420 KB.

## 7. Neural next-word model

The model is `gpt256` from `tools/lm/models.py`: a word-level GPT-2 style decoder with 4 layers,
width 256, 4 heads, feed-forward 1024, context 32 tokens and a tied, factorized 128-dimensional
embedding (9,629,184 parameters at 50,000 words). Tokens are lowercased words, the punctuation
marks `. , ! ? : ;` and `<num>` for numbers; ids 0, 1, 2 are `<pad>`, `<unk>`, `<bos>`. It is a
word model, not SentencePiece: `prep_sp.py` and `evaluate_sp.py` are a subword experiment that
was not shipped. `tools/lm/README.md` describes every script.

### 7.1 Data

`tools/lm/fetch_norm.py` knows `sk`, `cs`, `de`, `es`, `nl` and `hu` for FineWeb-2 and those plus
`en` for Wikipedia. Add your language to both dictionaries in that file (part of the pull request):

```python
FW2 = {..., "hu": "hun_Latn", "LL": "FW2"}
WIKI_FILES = {..., "hu": 4, "LL": N}   # N = parquet files of 20231101.<lang> in wikimedia/wikipedia
```

Without the `FW2` entry the script prints `skip` for every shard and ends with `done fw2 LL 0`:
always read the last line of its output. Hungarian used 100M words of FineWeb-2 and 30M words of
Wikipedia:

```sh
$PY -I tools/lm/fetch_norm.py fw2  LL 100000000 $W/norm/LL.fw2.txt
$PY -I tools/lm/fetch_norm.py wiki LL 30000000  $W/norm/LL.wiki.txt
$PY tools/lm/prepare.py $W LL 50000 /dev/null
```

The last argument of `prepare.py` is an optional file of your own writing (one sentence per line)
that becomes separate `ptrain` / `ptest` streams for fine-tuning; `/dev/null` means none, which is
how de, es, nl and hu were built. `prepare.py` holds out 1 % of the paragraphs (by hash) as the
test split and prints one JSON line. Hungarian:
`"train_tokens_counted": 129468554, "coverage": 0.866, "train": 133184381, "test": 1329217`.
Coverage is the share of running tokens inside the vocabulary: 0.87 (hu) to 0.97 (en) across the
shipped languages. It writes `$W/vocab/LL.txt` and `$W/ids/LL.{train,test,ptrain,ptest}.u16`. Any file
matching `$W/norm/LL.*.txt` is used, so other open text can be added in the same normalized form.

### 7.2 Baseline, training, evaluation

```sh
$PY tools/lm/evaluate.py --work $W --lang LL --model kn3 --n 10000        # Kneser-Ney trigram baseline, CPU
$PY tools/lm/train.py --work $W --langs LL --model gpt256 --steps 50000 --warmup 1500 --eval_every 10000 \
  --out L-LL-gpt256
$PY tools/lm/evaluate.py --work $W --lang LL --model $W/ckpt/L-LL-gpt256 --n 10000
```

These are the settings used for de, es, nl and hu; the rest are the script's defaults: learning
rate 2e-3 with linear warm-up and cosine decay to 5 %, AdamW with weight decay 0.01, gradient
clipping at 1.0, batch 128, context 32, a quarter of the windows left-padded. The run writes
`config.json`, `tokens.txt`, `last.safetensors` and `best.safetensors` to `$W/ckpt/L-LL-gpt256/`
and logs a JSON line every 200 steps. Hungarian on an Apple M2 Max: about 40,000 tokens per
second, 89 minutes, held-out perplexity (all tokens) 122.8 at step 10,000 and 88.9 at step 50,000.
If Metal reports a watchdog error the script retries the step by itself.

What `evaluate.py` reported on the open test split (10,000 positions; KSS = estimated keystroke
savings with three suggestions):

| Language | Out of vocabulary | Top-1 | Top-3 | KSS, gpt256 | KSS, KN3 |
|---|---|---|---|---|---|
| es | 4.1 % | 23.0 % | 37.6 % | 55.6 % | not recorded |
| nl | 6.4 % | 22.2 % | 35.6 % | 53.4 % | 46.9 % |
| en | 4.2 % | 21.0 % | 33.0 % | 51.9 % | 47.5 % |
| de | 9.6 % | 17.4 % | 28.9 % | 51.1 % | not recorded |
| cs | 12.0 % | 17.9 % | 26.8 % | 46.8 % | 40.6 % |
| sk | 13.4 % | 17.8 % | 27.5 % | 46.8 % | 40.9 % |
| hu | 16.2 % | 17.1 % | 27.6 % | 42.7 % | 37.1 % |

A good model beats its own KN3 baseline by 4 to 6 points of KSS. Inflected languages score lower
because more targets are outside a 50,000-word vocabulary. If your model does not beat KN3, look
at the data (spam, wrong language, too little text) before changing hyperparameters.

### 7.3 Export and verification

```sh
$PY tools/lm/export_keyboard_lm.py $W/ckpt/L-LL-gpt256 $W/export LL
cp $W/export/LL.lmb $W/export/LL.lmv app/src/main/assets/swipe/
```

The export quantizes linear layers and the embedding table to int8 with one scale per row. Expect
`LL.lmb` of exactly 3,321,376 bytes (the same for every `gpt256` model) and `LL.lmv` of about
7.1 MB for 50,000 words. `--vocab N` keeps only the N most frequent words (English ships 30,000,
4.2 MB).

Verify that the phone's C++ runtime computes what MLX computes. This needs the host build of
`tools/swipe-replay` (section 9):

```sh
$PY tools/lm/verify_keyboard_lm.py contexts --work $W --lang LL --n 300 --out $W/export/LL.ctx.jsonl
build/swipe-replay/lm-check $W/export/LL.lmb $W/export/LL.lmv $W/export/LL.ctx.jsonl > $W/export/LL.cpp.jsonl
$PY tools/lm/verify_keyboard_lm.py compare --ckpt $W/ckpt/L-LL-gpt256 --contexts $W/export/LL.ctx.jsonl \
  --cpp $W/export/LL.cpp.jsonl
```

`compare` prints `max_abs_diff_hidden`, `min_cosine`, `mean_cosine`, `same_top1`, `same_top10_set`
and `mean_top10_overlap`. The repository records no reference values; with int8 against fp32
expect cosine and `same_top1` close to 1 and put the line you get into the pull request. A low
cosine means the export and the checkpoint do not match (wrong checkpoint directory, edited
`tokens.txt`).

At runtime `NeuralLanguageModel.kt` loads `swipe/LL.lmb` + `swipe/LL.lmv` for every enabled
language that has both, gives the model up to 31 tokens before the cursor, mixes it with the
n-gram model (weight 0.7) and falls back to the n-grams when an answer takes longer than 60 ms.

## 8. Wiring into the app

Checklist from the commits in the table at the top. "Automatic" means nothing has to be edited.

| Where | What to do | Example |
|---|---|---|
| `app/src/main/assets/swipe/LL.{kpd,lmb,lmv}` | add the files; discovery and badges are automatic | `53ae4c4f` |
| `app/src/main/assets/dicts/main_LL.dict`, `emoji_LL.dict` | add the files (file name uses the locale: `main_en-US.dict`, `main_sk-x-nd.dict`) | `53ae4c4f`, `00efdb4e` |
| `SwipeGestureDecoder.kt`, `LANGUAGE_PARAMS` | add `"LL" to floatArrayOf(gamma, beta, lambda, capital-letter penalty, bigram weight)`. Start with `0.5f, 0.9866f, 0.025f, -2f, 0.1f`; use `0f` for the capital-letter penalty if nouns are capitalized as in German. Without an entry the English values are used | `53ae4c4f` |
| `app/src/main/res/xml/method.xml` | only if no `<subtype>` for the language exists: copy a neighbouring block, set `imeSubtypeLocale`, `languageTag`, the layout in `imeSubtypeExtraValue`, and a new unused `subtypeId` that never changes afterwards | `714b02bc` |
| `app/src/main/res/xml/spellchecker.xml` | only if missing: a `<subtype>` with `android:subtypeLocale="LL"` | already there for hu |
| `app/src/main/assets/locale_key_texts/LL.txt` | long-press letters (`[popup_keys]`), extra keys, TLDs. Create it if missing; put the letters in Gboard's order | `23961b4f` (de, es, nl) |
| `app/src/main/assets/layouts/main/` | only for a layout that does not exist yet; formats are in `layouts.md` | `hungarian_extended_qwertz.txt` |
| `Defaults.kt`, `PREF_ADDITIONAL_SUBTYPES` | only to offer a second layout by default (Hungarian: QWERTY next to QWERTZ; German: `qwertz+`) | `23961b4f` |
| `tools/dicts/build_dicts.py`, `tools/lm/fetch_norm.py` | the `LANG`, `FW2` and `WIKI_FILES` entries from sections 4 and 7 | `53ae4c4f` |
| `tools/swipe-replay/synth.py`, `ROWS` | only if the layout has letter keys beyond a to z (`qwertz-de`, `qwerty-es`) | `23961b4f` |
| `native/droneswipe/kp_dictionary.cpp` | only for the cases of section 3; then rebuild with `tools/build-swipe-lib.sh` | `23961b4f` |
| `AppLanguage.kt` (`LANGUAGES`, `displayNameRes`), `res/values/strings_app_language.xml`, `res/xml/locales_config.xml`, `res/values-LL/strings*.xml` | optional: Droneboard's own settings translated into the language. A keyboard language does not need it | `53ae4c4f` |
| `README.md`, `docs/dictionaries.md`, `tools/dicts/README.md`, `tools/swipe-replay/README.md`, `zapstore.yaml` | language lists, the exact build commands you ran, the numbers you measured, a tag | `53ae4c4f` |
| `website/build.py` | the language list in the "multilingual swipe" text of all five site languages, then `python3 website/build.py` and commit the regenerated pages | `53ae4c4f` |
| `website/languages.py` | the language in the right row of the languages panel (swipe and neural, or dictionary only), in all five site languages | (new) |

No unit test enumerates the languages, so no test list needs a new entry. Version numbers and
`docs/RELEASE_NOTES_*.md` belong to the maintainer's release, not to your pull request.

## 9. Testing and evaluation

**Unit tests** (must stay green):

```sh
./gradlew :app:testDebugUnitTest
```

**Swipe replay on the desktop.** `tools/swipe-replay` runs the keyboard's exact decoder. Building it
needs a checkout of FUTO's swipe-library at the revision in `tools/build-swipe-lib.sh`
(`SWIPE_LIBRARY_REV`) with ExecuTorch built for the host (`make host` in that checkout); this is the
heaviest setup step in the guide.

```sh
cmake -S tools/swipe-replay -B build/swipe-replay -DSWIPE_LIBRARY_DIR=<swipe-library>
cmake --build build/swipe-replay -j
A=app/src/main/assets/swipe
build/swipe-replay/swipe-replay --encoder $A/honorable_sturgeon/model_int8.pte \
  --dict LL:$A/LL.kpd --dict en:$A/en.kpd \
  --params LL=0.5,0.9866,0.025,-2,0.1,0 --params en=0.5,0.9866,0.025,-2,0.1,0 \
  --neural LL:$A --neural en:$A --beam 200 --out $W/pred-LL.jsonl $W/eval-LL.jsonl
```

It prints top-1 and top-3 and splits the errors into diacritics, wrong language, not in beam and
other. Run it with and without `--neural` (or with `--no-fuse`), and once more on words with
accents only (`synth.py --require "<accented letters>"`) and on sloppy swipes (`--sloppy 1`). Tune
`LANGUAGE_PARAMS` by changing `--params`; for Hungarian gamma 0.4 and 0.6 and bigram weights 0.05
to 0.2 were all equal or worse than the default. The `--params` and the order of `--dict` must
match what the app does.

Reference results from `tools/swipe-replay/README.md`: precise synthetic swipes with held-out
sentence context, the language plus English, beam 200, top-1 / top-3. The first three rows are one
evaluation set per language, scored without and with the neural model; the last three are the
earlier per-language sets, measured before the neural reranking existed:

| Language | n-grams only | with the neural model |
|---|---|---|
| sk | 86.8 / 94.6 | 90.0 / 95.1 |
| de | 83.5 / 91.4 | 88.3 / 92.5 |
| hu | 79.1 / 87.8 | 83.4 / 89.5 |
| hu, 1500 swipes on QWERTZ | 80.5 / 89.7 (words with accents 79.7 / 86.7) | not measured |
| es, QWERTY with ñ | 86.6 / 96.3 | not measured |
| nl | 84.6 / 94.3 | not measured |

About 80 % top-1 without the neural model is the floor reached so far. If "not in beam" dominates
the errors, the word list lacks the targets (compounds, long inflected forms); if "diacritics"
dominates, look at the word frequencies and n-grams.

**Next-word prediction through the app's code path:** `tools/lm/make_lm_eval_set.py` writes
held-out items, `build/swipe-replay/lm-eval` scores the `.kpd` and the neural model together:

```sh
$PY -I tools/lm/make_lm_eval_set.py --work $W --langs LL --split test --n 1000 --out $W/items-LL.jsonl
build/swipe-replay/lm-eval --dict LL:$A/LL.kpd --dict en:$A/en.kpd --neural LL:$A --neural en:$A \
  --mode interp --tokens $W/items-LL.jsonl
```

The model's own top 3 reached 54 to 57 % KSS for sk, en and cs. `TapPredictionEval` (see
`tools/swipe-replay/README.md`) measures what the suggestion strip really shows; it needs the AOSP
native library built for the host and is optional for a new language.

**On a phone** (`./gradlew assembleDebug`, install the APK from `app/build/outputs/apk/debug/`):

- The language is in Settings → Languages & Layouts with the badges "neural" and "swipe".
- Swipe 20 common words and 10 words with accents, alone and together with English enabled.
- Type a sentence by tapping: next-word suggestions appear in the language and completion ignores
  accents.
- Long-press every vowel and check the accented letters and their order.
- Search an emoji by a word of the language.
- Restart the phone and type on the lock screen: the keyboard must still work.

## 10. Size budget, licences, pull request

**Size.** There is no hard limit, but every language is in every APK. Hungarian added 10.3 MB
(`.kpd`) + 3.3 MB (`.lmb`) + 7.1 MB (`.lmv`) + 4.5 MB (`main_hu.dict`, replacing 0.4 MB) + 0.3 MB
(emoji), about 25 MB before APK compression. Stay in that range and justify anything larger with a
measurement.

**Licences to record in the pull request and in `tools/dicts/README.md`:**

| Source | Licence | Shipped? |
|---|---|---|
| [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) (FineWeb sample-10BT for English) | ODC-By | derived word lists, counts and model weights |
| [Wikipedia](https://huggingface.co/datasets/wikimedia/wikipedia), dump 20231101 | CC BY-SA | derived counts and model weights |
| Hunspell dictionary from LibreOffice/dictionaries | varies per language, state it | no, filter only |
| Emoji dictionary from aosp-dictionaries | CLDR: Unicode License v3; Signal data: AGPL-3.0 | yes |
| FUTO Swipe encoder `swipe/honorable_sturgeon` | FUTO Model Weights License (`LICENSE.md` next to it) | yes, unchanged, language independent |

Any other corpus must allow redistribution of derived data, and you must name it.

**The pull request contains:** the asset files; the script edits (`build_dicts.py`,
`fetch_norm.py`, optionally `synth.py`); the app wiring of section 8; the documentation updates;
and in the description: corpus and Hunspell sources with licences, the exact commands, the
`prepare.py` statistics line, the KN3 and gpt256 `evaluate.py` lines, the `compare` line, the
swipe-replay table, asset sizes, the phone model you tested on, and what you did not test. Do not
commit corpora, checkpoints, `$W`, a blocklist or any text of your own. The repository is public.

## 11. Known gaps and manual steps

- **The scripts that chained the steps** (queueing, per-language preparation) are not in the
  tree, because they are tied to one machine. The commands in section 7 are the same sequence by hand.
- **`tools/lm/make_nd.py`** (the neural model for a language typed without diacritics, as `sk_nd`
  and `cs_nd`) and **`tools/lm/swipe_inputs.py`** (how the swipe inputs for German, Spanish and
  Dutch were built, including ß as "ss") are in the tree as they were used, but have not been run
  again from `main`. Read them before relying on them.
- **The shipped sk, en and cs models were fine-tuned** on the author's own writing mixed with
  open data. `train.py` on `main` can fine-tune (`--split ptrain --init <ckpt>/best.safetensors`)
  but lacks the open-data mixing that was used, and that text is not public. A new language does
  not need this step.
- **Held-out text for swipe evaluation.** The published Hungarian, German, Spanish and Dutch
  numbers used the FineWeb-2 test split. `fetch_corpus.py` only fetches the first train shard, so
  this guide uses `count_ngrams.py --exclude` instead; the word list itself has still seen those
  sentences, so the numbers are slightly optimistic compared with the published ones.
- **No Hunspell dictionary, no pipeline.** `build_dicts.py` has no switch to run without the
  filter. For a language without one you have to add that switch and check the list by hand.
- **The emoji dictionary is downloaded**, not built. If aosp-dictionaries has none for the
  language, ship without it.
- **The swipe encoder cannot be retrained here.** Its weights come from FUTO under a non-open
  licence and are shared by all languages; this guide never touches them.
- **No dependency file.** The Python packages are listed in section 2 without pinned versions.
- **The export check has no recorded thresholds**, and `lm-check` needs the swipe-library host
  build even though the neural runtime itself does not use ExecuTorch.
- **Stale comments:** the header of `tools/swipe-replay/lm_eval.cpp` still names `<lang>.lm.pte`;
  the code loads `<lang>.lmb`. `export_et.py` is the old ExecuTorch export and is not used by the
  app.
- **Layouts, long-press order and swipe parameters are judgment calls** made by hand, by
  comparing with Gboard and with the replay results.
- **Training off Apple Silicon** is not supported by the scripts (section 2).
