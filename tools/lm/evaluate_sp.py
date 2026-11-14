"""Keyboard metrics for a SentencePiece (subword) LM: words are produced by a small beam
search over pieces, so the model can also offer words outside any fixed word list.

usage: evaluate_sp.py --work DIR --lang sk --split test|ptest --model ckpt_dir [--n 4000]
Same metrics as evaluate.py; a target word is scored as found if it is among the 3 best
complete words (prefix-constrained for the completion metrics).
"""
import argparse
import json
import math
import sys
import time
from pathlib import Path

import mlx.core as mx
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from models import build  # noqa: E402

WS = "▁"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--split", default="test")
    ap.add_argument("--model", required=True)
    ap.add_argument("--n", type=int, default=4000)
    ap.add_argument("--beam", type=int, default=12)
    ap.add_argument("--max_pieces", type=int, default=4)
    a = ap.parse_args()
    work, ck = Path(a.work), Path(a.model)
    cfg = json.loads((ck / "config.json").read_text())
    pieces = (ck / "tokens.txt").read_text(encoding="utf-8").split("\n")[:-1]
    model = build(cfg["model"], len(pieces))
    model.load_weights(str(ck / "best.safetensors"))
    ctx = cfg["ctx"]
    V = len(pieces)
    starts = np.array([p.startswith(WS) for p in pieces])
    text = [p[1:] if p.startswith(WS) else p for p in pieces]
    is_alpha_start = np.array([s and t.isalpha() for s, t in zip(starts, text)])
    boundary = starts.copy()
    boundary[[i for i, p in enumerate(pieces) if p in (".", ",", "!", "?", ":", ";")]] = True
    boundary[0] = boundary[2] = True

    stream = np.memmap(work / "ids" / f"{a.lang}.sp.{a.split}.u16", dtype=np.uint16, mode="r").astype(np.int64)
    # word starts: alphabetic word-start pieces; the word continues while pieces are not starts
    cand = np.flatnonzero(is_alpha_start[np.minimum(stream, V - 1)])
    cand = cand[cand >= ctx]
    rng = np.random.default_rng(0)
    pos = np.sort(rng.choice(cand, size=min(a.n, len(cand)), replace=False))

    def logp(hists):
        x = mx.array(np.stack([h[-ctx:] for h in hists]).astype(np.int32))
        lg = model(x)[:, -1, :]
        return np.array(lg - mx.logsumexp(lg, axis=-1, keepdims=True))

    def top_words(hist, prefix):
        """3 best complete words for the history, consistent with the typed prefix."""
        beams = [((), 0.0)]
        done = {}
        for depth in range(a.max_pieces):
            if not beams:
                break
            lps = logp([np.concatenate([hist, np.array(b, dtype=np.int64)]) for b, _ in beams])
            nxt = []
            for (b, s), lp in zip(beams, lps):
                word = "".join(text[i] for i in b)
                if b:  # finishing here: next piece is a boundary
                    fin = s + float(np.logaddexp.reduce(lp[boundary]))
                    if word.startswith(prefix) and fin > done.get(word, -math.inf):
                        done[word] = fin
                allowed = is_alpha_start if not b else ~boundary
                cand_ids = np.flatnonzero(allowed)
                if prefix:
                    ok = [i for i in cand_ids if (word + text[i]).startswith(prefix) or prefix.startswith(word + text[i])]
                    cand_ids = np.array(ok, dtype=np.int64)
                if len(cand_ids) == 0:
                    continue
                k = min(a.beam, len(cand_ids))
                best = cand_ids[np.argpartition(-lp[cand_ids], k - 1)[:k]]
                nxt += [(b + (int(i),), s + float(lp[i])) for i in best]
            nxt.sort(key=lambda t: -t[1])
            beams = nxt[:a.beam]
        return [w for w, _ in sorted(done.items(), key=lambda t: -t[1])[:3]]

    n = top1 = top3 = 0
    pre = {1: [0, 0], 2: [0, 0]}
    saved = chars = 0
    t0 = time.time()
    for p in pos:
        j = p + 1
        while j < len(stream) and not boundary[stream[j]] and stream[j] < V:
            j += 1
        target = "".join(text[i] for i in stream[p:j])
        hist = stream[p - ctx:p]
        L = len(target)
        n += 1
        chars += L + 1
        words = top_words(hist, "")
        top1 += bool(words) and words[0] == target
        hit0 = target in words
        top3 += hit0
        s = L if hit0 else 0
        for k in (1, 2, 3):
            if L <= k:
                break
            hit = target in top_words(hist, target[:k])
            if k in pre:
                pre[k][0] += hit
                pre[k][1] += 1
            if hit and not s:
                s = L - k
            if s and k >= 2:
                break
        saved += s
    print(json.dumps({"lang": a.lang, "split": a.split, "model": a.model, "n": n, "oov": 0.0,
                      "top1": round(top1 / n, 4), "top3": round(top3 / n, 4),
                      "pre1_top3": round(pre[1][0] / max(pre[1][1], 1), 4),
                      "pre2_top3": round(pre[2][0] / max(pre[2][1], 1), 4),
                      "ppl_in_vocab": None, "keystroke_savings": round(saved / chars, 4),
                      "eval_s": round(time.time() - t0, 1)}), flush=True)


if __name__ == "__main__":
    main()
