#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Pack a swipe word list and bigram list (TSV, see build_dicts.py) into one compact .kpd file.

    pack_swipe_dict.py <lang>.words.tsv <lang>.bigrams.tsv out.kpd [--min-bigram=N]
                       [--trigrams=<lang>.trigrams.tsv --min-trigram=N --trigram-bytes=B] [--total=T]

Trigrams (count_ngrams.py: w1, w2, w3, count, count(w1 w2)) below --min-trigram are dropped, and of
the rest only the most frequent ones that fit into --trigram-bytes are kept.

The format is described in native/droneswipe/FORMAT.md; the native loader is
KpDictionary::load_kpd in native/droneswipe/kp_dictionary.cpp.
"""
import math
import struct
import sys
import unicodedata


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def front(prev, cur):
    pb, cb = prev.encode(), cur.encode()
    shared = 0
    while shared < min(len(pb), len(cb)) and pb[shared] == cb[shared]:
        shared += 1
    while shared and shared < len(cb) and (cb[shared] & 0xC0) == 0x80:
        shared -= 1  # never split a UTF-8 sequence
    return varint(shared) + varint(len(cb) - shared) + cb[shared:]


def native_lower_cp(cp):
    """Mirror of lower_cp() in kp_dictionary.cpp."""
    if 0x41 <= cp <= 0x5A:
        return cp + 32
    if 0xC0 <= cp <= 0xDE and cp != 0xD7:
        return cp + 32
    if 0x100 <= cp <= 0x17F:
        shifted = 0x139 <= cp <= 0x148 or 0x179 <= cp <= 0x17E
        if (cp % 2 == 1) if shifted else (cp % 2 == 0):
            return cp + 1
    return cp


def native_upper_cp(cp):
    """Mirror of upper_cp() in kp_dictionary.cpp (inverse of native_lower_cp)."""
    if 0x61 <= cp <= 0x7A:
        return cp - 32
    if 0xE0 <= cp <= 0xFE and cp != 0xF7:
        return cp - 32
    if 0x100 <= cp <= 0x17F:
        shifted = 0x139 <= cp <= 0x148 or 0x179 <= cp <= 0x17E
        if (cp % 2 == 0) if shifted else (cp % 2 == 1):
            return cp - 1
    return cp


def native_lower(s):
    return "".join(chr(native_lower_cp(ord(c))) for c in s)


def capitalized(low):
    return chr(native_upper_cp(ord(low[0]))) + low[1:] if low else low


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    min_bigram = 1
    trigrams_tsv = None
    min_trigram = 3
    trigram_bytes = 2_000_000
    total_override = None
    for a in sys.argv[1:]:
        if a.startswith("--min-bigram="):
            min_bigram = int(a.split("=", 1)[1])
        elif a.startswith("--trigrams="):
            trigrams_tsv = a.split("=", 1)[1]
        elif a.startswith("--min-trigram="):
            min_trigram = int(a.split("=", 1)[1])
        elif a.startswith("--trigram-bytes="):
            trigram_bytes = int(a.split("=", 1)[1])
        elif a.startswith("--total="):
            total_override = float(a.split("=", 1)[1])
    words_tsv, bigrams_tsv, out = args

    by_lower = {}     # lower -> list of (surface, freq) in TSV order
    lower_count = {}
    keypaths = {}
    n_variants = 0
    for line in open(words_tsv, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) != 4 or not f[1]:
            continue
        surface, kp, freq, count = f[0], f[1], int(float(f[2])), float(f[3])
        low = native_lower(surface)
        by_lower.setdefault(low, []).append((surface, max(0, min(255, freq))))
        lower_count.setdefault(low, count)  # first variant's count, like the TSV loader
        keypaths.setdefault(low, kp)
        n_variants += 1

    # letters other than a-z that occur in key-path derivations
    cmap = {}
    for low in by_lower:
        for ch in low:
            if not ("a" <= ch <= "z") and ch.isalpha():
                base = unicodedata.normalize("NFD", ch)[0]
                if "a" <= base <= "z":
                    cmap[ord(ch)] = base

    def derive(low):
        return "".join(ch if "a" <= ch <= "z" else cmap.get(ord(ch), "") for ch in low)

    lowers = sorted(by_lower, key=lambda s: s.encode())
    lower_id = {s: i for i, s in enumerate(lowers)}
    total = total_override or float(sum(lower_count.values())) or 1.0

    bigrams = {}
    n_big = 0
    for line in open(bigrams_tsv, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) != 3:
            continue
        a, b, c = native_lower(f[0]), native_lower(f[1]), float(f[2])
        if c < min_bigram or a not in lower_id or b not in lower_id:
            continue
        bigrams.setdefault(lower_id[a], {})[lower_id[b]] = c
        n_big += 1

    sections = []  # (tag, bytes)
    cm = bytearray(struct.pack("<I", len(cmap)))
    for cp in sorted(cmap):
        cm += struct.pack("<IB", cp, ord(cmap[cp]))
    sections.append((b"CMAP", cm))
    lw = bytearray()
    prev = ""
    for s_ in lowers:
        lw += front(prev, s_)
        prev = s_
    sections.append((b"LOWR", lw))
    lc = bytearray()
    for s_ in lowers:
        lc += struct.pack("<H", max(0, min(65535, round(math.log(max(1, lower_count[s_])) * 2048))))
    sections.append((b"LOGC", lc))
    rec = bytearray()
    freqs = bytearray()
    explicit_kp = 0
    explicit_surf = 0
    for low in lowers:
        vs = by_lower[low]
        has_low = [fr for s_, fr in vs if s_ == low]
        has_cap = [fr for s_, fr in vs if s_ != low and s_ == capitalized(low)]
        rest = [(s_, fr) for s_, fr in vs if s_ != low and s_ != capitalized(low)]
        kp = keypaths[low]
        own_kp = derive(low) != kp
        spec = (1 if has_low else 0) | (2 if has_cap else 0) | (4 if own_kp else 0) | (len(rest) << 3)
        rec += varint(spec)
        if own_kp:
            kb = kp.encode()
            rec += varint(len(kb)) + kb
            explicit_kp += 1
        for s_, _ in rest:
            sb = s_.encode()
            rec += varint(len(sb)) + sb
            explicit_surf += 1
        freqs += bytes(has_low[:1] + has_cap[:1] + [fr for _, fr in rest])
    assert len(freqs) == n_variants, (len(freqs), n_variants)
    sections.append((b"WREC", rec))
    sections.append((b"FREQ", freqs))
    bg = bytearray()
    last_prev = 0
    for p in sorted(bigrams):
        bg += varint(p - last_prev) + varint(len(bigrams[p]))
        last_prev = p
        last_w = 0
        for w in sorted(bigrams[p]):
            bg += varint(w - last_w) + bytes([max(0, min(255, round(math.log(bigrams[p][w]) * 16)))])
            last_w = w
    sections.append((b"BIGR", bg))

    n_tri = 0
    if trigrams_tsv:
        rows = []
        for line in open(trigrams_tsv, encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) != 5 or float(f[3]) < min_trigram:
                continue
            ids = [lower_id.get(native_lower(w)) for w in f[:3]]
            if None in ids:
                continue
            rows.append((float(f[3]), ids[0], ids[1], ids[2], float(f[4])))
        rows.sort(key=lambda r: -r[0])
        # most frequent first, until the estimated size is used up (about 3 bytes per continuation
        # plus 6 per context)
        kept = {}
        size = 8
        for c, w1, w2, w3, cc in rows:
            ctx = kept.get((w1, w2))
            cost = 3 + (0 if ctx else 6)
            if size + cost > trigram_bytes:
                break
            if ctx is None:
                ctx = kept[(w1, w2)] = [cc, {}]
            ctx[1][w3] = c
            size += cost
        tg = bytearray()
        last1 = last2 = 0
        body = bytearray()
        for (w1, w2) in sorted(kept):
            cc, conts = kept[(w1, w2)]
            if w1 != last1 or not body:
                body += varint(w1 - last1) + varint(w2)
            else:
                body += varint(0) + varint(w2 - last2)
            last1, last2 = w1, w2
            body += bytes([max(0, min(255, round(math.log(max(1, cc)) * 16)))]) + varint(len(conts))
            last_w = 0
            for w in sorted(conts):
                body += varint(w - last_w) + bytes([max(0, min(255, round(math.log(conts[w]) * 16)))])
                last_w = w
            n_tri += len(conts)
        tg += struct.pack("<II", len(kept), n_tri) + body
        sections.append((b"TRIG", tg))

    header = b"KPD1" + struct.pack("<IIIIIdI", 2, len(lowers), n_variants, len(bigrams), n_big, total, len(sections))
    offset = len(header) + 12 * len(sections)
    directory = bytearray()
    for tag, data in sections:
        directory += tag + struct.pack("<II", offset, len(data))
        offset += len(data)
    buf = bytearray(header) + directory
    for _, data in sections:
        buf += data
    with open(out, "wb") as o:
        o.write(buf)
    print(f"{out}: {len(lowers)} words, {n_variants} variants ({explicit_surf} explicit, "
          f"{explicit_kp} explicit key paths), {n_big} bigrams, {n_tri} trigrams, {len(buf)} bytes")


if __name__ == "__main__":
    main()
