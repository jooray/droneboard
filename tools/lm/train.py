"""Train a word-level keyboard LM with MLX.

usage: train.py --work DIR --langs sk[,en,...] --model gpt384 --steps N --out NAME
                [--split train|ptrain] [--init ckpt.npz] [--lr 2e-3] [--batch 256] [--ctx 32]
One language: the per-language vocabulary. Several languages: a union vocabulary with a
language-tag token (<lang:xx>) in place of <bos>; masks restrict output at inference.
"""
import argparse
import json
import math
import time
from functools import partial
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
import numpy as np
from mlx.utils import tree_flatten

from models import build, n_params

p = argparse.ArgumentParser()
p.add_argument("--work", required=True)
p.add_argument("--langs", required=True)
p.add_argument("--model", required=True)
p.add_argument("--steps", type=int, required=True)
p.add_argument("--out", required=True)
p.add_argument("--split", default="train")
p.add_argument("--init")
p.add_argument("--lr", type=float, default=2e-3)
p.add_argument("--batch", type=int, default=128)
p.add_argument("--ctx", type=int, default=32)
p.add_argument("--warmup", type=int, default=500)
p.add_argument("--eval_every", type=int, default=2000)
p.add_argument("--suffix", default="", help=".sp for the SentencePiece variant")
args = p.parse_args()
work = Path(args.work)
langs = args.langs.split(",")


def load_vocab(lang):
    return (work / "vocab" / f"{lang}{args.suffix}.txt").read_text(encoding="utf-8").split("\n")[:-1]


def union_vocab(langs):
    """Union of per-language vocabularies; returns tokens and local->union maps."""
    if len(langs) == 1:
        v = load_vocab(langs[0])
        return v, {langs[0]: np.arange(len(v), dtype=np.int32)}
    tokens = ["<pad>", "<unk>", "<bos>"] + [f"<lang:{l}>" for l in langs]
    index = {t: i for i, t in enumerate(tokens)}
    maps = {}
    for l in langs:
        v = load_vocab(l)
        m = np.zeros(len(v), dtype=np.int32)
        for i, w in enumerate(v):
            if w not in index:
                index[w] = len(tokens)
                tokens.append(w)
            m[i] = index[w]
        m[2] = index[f"<lang:{l}>"]  # <bos> becomes the language tag
        maps[l] = m
    return tokens, maps


tokens, maps = union_vocab(langs)
V = len(tokens)
data = {l: np.memmap(work / "ids" / f"{l}{args.suffix}.{args.split}.u16", dtype=np.uint16, mode="r") for l in langs}
test = {l: np.memmap(work / "ids" / f"{l}{args.suffix}.{'ptest' if args.split == 'ptrain' else 'test'}.u16",
                     dtype=np.uint16, mode="r") for l in langs}
sizes = np.array([len(data[l]) for l in langs], dtype=np.float64)
probs = np.sqrt(sizes) / np.sqrt(sizes).sum()  # temperature-sampled language mix
rng = np.random.default_rng(0)


def batch(src, n, T):
    xs = []
    choice = rng.choice(len(langs), size=n, p=probs)
    for li in range(len(langs)):
        k = int((choice == li).sum())
        if not k:
            continue
        arr = src[langs[li]]
        starts = rng.integers(0, len(arr) - T - 1, size=k)
        w = np.asarray(arr[starts[:, None] + np.arange(T + 1)], dtype=np.int32)
        xs.append(maps[langs[li]][w])
    w = np.concatenate(xs)
    # left-pad 25% of windows (a keyboard often has less than ctx tokens of history)
    pad = rng.random(len(w)) < 0.25
    cut = rng.integers(1, T, size=len(w))
    w[pad] = np.where(np.arange(T + 1)[None, :] < cut[pad][:, None], 0, w[pad])
    return mx.array(w[:, :-1]), mx.array(w[:, 1:])


model = build(args.model, V)
if args.init:
    model.load_weights(args.init)
mx.eval(model.parameters())
print(json.dumps({"model": args.model, "langs": langs, "vocab": V, "params": n_params(model)}), flush=True)


def loss_fn(model, x, y):
    logits = model(x)
    mask = (y != 0).astype(mx.float32)
    ce = nn.losses.cross_entropy(logits, y, reduction="none")
    return (ce * mask).sum() / mask.sum()


sched = optim.join_schedules(
    [optim.linear_schedule(1e-6, args.lr, args.warmup), optim.cosine_decay(args.lr, args.steps - args.warmup, args.lr * 0.05)],
    [args.warmup])
opt = optim.AdamW(learning_rate=sched, weight_decay=0.01)
state = [model.state, opt.state]


@partial(mx.compile, inputs=state, outputs=state)
def step(x, y):
    loss, grads = nn.value_and_grad(model, loss_fn)(model, x, y)
    grads, _ = optim.clip_grad_norm(grads, 1.0)
    opt.update(model, grads)
    return loss


def evaluate(n_batches=40):
    tot = 0.0
    for _ in range(n_batches):
        x, y = batch(test, 128, args.ctx)
        tot += loss_fn(model, x, y).item()
    return tot / n_batches


ckdir = work / "ckpt" / args.out
ckdir.mkdir(parents=True, exist_ok=True)
(ckdir / "config.json").write_text(json.dumps({**vars(args), "vocab": V}))
(ckdir / "tokens.txt").write_text("\n".join(tokens) + "\n", encoding="utf-8")
t0 = time.time()
run = 0.0
best = math.inf
for it in range(1, args.steps + 1):
    x, y = batch(data, args.batch, args.ctx)
    for attempt in range(5):
        try:
            loss = step(x, y)
            mx.eval(state)
            break
        except RuntimeError as e:  # Metal watchdog ("Impacting Interactivity") on a busy display
            print(json.dumps({"step": it, "retry": attempt, "error": str(e)[:80]}), flush=True)
            time.sleep(2)
    else:
        raise SystemExit("repeated Metal failures")
    run = 0.98 * run + 0.02 * loss.item() if it > 1 else loss.item()
    if it % 200 == 0:
        tps = it * args.batch * args.ctx / (time.time() - t0)
        print(json.dumps({"step": it, "loss": round(run, 4), "tok_s": int(tps), "min": round((time.time() - t0) / 60, 1)}), flush=True)
    if it % args.eval_every == 0 or it == args.steps:
        ev = evaluate()
        print(json.dumps({"step": it, "eval_loss": round(ev, 4), "eval_ppl": round(math.exp(ev), 1)}), flush=True)
        model.save_weights(str(ckdir / "last.safetensors"))
        if ev < best:
            best = ev
            model.save_weights(str(ckdir / "best.safetensors"))
print(json.dumps({"done": True, "best_eval_loss": best, "minutes": round((time.time() - t0) / 60, 1)}), flush=True)
