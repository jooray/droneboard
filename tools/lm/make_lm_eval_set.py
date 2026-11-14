"""Held-out items for tools/swipe-replay lm-eval from the LM token streams (tools/lm/prepare.py).

usage: make_lm_eval_set.py --work DIR --langs sk,en --split test|ptest --n 1500 --out items.jsonl

Each item is one word of a held-out paragraph:
  target   the word
  lang     the paragraph's language
  context  up to 3 words before it in the same sentence (what the keyboard's n-gram context holds)
  tokens   up to 31 tokens before it in the paragraph, punctuation and <num> included
  bos      the paragraph starts within those tokens
Targets are words of the language's vocabulary (letters only); <unk> targets are skipped, since
no word-level model can predict them. Items of all languages are shuffled together; evaluate them
with every language enabled to measure the language posterior too.
"""
import argparse
import json
import random
import re
from pathlib import Path

import numpy as np

WORD = re.compile(r"^[^\W\d_]+(?:'[^\W\d_]+)*$")
SENTENCE_END = {".", "!", "?"}


def paragraphs(work, lang, split):
    vocab = (work / "vocab" / f"{lang}.txt").read_text(encoding="utf-8").split("\n")[:-1]
    ids = np.memmap(work / "ids" / f"{lang}.{split}.u16", dtype=np.uint16, mode="r")
    starts = np.flatnonzero(ids == 2)
    for s, e in zip(starts, list(starts[1:]) + [len(ids)]):
        toks = [vocab[i] for i in ids[s + 1:e]]
        if len(toks) >= 4:
            yield lang, toks


def items_of(lang, toks, rng, per_para):
    pos = [i for i, t in enumerate(toks) if WORD.match(t) and t != "<unk>"]
    rng.shuffle(pos)
    for i in sorted(pos[:per_para]):
        words = []
        for t in reversed(toks[:i]):
            if t in SENTENCE_END or len(words) == 3:
                break
            if WORD.match(t):
                words.insert(0, t)
            elif t != ",":
                break
        start = max(0, i - 31)
        yield {"target": toks[i], "lang": lang, "context": words, "tokens": toks[start:i], "bos": start == 0}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--langs", required=True)
    ap.add_argument("--split", default="test")
    ap.add_argument("--n", type=int, default=1500)
    ap.add_argument("--per_para", type=int, default=3)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    work = Path(a.work)
    langs = a.langs.split(",")
    paras = {l: list(paragraphs(work, l, a.split)) for l in langs}
    for l in langs:
        rng.shuffle(paras[l])
    out = []
    per_lang = a.n // len(langs)
    for l in langs:
        got = 0
        for lang, toks in paras[l]:
            for it in items_of(lang, toks, rng, a.per_para):
                out.append(it)
                got += 1
            if got >= per_lang:
                break
    rng.shuffle(out)
    with open(a.out, "w", encoding="utf-8") as f:
        for it in out:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")
    print(json.dumps({"items": len(out), "langs": langs, "split": a.split}))


if __name__ == "__main__":
    main()
