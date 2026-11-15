#!/usr/bin/env python3
"""Synthetic swipes with real sentence context, in swipe-replay's input format.

usage: synth.py <corpus.txt> <lang> <n> <out.jsonl> [--seed 1] [--min-len 2] [--cased]
                [--layout qwerty|qwertz|qwertz-de|qwerty-es]
       synth.py --words <targets.tsv> <lang> <n per line> <out.jsonl> [--seed 1] [--layout ...]

Picks random sentences from the corpus (one per line or plain text), a random word of length
>= min-len in each, and writes the swipe for that word together with the up to 3 words before
it. The path follows the bench-swipe generator (internal research): key centres of the base
letters with Gaussian aim error (sigma 0.18 key widths), mild corner rounding, variable speed
and 60-120 Hz sampling. Coordinates are normalized like the keyboard does it: x by keyboard
width, letter rows at y = 1/6, 1/2, 5/6.

With --words, the first argument is a TSV of fixed targets instead of a corpus: the target word,
then optionally the words before it (space separated; empty = start of a sentence) and a group name
for the "lang" field (default <lang>). Every line yields <n> gestures with different noise.
Apostrophes and hyphens are skipped in the path, like in the key paths ("I'll" is swiped i-l-l).
"""
import argparse
import json
import math
import random
import re
import unicodedata

# rows, the offset of every row in key widths and the number of key widths across
ROWS = {
    "qwerty": (["qwertyuiop", "asdfghjkl", "zxcvbnm"], [0.0, 0.5, 1.5], 10),
    "qwertz": (["qwertzuiop", "asdfghjkl", "yxcvbnm"], [0.0, 0.5, 1.5], 10),
    # Gboard German (QWERTZ with ü ö ä keys) and Spanish (QWERTY with ñ)
    "qwertz-de": (["qwertzuiopü", "asdfghjklöä", "yxcvbnm"], [0.0, 0.0, 2.0], 11),
    "qwerty-es": (["qwertyuiop", "asdfghjklñ", "zxcvbnm"], [0.0, 0.0, 1.5], 10),
}
KEYS_ACROSS = 10


def layout(name):
    global KEYS_ACROSS
    rows, offsets, KEYS_ACROSS = ROWS[name]
    keys = {}
    for r, row in enumerate(rows):
        for i, c in enumerate(row):
            keys[c] = ((offsets[r] + i + 0.5) / KEYS_ACROSS, (2 * r + 1) / 6.0)
    return keys


def base(word, keys=None):
    """Keys the swipe passes: letters with diacritics on their own key when the layout has it
    (ñ, ü), otherwise their base letter; ß as s (s-s is one stop)."""
    out = []
    for ch in word.lower().replace("ß", "ss"):
        if ch in "'’-":
            continue
        b = ch if keys and ch in keys else unicodedata.normalize("NFD", ch)[0]
        if not ("a" <= b <= "z") and not (keys and b in keys):
            return None
        if not out or out[-1] != b:
            out.append(b)
    return out or None


def chaikin(pts, r=0.12):
    if len(pts) < 3:
        return pts
    new = [pts[0]]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        new.append((x0 + r * (x1 - x0), y0 + r * (y1 - y0)))
        new.append((x1 - r * (x1 - x0), y1 - r * (y1 - y0)))
    new.append(pts[-1])
    return new


def gesture(word, keys, rnd):
    letters = base(word, keys)
    kw = 1.0 / KEYS_ACROSS  # key width in normalized units
    centers = [(keys[c][0] + rnd.gauss(0, 0.18 * kw), keys[c][1] + rnd.gauss(0, 0.18 * kw * 1.6)) for c in letters]
    if len(centers) == 1:
        x, y = centers[0]
        centers = [(x, y), (x + 0.01, y + 0.005)]
    pts = chaikin(centers)
    speed = rnd.uniform(0.9, 1.8) / 1000.0  # keyboard widths per ms
    hz = rnd.uniform(60, 120)
    step = speed * 1000.0 / hz
    xs, ys, ts = [], [], []
    t = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        d = math.dist((x0, y0), (x1, y1))
        n = max(1, int(d / step))
        for k in range(n):
            f = k / n
            xs.append(x0 + f * (x1 - x0) + rnd.gauss(0, 0.002))
            ys.append(y0 + f * (y1 - y0) + rnd.gauss(0, 0.003))
            ts.append(t)
            t += 1000.0 / hz * rnd.uniform(0.85, 1.15)
    xs.append(pts[-1][0]); ys.append(pts[-1][1]); ts.append(t)
    return xs, ys, ts


WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)?", re.UNICODE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus")
    ap.add_argument("lang")
    ap.add_argument("n", type=int)
    ap.add_argument("out")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--layout", default="qwerty")
    ap.add_argument("--min-len", type=int, default=2)
    ap.add_argument("--max-lines", type=int, default=400000)
    ap.add_argument("--require", default="", help="keep only lines containing one of these letters")
    ap.add_argument("--forbid", default="", help="drop lines containing any of these letters")
    ap.add_argument("--words", action="store_true", help="the first argument is a TSV of fixed targets")
    ap.add_argument("--cased", action="store_true",
                    help="also capitalized words inside a sentence (German nouns), not only lowercase ones")
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    keys = layout(a.layout)
    if a.words:
        lay = [{"c": c, "x": x, "y": y} for c, (x, y) in sorted(keys.items())]
        with open(a.corpus, encoding="utf-8") as f, open(a.out, "w") as o:
            for line in f:
                if not line.strip() or line.startswith("#"):
                    continue
                cols = line.rstrip("\n").split("\t")
                context = cols[1].split() if len(cols) > 1 else []
                group = cols[2] if len(cols) > 2 and cols[2] else a.lang
                for _ in range(a.n):
                    xs, ys, ts = gesture(cols[0], keys, rnd)
                    o.write(json.dumps({"target": cols[0], "lang": group, "context": context[-3:],
                                        "layout": lay, "x": xs, "y": ys, "t": ts}, ensure_ascii=False) + "\n")
        return
    lines = []
    with open(a.corpus, encoding="utf-8", errors="ignore") as f:
        for i, line in enumerate(f):
            if i >= a.max_lines:
                break
            if len(line) <= 20:
                continue
            if a.require and not any(c in line for c in a.require):
                continue
            if a.forbid and any(c in line for c in a.forbid):
                continue
            lines.append(line)
    lay = [{"c": c, "x": x, "y": y} for c, (x, y) in sorted(keys.items())]
    written = 0
    with open(a.out, "w") as o:
        while written < a.n:
            words = WORD.findall(rnd.choice(lines))
            # plain words only, so that earlier evaluation sets stay reproducible
            idx = [i for i, w in enumerate(words) if len(w) >= a.min_len and base(w, keys) and w.isalpha()
                   and (w.lower() == w or a.cased and i > 0 and w[0].isupper() and w[1:].islower())]
            if not idx:
                continue
            i = rnd.choice(idx)
            xs, ys, ts = gesture(words[i], keys, rnd)
            o.write(json.dumps({"target": words[i], "lang": a.lang, "context": [w for w in words[max(0, i - 3):i]],
                                "layout": lay, "x": xs, "y": ys, "t": ts}, ensure_ascii=False) + "\n")
            written += 1


main()
