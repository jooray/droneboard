#!/usr/bin/env python3
"""Generates human-like swipe gestures for Inj (tools/swipe-inject/Inj.java).

usage: gen.py --keys keys.json --out g.txt [--speed 1.3] [--noise 6] [--seed 1] word [word ...]

keys.json maps letters to screen centers: {"q": [54, 1715], ...}; build it with --grid instead:
  --grid "qwertyuiop:54,1715 asdfghjkl:108,1875 zxcvbnm:216,2035" --pitch 108
The path is a Catmull-Rom curve through the key centers of the word's base letters
(diacritics removed), sampled every 8 ms at --speed px/ms, with gaussian jitter.
Words are separated by a 700 ms pause.
"""
import argparse
import json
import math
import random
import unicodedata


def base_letters(word):
    out = []
    for ch in word.lower():
        b = unicodedata.normalize("NFD", ch)[0]
        if "a" <= b <= "z":
            if not out or out[-1] != b:
                out.append(b)
    return out


def catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return tuple(0.5 * ((2 * p1[i]) + (-p0[i] + p2[i]) * t + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2
                        + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3) for i in range(2))


def path(points, step_px):
    if len(points) == 1:
        x, y = points[0]
        return [(x, y), (x + 1, y), (x + 2, y)]
    pts = [points[0]] + points + [points[-1]]
    dense = []
    for i in range(1, len(pts) - 2):
        for k in range(20):
            dense.append(catmull(pts[i - 1], pts[i], pts[i + 1], pts[i + 2], k / 20))
    dense.append(points[-1])
    out = [dense[0]]
    acc = 0.0
    for a, b in zip(dense, dense[1:]):
        acc += math.dist(a, b)
        if acc >= step_px:
            out.append(b)
            acc = 0.0
    if out[-1] != dense[-1]:
        out.append(dense[-1])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keys")
    ap.add_argument("--grid")
    ap.add_argument("--pitch", type=float, default=108)
    ap.add_argument("--out", required=True)
    ap.add_argument("--speed", type=float, default=1.3, help="px per ms")
    ap.add_argument("--noise", type=float, default=6)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--pause", type=int, default=700)
    ap.add_argument("words", nargs="+")
    a = ap.parse_args()
    keys = {}
    if a.keys:
        keys = {k: tuple(v) for k, v in json.load(open(a.keys)).items()}
    if a.grid:
        for row in a.grid.split():
            letters, start = row.split(":")
            x0, y0 = map(float, start.split(","))
            for i, c in enumerate(letters):
                keys[c] = (x0 + i * a.pitch, y0)
    rnd = random.Random(a.seed)
    with open(a.out, "w") as f:
        for w in a.words:
            centers = [keys[c] for c in base_letters(w)]
            # fingers land a bit low and do not hit centers exactly
            centers = [(x + rnd.gauss(0, a.noise * 2), y + 8 + rnd.gauss(0, a.noise * 2)) for x, y in centers]
            pts = path(centers, a.speed * 8)
            pts = [(x + rnd.gauss(0, a.noise / 2), y + rnd.gauss(0, a.noise / 2)) for x, y in pts]
            f.write(f"D {pts[0][0]:.1f} {pts[0][1]:.1f}\n")
            for x, y in pts[1:-1]:
                f.write(f"M {x:.1f} {y:.1f}\n")
            f.write(f"U {pts[-1][0]:.1f} {pts[-1][1]:.1f}\n")
            f.write(f"W {a.pause}\n")


main()
