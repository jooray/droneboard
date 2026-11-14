"""Compare Droneboard's C++ neural LM runtime with the MLX model it was exported from.

usage:
  verify_keyboard_lm.py contexts --work DIR --lang sk --n 300 --out ctx.jsonl
      held-out contexts: <bos> + the start of a paragraph (up to 32 ids), or 8-24 ids from inside one
  lm-check sk.lmb sk.lmv ctx.jsonl > cpp.jsonl            (tools/swipe-replay)
  verify_keyboard_lm.py compare --ckpt CKPT_DIR --contexts ctx.jsonl --cpp cpp.jsonl

compare reports the largest absolute difference of the 128-dim hidden state (the input of the tied
output layer), the cosine similarity, and how often the top-10 next tokens are the same set and the
same top-1 (MLX fp32 versus int8 C++).
"""
import argparse
import json
import random
import sys
from pathlib import Path

import numpy as np


def contexts(a):
    work = Path(a.work)
    ids = np.memmap(work / "ids" / f"{a.lang}.test.u16", dtype=np.uint16, mode="r")
    starts = np.flatnonzero(ids == 2)
    rng = random.Random(0)
    out = []
    for s in rng.sample(list(starts[:-1]), min(len(starts) - 1, a.n * 3)):
        para = [int(x) for x in ids[s:s + 64]]
        if 2 in para[1:]:
            para = para[:para.index(2, 1)]
        if len(para) < 9:
            continue
        if len(out) % 2 == 0:
            ctx = para[:rng.randint(4, 32)]
        else:
            i = rng.randint(1, max(1, len(para) - 9))
            ctx = para[i:i + rng.randint(8, 24)]
        out.append(ctx)
        if len(out) >= a.n:
            break
    with open(a.out, "w") as f:
        for c in out:
            f.write(json.dumps({"ids": c}) + "\n")
    print(json.dumps({"contexts": len(out)}))


def compare(a):
    import mlx.core as mx
    sys.path.insert(0, str(Path(__file__).parent))
    from models import build
    ck = Path(a.ckpt)
    cfg = json.loads((ck / "config.json").read_text())
    V = len((ck / "tokens.txt").read_text(encoding="utf-8").split("\n")[:-1])
    m = build(cfg["model"], V)
    m.load_weights(str(ck / "best.safetensors"))
    ctxs = [json.loads(l)["ids"] for l in open(a.contexts)]
    cpp = [json.loads(l) for l in open(a.cpp)]
    assert len(ctxs) == len(cpp)
    max_diff, cos, same10, same1, overlap = 0.0, [], 0, 0, 0
    for ids, c in zip(ctxs, cpp):
        x = mx.array([ids], dtype=mx.int32)
        T = len(ids)
        h = m.up(m.emb(x)) + m.pos(mx.arange(T))
        mask = m.blocks[0].attn.create_additive_causal_mask(T).astype(h.dtype)
        for b in m.blocks:
            h = b(h, mask)
        z = m.down(m.ln(h))[0, -1]
        logits = np.array(m.emb.as_linear(z))
        z = np.array(z)
        zc = np.array(c["z"], dtype=np.float32)
        max_diff = max(max_diff, float(np.abs(z - zc).max()))
        cos.append(float(z @ zc / (np.linalg.norm(z) * np.linalg.norm(zc) + 1e-9)))
        top = list(np.argsort(-logits)[:10])
        same10 += set(top) == set(c["top10"])
        same1 += top[0] == c["top10"][0]
        overlap += len(set(top) & set(c["top10"]))
    n = len(ctxs)
    print(json.dumps({"contexts": n, "max_abs_diff_hidden": round(max_diff, 4), "min_cosine": round(min(cos), 5),
                      "mean_cosine": round(float(np.mean(cos)), 6), "same_top1": round(same1 / n, 4),
                      "same_top10_set": round(same10 / n, 4), "mean_top10_overlap": round(overlap / n, 2)}))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("contexts")
    c.add_argument("--work", required=True)
    c.add_argument("--lang", required=True)
    c.add_argument("--n", type=int, default=300)
    c.add_argument("--out", required=True)
    v = sub.add_parser("compare")
    v.add_argument("--ckpt", required=True)
    v.add_argument("--contexts", required=True)
    v.add_argument("--cpp", required=True)
    a = ap.parse_args()
    contexts(a) if a.cmd == "contexts" else compare(a)


if __name__ == "__main__":
    main()
