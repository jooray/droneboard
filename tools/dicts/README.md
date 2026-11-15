# Dictionaries

Droneboard builds its word lists from open data, so anyone can rebuild them:

- **Text:** FineWeb-2 for Slovak and Czech, and the FineWeb sample-10BT for English. Both are under ODC-By. Czech also uses Czech Wikipedia (CC BY-SA).
- **Spelling filter:** Hunspell dictionaries from LibreOffice. They only filter words and are not shipped.

Never use the Slovak or Czech national corpora (SNK, ČNK). Their licenses do not allow redistribution.

```sh
uv venv .venv && uv pip install --python .venv/bin/python fsspec aiohttp requests pyarrow
.venv/bin/python -I tools/dicts/fetch_corpus.py slk_Latn 280 work/corpus-sk   # about 110M tokens
.venv/bin/python -I tools/dicts/fetch_corpus.py eng 60 work/corpus-en
# Hunspell: sk_SK.{dic,aff} and en_US.{dic,aff} from github.com/LibreOffice/dictionaries
.venv/bin/python -I tools/dicts/build_dicts.py sk work/out work/hunspell/sk_SK work/corpus-sk/corpus.txt
.venv/bin/python -I tools/dicts/build_dicts.py en work/out work/hunspell/en_US work/corpus-en/corpus.txt
# vocabulary the corpora miss, from a personal writing corpus (one sentence per line) and the
# allowlist; also writes vocab/<lang>.vocab.combined for tap typing and the spell checker
.venv/bin/python -I tools/dicts/personal_vocab.py sk work/out/sk.words.tsv personal/sk.txt work/hunspell/sk_SK \
  tools/dicts/vocab/tech-allowlist.txt work/out/en.words.tsv work/vocab --weight 8
.venv/bin/python -I tools/dicts/personal_vocab.py en work/out/en.words.tsv personal/en.txt work/hunspell/en_US \
  tools/dicts/vocab/tech-allowlist.txt work/out/sk.words.tsv work/vocab --weight 2 --min-count 3
# unigram, bigram and trigram counts on the corpora plus the personal text (weighted); the swipe
# evaluation sentences are excluded so that tools/swipe-replay measures held-out text
.venv/bin/python -I tools/dicts/count_ngrams.py sk work/vocab/sk.words.tsv work/ngrams --corpus work/corpus-sk/corpus.txt \
  --personal work/vocab/sk.personal.txt --personal-weight 8 --exclude eval-sk.jsonl --min-trigram 2
.venv/bin/python -I tools/dicts/count_ngrams.py en work/vocab/en.words.tsv work/ngrams --corpus work/corpus-en/corpus.txt \
  --personal work/vocab/en.personal.txt --personal-weight 2 --exclude eval-en.jsonl --min-trigram 2
# pack for the app; bigrams and trigrams below the thresholds are dropped, trigrams to about 2 MB
python3 -I tools/dicts/pack_swipe_dict.py work/ngrams/sk.words.tsv work/ngrams/sk.bigrams.tsv app/src/main/assets/swipe/sk.kpd \
  --min-bigram=30 --trigrams=work/ngrams/sk.trigrams.tsv --min-trigram=5 --trigram-bytes=2000000
python3 -I tools/dicts/pack_swipe_dict.py work/ngrams/en.words.tsv work/ngrams/en.bigrams.tsv app/src/main/assets/swipe/en.kpd \
  --min-bigram=10 --trigrams=work/ngrams/en.trigrams.tsv --min-trigram=3 --trigram-bytes=2000000
for l in sk en; do
  DRONEBOARD_COMBINED=work/vocab/$l.vocab.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/vocab_$l.dict \
    ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool --rerun
done
DRONEBOARD_COMBINED=work/out/sk.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/main_sk.dict \
  ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool
```

### Czech

