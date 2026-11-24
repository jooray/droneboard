"""Train a word-level keyboard LM with MLX.

usage: train.py --work DIR --langs sk[,en,...] --model striped256 --steps N --out NAME
                [--split train|ptrain] [--init ckpt.safetensors] [--lr 2e-3] [--batch 64] [--ctx 64]
                [--data .big] [--mix_open 0.5] [--open_data .big]
                [--self_norm 0.035] [--sn_start 0.5]
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
p.add_argument("--data", default="", help="optional extra infix for train split, e.g. .big -> sk.big.train.u16")
p.add_argument("--mix_open", type=float, default=0.0, help="fraction of open train data mixed in during ptrain FT")
p.add_argument("--open_data", default="", help="infix for mixed open data during ptrain FT, e.g. .big")
p.add_argument("--self_norm", type=float, default=0.0, help="target weight for self-normalization loss (logZ - c)^2")
p.add_argument("--sn_start", type=float, default=0.5, help="fraction of steps before ramping self_norm from 0 -> target")
p.add_argument("--sn_target", type=float, default=0.0, help="target logZ constant during self-normalization")
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
data_infix = args.data if args.split == "train" else ""
data = {l: np.memmap(work / "ids" / f"{l}{args.suffix}{data_infix}.{args.split}.u16", dtype=np.uint16, mode="r") for l in langs}
open_data = (
    {l: np.memmap(work / "ids" / f"{l}{args.suffix}{args.open_data}.train.u16", dtype=np.uint16, mode="r") for l in langs}
    if args.mix_open > 0.0
    else None
)
test = {l: np.memmap(work / "ids" / f"{l}{args.suffix}.{'ptest' if args.split == 'ptrain' else 'test'}.u16",
                     dtype=np.uint16, mode="r") for l in langs}
sizes = np.array([len(data[l]) for l in langs], dtype=np.float64)
probs = np.sqrt(sizes) / np.sqrt(sizes).sum()  # temperature-sampled language mix
rng = np.random.default_rng(0)


def sample_windows(src, n, T):
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
    return np.concatenate(xs)


def batch(src, n, T, mix_open=0.0):
    if mix_open > 0.0 and open_data is not None:
        n_open = int(round(n * mix_open))
        n_main = max(1, n - n_open)
        w = np.concatenate([sample_windows(src, n_main, T), sample_windows(open_data, n_open, T)])
    else:
        w = sample_windows(src, n, T)
    # left-pad 25% of windows (a keyboard often has less than ctx tokens of history)
    pad = rng.random(len(w)) < 0.25
    cut = rng.integers(1, T, size=len(w))
    w[pad] = np.where(np.arange(T + 1)[None, :] < cut[pad][:, None], 0, w[pad])
    return mx.array(w[:, :-1]), mx.array(w[:, 1:])


model = build(args.model, V, tokens=tokens, ctx=args.ctx)
if args.init:
    model.load_weights(args.init)
mx.eval(model.parameters())
export_p = n_params(model) - (model.morph_emb.weight.size if getattr(model, "use_morph", False) else 0)
print(json.dumps({"model": args.model, "langs": langs, "vocab": V, "ctx": args.ctx,
                  "params": n_params(model), "export_params": export_p, "self_norm": args.self_norm}), flush=True)


def ce_loss_fn(model, x, y):
    logits = model(x).astype(mx.float32)
    mask = (y != 0).astype(mx.float32)
    ce = nn.losses.cross_entropy(logits, y, reduction="none")
    return (ce * mask).sum() / mask.sum()


def train_loss_fn(model, x, y, sn_weight):
    logits = model(x).astype(mx.float32)
    mask = (y != 0).astype(mx.float32)
    denom = mask.sum()
    ce = (nn.losses.cross_entropy(logits, y, reduction="none") * mask).sum() / denom
    if args.self_norm > 0.0:
        lse = mx.logsumexp(logits, axis=-1)
        sn = (((lse - args.sn_target) ** 2) * mask).sum() / denom
        return ce + sn_weight * sn
    return ce


sched = optim.join_schedules(
    [optim.linear_schedule(1e-6, args.lr, args.warmup), optim.cosine_decay(args.lr, args.steps - args.warmup, args.lr * 0.05)],
    [args.warmup])
opt = optim.AdamW(learning_rate=sched, weight_decay=0.01)
state = [model.state, opt.state]


@partial(mx.compile, inputs=state, outputs=state)
def step(x, y, sn_w):
    loss, grads = nn.value_and_grad(model, train_loss_fn)(model, x, y, sn_w)
    grads, _ = optim.clip_grad_norm(grads, 1.0)
    opt.update(model, grads)
    return loss


def evaluate(n_batches=40):
    tot = 0.0
    lse_vals = []
    for _ in range(n_batches):
        x, y = batch(test, max(32, 4096 // args.ctx), args.ctx)
        logits = model(x).astype(mx.float32)
        mask = (y != 0).astype(mx.float32)
        ce = (nn.losses.cross_entropy(logits, y, reduction="none") * mask).sum() / mask.sum()
        tot += ce.item()
        if args.self_norm > 0.0:
            lse_vals.append(np.array(mx.logsumexp(logits[:, -1, :], axis=-1)))
    ev = tot / n_batches
    if lse_vals:
        cat = np.concatenate(lse_vals)
        return ev, float(cat.mean()), float(cat.std())
    return ev, 0.0, 0.0


def current_sn_weight(it):
    if args.self_norm <= 0.0:
        return 0.0
    if args.sn_start <= 0.0:
        return args.self_norm
    start_step = int(args.steps * args.sn_start)
    if it < start_step:
        return 0.0
    ramp = max(1, int(args.steps * 0.15))
    frac = min(1.0, (it - start_step) / ramp)
    return args.self_norm * frac


ckdir = work / "ckpt" / args.out
ckdir.mkdir(parents=True, exist_ok=True)
cfg_dict = {**vars(args), "vocab": V}
(ckdir / "config.json").write_text(json.dumps(cfg_dict))
(ckdir / "tokens.txt").write_text("\n".join(tokens) + "\n", encoding="utf-8")
t0 = time.time()
run = 0.0
best = math.inf
best_logz_mean = 0.0
best_logz_std = 0.0
for it in range(1, args.steps + 1):
    x, y = batch(data, args.batch, args.ctx, mix_open=args.mix_open)
    sn_w = mx.array(current_sn_weight(it), dtype=mx.float32)
    for attempt in range(5):
        try:
            loss = step(x, y, sn_w)
            mx.eval(state)
            break
        except RuntimeError as e:  # Metal watchdog ("Impacting Interactivity") on a busy display
            print(json.dumps({"step": it, "retry": attempt, "error": str(e)[:80]}), flush=True)
            time.sleep(2)
    else:
        raise SystemExit("repeated Metal failures")
    run = 0.98 * run + 0.02 * loss.item() if it > 1 else loss.item()
    if it % 500 == 0:
        tps = it * args.batch * args.ctx / (time.time() - t0)
        print(json.dumps({"step": it, "loss": round(run, 4), "sn_w": round(current_sn_weight(it), 4),
                          "tok_s": int(tps), "min": round((time.time() - t0) / 60, 1)}), flush=True)
    if it % args.eval_every == 0 or it == args.steps:
        ev, lz_m, lz_s = evaluate()
        print(json.dumps({"step": it, "eval_loss": round(ev, 4), "eval_ppl": round(math.exp(ev), 1),
                          "logz_mean": round(lz_m, 4), "logz_std": round(lz_s, 4)}), flush=True)
        model.save_weights(str(ckdir / "last.safetensors"))
        # Once self_norm ramp finishes (or if self_norm == 0), track best checkpoint
        sn_ready = args.self_norm == 0.0 or current_sn_weight(it) >= args.self_norm * 0.99 or it == args.steps
        if sn_ready and (ev < best or (args.self_norm > 0.0 and best_logz_std == 0.0)):
            best = ev
            best_logz_mean = lz_m
            best_logz_std = lz_s
            model.save_weights(str(ckdir / "best.safetensors"))
            cfg_dict["logz_mean"] = round(lz_m, 6)
            cfg_dict["logz_std"] = round(lz_s, 6)
            (ckdir / "config.json").write_text(json.dumps(cfg_dict))
print(json.dumps({"done": True, "best_eval_loss": round(best, 4), "logz_mean": round(best_logz_mean, 4),
                  "logz_std": round(best_logz_std, 4), "minutes": round((time.time() - t0) / 60, 1)}), flush=True)
