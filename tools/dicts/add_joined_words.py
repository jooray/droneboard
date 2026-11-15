#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Add words with inner hyphens or apostrophes ("e-mail", "viac-menej", "sci-fi") to a shipped .kpd
dictionary, counted on a text corpus, without rebuilding the dictionary from its own corpora.

    add_joined_words.py <lang> <in.kpd> <out.kpd> <corpus.txt>... [--min-count=N] [--max-words=N]
                        [--max-lines=N] [--list]

build_dicts.py keeps such words since 0.1.12; this tool brings them into the dictionaries that were
built before. A candidate is kept when:

- it occurs at least --min-count times (default 5) in the corpus and is not in the dictionary yet;
- it is lowercase or every part is capitalized ("Wi-Fi"), and no part is in capitals ("Smer-SD");
- only the first part may be a single letter ("e-mail", "t-shirt", not "implant-s");
- its key path is already a word ("e-mail" next to "email") or every part of two or more letters
  is a word of the dictionary ("viac-menej");
- apostrophes count only in English; in Slovak and Czech text they mark English words.

Counts are scaled to the dictionary by the 2,000 most frequent words, which both sources know, and
the frequency comes from the rank, like in build_dicts.py. The words get no n-grams (the scoring
backs off to the unigram); the key path skips the hyphens and apostrophes, the suggestion keeps them.
"""
import bisect
import collections
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_dicts import TOKEN, keypath, rank_to_freq  # noqa: E402
from pack_swipe_dict import native_lower  # noqa: E402
from unpack_swipe_dict import unpack  # noqa: E402

JOIN = "'-"
# apostrophes only where the language writes them; in Slovak and Czech text they are English words
JOINERS = {"en": "'-"}


def parts(w):
    out, cur = [], ""
    for ch in w:
        if ch in JOIN:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    return out + [cur]


def shape_ok(w):
    ps = parts(w)
    if any(len(p) > 1 and p.isupper() for p in ps):
        return False
    if w == w.lower():
        return True
    return all(p[:1].isupper() and p[1:] == p[1:].lower() for p in ps if p)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    min_count = int(opts.get("min-count", 5))
    max_words = int(opts.get("max-words", 3000))
    max_lines = int(opts.get("max-lines", 0))
    lang, src, dst, *corpora = args

    joiners = JOINERS.get(lang, "-")
    work = tempfile.mkdtemp(prefix="joined-")
    (words_tsv, bigrams_tsv, trigrams_tsv), total = unpack(src, work, lang)
    rows = [l.rstrip("\n").split("\t") for l in open(words_tsv, encoding="utf-8")]
    count_of = {}
    for s, _, _, c in rows:
        count_of.setdefault(native_lower(s), float(c))
    known = set(count_of)
    known_kp = {kp for _, kp, _, _ in rows}
    top = sorted(count_of, key=lambda w: -count_of[w])[:2000]

    joined = collections.Counter()
    plain = collections.Counter()
    top_set = set(top)
    for path in corpora:
        with open(path, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f):
                if max_lines and n >= max_lines:
                    break
                for t in TOKEN.findall(line.replace("’", "'")):
                    if any(ch in JOIN for ch in t):
                        if any(ch in JOIN and ch not in joiners for ch in t):
                            continue
                        joined[t] += 1
                    else:
                        low = t.lower()
                        if low in top_set:
                            plain[low] += 1
    scale = sum(count_of[w] for w in top if plain[w]) / max(1, sum(plain[w] for w in top))

    by_lower = collections.defaultdict(collections.Counter)
    for t, c in joined.items():
        by_lower[native_lower(t)][t] += c
    cand = []
    for low, surfaces in by_lower.items():
        c = sum(surfaces.values())
        if c < min_count or low in known:
            continue
        surface = max((s for s in surfaces if shape_ok(s)), key=lambda s: surfaces[s], default=None)
        kp = keypath(low)
        if not surface or not kp or len(kp) < 3:
            continue
        if kp not in known_kp and not all(len(p) < 2 or native_lower(p) in known for p in parts(low)):
            continue
        if any(len(p) < 2 for p in parts(low)[1:]):  # "e-mail" and "t-shirt", but not "implant-s"
            continue
        cand.append((c * scale, surface, kp))
    cand.sort(reverse=True)
    cand = cand[:max_words]

    counts = sorted(count_of.values(), reverse=True)
    neg = [-c for c in counts]
    with open(words_tsv, "a", encoding="utf-8") as o:
        for c, surface, kp in cand:
            rank = bisect.bisect_left(neg, -c) + 1
            o.write(f"{surface}\t{kp}\t{rank_to_freq(rank)}\t{max(1.0, c)!r}\n")
    print(f"{lang}: scale {scale:.3f}, added {len(cand)} words, e.g. "
          + ", ".join(s for _, s, _ in cand[:25]), file=sys.stderr)
    if "--list" in sys.argv:
        for c, surface, _ in cand:
            print(f"{surface}\t{c:.0f}")
    subprocess.run([sys.executable, "-I", os.path.join(os.path.dirname(os.path.abspath(__file__)), "pack_swipe_dict.py"),
                    words_tsv, bigrams_tsv, dst, f"--trigrams={trigrams_tsv}", "--min-trigram=0",
                    "--trigram-bytes=1000000000", f"--total={total + sum(max(1.0, c) for c, _, _ in cand)!r}"], check=True)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
