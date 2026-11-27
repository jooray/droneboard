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
from mlx.utils import tree_flatten, tree_map, tree_unflatten
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
p.add_argument("--init_body", help="load only matching-shape tensors from ckpt (skip mismatched vocab/morph)")
p.add_argument("--lr", type=float, default=2e-3)
p.add_argument("--min_lr_ratio", type=float, default=0.05)
p.add_argument("--schedule", choices=["cosine", "wsd"], default="cosine")
p.add_argument("--decay_frac", type=float, default=0.20, help="for wsd: fraction of steps in final cosine decay")
p.add_argument("--batch", type=int, default=128)
p.add_argument("--grad_accum", type=int, default=1)
p.add_argument("--ctx", type=int, default=32)
p.add_argument("--warmup", type=int, default=500)
p.add_argument("--eval_every", type=int, default=2000)
p.add_argument("--eval_open", action="store_true", help="when split=ptrain, also evaluate open test split")
p.add_argument("--suffix", default="", help=".sp for the SentencePiece variant")
p.add_argument("--data", default="", help="optional extra infix for train split, e.g. .big -> sk.big.train.u16")
p.add_argument("--mix_open", type=float, default=0.0, help="fraction of open train data mixed in during ptrain FT")
p.add_argument("--open_data", default="", help="infix for mixed open data during ptrain FT, e.g. .big")
p.add_argument("--self_norm", type=float, default=0.0, help="target weight for self-normalization loss (logZ - c)^2")
p.add_argument("--sn_start", type=float, default=0.5, help="fraction of steps before ramping self_norm from 0 -> target")
p.add_argument("--sn_target", type=float, default=0.0, help="target logZ constant during self-normalization")
p.add_argument("--pad_prob", type=float, default=0.25, help="fraction of training windows left-padded with <pad>")
p.add_argument("--bos_prob", type=float, default=0.0, help="fraction of training windows aligned to start at <bos>")
p.add_argument("--wd", type=float, default=0.01, help="AdamW weight decay")
p.add_argument("--wd_2d_only", action="store_true", help="apply decoupled weight decay only to ndim >= 2 tensors")
p.add_argument("--ema", type=float, default=0.0, help="EMA weight decay per step (e.g. 0.999); 0 disables")
p.add_argument("--ema_every", type=int, default=10, help="update EMA every K optimizer steps")
p.add_argument("--qat", action="store_true", help="enable STE fake quantization matching neural_lm.cpp")
p.add_argument("--qat_int8_rows", type=int, default=50000, help="INT8 vocab rows for QAT; rest INT4-g32")
p.add_argument("--init_bias_freq", action="store_true", help="initialize out_bias from log unigram counts")
p.add_argument("--seed", type=int, default=0)
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
open_test = (
    {l: np.memmap(work / "ids" / f"{l}{args.suffix}.test.u16", dtype=np.uint16, mode="r") for l in langs}
    if args.eval_open and args.split == "ptrain"
    else None
)
sizes = np.array([len(data[l]) for l in langs], dtype=np.float64)
probs = np.sqrt(sizes) / np.sqrt(sizes).sum()  # temperature-sampled language mix
rng = np.random.default_rng(args.seed)
mx.random.seed(args.seed)


def sample_windows(src, n, T, r, bos_prob=0.0):
    xs = []
    choice = r.choice(len(langs), size=n, p=probs)
    for li in range(len(langs)):
        k = int((choice == li).sum())
        if not k:
            continue
        arr = src[langs[li]]
        n_tok = len(arr)
        max_start = max(1, n_tok - T - 1)
        if bos_prob > 0.0 and n_tok > T + 180:
            k_bos = int(round(k * bos_prob))
            k_rand = k - k_bos
            parts = []
            if k_rand > 0:
                s_rand = r.integers(0, max_start, size=k_rand)
                parts.append(np.asarray(arr[s_rand[:, None] + np.arange(T + 1)], dtype=np.int32))
            if k_bos > 0:
                scan = 160
                s_probe = r.integers(0, max(1, n_tok - T - scan - 1), size=k_bos)
                probe = np.asarray(arr[s_probe[:, None] + np.arange(scan)], dtype=np.uint16)
                is_bos = (probe == 2)
                off = is_bos.argmax(axis=1)
                found = is_bos.any(axis=1)
                s_bos = np.where(found, s_probe + off, s_probe)
                parts.append(np.asarray(arr[s_bos[:, None] + np.arange(T + 1)], dtype=np.int32))
            w = np.concatenate(parts, axis=0)
        else:
            starts = r.integers(0, max_start, size=k)
            w = np.asarray(arr[starts[:, None] + np.arange(T + 1)], dtype=np.int32)
        xs.append(maps[langs[li]][w])
    return np.concatenate(xs)


