"""Export an MLX-trained tiny GPT word LM for Droneboard's on-device runtime (native/droneswipe/neural_lm.cpp).

usage: export_keyboard_lm.py <ckpt_dir> <out_dir> <lang> [--vocab N]

Writes two files (little endian, numpy only, no ExecuTorch):
  <lang>.lmv  vocabulary and the tied embedding table, int8 with a float scale per row; the runtime uses
              it both to embed the context and to score every word (dot product, softmax).
                char[4] "DBLM", u32 version=1, u32 V, u32 E, u32 CTX, u32 words_bytes
                words: V tokens, UTF-8, NUL-terminated (id = order; 0 <pad>, 1 <unk>, 2 <bos>), padded to 4 bytes
                float32 scale[V], int8 table[V][E]
  <lang>.lmb  the model body: embeddings [E] -> up (E->d) + learned positions -> pre-LN blocks
              (multi-head attention with q/k/v/o without bias, GELU feed-forward with bias) -> LayerNorm -> down (d->E)
                char[4] "DBLB", u32 version=1, u32 E, d, layers, heads, ff, ctx
                then the tensors in this order; a linear layer is float32 scale[out] + int8 w[out][in]
                (symmetric, per output row), everything else float32:
                up, pos[ctx][d], per block: ln1.w, ln1.b, q, k, v, o, ln2.w, ln2.b, ff1, ff1.b, ff2, ff2.b;
                ln.w, ln.b, down

--vocab N keeps the N most frequent tokens (the vocabulary is sorted by count): a smaller table, renormalized
over the kept words; dropped words become <unk> in the context.
"""
import argparse
import json
import struct
from pathlib import Path

import numpy as np
from safetensors.numpy import load_file

GPT = {"gpt256": (128, 256, 4, 4), "gpt384": (128, 384, 4, 6), "gpt512": (128, 512, 4, 4)}


def quantize_rows(w):
    w = np.asarray(w, dtype=np.float32)
    scale = np.maximum(np.abs(w).max(1), 1e-8) / 127
    q = np.clip(np.round(w / scale[:, None]), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def load(ckdir, vocab=0):
    cfg = json.loads((ckdir / "config.json").read_text())
    if cfg["model"] not in GPT:
        raise SystemExit(f"only the tiny GPT models are supported, not {cfg['model']}")
    w = {k: np.asarray(v, dtype=np.float32) for k, v in load_file(str(ckdir / "best.safetensors")).items()}
    tokens = (ckdir / "tokens.txt").read_text(encoding="utf-8").split("\n")[:-1]
    assert len(tokens) == w["emb.weight"].shape[0]
    if vocab and vocab < len(tokens):
        tokens = tokens[:vocab]
        w["emb.weight"] = w["emb.weight"][:vocab]
    return w, tokens, cfg


def write_lmv(path, tokens, emb, ctx):
    q, scale = quantize_rows(emb)
    words = b"".join(t.encode("utf-8") + b"\0" for t in tokens)
    pad = (-len(words)) % 4
    with open(path, "wb") as f:
        f.write(b"DBLM" + struct.pack("<5I", 1, q.shape[0], q.shape[1], ctx, len(words) + pad))
        f.write(words + b"\0" * pad)
        f.write(scale.astype("<f4").tobytes())
        f.write(q.tobytes())


def write_lmb(path, w, cfg):
    E, d, L, H = GPT[cfg["model"]]
    ctx = cfg["ctx"]
    ff = w["blocks.0.ff1.weight"].shape[0]
    with open(path, "wb") as f:
        f.write(b"DBLB" + struct.pack("<7I", 1, E, d, L, H, ff, ctx))

        def lin(name):
            q, s = quantize_rows(w[name])
            f.write(s.astype("<f4").tobytes())
            f.write(q.tobytes())

        def vec(name):
            f.write(np.ascontiguousarray(w[name], dtype="<f4").tobytes())

        lin("up.weight")
        vec("pos.weight")
        for i in range(L):
            b = f"blocks.{i}."
            vec(b + "ln1.weight"); vec(b + "ln1.bias")
            for p in ("query", "key", "value", "out"):
                lin(b + f"attn.{p}_proj.weight")
            vec(b + "ln2.weight"); vec(b + "ln2.bias")
            lin(b + "ff1.weight"); vec(b + "ff1.bias")
            lin(b + "ff2.weight"); vec(b + "ff2.bias")
        vec("ln.weight"); vec("ln.bias")
        lin("down.weight")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("out")
    ap.add_argument("lang")
    ap.add_argument("--vocab", type=int, default=0)
    a = ap.parse_args()
    w, tokens, cfg = load(Path(a.ckpt), a.vocab)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    write_lmv(out / f"{a.lang}.lmv", tokens, w["emb.weight"], cfg["ctx"])
    write_lmb(out / f"{a.lang}.lmb", w, cfg)
    print(json.dumps({"lang": a.lang, "model": cfg["model"], "vocab": len(tokens),
                      "lmv_mb": round((out / f"{a.lang}.lmv").stat().st_size / 1e6, 2),
                      "lmb_mb": round((out / f"{a.lang}.lmb").stat().st_size / 1e6, 2)}))


if __name__ == "__main__":
    main()
