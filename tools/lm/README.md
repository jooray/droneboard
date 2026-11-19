# tools/lm: the on-device next-word model

Scripts that train Droneboard's small neural next-word model for one language and export it to the
two files the keyboard loads, `app/src/main/assets/swipe/<lang>.lmb` and `<lang>.lmv`. The runtime
is `native/droneswipe/neural_lm.cpp`; the app side is
`app/src/main/java/helium314/keyboard/latin/lm/NeuralLanguageModel.kt`.

The full procedure for a new language, with the settings that were used and the numbers the
shipped languages reached, is in [docs/adding-a-language.md](../../docs/adding-a-language.md)
(section 7). This file only says what each script is and in which order they run.

## Setup

Training and the neural evaluation use [MLX](https://github.com/ml-explore/mlx), so they need an
Apple Silicon Mac. Data preparation, the Kneser-Ney baseline and the export need only numpy.
There is no requirements file:

```sh
W=~/droneboard-work && mkdir -p $W/norm        # work directory, outside the repository
uv venv $W/.venv
uv pip install --python $W/.venv/bin/python fsspec aiohttp requests pyarrow numpy mlx safetensors
PY=$W/.venv/bin/python
```

All scripts share one work directory:

| Path | Content | Written by |
|---|---|---|
| `$W/norm/<lang>.*.txt` | normalized text, one paragraph per line | `fetch_norm.py` |
| `$W/vocab/<lang>.txt`, `.counts.npy`, `.stats.json` | vocabulary (id = line number), counts, statistics | `prepare.py` |
| `$W/ids/<lang>.{train,test,ptrain,ptest}.u16` | token ids, `uint16` | `prepare.py` |
| `$W/ckpt/<name>/` | `config.json`, `tokens.txt`, `best.safetensors`, `last.safetensors` | `train.py` |

## The pipeline, in order

```sh
# 1. text: FineWeb-2 and Wikipedia, streamed and normalized (add a new language to FW2 and WIKI_FILES first)
$PY -I tools/lm/fetch_norm.py fw2  hu 100000000 $W/norm/hu.fw2.txt
$PY -I tools/lm/fetch_norm.py wiki hu 30000000  $W/norm/hu.wiki.txt
# 2. vocabulary of 50,000 words and id streams; /dev/null = no text of your own
$PY tools/lm/prepare.py $W hu 50000 /dev/null
# 3. baseline: interpolated Kneser-Ney trigrams on the same streams (CPU)
$PY tools/lm/evaluate.py --work $W --lang hu --model kn3 --n 10000
# 4. train (89 minutes on an Apple M2 Max)
$PY tools/lm/train.py --work $W --langs hu --model gpt256 --steps 50000 --warmup 1500 --eval_every 10000 --out L-hu-gpt256
# 5. keyboard metrics of the trained model
$PY tools/lm/evaluate.py --work $W --lang hu --model $W/ckpt/L-hu-gpt256 --n 10000
# 6. export for the phone
$PY tools/lm/export_keyboard_lm.py $W/ckpt/L-hu-gpt256 $W/export hu
# 7. check the C++ runtime against MLX (lm-check is built by tools/swipe-replay)
$PY tools/lm/verify_keyboard_lm.py contexts --work $W --lang hu --n 300 --out $W/export/hu.ctx.jsonl
build/swipe-replay/lm-check $W/export/hu.lmb $W/export/hu.lmv $W/export/hu.ctx.jsonl > $W/export/hu.cpp.jsonl
$PY tools/lm/verify_keyboard_lm.py compare --ckpt $W/ckpt/L-hu-gpt256 --contexts $W/export/hu.ctx.jsonl --cpp $W/export/hu.cpp.jsonl
```

## Scripts

| Script | What it does |
|---|---|
| `fetch_norm.py <source> <lang> <max_words> <out.txt>` | Streams FineWeb-2 (`fw2`), FineWeb sample-10BT (`fw`, English) or Wikipedia (`wiki`) from Hugging Face and writes lowercased, tokenized paragraphs: words, `. , ! ? : ;` and `<num>`. On `main` it knows `sk`, `cs` (and `en` for `fw` and `wiki`); other languages need an entry in `FW2` and `WIKI_FILES`. An unknown language ends with `done ... 0` instead of an error |
| `prepare.py <workdir> <lang> <vocab_size> <own_text.txt>` | Builds the word vocabulary (ids 0 `<pad>`, 1 `<unk>`, 2 `<bos>`; words on the local blocklist of `tools/dicts/blocklist.py` are left out) and the `uint16` id streams. 1 % of the paragraphs are held out as `test`. The last argument is optional text of your own for the `ptrain` / `ptest` streams; pass `/dev/null` for none. The vocabulary cannot exceed 65,535 words |
| `models.py` | The MLX models. `gpt256` is the one that ships: a GPT-2 style decoder with 4 layers, width 256, 4 heads, context 32 and a tied, factorized 128-dimensional embedding (9.6M parameters at 50,000 words). `gpt384`, `gpt512`, `cifg`, `cifg2` and `gru` are comparison models; only the `gpt*` ones can be exported |
| `train.py` | Trains one model. Required: `--work --langs --model --steps --out`. Defaults: `--lr 2e-3 --batch 128 --ctx 32 --warmup 500 --eval_every 2000 --split train`. `--split ptrain --init <ckpt>/best.safetensors` fine-tunes on your own text. Several languages in `--langs` train one multilingual model with a union vocabulary (an experiment; the app ships one model per language) |
| `kn.py` | Interpolated Kneser-Ney bigram and trigram model, used by `evaluate.py --model kn2|kn3` |
| `evaluate.py --work --lang --model <ckpt_dir>|kn2|kn3 [--split test|ptest] [--n 20000]` | Keyboard metrics on held-out text: out-of-vocabulary rate, next-word top-1 and top-3, completion after one and two letters, perplexity, estimated keystroke savings |
| `export_keyboard_lm.py <ckpt_dir> <out_dir> <lang> [--vocab N]` | Writes `<lang>.lmb` (the transformer body, int8 linear layers) and `<lang>.lmv` (vocabulary and the int8 embedding table). The byte layout is in the script's docstring. `--vocab N` keeps the N most frequent words |
| `verify_keyboard_lm.py contexts|compare` | Samples held-out contexts, then compares the hidden state and the top 10 next words of the C++ runtime (`lm-check` output) with the MLX model |
| `make_lm_eval_set.py --work --langs --split --n --out` | Held-out items (target word, context words, tokens before it) for `tools/swipe-replay` `lm-eval` and `synth.py --items`, and for the app's `TapPredictionEval` |
| `mix_eval.py` | Code-switching evaluation: per-language models combined by a language posterior against one multilingual model |
| `prep_sp.py`, `evaluate_sp.py` | The SentencePiece (subword) variant of `prepare.py` and `evaluate.py`, from the word-versus-subword comparison. Not used for the shipped models; needs `sentencepiece`. Train it with `train.py --suffix .sp` |
| `export_et.py` | The earlier export to ExecuTorch (`.pte`). Not used by the app any more, which runs its own C++ runtime; needs `torch` and `executorch` |

## What good looks like

`evaluate.py` on the open test split, `gpt256`, 50,000 words (English 30,000 in the app): estimated
keystroke savings from 42.7 % (Hungarian) to 55.6 % (Spanish), each 4 to 6 points above the
Kneser-Ney trigram baseline of the same language. The exported `.lmb` is 3,321,376 bytes for every
language and the `.lmv` about 7.1 MB at 50,000 words.

## Not in this directory

- The shell scripts that queued the training runs are tied to one machine and are not here.
  The commands above are the same steps run by hand.
- `make_nd.py` (model for a language typed without diacritics: `sk_nd`, `cs_nd`) and
  `swipe_inputs.py` (swipe inputs for German, Spanish and Dutch) are here as they were used, but
  have not been run again from `main`.
- The Slovak, English and Czech models in the app were additionally fine-tuned on the author's own
  writing, which is not public. A new language is trained on open data only.