def make_batch(src, n, T, r, mix_open=0.0, pad_prob=0.0, bos_prob=0.0):
    if mix_open > 0.0 and open_data is not None:
        n_open = int(round(n * mix_open))
        n_main = max(1, n - n_open)
        w = np.concatenate([
            sample_windows(src, n_main, T, r, bos_prob=bos_prob),
            sample_windows(open_data, n_open, T, r, bos_prob=bos_prob),
        ])
    else:
        w = sample_windows(src, n, T, r, bos_prob=bos_prob)
    if pad_prob > 0.0:
        pad = r.random(len(w)) < pad_prob
        cut = r.integers(1, T, size=len(w))
        w[pad] = np.where(np.arange(T + 1)[None, :] < cut[pad][:, None], 0, w[pad])
    return mx.array(w[:, :-1]), mx.array(w[:, 1:])


model = build(args.model, V, tokens=tokens, ctx=args.ctx)
if args.init_bias_freq and not args.init and len(langs) == 1 and hasattr(model, "out_bias"):
    counts_path = work / "vocab" / f"{langs[0]}{args.suffix}.counts.npy"
    if counts_path.exists():
        c = np.load(counts_path).astype(np.float64)
        if len(c) == V:
            p_uni = (c + 1.0) / (c.sum() + V)
            lb = np.log(p_uni)
            lb -= lb.mean()
            lb[0] = -15.0  # <pad> never predicted
            model.out_bias = mx.array(lb.astype(np.float32))
if args.init:
    model.load_weights(args.init)
elif args.init_body:
    src_w = mx.load(args.init_body)
    cur = dict(tree_flatten(model.parameters()))
    loaded = []
    for k, v in src_w.items():
        if k in cur and cur[k].shape == v.shape and not k.startswith(("emb.", "morph_", "out_bias")):
            loaded.append((k, v))
    model.load_weights(loaded, strict=False)
    print(json.dumps({"init_body": args.init_body, "loaded_tensors": len(loaded)}), flush=True)

if args.qat and hasattr(model, "qat"):
    model.qat = True
    model.qat_int8_rows = args.qat_int8_rows

mx.eval(model.parameters())
export_p = n_params(model) - (model.morph_emb.weight.size if getattr(model, "use_morph", False) else 0)
print(json.dumps({"model": args.model, "langs": langs, "vocab": V, "ctx": args.ctx,
                  "params": n_params(model), "export_params": export_p, "self_norm": args.self_norm,
                  "grad_accum": args.grad_accum, "pad_prob": args.pad_prob, "bos_prob": args.bos_prob,
                  "schedule": args.schedule, "wd": args.wd, "wd_2d_only": args.wd_2d_only,
                  "ema": args.ema, "qat": args.qat, "qat_int8_rows": args.qat_int8_rows}), flush=True)


def train_loss_fn(model, x, y, sn_weight):
    logits = model(x).astype(mx.float32)
    mask = (y != 0).astype(mx.float32)
    denom = mx.maximum(mask.sum(), 1.0)
    ce = (nn.losses.cross_entropy(logits, y, reduction="none") * mask).sum() / denom
    if args.self_norm > 0.0:
        lse = mx.logsumexp(logits, axis=-1)
        sn = (((lse - args.sn_target) ** 2) * mask).sum() / denom
        return ce + sn_weight * sn
    return ce


min_lr = args.lr * args.min_lr_ratio
if args.schedule == "wsd":
    decay_steps = max(1, int(args.steps * args.decay_frac))
    stable_end = max(args.warmup + 1, args.steps - decay_steps)
    sched = optim.join_schedules(
        [
            optim.linear_schedule(1e-6, args.lr, max(1, args.warmup)),
            optim.linear_schedule(args.lr, args.lr, max(1, stable_end - args.warmup)),
            optim.cosine_decay(args.lr, max(1, args.steps - stable_end), min_lr),
        ],
        [args.warmup, stable_end],
    )
