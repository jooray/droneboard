#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""A language typed without diacritics (sk_nd, cs_nd) from the shipped dictionary of its language.

    strip_diacritics.py <lang> <lang>.kpd <out_dir> [--locale=sk-x-nd] [--description=...]

Unpacks <lang>.kpd (unpack_swipe_dict.py), drops the combining marks of every word (NFD: ä->a,
ô->o, ľ->l, ř->r, ů->u) and merges the words, bigrams and trigrams that become equal, with their
counts summed ("súd" and "sud" -> "sud"). Writes to <out_dir>:

    <lang>_nd.words.tsv, .bigrams.tsv, .trigrams.tsv, .total   input of pack_swipe_dict.py
    <lang>_nd.combined                                          HeliBoard word list with bigrams
                                                                (-> main_<locale>.dict, as build_dicts.py)

A merged surface keeps the highest frequency class (0-255) of its sources. The combined list
gets the bigram probabilities of build_dicts.py (P(b|a) on the 0-255 scale, 48 per word).
"""
import collections
import datetime
import math
import os
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import blocklist  # noqa: E402
from pack_swipe_dict import native_lower  # noqa: E402
from unpack_swipe_dict import unpack  # noqa: E402

MAX_BIGRAMS = 600_000   # build_dicts.py
MIN_BIGRAM_COUNT = 3
BIGRAMS_PER_WORD = 48


def strip(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--"))
    lang, kpd, out = args
    nd = f"{lang}_nd"
    locale = opts.get("locale", f"{lang}-x-nd")
    description = opts.get("description", nd)
    os.makedirs(out, exist_ok=True)
    tmp = os.path.join(out, "unpacked")
    (words_tsv, bigrams_tsv, trigrams_tsv), total = unpack(kpd, tmp, lang)
    blocked = blocklist.load(opts.get("blocklist"))  # the stripped form of a word may be a blocked one

    # words: lowercase counts summed over the distinct lowercase forms that merge
    lower_count = {}                       # original lowercase -> count
    variants = collections.OrderedDict()   # stripped surface -> [keypath, freq]
    for line in open(words_tsv, encoding="utf-8"):
        surface, kp, freq, count = line.rstrip("\n").split("\t")
        if surface in blocked:
            continue
        lower_count.setdefault(native_lower(surface), float(count))
        s = strip(surface)
        v = variants.setdefault(s, [kp, 0])
        v[1] = max(v[1], int(freq))
    merged_count = collections.Counter()
    for low, c in lower_count.items():
        merged_count[native_lower(strip(low))] += c
    with open(os.path.join(out, f"{nd}.words.tsv"), "w", encoding="utf-8") as o:
        for s, (kp, freq) in variants.items():
            o.write(f"{s}\t{kp}\t{freq}\t{merged_count[native_lower(s)]!r}\n")

    bigrams = collections.Counter()
    for line in open(bigrams_tsv, encoding="utf-8"):
        a, b, c = line.rstrip("\n").split("\t")
        if a in blocked or b in blocked:
            continue
        bigrams[(strip(a), strip(b))] += float(c)
    with open(os.path.join(out, f"{nd}.bigrams.tsv"), "w", encoding="utf-8") as o:
        for (a, b), c in bigrams.items():
            o.write(f"{a}\t{b}\t{c!r}\n")

    trigrams = collections.Counter()
    pair_seen = set()
    pair_count = collections.Counter()
    for line in open(trigrams_tsv, encoding="utf-8"):
        a, b, c, n, cc = line.rstrip("\n").split("\t")
        if a in blocked or b in blocked or c in blocked:
            continue
        key = (strip(a), strip(b))
        trigrams[key + (strip(c),)] += float(n)
        if (a, b) not in pair_seen:
            pair_seen.add((a, b))
            pair_count[key] += float(cc)
    with open(os.path.join(out, f"{nd}.trigrams.tsv"), "w", encoding="utf-8") as o:
        for (a, b, c), n in trigrams.items():
            o.write(f"{a}\t{b}\t{c}\t{n!r}\t{pair_count[(a, b)]!r}\n")
    with open(os.path.join(out, f"{nd}.total"), "w") as o:
        o.write(repr(total) + "\n")

    # HeliBoard combined list, as build_dicts.py writes it
    by_first = collections.defaultdict(list)
    for (a, b), c in collections.Counter(bigrams).most_common(MAX_BIGRAMS):
        if c >= MIN_BIGRAM_COUNT:
            by_first[a].append((b, c))
    ranked = sorted(variants.items(), key=lambda kv: (-kv[1][1], -merged_count[native_lower(kv[0])]))
    surface_of = {}
    for s, _ in ranked:
        surface_of.setdefault(native_lower(s), s)
    date = int(datetime.datetime(2026, 11, 15).timestamp())
    n_big = 0
    with open(os.path.join(out, f"{nd}.combined"), "w", encoding="utf-8") as o:
        o.write(f"dictionary=main:{locale},locale={locale},description={description},date={date},version=1\n")
        for s, (_kp, freq) in ranked:
            o.write(f" word={s},f={freq}\n")
            low = native_lower(s)
            if surface_of.get(low) != s:
                continue
            prev = max(merged_count[low], 1)
            for b, c in sorted(by_first.get(low, []), key=lambda x: -x[1])[:BIGRAMS_PER_WORD]:
                if b not in surface_of:
                    continue
                bf = max(1, min(255, round(255 + 16 * math.log2(max(c / prev, 1e-12)))))
                o.write(f"  bigram={surface_of[b]},f={bf}\n")
                n_big += 1
    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    print(f"{kpd}: {len(lower_count):,} words -> {len(merged_count):,} ({nd}), {len(variants):,} surfaces, "
          f"{len(bigrams):,} bigrams, {len(trigrams):,} trigrams, {n_big:,} bigrams in the combined list",
          file=sys.stderr)


if __name__ == "__main__":
    main()
