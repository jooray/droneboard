#!/usr/bin/env python3
"""Noisy tap typing for TapPredictionEval.typos: for every item of items-<lang>.jsonl the target word typed with
touch points scattered around the QWERTY key centres (the geometry of TapPredictionEval), in four conditions:
s25, s35, s45 (Gaussian sigma in key widths, the same fraction of key height) and struct (exact keys, 1 word in 12 gets
one transposition, omission or doubling). A touch lands on the key under it (nearest key outside the grid), that key's
letter is typed, the real coordinates go to the dictionary. Accented letters are typed as their base letter.

  make_tap_typos.py items-sk.jsonl out-prefix   ->  out-prefix-<cond>.jsonl, one line per item:
  {"typed": "...", "pts": [[x, y], ...], "wrong": bool, "skip": bool}
"""
import json, random, sys, unicodedata

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
KW, KH = 100, 150
OFF = [0, 50, 150]
CENTER = {}
for r, row in enumerate(ROWS):
    for i, c in enumerate(row):
        CENTER[c] = (OFF[r] + i * KW + KW / 2, r * KH + KH / 2)


def fold(word):
    out = []
    for ch in unicodedata.normalize("NFD", word.lower()):
        if unicodedata.category(ch) != "Mn":
            out.append(ch)
    return "".join(out).replace("ß", "ss")


def key_at(x, y):
    best, bd = None, 1e18
    for c, (cx, cy) in CENTER.items():
        dx = max(0.0, abs(x - cx) - KW / 2)
        dy = max(0.0, abs(y - cy) - KH / 2)
        d = dx * dx + dy * dy
        if d < bd or (d == bd and abs(x - cx) < abs(x - CENTER[best][0])):
            best, bd = c, d
    return best


def main():
    items = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    prefix = sys.argv[2]
    for cond, sigma in (("s25", 0.25), ("s35", 0.35), ("s45", 0.45), ("struct", 0.0)):
        rng = random.Random(20261117 + int(sigma * 100))
        letters_total = letters_wrong = 0
        with open("%s-%s.jsonl" % (prefix, cond), "w") as out:
            for it in items:
                base = fold(it["target"])
                if not base or any(c not in CENTER for c in base):
                    out.write(json.dumps({"typed": "", "pts": [], "wrong": False, "skip": True}) + "\n")
                    continue
                typed = list(base)
                if cond == "struct":
                    if rng.random() < 1 / 12:
                        ops = ["double"]
                        if len(base) >= 2:
                            ops.append("omit")
                            if any(base[i] != base[i + 1] for i in range(len(base) - 1)):
                                ops.append("swap")
                        op = rng.choice(ops)
                        if op == "double":
                            i = rng.randrange(len(base)); typed.insert(i, typed[i])
                        elif op == "omit":
                            del typed[rng.randrange(len(base))]
                        else:
                            i = rng.choice([i for i in range(len(base) - 1) if base[i] != base[i + 1]])
                            typed[i], typed[i + 1] = typed[i + 1], typed[i]
                    pts = [list(CENTER[c]) for c in typed]
                else:
                    pts = []
                    for i, c in enumerate(base):
                        cx, cy = CENTER[c]
                        x = rng.gauss(cx, sigma * KW); y = rng.gauss(cy, sigma * KH)
                        typed[i] = key_at(x, y)
                        pts.append([round(x, 1), round(y, 1)])
                    letters_total += len(base); letters_wrong += sum(1 for a, b in zip(typed, base) if a != b)
                t = "".join(typed)
                out.write(json.dumps({"typed": t, "pts": pts, "wrong": t != base, "skip": False}) + "\n")
        if cond != "struct":
            print("%s %s: %.1f %% of letters land on another key" % (prefix, cond, 100.0 * letters_wrong / max(letters_total, 1)))


main()