else:
    stable_end = None
    sched = optim.join_schedules(
        [
            optim.linear_schedule(1e-6, args.lr, max(1, args.warmup)),
            optim.cosine_decay(args.lr, max(1, args.steps - args.warmup), min_lr),
        ],
        [args.warmup],
    )

class AdamW2D(optim.AdamW):
    """AdamW that applies decoupled weight decay only to tensors with ndim >= 2."""

    def apply_single(self, gradient, parameter, state):
        if parameter.ndim < 2 and self.weight_decay != 0.0:
            return super(optim.AdamW, self).apply_single(gradient, parameter, state)
        return super().apply_single(gradient, parameter, state)


opt_cls = AdamW2D if args.wd_2d_only else optim.AdamW
opt = opt_cls(learning_rate=sched, weight_decay=args.wd)
state = [model.state, opt.state]

if args.grad_accum == 1:
    @partial(mx.compile, inputs=state, outputs=state)
    def step(x, y, sn_w):
        loss, grads = nn.value_and_grad(model, train_loss_fn)(model, x, y, sn_w)
        grads, _ = optim.clip_grad_norm(grads, 1.0)
        opt.update(model, grads)
        return loss
else:
    @partial(mx.compile, inputs=model.state, outputs=model.state)
    def grad_only(x, y, sn_w):
        return nn.value_and_grad(model, train_loss_fn)(model, x, y, sn_w)

    @partial(mx.compile, inputs=state, outputs=state)
    def opt_apply(grads):
        grads, _ = optim.clip_grad_norm(grads, 1.0)
        opt.update(model, grads)


