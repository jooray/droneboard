# Swipe dictionary format (`.kpd`)

One `.kpd` file per language holds the swipe word list, word frequencies and the previous-word
statistics. `tools/dicts/pack_swipe_dict.py` writes it from the TSVs of `tools/dicts/build_dicts.py`
or repacks v1 into v2 (`--repack-v2` / `--repack-all`).
`KpDictionary::load_kpd` and `KpDictionary::load_kpd_fd` (`kp_dictionary.cpp`) transparently support
both **v1** (`KPD1`, compact varint-packed, unpacked into RAM on load) and **v2** (`DKPD`, flat
zero-copy `mmap`, loads in `<2 ms` with zero `unordered_map` allocations).

All integers are little endian.

---

## Format v2 (`DKPD`) — Flat Zero-Copy `mmap`

All sections start on an 8-byte boundary so `u32`, `f32`, `u64` and `FlatVariant` arrays are
dereferenced directly out of the read-only `mmap` mapping. The mapping stays open for the lifetime
of `KpDictionary` and is cleanly `munmap`ped in `~KpDictionary()`.

### Header (56 bytes)

| Offset | Field | Type | Meaning |
|---:|---|---|---|
| 0 | magic | 4 bytes | `DKPD` |
| 4 | version | u32 | `2` |
| 8 | flags | u32 | bit 0: BFS-ordered trie (implicit contiguous children `[child_start[n], child_start[n+1])`, no `TCHL` section); bit 1: trie contains non-`a..z` byte; bit 2: 24-byte `DKPF` integrity footer appended at EOF |
| 12 | n_lower | u32 | lowercase words (ids `0 … n_lower-1`, sorted by unsigned UTF-8 bytes) |
| 16 | n_variants | u32 | surface variants (`ľahko`, `Ľahko`, `I'll`, …) |
| 20 | n_kps | u32 | unique key paths (sorted by unsigned UTF-8/ASCII bytes) |
| 24 | n_nodes | u32 | trie nodes (root = `0`) |
| 28 | n_big | u32 | bigram entries |
| 32 | n_tri_ctx | u32 | trigram contexts |
| 36 | n_tri | u32 | trigram entries |
| 40 | total | f64 | sum of lowercase word counts (unigram denominator) |
| 48 | n_sections | u32 | entries in the section directory |
| 52 | reserved | u32 | `0` |
| 56 | directory | `n_sections × (char[4] tag, u32 offset, u32 length)` | 8-byte aligned offsets from file start |

### Sections (v2)

| Tag | Required | Layout | Meaning |
|---|---|---|---|
| `TCHS` | yes | `u32[n_nodes + 1]` | Trie child CSR offsets (`TCHS[n_nodes] == n_nodes` when `flags & 1`) |
| `TCHL` | if `!(flags & 1)` | `u32[TCHS[n_nodes]]` | Explicit child node ids (omitted for BFS-ordered tries) |
| `TNCH` | yes | `u8[n_nodes]` | Base key-path byte per node (`0` for root) |
| `TNTM` | yes | `i32[n_nodes]` | Terminal key-path id (`0 … n_kps-1`), or `-1` |
| `TNFQ` | yes | `u8[n_nodes]` | Maximum variant frequency (`0–255`) at terminal nodes |
| `TNDP` | yes | `u16[n_nodes]` | Trie node depth (`0` for root) |
| `LWPL` | yes | `char[]` | NUL-terminated UTF-8 lowercase string pool, sorted by unsigned bytes |
| `LWOF` | yes | `u32[n_lower + 1]` | Start byte offset of each lowercase word (`len = LWOF[i+1] - LWOF[i] - 1`) |
| `LOGC` | yes | `u16[n_lower]` | `round(ln(count) × 2048)` |
| `BEST` | yes | `u32[n_lower]` | Variant id of the most frequent surface form (`UINT32_MAX` if none) |
| `KPPL` | yes | `char[]` | NUL-terminated sorted key-path string pool |
| `KPOF` | yes | `u32[n_kps + 1]` | Start byte offset of each key path |
| `KPVS` | yes | `u32[n_kps + 1]` | CSR start into `KPVI` per key path |
| `KPVI` | yes | `u32[KPVS[n_kps]]` | Variant ids for each key path (allows collapsed-double aliases like `il → I'll` to share variants) |
| `VARS` | yes | `FlatVariant[n_variants]` | Packed 12-byte structs: `u32 lower, u32 surf_off, u16 surf_len, u8 freq, u8 flags` (`surf_off == UINT32_MAX` means surface equals `lowers[lower]`; `flags & 1` = capitalized) |
| `VRPL` | optional | `char[]` | NUL-terminated surface strings that differ from their lowercase form |
| `BGST` | yes | `u32[n_lower + 1]` | Bigram CSR offsets by `prev` id |
| `BGWD` | if `n_big > 0` | `u32[n_big]` | Sorted target `lower` ids per `prev` |
| `BGLC` | if `n_big > 0` | `u8[n_big]` | `round(ln(count) × 16)` |
| `BGRM` | yes | `f32[n_lower]` | Precomputed unallocated-mass backoff weight `max(50, max(0.15 × C(prev), C(prev) − Σ stored_bigrams))` |
| `TGCT` | if `n_tri_ctx > 0` | `u64[n_tri_ctx]` | Sorted trigram contexts `((u64)prev2 << 32) | prev` |
| `TGCL` | if `n_tri_ctx > 0` | `u8[n_tri_ctx]` | `round(ln(count(prev2 prev)) × 16)` |
| `TGRM` | if `n_tri_ctx > 0` | `f32[n_tri_ctx]` | Precomputed unallocated-mass backoff `max(0.15 × C(prev2 prev), C(prev2 prev) − Σ stored_trigrams)` |
| `TGST` | if `n_tri_ctx > 0` | `u32[n_tri_ctx + 1]` | Trigram CSR offsets |
| `TGWD` | if `n_tri > 0` | `u32[n_tri]` | Sorted target `lower` ids per context |
| `TGLC` | if `n_tri > 0` | `u8[n_tri]` | `round(ln(count) × 16)` |
| `TOPW` | yes | `u32[<=128]` | Top 128 most frequent `lower` ids for O(1) empty-prefix `predict()` |
| `TPBS` | yes | `u32[257]` | CSR offsets into `TPBI` per leading UTF-8 byte `0..255` |
| `TPBI` | yes | `u32[TPBS[256]]` | Top 64 most frequent `lower` ids per leading byte for O(1) 1-char `predict()` |
| `BYRG` | yes | `u32[257]` | Contiguous `[BYRG[b], BYRG[b+1])` range in `LOWR` for leading byte `b` |