The Czech FineWeb-2 shard is full of porn and dating spam ("sex" was the 27th most frequent word), so
spam documents are dropped first. Czech Wikipedia (CC BY-SA, normalized and lowercased) adds rarer
words; `clean_wiki.py` turns `<num>` into a sentence break and drops table markup. Czech is heavily
inflected, so its list keeps up to 500,000 forms.

```sh
.venv/bin/python -I tools/dicts/fetch_corpus.py ces_Latn 280 work/corpus-cs
python3 -I tools/dicts/filter_spam.py cs work/corpus-cs/clean.txt work/corpus-cs/corpus.txt
python3 -I tools/dicts/clean_wiki.py work/cs.wiki.txt work/cs.wiki.clean.txt
# Hunspell: cs_CZ.{dic,aff} from github.com/LibreOffice/dictionaries
python3 -I tools/dicts/build_dicts.py cs work/out work/hunspell/cs_CZ work/corpus-cs/clean.txt work/cs.wiki.clean.txt
.venv/bin/python -I tools/dicts/personal_vocab.py cs work/out/cs.words.tsv personal/cs.txt work/hunspell/cs_CZ \
  tools/dicts/vocab/tech-allowlist.txt work/out/en.words.tsv work/vocab --weight 8
.venv/bin/python -I tools/dicts/count_ngrams.py cs work/vocab/cs.words.tsv work/ngrams --corpus work/corpus-cs/clean.txt \
  --corpus work/cs.wiki.clean.txt --personal work/vocab/cs.personal.txt --personal-weight 8 --exclude eval-cs.jsonl --min-trigram 2
python3 -I tools/dicts/pack_swipe_dict.py work/ngrams/cs.words.tsv work/ngrams/cs.bigrams.tsv app/src/main/assets/swipe/cs.kpd \
  --min-bigram=5 --trigrams=work/ngrams/cs.trigrams.tsv --min-trigram=2 --trigram-bytes=8000000
DRONEBOARD_COMBINED=work/vocab/cs.vocab.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/vocab_cs.dict \
  ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool --rerun
DRONEBOARD_COMBINED=work/out/cs.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/main_cs.dict \
  ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool --rerun
```

The Czech `.kpd` keeps more n-grams than the Slovak one (19 MB): on synthetic Czech swipes with
sentence context, top-1 goes from 85.5 % (Slovak thresholds, 5.8 MB) to 87.6 %.

### German, Spanish and Dutch

The same pipeline on FineWeb-2 `deu_Latn`, `spa_Latn` and `nld_Latn` (about 100M words each, no
personal text). German `ß` has no NFD decomposition, so `keypath()` maps it to `ss` ("Straße" is
swiped s-t-r-a-s-e); Spanish `ñ` becomes `n` through NFD. HeliBoard's `main_de.dict`, `main_es.dict`
and `main_nl.dict` stay the typing dictionaries; the emoji dictionaries are aosp-dictionaries'
`emoji_de/es/nl.dict`.

```sh
for l in de:deu_Latn:de_DE_frami es:spa_Latn:es_ES nl:nld_Latn:nl_NL; do
  IFS=: read L F H <<< "$l"
  .venv/bin/python -I tools/dicts/fetch_corpus.py $F 280 work/corpus-$L
  python3 -I tools/dicts/build_dicts.py $L work/out work/hunspell/$H work/corpus-$L/corpus.txt
  .venv/bin/python -I tools/dicts/count_ngrams.py $L work/out/$L.words.tsv work/ngrams --corpus work/corpus-$L/corpus.txt --min-trigram 2
done
python3 -I tools/dicts/pack_swipe_dict.py work/ngrams/de.words.tsv work/ngrams/de.bigrams.tsv app/src/main/assets/swipe/de.kpd \
  --min-bigram=15 --trigrams=work/ngrams/de.trigrams.tsv --min-trigram=3 --trigram-bytes=3000000
for L in es nl; do
  python3 -I tools/dicts/pack_swipe_dict.py work/ngrams/$L.words.tsv work/ngrams/$L.bigrams.tsv app/src/main/assets/swipe/$L.kpd \
    --min-bigram=10 --trigrams=work/ngrams/$L.trigrams.tsv --min-trigram=3 --trigram-bytes=4000000
done
```

