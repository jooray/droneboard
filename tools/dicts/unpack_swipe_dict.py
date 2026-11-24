#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Unpack a .kpd file (v1 'KPD1' or v2 'DKPD') into the TSVs that pack_swipe_dict.py reads, so
that a shipped dictionary can be inspected or extended without the corpora it was built from.

    unpack_swipe_dict.py <lang>.kpd <out_dir>

writes <out_dir>/<lang>.words.tsv, .bigrams.tsv, .trigrams.tsv and .total (the unigram
denominator of the header). Counts are written as the logarithms stored in the file allow
(exp(q / 2048) for words, exp(q / 16) for n-grams). The format is described in
native/droneswipe/FORMAT.md.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pack_swipe_dict import read_kpd_data, read_varint as varint  # noqa: E402


def unpack(path, out, lang=None):
    """Writes the TSVs of path (v1 or v2) into out; returns ([words, bigrams, trigrams], total)."""
    lang = lang or os.path.basename(path).split(".")[0]
    d = read_kpd_data(path)
    lowers = d["lowers"]
    lower_logc_q = d["lower_logc_q"]
    variants = d["variants"]
    kp_to_vids = d["kp_to_vids"]
    bigrams = d["bigrams"]
    trigrams = d["trigrams"]
    total = d["total"]

    # Map each variant to its primary key path (longest key path wins if collapsed-double alias exists)
    vid_to_kp = {}
    for kp, vids in kp_to_vids.items():
        for vid in vids:
            cur = vid_to_kp.get(vid)
            if cur is None or len(kp) > len(cur):
                vid_to_kp[vid] = kp

    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, f"{lang}.words.tsv"), "w", encoding="utf-8") as w:
        for vid, (surf, freq, wid, _) in enumerate(variants):
            kp = vid_to_kp.get(vid, "")
            count = repr(math.exp(lower_logc_q[wid] / 2048.0))
            w.write(f"{surf}\t{kp}\t{freq}\t{count}\n")

    n_big = 0
    with open(os.path.join(out, f"{lang}.bigrams.tsv"), "w", encoding="utf-8") as w:
        for p in sorted(bigrams):
            row = bigrams[p]
            for x in sorted(row):
                w.write(f"{lowers[p]}\t{lowers[x]}\t{math.exp(row[x] / 16.0)!r}\n")
                n_big += 1

    with open(os.path.join(out, f"{lang}.trigrams.tsv"), "w", encoding="utf-8") as w:
        for (w1, w2) in sorted(trigrams):
            ctx_q, row = trigrams[(w1, w2)]
            cc = math.exp(ctx_q / 16.0)
            for x in sorted(row):
                w.write(f"{lowers[w1]}\t{lowers[w2]}\t{lowers[x]}\t{math.exp(row[x] / 16.0)!r}\t{cc!r}\n")

    with open(os.path.join(out, f"{lang}.total"), "w") as w:
        w.write(repr(total) + "\n")

    print(f"{path}: {len(lowers)} words, {len(variants)} variants, {n_big} bigrams -> {out}", file=sys.stderr)
    return [os.path.join(out, f"{lang}.{k}.tsv") for k in ("words", "bigrams", "trigrams")], total


def main():
    unpack(*sys.argv[1:3])


if __name__ == "__main__":
    main()
