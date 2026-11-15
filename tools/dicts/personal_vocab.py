#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Find words a personal writing corpus uses that a swipe word list lacks, and add them.

usage: personal_vocab.py <lang> <words.tsv> <personal.txt> <hunspell_prefix> <allowlist.txt>
                         <other_lang_words.tsv> <out_dir> [--weight W]

writes:
  <out_dir>/<lang>.personal.txt      the personal sentences that are really in <lang> (sentences
                                     whose words the other language's list knows better are dropped)
  <out_dir>/<lang>.added.tsv         the new words: surface, count in the personal corpus, reason
  <out_dir>/<lang>.words.tsv         the input word list plus the new words (same columns)

A missing word is added when it passes the language's alphabet, is not mostly capitalized inside
sentences (names), is not a common word of the other language, and either occurs at least twice,
or Hunspell knows it, or it is on the allowlist (tools/dicts/vocab/tech-allowlist.txt).
"""
import argparse
import bisect
import collections
import datetime
import os
import re
import subprocess
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_dicts import LANG, keypath, rank_to_freq  # noqa: E402

TOKEN = re.compile(r"[^\W\d_]+(?:['-][^\W\d_]+)*")  # as in build_dicts.py
VOWELS = set("aeiouyáäéíóôúýěů")


def hunspell_accepts(words, prefix):
    if not words:
        return set()
    out = subprocess.run(["hunspell", "-i", "utf-8", "-d", prefix, "-G"], input="\n".join(words),
                         capture_output=True, text=True, check=True).stdout
    return set(out.split("\n")) - {""}


def load_lower(path):
    words = {}
    for line in open(path, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) == 4:
            low = f[0].lower()
            words[low] = max(words.get(low, 0), int(f[3]))
    return words


def main():
    ap = argparse.ArgumentParser()
    for name in ("lang", "words", "personal", "hunspell", "allowlist", "other", "out"):
        ap.add_argument(name)
    ap.add_argument("--weight", type=float, default=8.0)
    ap.add_argument("--min-count", type=int, default=2)
    a = ap.parse_args()
    cfg = LANG[a.lang]
    alphabet = set(cfg["alphabet"])
    base = load_lower(a.words)
    other = load_lower(a.other)
    other_common = {w for w, c in sorted(other.items(), key=lambda kv: -kv[1])[:30000]}
    allow = {l.strip().lower() for l in open(a.allowlist, encoding="utf-8")
             if l.strip() and not l.startswith("#")}

    # keep the personal sentences that are really in this language
    kept = []
    dropped = 0
    for line in open(a.personal, encoding="utf-8"):
        toks = [t.lower() for t in TOKEN.findall(line.replace("’", "'"))]
        if not toks:
            continue
        mine = sum(t in base for t in toks)
        theirs = sum(t in other for t in toks)
        if theirs > mine:
            dropped += 1
            continue
        kept.append(line.rstrip("\n"))
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, f"{a.lang}.personal.txt"), "w", encoding="utf-8") as o:
        o.write("\n".join(kept) + "\n")
    print(f"{a.lang}: kept {len(kept):,} personal sentences, dropped {dropped:,} in the other language",
          file=sys.stderr)

    count = collections.Counter()
    inner = collections.Counter()      # occurrences not at the start of a sentence
    inner_cap = collections.Counter()  # ... of which capitalized
    surfaces = collections.defaultdict(collections.Counter)
    for line in kept:
        for i, t in enumerate(TOKEN.findall(line.replace("’", "'"))):
            low = t.lower()
            count[low] += 1
            surfaces[low][t] += 1
            if i > 0:
                inner[low] += 1
                if t[0].isupper():
                    inner_cap[low] += 1

    cand = []
    for low, c in count.items():
        if low in base or len(low) < 2 or any(ch not in alphabet and ch not in "'-" for ch in low) or not keypath(low):
            continue
        allowed = low in allow
        if not allowed:
            if all(s.isupper() for s in surfaces[low]):  # BTC, NATO: abbreviations only seen in caps
                continue
            if inner[low] and inner_cap[low] >= 0.7 * inner[low]:  # names
                continue
            if inner[low] == 0 and c < 3:  # only ever sentence-initial: too little evidence
                continue
            if low in other_common:
                continue
        cand.append(low)
    spelled = hunspell_accepts(cand, a.hunspell)
    # typos of common words: one edit (delete, insert, substitute, transpose) away from a frequent word
    frequent = {w for w, _ in sorted(base.items(), key=lambda kv: -kv[1])[:60000] if len(w) >= 4}
    deletes = collections.defaultdict(set)
    for w in frequent:
        for i in range(len(w)):
            deletes[w[:i] + w[i + 1:]].add(w)

    def near_frequent(w):
        if w in deletes:  # an insertion away (or a transposition/substitution, via the shared deletes)
            return True
        for i in range(len(w)):
            d = w[:i] + w[i + 1:]
            if d in frequent or d in deletes:
                return True
        return False

    def garbage(w):
        if not any(ch in VOWELS for ch in w):
            return True
        run = longest = 0
        for ch in w:
            run = 0 if ch in VOWELS else run + 1
            longest = max(longest, run)
        if longest >= 5 or re.search(r"(.)\1\1", w):
            return True
        return len(w) >= 4 and set(w) <= set("abcdef")  # hex ids

    added = []
    for low in cand:
        if low in allow:
            reason = "allowlist"
        elif low in spelled:
            reason = "hunspell"
        elif (count[low] >= a.min_count and len(low) >= 3 and len(low) <= 16 and not garbage(low)
              and not (count[low] < 5 and near_frequent(low))):
            reason = "count"
        else:
            reason = None
        if reason:
            added.append((low, count[low], reason))
    added.sort(key=lambda x: -x[1])

    # frequency: rank the weighted personal count among the word list's corpus counts
    counts = sorted((c for c in base.values()), reverse=True)
    neg = [-c for c in counts]
    rows = [line.rstrip("\n") for line in open(a.words, encoding="utf-8") if line.count("\t") == 3]
    with open(os.path.join(a.out, f"{a.lang}.added.tsv"), "w", encoding="utf-8") as o:
        for low, c, reason in added:
            o.write(f"{low}\t{c}\t{reason}\n")
    freqs = []
    with open(os.path.join(a.out, f"{a.lang}.words.tsv"), "w", encoding="utf-8") as o:
        for r in rows:
            o.write(r + "\n")
        for low, c, _ in added:
            weighted = max(1, round(c * a.weight))
            rank = bisect.bisect_left(neg, -weighted) + 1
            freqs.append((low, rank_to_freq(rank)))
            o.write(f"{low}\t{keypath(low)}\t{rank_to_freq(rank)}\t{weighted}\n")
    # a small supplementary dictionary for tap typing and the spell checker: HeliBoard loads every
    # dictionary type of a locale (assets/dicts/vocab_<lang>.dict) next to the main dictionary
    with open(os.path.join(a.out, f"{a.lang}.vocab.combined"), "w", encoding="utf-8") as o:
        o.write(f"dictionary=vocab:{a.lang},locale={a.lang},description=Droneboard vocabulary,"
                f"date={int(datetime.datetime(2026, 11, 14).timestamp())},version=1\n")
        for low, f in freqs:
            o.write(f" word={low},f={f}\n")
    reasons = collections.Counter(r for _, _, r in added)
    print(f"{a.lang}: added {len(added):,} words ({dict(reasons)})", file=sys.stderr)


if __name__ == "__main__":
    main()
