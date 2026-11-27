#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Paired statistical comparison of two swipe-replay or lm-eval --out JSONL files.

usage: paired_eval.py baseline.jsonl candidate.jsonl [--boot 5000] [--seed 0]

Reports wins/losses/ties, exact two-sided McNemar binomial p-value, and paired bootstrap
95% confidence intervals for delta top-1, top-3, and (for lm-eval) keystroke savings (KSS).
"""
import argparse
import json
import math
import random
import sys
from collections import defaultdict

try:
    import numpy as np
except ImportError:
    np = None


def mcnemar_exact_p(wins: int, losses: int) -> float:
    n = wins + losses
    if n == 0:
        return 1.0
    k = min(wins, losses)
    # Exact two-sided binomial test at p = 0.5 using log-space comb for large n.
    log_half = -n * math.log(2.0)
    tail = sum(math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + log_half)
               for i in range(k + 1))
    return min(1.0, 2.0 * tail)


def _quantile(sorted_vals, q: float) -> float:
    pos = q * (len(sorted_vals) - 1)
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return sorted_vals[lo]
    return sorted_vals[lo] + (pos - lo) * (sorted_vals[hi] - sorted_vals[lo])


def bootstrap_ci_diff(a, b, n_boot: int, rng):
    if np is not None:
        diff = b - a
        n = len(diff)
        idx = rng.integers(0, n, size=(n_boot, n))
        means = diff[idx].mean(axis=1) * 100.0
        lo, hi = np.percentile(means, [2.5, 97.5])
        return float(diff.mean() * 100.0), float(lo), float(hi)
    diff = [y - x for x, y in zip(a, b)]
    n = len(diff)
    means = sorted(sum(rng.choices(diff, k=n)) / n * 100.0 for _ in range(min(n_boot, 2000)))
    point = sum(diff) / n * 100.0
    return point, _quantile(means, 0.025), _quantile(means, 0.975)


def bootstrap_ci_kss(sa, sb, ch, n_boot: int, rng):
    if np is not None:
        n = len(ch)
        idx = rng.integers(0, n, size=(n_boot, n))
        ch_sum = np.maximum(ch[idx].sum(axis=1), 1)
        kss_diff = (sb[idx].sum(axis=1) - sa[idx].sum(axis=1)) / ch_sum * 100.0
        lo, hi = np.percentile(kss_diff, [2.5, 97.5])
        point = float((sb.sum() - sa.sum()) / max(int(ch.sum()), 1) * 100.0)
        return point, float(lo), float(hi)
    pairs = [(b - a, c) for a, b, c in zip(sa, sb, ch)]
    n = len(pairs)
    vals = []
    for _ in range(min(n_boot, 2000)):
        ds = 0.0
        cs = 0.0
        for d, c in rng.choices(pairs, k=n):
            ds += d
            cs += c
        vals.append(ds / max(cs, 1.0) * 100.0)
    vals.sort()
    point = (sum(sb) - sum(sa)) / max(sum(ch), 1.0) * 100.0
    return point, _quantile(vals, 0.025), _quantile(vals, 0.975)


def report_group(name: str, items_a: list, items_b: list, is_lm: bool, n_boot: int, rng):
    n = len(items_a)
    if n == 0:
        return
    if is_lm:
        a1 = [float(x["top1"]) for x in items_a]
        b1 = [float(x["top1"]) for x in items_b]
        a3 = [float(x["top3"]) for x in items_a]
        b3 = [float(x["top3"]) for x in items_b]
        sa = [float(x["saved"]) for x in items_a]
        sb = [float(x["saved"]) for x in items_b]
        ch = [float(x["chars"]) for x in items_a]
    else:
        a1 = [1.0 if x["rank"] == 0 else 0.0 for x in items_a]
        b1 = [1.0 if x["rank"] == 0 else 0.0 for x in items_b]
        a3 = [1.0 if 0 <= x["rank"] < 3 else 0.0 for x in items_a]
        b3 = [1.0 if 0 <= x["rank"] < 3 else 0.0 for x in items_b]

    if np is not None:
        a1, b1, a3, b3 = np.array(a1), np.array(b1), np.array(a3), np.array(b3)
        if is_lm:
            sa, sb, ch = np.array(sa), np.array(sb), np.array(ch)
        w1 = int(((b1 == 1) & (a1 == 0)).sum())
        l1 = int(((b1 == 0) & (a1 == 1)).sum())
        w3 = int(((b3 == 1) & (a3 == 0)).sum())
        l3 = int(((b3 == 0) & (a3 == 1)).sum())
        m_a1, m_b1 = float(a1.mean()), float(b1.mean())
        m_a3, m_b3 = float(a3.mean()), float(b3.mean())
    else:
        w1 = sum(1 for x, y in zip(a1, b1) if y == 1.0 and x == 0.0)
        l1 = sum(1 for x, y in zip(a1, b1) if y == 0.0 and x == 1.0)
        w3 = sum(1 for x, y in zip(a3, b3) if y == 1.0 and x == 0.0)
        l3 = sum(1 for x, y in zip(a3, b3) if y == 0.0 and x == 1.0)
        m_a1, m_b1 = sum(a1) / n, sum(b1) / n
        m_a3, m_b3 = sum(a3) / n, sum(b3) / n

    p1 = mcnemar_exact_p(w1, l1)
    d1, lo1, hi1 = bootstrap_ci_diff(a1, b1, n_boot, rng)

    p3 = mcnemar_exact_p(w3, l3)
    d3, lo3, hi3 = bootstrap_ci_diff(a3, b3, n_boot, rng)

    line = (f"{name:<8s} n={n:5d} | top1 {m_a1*100:5.2f}->{m_b1*100:5.2f} "
            f"({d1:+5.2f} pp, 95% [{lo1:+.2f},{hi1:+.2f}], W/L={w1}/{l1}, p={p1:.4g}) | "
            f"top3 {m_a3*100:5.2f}->{m_b3*100:5.2f} "
            f"({d3:+5.2f} pp, W/L={w3}/{l3}, p={p3:.4g})")
    if is_lm:
        ka = float(sum(sa)) / max(float(sum(ch)), 1.0) * 100.0
        kb = float(sum(sb)) / max(float(sum(ch)), 1.0) * 100.0
        dk, lok, hik = bootstrap_ci_kss(sa, sb, ch, n_boot, rng)
        line += f" | KSS {ka:5.2f}->{kb:5.2f} ({dk:+5.2f} pp, 95% [{lok:+.2f},{hik:+.2f}])"
    print(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline")
    ap.add_argument("candidate")
    ap.add_argument("--boot", type=int, default=5000)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    a = [json.loads(line) for line in open(args.baseline, encoding="utf-8") if line.strip()]
    b = [json.loads(line) for line in open(args.candidate, encoding="utf-8") if line.strip()]
    if len(a) != len(b):
        sys.exit(f"item count mismatch: {len(a)} vs {len(b)}")
    for i, (xa, xb) in enumerate(zip(a, b)):
        if xa.get("target") != xb.get("target"):
            sys.exit(f"target mismatch at line {i + 1}: {xa.get('target')!r} vs {xb.get('target')!r}")
    if not a:
        sys.exit("empty input")

    is_lm = "saved" in a[0]
    rng = np.random.default_rng(args.seed) if np is not None else random.Random(args.seed)
    by_lang = defaultdict(lambda: ([], []))
    for xa, xb in zip(a, b):
        lang = xa.get("lang", "all")
        by_lang[lang][0].append(xa)
        by_lang[lang][1].append(xb)
    if len(by_lang) > 1:
        for lang in sorted(by_lang):
            report_group(lang, by_lang[lang][0], by_lang[lang][1], is_lm, args.boot, rng)
    report_group("ALL", a, b, is_lm, args.boot, rng)


if __name__ == "__main__":
    main()