That gives 7.7 MB (de), 6.6 MB (es) and 7.7 MB (nl). Against the Slovak thresholds (6.1, 3.6 and
4.6 MB) the larger n-gram sets add 0.3–0.6 pp top-1 on synthetic swipes with held-out sentence
context (FineWeb-2 test split).

The script produces three outputs:

- **`<lang>.words.tsv`** feeds the swipe engine. Its columns are `surface`, `keypath`, `freq`, `count`:
  - `keypath` is the word reduced to base letters a–z;
  - `freq` is on HeliBoard's 0–255 scale, using one rank curve for all languages;
  - `count` is the corpus count of the lowercase form.
- **`<lang>.bigrams.tsv`** holds previous-word counts for swipe rescoring.

  `pack_swipe_dict.py` turns these two TSVs into the `<lang>.kpd` file the app ships. The format is described in [native/droneswipe/FORMAT.md](../../native/droneswipe/FORMAT.md).
- **`<lang>.combined`** is a HeliBoard word list with bigrams. It becomes `main_<lang>.dict` for tap typing and suggestions.

The Slovak list is cleaned in four ways:

- All-caps tokens are dropped.
- Words with letters that are not in Slovak are dropped, which removes Czech forms such as "věci".
- A capitalized form is kept only when it clearly dominates its lowercase form.
- The one-letter words a, v, s, z, o, k, u, i are always present.

## Personal and technical vocabulary

`personal_vocab.py` adds the words of a writing corpus that the word list lacks. The shipped lists
use the public writing of Droneboard's author (blogs, books, Nostr posts) plus
`vocab/tech-allowlist.txt` (Bitcoin, Nostr, privacy and cypherpunk terms):

- A word is added when it occurs at least twice (three times for English), or Hunspell knows it, or it is on the allowlist.
- Words that are mostly capitalized inside sentences (names), only seen in capitals (abbreviations), common words of the other language, letter salad (hashes, ids) and one-edit typos of frequent words are left out.
- The new words go into the swipe lists and into a small extra dictionary, `assets/dicts/vocab_<lang>.dict`. HeliBoard loads every dictionary type of a locale next to the main one, so tap typing and the spell checker know them without rebuilding the main dictionary.
- Czech text only ever feeds Czech lists.

## Trigrams

`count_ngrams.py` counts unigrams, bigrams and trigrams of the vocabulary with an out-of-vocabulary
token breaking the chain. The personal corpus is added with a moderate weight (about 5 % of the
counts). Trigrams are stored in the `TRIG` section of the `.kpd` files and used by swipe ranking
and by tap-typing prediction (see `native/droneswipe/FORMAT.md`).

## Apostrophes and hyphens

Words keep their inner apostrophes and hyphens ("don't", "I'll", "e-mail", "je-li"), and their
key paths skip them the way they skip diacritics: swiping i-l-l reaches both "ill" and "I'll", and
the ranking picks between words of the same key path by the words before the cursor (see
`same_path` in `native/droneswipe/kp_dictionary.hpp`). `build_dicts.py`, `count_ngrams.py` and
`personal_vocab.py` count a hyphenated word as one token when it is in the list and as its parts
otherwise. Apostrophes belong to English only; in Slovak and Czech text they mark English words.

The dictionaries shipped before 0.1.12 were built without hyphenated words. `add_joined_words.py`
adds them to a `.kpd` from any corpus without rebuilding it, by way of `unpack_swipe_dict.py`
(which turns a `.kpd` back into the TSVs that `pack_swipe_dict.py` reads, byte for byte):

