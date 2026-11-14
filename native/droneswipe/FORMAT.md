# Swipe dictionary format (`.kpd`)

One `.kpd` file per language holds the swipe word list, word frequencies and the previous-word
statistics. `tools/dicts/pack_swipe_dict.py` writes it from the TSVs of `tools/dicts/build_dicts.py`.
`KpDictionary::load_kpd` (`kp_dictionary.cpp`) reads it through `mmap`, builds the key-path trie in
memory and keeps the n-gram data as compact arrays.

All integers are little endian. A *varint* is unsigned LEB128. A *front-coded string* is
`varint shared, varint suffix_len, suffix bytes`: the first `shared` bytes are copied from the
previous string of the same list, and a UTF-8 sequence is never split.

## Header (version 2)

| Field | Type | Meaning |
|---|---|---|
| magic | 4 bytes | `KPD1` |
| version | u32 | `2`. A reader rejects versions it does not know |
| n_lower | u32 | lowercase words (ids `0 … n_lower-1`, in `LOWR` order) |
| n_variants | u32 | surface variants (`ľahko`, `Ľahko`, …) |
| n_bigram_prevs | u32 | previous words that have bigrams |
| n_bigrams | u32 | bigram pairs |
| total | f64 | sum of the lowercase word counts (the unigram denominator) |
| n_sections | u32 | entries in the section directory |
| directory | n_sections × (4-byte tag, u32 offset, u32 length) | offsets from the start of the file |

Readers skip sections whose tag they do not know. New optional data is added as a new section
with the same version. Only an incompatible change to an existing section bumps the version.

## Sections

| Tag | Required | Content |
|---|---|---|
| `CMAP` | yes | `u32 n`, then `n × (u32 code point, u8 base letter)`: letters other than a–z that map to a–z in key paths (`ľ` → `l`) |
| `LOWR` | yes | `n_lower` front-coded lowercase words, sorted by UTF-8 bytes. The position is the word id |
| `LOGC` | yes | `n_lower × u16`: `round(ln(count) × 2048)` |
| `WREC` | yes | one record per lowercase word, in id order (below) |
| `FREQ` | yes | `n_variants × u8`: frequency on the 0–255 log scale, in record order |
| `BIGR` | no | previous-word statistics (below) |
| `TRIG` | no | trigrams (below) |

### Word record (`WREC`)

`varint spec`, then the optional parts in this order:

- bit 0: the lowercase word itself is a variant;
- bit 1: the word with an upper-case first letter is a variant;
- bit 2: an explicit key path follows (`varint len, bytes`). Otherwise the key path is derived: a–z are kept, `CMAP` letters are replaced by their base letter, everything else (apostrophes, hyphens) is dropped;
- bits 3 and up: the number of explicit surface variants that follow, each `varint len, bytes` (e.g. `iPhone`, `NATO`).

The variants' frequencies come from `FREQ` in this order: lowercase, capitalized, explicit ones.

### Bigrams (`BIGR`)

`n_bigram_prevs` groups sorted by previous-word id:
`varint prev_id_delta, varint n, n × (varint word_id_delta, u8 round(ln(count) × 16))`.
Word ids within a group are sorted, and deltas restart at 0 in every group. The loader turns this
into a CSR array (`big_start_`, `big_word_`, `big_logc_`), and lookups use binary search.

### Trigrams (`TRIG`)

`u32 n_contexts, u32 n_trigrams`, then `n_contexts` groups sorted by `(w1, w2)` (the two words
before the cursor, oldest first):
`varint w1_delta, varint w2, u8 round(ln(count(w1 w2)) × 16), varint n, n × (varint w3_delta, u8 round(ln(count) × 16))`.
`w2` is the delta to the previous group's `w2` when `w1_delta` is 0, otherwise absolute. Word ids
within a group are sorted, and `w3` deltas restart at 0 in every group. The context count is the
full bigram count of `w1 w2` before pruning, so the trigram estimate stays a proper probability.

Scoring (`KpDictionary::logp_ctx`): the bigram estimate `P2(w | w2)` is Dirichlet-smoothed towards
the unigram (K = 50), and the trigram estimate towards `P2`:
`P3 = (count(w1 w2 w) + k3 × P2) / (count(w1 w2) + k3)` with `k3 = 10`. A context that is not in
the section backs off to `P2`; an unknown previous word to the unigram. Swipe ranking adds
`nu × (ln P3 − ln P(w))`; tap-typing prediction (`predict_words`) ranks by `ln P3` plus the
language posterior of the context.

Readers before the trigram support skip the section.

## Quantization and pruning

The ranking uses counts only through log probabilities, so they are stored as logarithms:
1/2048 nats for words and 1/16 nats (about 6 %) for bigrams and trigrams. Bigrams below a minimum
count are dropped: 30 for Slovak and 10 for English in the shipped files. Trigrams below a minimum
count (5 for Slovak, 3 for English) are dropped, and of the rest only the most frequent ones that
fit into about 2 MB per language are kept.

Replay on the user's 106 recorded swipes gives the same top-1 as the TSV version (82.1 %). The
synthetic evaluations in `tools/swipe-replay` move by at most 0.6 pp (SK 87.8 → 87.6, EN
89.3 → 88.7, mixed 88.6 → 88.9).

## Sizes

| | TSV (zip-compressed in the APK) | `.kpd` without trigrams, raw / gzip | `.kpd` with trigrams, raw / gzip |
|---|---|---|---|
| Slovak | 7.2 MB | 3.4 / 1.9 MB | 5.4 / 3.5 MB |
| English | 4.0 MB | 1.2 / 0.9 MB | 3.1 / 2.3 MB |

The Slovak dictionary without trigrams loads in 0.33 s instead of 0.79 s for the TSVs (Mac).