### Integrity Footer (`DKPF`, 24 bytes, when `flags & 4`)

`KpDictionary::save_v2()` appends a 24-byte footer, calls `fsync()` on the file before atomic
`rename()`, and `fsync()`s the parent directory after rename. `load_v2_buffer()` verifies both
file size and hash before accepting any mapping:

| Offset (from EOF − 24) | Field | Type | Meaning |
|---:|---|---|---|
| 0 | magic | 4 bytes | `DKPF` |
| 4 | reserved | u32 | `0` |
| 8 | total_size | u64 | Expected total file size in bytes (including this 24-byte footer) |
| 16 | hash64 | u64 | 64-bit `FastHash64` (8-byte word mixing) over bytes `[0, total_size - 24)` |

### Scoring & Unallocated-Mass Backoff

Because `.kpd` prunes low-count bigrams and trigrams, fixed Dirichlet `K = 50` caused unseen
continuations after high-frequency words (`"v"`, `"a"`, `"na"`) to receive extreme negative PMI
(`-11.5` to `-15.9`). Both v1 and v2 loaders now use unallocated-mass backoff:

- Bigram: `rem = max(0.15 × C(prev), C(prev) − Σ stored_bigrams(prev))`, `K_eff = max(50, rem)`,
  `P2(w | prev) = min(1, (c(prev, w) + K_eff × P(w)) / (C(prev) + 50))`
- Trigram: `rem3 = max(0.15 × C(prev2, prev), C(prev2, prev) − Σ stored_trigrams)`,
  `K3_eff = max(k3, rem3)`,
  `P3(w | prev2, prev) = min(1, (c(prev2, prev, w) + K3_eff × P2) / (C(prev2, prev) + k3))`

---

## Format v1 (`KPD1`) — Legacy Varint-Packed (Backward Compatible)

A *varint* is unsigned LEB128. A *front-coded string* is `varint shared, varint suffix_len, suffix bytes`:
the first `shared` bytes are copied from the previous string of the same list, and a UTF-8 sequence is never split.

### Header (KPD1, version 2)

| Field | Type | Meaning |
|---|---|---|
| magic | 4 bytes | `KPD1` |
| version | u32 | `2` |
| n_lower | u32 | lowercase words (ids `0 … n_lower-1`, in `LOWR` order) |
| n_variants | u32 | surface variants |
| n_bigram_prevs | u32 | previous words that have bigrams |
| n_bigrams | u32 | bigram pairs |
| total | f64 | sum of lowercase word counts |
| n_sections | u32 | entries in the section directory |
| directory | n_sections × (4-byte tag, u32 offset, u32 length) | offsets from file start |

### Sections (KPD1)

| Tag | Required | Content |
|---|---|---|
| `CMAP` | yes | `u32 n`, then `n × (u32 code point, u8 base letter)` |
| `LOWR` | yes | `n_lower` front-coded lowercase words, sorted by UTF-8 bytes |
| `LOGC` | yes | `n_lower × u16`: `round(ln(count) × 2048)` |
| `WREC` | yes | one varint-coded word record per lowercase word |
| `FREQ` | yes | `n_variants × u8`: frequency on the 0–255 log scale |
| `BIGR` | no | delta-varint bigrams |
| `TRIG` | no | delta-varint trigrams |