```sh
B=internal/research/droneboard/bench-swipe/data/corpus   # FineWeb-2 samples (ODC-By)
P=personal                                               # the personal writing corpus
python3 -I tools/dicts/add_joined_words.py sk app/src/main/assets/swipe/sk.kpd sk.kpd $B/slk_Latn/B.txt $P/sk.final.txt
python3 -I tools/dicts/add_joined_words.py cs app/src/main/assets/swipe/cs.kpd cs.kpd $B/ces_Latn/B.txt $P/cs.final.txt
python3 -I tools/dicts/add_joined_words.py en app/src/main/assets/swipe/en.kpd en.kpd $P/en.final.txt --min-count=5
```

This added 361 Slovak words (e-mail, on-line, Wi-Fi, make-up, viac-menej, sem-tam), 408 Czech ones
(e-mail, je-li, chcete-li, Wi-Fi, know-how) and 132 English ones (open-source, so-called,
well-known, peer-to-peer). They have no n-grams yet; the next full rebuild counts them.

## Slovak and Czech without diacritics

The languages "Slovak (no diacritics)" and "Czech (no diacritics)" have the locales `sk-x-nd` and
`cs-x-nd`. HeliBoard's dictionary lookup treats them as separate languages (`main_sk-x-nd.dict`,
never `main_sk.dict`; only the emoji dictionary is shared), and Droneboard's engines load the assets
named after `LocaleUtils.engineKey`: `swipe/sk_nd.kpd`, `swipe/sk_nd.lmb` + `.lmv`.

`strip_diacritics.py` derives them from the shipped dictionaries. It drops the combining marks of
every word and merges the words, bigrams and trigrams that become equal, with their counts summed
("súd" and "sud" become "sud"). It also writes the `.combined` list for the typing dictionary:

```sh
python3 -I tools/dicts/strip_diacritics.py sk app/src/main/assets/swipe/sk.kpd work/nd "--description=Slovenčina (bez diakritiky)"
python3 -I tools/dicts/strip_diacritics.py cs app/src/main/assets/swipe/cs.kpd work/nd "--description=Čeština (bez diakritiky)"
python3 -I tools/dicts/pack_swipe_dict.py work/nd/sk_nd.words.tsv work/nd/sk_nd.bigrams.tsv app/src/main/assets/swipe/sk_nd.kpd \
  --min-bigram=0 --trigrams=work/nd/sk_nd.trigrams.tsv --min-trigram=0 --trigram-bytes=2000000 --total=$(cat work/nd/sk_nd.total)
python3 -I tools/dicts/pack_swipe_dict.py work/nd/cs_nd.words.tsv work/nd/cs_nd.bigrams.tsv app/src/main/assets/swipe/cs_nd.kpd \
  --min-bigram=6 --trigrams=work/nd/cs_nd.trigrams.tsv --min-trigram=2 --trigram-bytes=500000 --total=$(cat work/nd/cs_nd.total)
for l in sk cs; do
  DRONEBOARD_COMBINED=work/nd/${l}_nd.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/main_$l-x-nd.dict \
    ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool --rerun
done
```

The personal vocabulary is already part of the `.kpd` words, so there is no `vocab_<lang>-x-nd.dict`.
The neural models `sk_nd` and `cs_nd` are trained by tools/lm (`make_nd.py`) on the stripped token
streams.

`cs_nd.kpd` keeps nearly all bigrams but few trigrams: 9.4 MB instead of 18 MB with the same
accuracy on held-out stripped Czech swipes (89.6 / 96.2 against 89.6 / 96.3 top-1 / top-3).

Building the no-diacritics lists from stripped text instead (FineWeb-2 and the personal text with
the marks dropped, then `build_dicts.py` and `count_ngrams.py` on that) was tried for 0.1.13 and
not kept: on held-out stripped swipes it was no better (sk_nd 86.5 / 94.4 against 86.6 / 94.8; cs_nd
88.5 / 95.7 against 89.6 / 96.2 at the same size), and its word list lacks the personal and
hyphenated vocabulary that the derived lists inherit (nostr, lightning, e-mail, wi-fi).
