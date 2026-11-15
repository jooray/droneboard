#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Count unigrams, bigrams and trigrams of a swipe word list's vocabulary in open text corpora,
optionally adding a personal corpus with a weight. Output feeds pack_swipe_dict.py.

usage: count_ngrams.py <lang> <words.tsv> <out_dir> --corpus a.txt [--corpus b.txt ...]
         [--personal p.txt --personal-weight W] [--exclude eval.jsonl ...]
         [--min-bigram 3] [--min-trigram 3] [--jobs N]

writes:
  <out_dir>/<lang>.words.tsv     the input word list with the count column recounted on these
                                 corpora (lowercase form; personal counts x W added; at least 1)
  <out_dir>/<lang>.bigrams.tsv   prev \t word \t count
  <out_dir>/<lang>.trigrams.tsv  w1 \t w2 \t w3 \t count \t count(w1 w2)

Sentences are split at . ! ? and newlines; an out-of-vocabulary token breaks the n-gram chain.
--exclude takes swipe-replay evaluation files (target + up to 3 context words): every sentence that
contains one of their context+target word sequences (3 or 4 words) is skipped, so that the
evaluation sentences never leak into the counts.

Never feed SNK / ČNK (Slovak / Czech national corpus) data into this script: their licenses do
not allow redistribution.
"""
import argparse
import json
import multiprocessing as mp
import os
import re
import sys

import numpy as np

TOKEN = re.compile(r"[^\W\d_]+(?:['-][^\W\d_]+)*")  # as in build_dicts.py
SENTENCE = re.compile(r"[.!?\n]+")
BITS = 20  # word ids < 2^20

VOCAB = None
EX3 = None
EX4 = None


def init_worker(vocab, ex3, ex4):
    global VOCAB, EX3, EX4
    VOCAB, EX3, EX4 = vocab, ex3, ex4


def count_text(text):
    """Returns unique (key, count) arrays for unigrams, bigrams, trigrams and the number of
    skipped sentences."""
    uni, big, tri = [], [], []
    skipped = 0
    for s in SENTENCE.split(text.replace("’", "'")):
        toks = []
        for t in TOKEN.findall(s):
            t = t.lower()
            # a hyphenated word that is not in the list counts as its parts
            toks.extend(t.split("-") if "-" in t and t not in VOCAB else [t])
        if not toks:
            continue
        if EX4 or EX3:
            bad = False
            for i in range(len(toks) - 2):
                if tuple(toks[i:i + 3]) in EX3 or (i + 4 <= len(toks) and tuple(toks[i:i + 4]) in EX4):
                    bad = True
                    break
            if bad:
                skipped += 1
                continue
        ids = [VOCAB.get(t, -1) for t in toks]
        for i, a in enumerate(ids):
            if a < 0:
                continue
            uni.append(a)
            if i + 1 < len(ids) and ids[i + 1] >= 0:
                b = ids[i + 1]
                big.append((a << BITS) | b)
                if i + 2 < len(ids) and ids[i + 2] >= 0:
                    tri.append((a << (2 * BITS)) | (b << BITS) | ids[i + 2])
    out = []
    for arr in (uni, big, tri):
        k, c = np.unique(np.array(arr, dtype=np.int64), return_counts=True)
        out.append((k, c.astype(np.int64)))
    return out, skipped


def merge(acc, part):
    if acc is None:
        return part
    k = np.concatenate([acc[0], part[0]])
    c = np.concatenate([acc[1], part[1]])
    uk, inv = np.unique(k, return_inverse=True)
    return uk, np.bincount(inv, weights=c).astype(np.int64)


def chunks(path, size=32 << 20):
    with open(path, encoding="utf-8", errors="replace") as f:
        while True:
            block = f.read(size)
            if not block:
                return
            rest = f.readline()  # end at a line boundary
            yield block + rest


def count_corpus(paths, vocab, ex3, ex4, jobs):
    acc = [None, None, None]
    skipped = 0
    with mp.Pool(jobs, initializer=init_worker, initargs=(vocab, ex3, ex4)) as pool:
        for path in paths:
            for n, (parts, sk) in enumerate(pool.imap_unordered(count_text, chunks(path))):
                skipped += sk
                for i in range(3):
                    acc[i] = merge(acc[i], parts[i])
                print(f"  {os.path.basename(path)} chunk {n + 1}: {len(acc[2][0]):,} trigram types",
                      file=sys.stderr, flush=True)
    return acc, skipped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lang")
    ap.add_argument("words")
    ap.add_argument("out")
    ap.add_argument("--corpus", action="append", default=[])
    ap.add_argument("--personal")
    ap.add_argument("--personal-weight", type=float, default=1.0)
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("--min-bigram", type=int, default=3)
    ap.add_argument("--min-trigram", type=int, default=3)
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    a = ap.parse_args()

    rows = [line.rstrip("\n").split("\t") for line in open(a.words, encoding="utf-8")]
    rows = [r for r in rows if len(r) == 4]
    lowers = []
    vocab = {}
    for r in rows:
        low = r[0].lower()
        if low not in vocab:
            vocab[low] = len(lowers)
            lowers.append(low)
    assert len(lowers) < (1 << BITS)

    ex3, ex4 = set(), set()
    for path in a.exclude:
        for line in open(path, encoding="utf-8"):
            j = json.loads(line)
            seq = [w.lower() for w in j.get("context", [])][-3:] + [j["target"].lower()]
            if len(seq) == 4:
                ex4.add(tuple(seq))
            elif len(seq) == 3:
                ex3.add(tuple(seq))
    print(f"{a.lang}: vocabulary {len(lowers):,}, excluded sequences {len(ex3) + len(ex4):,}", file=sys.stderr)

    acc, skipped = count_corpus(a.corpus, vocab, ex3, ex4, a.jobs)
    print(f"  skipped {skipped:,} evaluation sentences", file=sys.stderr)
    if a.personal:
        pers, _ = count_corpus([a.personal], vocab, set(), set(), 1)
        for i in range(3):
            k, c = pers[i]
            acc[i] = merge(acc[i], (k, np.round(c * a.personal_weight).astype(np.int64)))

    uni = dict(zip(acc[0][0].tolist(), acc[0][1].tolist()))
    big = dict(zip(acc[1][0].tolist(), acc[1][1].tolist()))
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, f"{a.lang}.words.tsv"), "w", encoding="utf-8") as o:
        for r in rows:
            o.write(f"{r[0]}\t{r[1]}\t{r[2]}\t{max(1, uni.get(vocab[r[0].lower()], 0))}\n")
    mask = (1 << BITS) - 1
    n_big = 0
    with open(os.path.join(a.out, f"{a.lang}.bigrams.tsv"), "w", encoding="utf-8") as o:
        k, c = acc[1]
        order = np.argsort(-c, kind="stable")
        for i in order:
            if c[i] < a.min_bigram:
                break
            key = int(k[i])
            o.write(f"{lowers[key >> BITS]}\t{lowers[key & mask]}\t{int(c[i])}\n")
            n_big += 1
    n_tri = 0
    with open(os.path.join(a.out, f"{a.lang}.trigrams.tsv"), "w", encoding="utf-8") as o:
        k, c = acc[2]
        order = np.argsort(-c, kind="stable")
        for i in order:
            if c[i] < a.min_trigram:
                break
            key = int(k[i])
            w1, w2, w3 = key >> (2 * BITS), (key >> BITS) & mask, key & mask
            ctx = big.get((w1 << BITS) | w2, int(c[i]))
            o.write(f"{lowers[w1]}\t{lowers[w2]}\t{lowers[w3]}\t{int(c[i])}\t{ctx}\n")
            n_tri += 1
    print(f"  {a.lang}: {sum(uni.values()):,} tokens, {n_big:,} bigrams, {n_tri:,} trigrams", file=sys.stderr)


if __name__ == "__main__":
    main()
