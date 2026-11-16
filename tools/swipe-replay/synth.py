#!/usr/bin/env python3
"""Synthetic swipes with real sentence context, in swipe-replay's input format.

usage: synth.py <corpus.txt> <lang> <n> <out.jsonl> [--seed 1] [--min-len 2] [--cased]
                [--layout qwerty|qwertz|qwertz-de|qwerty-es] [--sloppy 0..3]
       synth.py --words <targets.tsv> <lang> <n per line> <out.jsonl> [--seed 1] [--layout ...]
       synth.py --items <items.jsonl> <lang> 1 <out.jsonl> [--seed 1] [--layout ...] [--sloppy 0..3]

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

With --items, the first argument is a held-out item set of tools/lm/make_lm_eval_set.py (target,
lang, context, tokens, bos); every item gets one gesture and keeps its fields, so the replay can give
the neural language model the same context as the keyboard does.

--sloppy s (0 = the precise default above) swipes less carefully, like someone typing fast: the aim
error grows to (0.18 + 0.12 s) key widths, the whole word drifts off by up to 0.1 s key widths,
corners are cut harder (more and stronger rounding), letters between two others are passed by on
the way (pulled towards the straight line by up to 0.25 s, the "shortcut" through a key), and the
finger moves up to (1 + 0.4 s) times faster with more jitter. s = 1, 2, 3 are the sloppy levels of
the evaluation.
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


def gesture(word, keys, rnd, sloppy=0.0):
    letters = base(word, keys)
    kw = 1.0 / KEYS_ACROSS  # key width in normalized units
    sigma = (0.18 + 0.12 * sloppy) * kw
    centers = [(keys[c][0] + rnd.gauss(0, sigma), keys[c][1] + rnd.gauss(0, sigma * 1.6)) for c in letters]
    if sloppy > 0:
        # the whole word a bit off, and inner letters passed by on the way to the next one
        dx, dy = rnd.gauss(0, 0.1 * sloppy * kw), rnd.gauss(0, 0.1 * sloppy * kw * 1.6)
        centers = [(x + dx, y + dy) for x, y in centers]
        cut = []
        for i, (x, y) in enumerate(centers):
            if 0 < i < len(centers) - 1:
                (ax, ay), (bx, by) = centers[i - 1], centers[i + 1]
                u = min(0.6, rnd.uniform(0, 0.25 * sloppy))
                x, y = x + u * ((ax + bx) / 2 - x), y + u * ((ay + by) / 2 - y)
            cut.append((x, y))
        centers = cut
    if len(centers) == 1:
        x, y = centers[0]
        centers = [(x, y), (x + 0.01, y + 0.005)]
    if sloppy > 0:
        pts = centers
        for _ in range(1 + int(sloppy >= 2)):
            pts = chaikin(pts, min(0.3, 0.12 + 0.06 * sloppy))
    else:
        pts = chaikin(centers)
    speed = rnd.uniform(0.9, 1.8 * (1 + 0.4 * sloppy)) / 1000.0  # keyboard widths per ms
    hz = rnd.uniform(60, 120)
    step = speed * 1000.0 / hz
    jx, jy = 0.002 * (1 + 0.5 * sloppy), 0.003 * (1 + 0.5 * sloppy)
    xs, ys, ts = [], [], []
    t = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        d = math.dist((x0, y0), (x1, y1))
        n = max(1, int(d / step))
        for k in range(n):
            f = k / n
            xs.append(x0 + f * (x1 - x0) + rnd.gauss(0, jx))
            ys.append(y0 + f * (y1 - y0) + rnd.gauss(0, jy))
            ts.append(t)
            t += 1000.0 / hz * rnd.uniform(0.85, 1.15)
    xs.append(pts[-1][0]); ys.append(pts[-1][1]); ts.append(t)
    return xs, ys, ts


WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)?", re.UNICODE)
# the neural language model's tokens (tools/lm/prepare.py)
TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|\d+(?:[.,]\d+)*|[.,!?:;]")


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
    ap.add_argument("--items", action="store_true", help="the first argument is a make_lm_eval_set.py item set")
    ap.add_argument("--tokens", action="store_true",
                    help="corpus mode: also write the neural model's tokens before the word (and bos)")
    ap.add_argument("--sloppy", type=float, default=0.0, help="0 = precise; 1-3 = less and less careful")
    ap.add_argument("--cased", action="store_true",
                    help="also capitalized words inside a sentence (German nouns), not only lowercase ones")
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    keys = layout(a.layout)
    if a.items:
        lay = [{"c": c, "x": x, "y": y} for c, (x, y) in sorted(keys.items())]
        with open(a.corpus, encoding="utf-8") as f, open(a.out, "w") as o:
            for line in f:
                it = json.loads(line)
                if not base(it["target"], keys):
                    continue
                xs, ys, ts = gesture(it["target"], keys, rnd, a.sloppy)
                it.update({"layout": lay, "x": xs, "y": ys, "t": ts})
                o.write(json.dumps(it, ensure_ascii=False) + "\n")
        return
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
        while written < a.n and a.tokens:
            # the tokens before the word as the keyboard gives them to the neural model, and the n-gram
            # context words of the same sentence, like tools/lm/make_lm_eval_set.py (but any word may be
            # the target, also one the neural vocabulary lacks)
            toks = TOKEN.findall(rnd.choice(lines))
            idx = [i for i, w in enumerate(toks) if len(w) >= a.min_len and w.isalpha() and base(w, keys)
                   and (w.lower() == w or a.cased and i > 0 and w[0].isupper() and w[1:].islower())]
            if not idx:
                continue
            i = rnd.choice(idx)
            norm = ["<num>" if t[0].isdigit() else t.lower().replace("’", "'") for t in toks[:i]]
            context = []
            for t in reversed(toks[:i]):
                if t in ".!?" or len(context) == 3:
                    break
                if t[0].isalpha():
                    context.insert(0, t)
                elif t != ",":
                    break
            xs, ys, ts = gesture(toks[i], keys, rnd, a.sloppy)
            o.write(json.dumps({"target": toks[i], "lang": a.lang, "context": context, "tokens": norm[-31:],
                                "bos": i <= 31, "layout": lay, "x": xs, "y": ys, "t": ts}, ensure_ascii=False) + "\n")
            written += 1
        while written < a.n:
            words = WORD.findall(rnd.choice(lines))
            # plain words only, so that earlier evaluation sets stay reproducible
            idx = [i for i, w in enumerate(words) if len(w) >= a.min_len and base(w, keys) and w.isalpha()
                   and (w.lower() == w or a.cased and i > 0 and w[0].isupper() and w[1:].islower())]
            if not idx:
                continue
            i = rnd.choice(idx)
            xs, ys, ts = gesture(words[i], keys, rnd, a.sloppy)
            o.write(json.dumps({"target": words[i], "lang": a.lang, "context": [w for w in words[max(0, i - 3):i]],
                                "layout": lay, "x": xs, "y": ys, "t": ts}, ensure_ascii=False) + "\n")
            written += 1


main()
