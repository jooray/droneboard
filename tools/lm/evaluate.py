"""Keyboard metrics for word-level LMs: next-word top-1/top-3, prefix completion after
1/2 typed letters, perplexity, and estimated keystroke savings.

usage: evaluate.py --work DIR --lang sk --split test|ptest --model ckpt_dir|kn2|kn3 [--n 20000]
"""
import argparse
import json
import math
import re
import time
from pathlib import Path

import numpy as np

WORD = re.compile(r"^[^\W\d_]+(?:'[^\W\d_]+)*$")


class Metrics:
    def __init__(self, tokens):
        self.tokens = tokens
        self.is_word = np.array([bool(WORD.match(t)) for t in tokens])
        self.prefix = {}
        for i, t in enumerate(tokens):
            if self.is_word[i]:
                for k in (1, 2, 3):
                    if len(t) > k:
                        self.prefix.setdefault(t[:k], []).append(i)
        self.prefix = {k: np.array(v) for k, v in self.prefix.items()}
        self.reset()

    def reset(self):
        self.n = 0
        self.unk = 0
        self.top1 = self.top3 = 0
        self.pre = {1: [0, 0], 2: [0, 0]}
        self.saved = self.chars = 0
        self.nll = 0.0
        self.nll_n = 0

    def add(self, logp, target):
        """logp: [V] log-probabilities, target: token id (word or <unk>)."""
        word = self.tokens[target] if target != 1 else None
        self.n += 1
        if target == 1:
            self.unk += 1
            self.chars += 7  # typical word + space, no saving possible
            return
        self.nll -= logp[target]
        self.nll_n += 1
        s = np.where(self.is_word, logp, -np.inf)
        top = np.argpartition(-s, 3)[:3]
        top = top[np.argsort(-s[top])]
        self.top1 += top[0] == target
        hit0 = target in top
        self.top3 += hit0
        L = len(word)
        self.chars += L + 1
        saved = L if hit0 else 0
        for k in (1, 2, 3):
            if L <= k:
                break
            ids = self.prefix.get(word[:k])
            if ids is None:
                break
            sub = ids[np.argsort(-s[ids])[:3]]
            hit = target in sub
            if k in self.pre:  # unconditional prefix-completion accuracy
                self.pre[k][0] += hit
                self.pre[k][1] += 1
            if hit and not saved:
                saved = L - k
        self.saved += saved

    def result(self):
        n = max(self.n, 1)
        return {
            "n": self.n,
            "oov": round(self.unk / n, 4),
            "top1": round(self.top1 / n, 4),
            "top3": round(self.top3 / n, 4),
            "pre1_top3": round(self.pre[1][0] / max(self.pre[1][1], 1), 4),
            "pre2_top3": round(self.pre[2][0] / max(self.pre[2][1], 1), 4),
            "ppl_in_vocab": round(math.exp(self.nll / max(self.nll_n, 1)), 1),
            "keystroke_savings": round(self.saved / max(self.chars, 1), 4),
        }


def positions(stream, tokens_is_word, n, ctx, seed=0):
    """Sample target positions whose target is a word or <unk>, with ctx tokens of history."""
    rng = np.random.default_rng(seed)
    cand = rng.integers(ctx, len(stream), size=n * 4)
    tgt = stream[cand]
    ok = tokens_is_word[np.minimum(tgt, len(tokens_is_word) - 1)] | (tgt == 1)
    return np.sort(cand[ok][:n])


def neural_scorer(ckdir):
    import mlx.core as mx
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    from models import build
    cfg = json.loads((ckdir / "config.json").read_text())
    tokens = (ckdir / "tokens.txt").read_text(encoding="utf-8").split("\n")[:-1]
    ctx = cfg["ctx"]
    model = build(cfg["model"], len(tokens), tokens=tokens, ctx=ctx)
    model.load_weights(str(ckdir / "best.safetensors"))
    # Pre-fold morphology embeddings once if present
    W = model.effective_emb_weight() if hasattr(model, "effective_emb_weight") else None
    if W is not None:
        mx.eval(W)

    def score(hist):
        x = mx.array(hist[:, -ctx:].astype(np.int32))
        if W is not None:
            lg = (model.hidden(x, W)[:, -1, :] @ W.T) + model.out_bias
        else:
            lg = model(x)[:, -1, :]
        lp = lg - mx.logsumexp(lg, axis=-1, keepdims=True)
        return np.array(lp, dtype=np.float32)

    return tokens, score, ctx


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--split", default="test")
    ap.add_argument("--model", required=True)
    ap.add_argument("--n", type=int, default=20000)
    ap.add_argument("--personal_weight", type=int, default=0,
                    help="KN only: add the personal training text this many times to the counts")
    a = ap.parse_args()
    work = Path(a.work)
    local_tokens = (work / "vocab" / f"{a.lang}.txt").read_text(encoding="utf-8").split("\n")[:-1]
    stream = np.memmap(work / "ids" / f"{a.lang}.{a.split}.u16", dtype=np.uint16, mode="r").astype(np.int64)
    t0 = time.time()
    if a.model.startswith("kn"):
        from kn import KN
        train = np.memmap(work / "ids" / f"{a.lang}.train.u16", dtype=np.uint16, mode="r")
        if a.personal_weight:
            pt = np.memmap(work / "ids" / f"{a.lang}.ptrain.u16", dtype=np.uint16, mode="r")
            train = np.concatenate([np.asarray(train)] + [np.asarray(pt)] * a.personal_weight)
        order = int(a.model[2])
        lm = KN(train, len(local_tokens), order=order, min_count=int(a.model[4:] or 1) if len(a.model) > 3 else 1)
        tokens, score, ctx = local_tokens, lm.logprobs, 2
        size_mb = lm.nbytes / 1e6
    else:
        tokens, score, ctx = neural_scorer(Path(a.model))
        size_mb = None
    # map the language's local ids into the model's token space (multilingual union vocab)
    if tokens is not local_tokens:
        idx = {t: i for i, t in enumerate(tokens)}
        lmap = np.array([idx.get(t, 1) for t in local_tokens], dtype=np.int64)
        lmap[2] = idx.get(f"<lang:{a.lang}>", 2)
        stream = lmap[stream]
        # restrict predictions to this language's vocabulary (what the keyboard enables)
        allowed = np.zeros(len(tokens), dtype=bool)
        allowed[lmap] = True
    else:
        allowed = None
    m = Metrics(tokens)
    if allowed is not None:
        m.is_word &= allowed
    win = max(ctx, 32)
    pos = positions(stream, m.is_word | (np.arange(len(tokens)) == 1), a.n, win)
    B = 128
    for i in range(0, len(pos), B):
        p = pos[i:i + B]
        hist = np.stack([stream[q - win:q] for q in p])
        lp = score(hist)
        for row, q in zip(lp, p):
            m.add(row, int(stream[q]))
    res = {"lang": a.lang, "split": a.split, "model": a.model + (f"+personal*{a.personal_weight}" if a.personal_weight else ""), **m.result(), "eval_s": round(time.time() - t0, 1)}
    if size_mb:
        res["table_mb"] = round(size_mb, 1)
    print(json.dumps(res), flush=True)


if __name__ == "__main__":
    main()