def evaluate_src(src, n_batches=40, seed=12345):
    erng = np.random.default_rng(seed)
    tot = 0.0
    lse_vals = []
    bs = max(32, 4096 // args.ctx)
    for _ in range(n_batches):
        x, y = make_batch(src, bs, args.ctx, erng, pad_prob=0.0, bos_prob=0.0)
        logits = model(x).astype(mx.float32)
        mask = (y != 0).astype(mx.float32)
        ce = (nn.losses.cross_entropy(logits, y, reduction="none") * mask).sum() / mx.maximum(mask.sum(), 1.0)
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


def current_lr(it):
    if it < args.warmup:
        return 1e-6 + (args.lr - 1e-6) * (it / max(1, args.warmup))
    if args.schedule == "wsd" and stable_end is not None:
        if it < stable_end:
            return args.lr
        frac = min(1.0, (it - stable_end) / max(1, args.steps - stable_end))
        return min_lr + 0.5 * (args.lr - min_lr) * (1.0 + math.cos(math.pi * frac))
    frac = min(1.0, (it - args.warmup) / max(1, args.steps - args.warmup))
    return min_lr + 0.5 * (args.lr - min_lr) * (1.0 + math.cos(math.pi * frac))


ckdir = work / "ckpt" / args.out
ckdir.mkdir(parents=True, exist_ok=True)
cfg_dict = {**vars(args), "vocab": V}
(ckdir / "config.json").write_text(json.dumps(cfg_dict))
(ckdir / "tokens.txt").write_text("\n".join(tokens) + "\n", encoding="utf-8")

ema_params = None
if args.ema > 0.0:
    ema_params = {k: mx.array(v) for k, v in tree_flatten(model.parameters())}
    mx.eval(ema_params)
    ema_decay_eff = args.ema ** args.ema_every

t0 = time.time()
run = 0.0
best = math.inf
best_ema = math.inf
best_logz_mean = 0.0
best_logz_std = 0.0
saved_pre_decay = False

for it in range(1, args.steps + 1):
    sn_w = mx.array(current_sn_weight(it), dtype=mx.float32)
    for attempt in range(5):
        try:
            if args.grad_accum == 1:
                x, y = make_batch(data, args.batch, args.ctx, rng, mix_open=args.mix_open,
                                  pad_prob=args.pad_prob, bos_prob=args.bos_prob)
                loss = step(x, y, sn_w)
                mx.eval(state)
                loss_val = loss.item()
            else:
                acc_grads = None
                loss_sum = 0.0
                for _ in range(args.grad_accum):
                    x, y = make_batch(data, args.batch, args.ctx, rng, mix_open=args.mix_open,
                                      pad_prob=args.pad_prob, bos_prob=args.bos_prob)
                    l_mb, g_mb = grad_only(x, y, sn_w)
                    if acc_grads is None:
                        acc_grads = g_mb
                    else:
                        acc_grads = tree_map(lambda a, b: a + b, acc_grads, g_mb)
                    mx.eval(acc_grads, l_mb)
                    loss_sum += l_mb.item()
                inv = 1.0 / args.grad_accum
                acc_grads = tree_map(lambda g: g * inv, acc_grads)
                opt_apply(acc_grads)
                mx.eval(state)
                loss_val = loss_sum * inv
            break
        except RuntimeError as e:  # Metal watchdog ("Impacting Interactivity") on a busy display
            print(json.dumps({"step": it, "retry": attempt, "error": str(e)[:80]}), flush=True)
            time.sleep(2)
    else:
        raise SystemExit("repeated Metal failures")

    if ema_params is not None and (it % args.ema_every == 0 or it == args.steps):
        d_eff = min(ema_decay_eff, (1.0 + it) / (10.0 + it)) if it < 2000 else ema_decay_eff
        cur = dict(tree_flatten(model.parameters()))
        ema_params = {k: ema_params[k] * d_eff + cur[k] * (1.0 - d_eff) for k in ema_params}
        mx.eval(ema_params)

    if args.schedule == "wsd" and stable_end is not None and not saved_pre_decay and it == stable_end:
        model.save_weights(str(ckdir / "pre_decay.safetensors"))
        saved_pre_decay = True

    run = 0.98 * run + 0.02 * loss_val if it > 1 else loss_val
    if it % 500 == 0:
        tps = it * args.batch * args.grad_accum * args.ctx / (time.time() - t0)
        print(json.dumps({"step": it, "loss": round(run, 4), "lr": round(current_lr(it), 6),
                          "sn_w": round(current_sn_weight(it), 4),
                          "tok_s": int(tps), "min": round((time.time() - t0) / 60, 1)}), flush=True)

    if it % args.eval_every == 0 or it == args.steps:
        ev, lz_m, lz_s = evaluate_src(test)
        rec = {"step": it, "eval_loss": round(ev, 4), "eval_ppl": round(math.exp(ev), 1),
               "logz_mean": round(lz_m, 4), "logz_std": round(lz_s, 4)}
        if open_test is not None:
            oev, _, _ = evaluate_src(open_test)
            rec["open_loss"] = round(oev, 4)
            rec["open_ppl"] = round(math.exp(oev), 1)
        model.save_weights(str(ckdir / "last.safetensors"))
        sn_ready = args.self_norm == 0.0 or current_sn_weight(it) >= args.self_norm * 0.99 or it == args.steps
        if sn_ready and (ev < best or (args.self_norm > 0.0 and best_logz_std == 0.0)):
            best = ev
            best_logz_mean = lz_m
            best_logz_std = lz_s
            model.save_weights(str(ckdir / "best.safetensors"))
            cfg_dict["logz_mean"] = round(lz_m, 6)
            cfg_dict["logz_std"] = round(lz_s, 6)
            (ckdir / "config.json").write_text(json.dumps(cfg_dict))

        if ema_params is not None:
            live = {k: mx.array(v) for k, v in tree_flatten(model.parameters())}
            mx.eval(live)
            model.update(tree_unflatten(list(ema_params.items())))
            mx.eval(model.parameters())
            ev_ema, _, _ = evaluate_src(test)
            rec["ema_loss"] = round(ev_ema, 4)
            rec["ema_ppl"] = round(math.exp(ev_ema), 1)
            if open_test is not None:
                oev_ema, _, _ = evaluate_src(open_test)
                rec["ema_open_loss"] = round(oev_ema, 4)
                rec["ema_open_ppl"] = round(math.exp(oev_ema), 1)
            model.save_weights(str(ckdir / "last_ema.safetensors"))
            if sn_ready and ev_ema < best_ema:
                best_ema = ev_ema
                model.save_weights(str(ckdir / "best_ema.safetensors"))
            model.update(tree_unflatten(list(live.items())))
            mx.eval(model.parameters())

        print(json.dumps(rec), flush=True)

print(json.dumps({"done": True, "best_eval_loss": round(best, 4),
                  "best_ema_loss": round(best_ema, 4) if ema_params is not None else None,
                  "logz_mean": round(best_logz_mean, 4), "logz_std": round(best_logz_std, 4),
                  "minutes": round((time.time() - t0) / 60, 1)}), flush=True)
