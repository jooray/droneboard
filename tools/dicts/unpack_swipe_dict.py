#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Unpack a .kpd file into the TSVs that pack_swipe_dict.py reads, so that a shipped dictionary can
be extended without the corpora it was built from.

    unpack_swipe_dict.py <lang>.kpd <out_dir>

writes <out_dir>/<lang>.words.tsv, .bigrams.tsv, .trigrams.tsv and .total (the unigram
denominator of the header). Counts are written as the logarithms stored in the file allow
(exp(q / 2048) for words, exp(q / 16) for n-grams), so that

    pack_swipe_dict.py words bigrams out.kpd --trigrams=trigrams --min-trigram=0 \\
        --trigram-bytes=1000000000 --total=<.total>

rebuilds the same file byte for byte. The format is described in native/droneswipe/FORMAT.md.
"""
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pack_swipe_dict import capitalized  # noqa: E402


def varint(b, i):
    n = s = 0
    while True:
        x = b[i]
        i += 1
        n |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return n, i


def unpack(path, out, lang=None):
    """Writes the TSVs of path into out; returns the paths (words, bigrams, trigrams) and the total."""
    lang = lang or os.path.basename(path).split(".")[0]
    b = open(path, "rb").read()
    if b[:4] != b"KPD1":
        raise SystemExit(f"{path}: not a .kpd file")
    version, n_lower, n_variants, n_prevs, n_big, total, n_sec = struct.unpack_from("<IIIIIdI", b, 4)
    if version != 2:
        raise SystemExit(f"{path}: unknown version {version}")
    off = 4 + struct.calcsize("<IIIIIdI")
    sec = {}
    for _ in range(n_sec):
        tag = b[off:off + 4]
        o, ln = struct.unpack_from("<II", b, off + 4)
        sec[tag] = (o, ln)
        off += 12

    o, ln = sec[b"CMAP"]
    cmap = {}
    for k in range(struct.unpack_from("<I", b, o)[0]):
        cp, base = struct.unpack_from("<IB", b, o + 4 + 5 * k)
        cmap[cp] = chr(base)

    o, ln = sec[b"LOWR"]
    i, prev, lowers = o, b"", []
    while i < o + ln:
        shared, i = varint(b, i)
        n, i = varint(b, i)
        prev = prev[:shared] + b[i:i + n]
        i += n
        lowers.append(prev.decode())
    assert len(lowers) == n_lower
    o, _ = sec[b"LOGC"]
    logc = struct.unpack_from(f"<{n_lower}H", b, o)

    def derive(low):
        return "".join(ch if "a" <= ch <= "z" else cmap.get(ord(ch), "") for ch in low)

    os.makedirs(out, exist_ok=True)
    i, _ = sec[b"WREC"]
    fi, _ = sec[b"FREQ"]
    n_var = 0
    with open(os.path.join(out, f"{lang}.words.tsv"), "w", encoding="utf-8") as w:
        for wid, low in enumerate(lowers):
            spec, i = varint(b, i)
            kp = derive(low)
            if spec & 4:
                n, i = varint(b, i)
                kp = b[i:i + n].decode()
                i += n
            surfaces = ([low] if spec & 1 else []) + ([capitalized(low)] if spec & 2 else [])
            for _ in range(spec >> 3):
                n, i = varint(b, i)
                surfaces.append(b[i:i + n].decode())
                i += n
            count = repr(math.exp(logc[wid] / 2048.0))
            for s in surfaces:
                w.write(f"{s}\t{kp}\t{b[fi]}\t{count}\n")
                fi += 1
                n_var += 1
    assert n_var == n_variants, (n_var, n_variants)

    with open(os.path.join(out, f"{lang}.bigrams.tsv"), "w", encoding="utf-8") as w:
        if b"BIGR" in sec:
            i, _ = sec[b"BIGR"]
            p = 0
            for _ in range(n_prevs):
                d, i = varint(b, i)
                p += d
                n, i = varint(b, i)
                x = 0
                for _ in range(n):
                    d, i = varint(b, i)
                    x += d
                    w.write(f"{lowers[p]}\t{lowers[x]}\t{math.exp(b[i] / 16.0)!r}\n")
                    i += 1

    with open(os.path.join(out, f"{lang}.trigrams.tsv"), "w", encoding="utf-8") as w:
        if b"TRIG" in sec:
            i, _ = sec[b"TRIG"]
            n_ctx, n_tri = struct.unpack_from("<II", b, i)
            i += 8
            w1 = w2 = 0
            for _ in range(n_ctx):
                d1, i = varint(b, i)
                w1 += d1
                v, i = varint(b, i)
                w2 = v if d1 else w2 + v
                cc = math.exp(b[i] / 16.0)
                i += 1
                n, i = varint(b, i)
                x = 0
                for _ in range(n):
                    d, i = varint(b, i)
                    x += d
                    w.write(f"{lowers[w1]}\t{lowers[w2]}\t{lowers[x]}\t{math.exp(b[i] / 16.0)!r}\t{cc!r}\n")
                    i += 1
    with open(os.path.join(out, f"{lang}.total"), "w") as w:
        w.write(repr(total) + "\n")
    print(f"{path}: {n_lower} words, {n_variants} variants, {n_big} bigrams -> {out}", file=sys.stderr)
    return [os.path.join(out, f"{lang}.{k}.tsv") for k in ("words", "bigrams", "trigrams")], total


def main():
    unpack(*sys.argv[1:3])


if __name__ == "__main__":
    main()
