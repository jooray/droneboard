"""Export an MLX-trained keyboard word LM for Droneboard's on-device runtime (native/droneswipe/neural_lm.cpp).

usage: export_keyboard_lm.py <ckpt_dir> <out_dir> <lang> [--vocab N] [--int8_rows 10000]

Supports both v1 (TinyGPT: gpt256/gpt384/gpt512) and v2 (StripedLM: striped256/modern256):

v1 format (little endian):
  <lang>.lmv  "DBLM", u32 version=1, u32 V, E, CTX, words_bytes; words (padded 4); f32 scale[V]; i8 table[V][E]
  <lang>.lmb  "DBLB", u32 version=1, u32 E, d, layers, heads, ff, ctx; tensors...

v2 format (little endian, 4-byte aligned):
  <lang>.lmv  "DBLM", u32 version=2,
              u32 V, u32 E, u32 CTX, u32 words_bytes,
              u32 n_int8, u32 group_size (=32), u32 flags (bit0=has_bias, bit1=self_norm),
              f32 logz_mean, u32 shortlist_k, u32 reserved (=0)
              payload:
                words[words_bytes]                      (V NUL-terminated UTF-8 strings, padded to 4)
                if flags & 1: f32 out_bias[V]
                if n_int8 > 0: f32 scale_i8[n_int8], i8 data_i8[n_int8 * E]
                if n_int4 > 0 (n_int4 = V - n_int8, G = E / 32):
                  f16 scale_i4[n_int4 * G] (+ 2-byte pad if n_int4*G odd)
                  u8 data_i4[n_int4 * (E / 2)]
                  (within each 32-element group, 16 packed bytes store
                   low_nibble = q[0..15] + 8, high_nibble = q[16..31] + 8, q in [-7, +7])

  <lang>.lmb  "DBLB", u32 version=2,
              u32 E, u32 d, u32 layers, u32 n_heads, u32 n_kv_heads, u32 ff, u32 ctx,
              u32 conv_k, u32 flags (bit0=qk_norm, bit1=rms_norm), f32 rope_theta,
              u8 block_types[layers] (0=RoPE-GQA, 1=GatedCausalConv1D), padded to 4
              tensors (each Linear(out,in) is f32 scale[out] + i8 w[out][in]):
                up(d, E)
                per block i:
                  n1.w[d]
                  if block_types[i] == 0 (RoPE-GQA):
                    qkv(d + 2*n_kv_heads*head_dim, d)  [stacked wq; wk; wv]
                    if qk_norm: q_norm.w[head_dim], k_norm.w[head_dim]
                    wo(d, d)
                  if block_types[i] == 1 (GatedCausalConv1D):
                    in_proj(2*d, d)
                    dw_weight[conv_k][d] (transposed from [d][conv_k] so each tap is contiguous)
                    dw_bias[d]
                    out_proj(d, d)
                  n2.w[d]
                  ff_gate_up(2*ff, d)  [stacked w_gate; w_up]
                  ff_down(d, ff)
                ln.w[d]
                down(E, d)
"""
import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from models import build_morph_table

GPT_V1 = {"gpt256": (128, 256, 4, 4), "gpt384": (128, 384, 4, 6), "gpt512": (128, 512, 4, 4)}
V2_MODELS = {
    "striped256": (128, 256, ("conv", "attn", "conv", "attn"), 4, 2, 688, 4),
    "modern256": (128, 256, ("attn", "attn", "attn", "attn"), 4, 2, 688, 4),
}


def load_safetensors_np(path):
    """Load safetensors using safetensors.numpy if available, else mlx.core."""
    try:
        from safetensors.numpy import load_file
        return {k: np.asarray(v, dtype=np.float32) for k, v in load_file(str(path)).items()}
    except ImportError:
        import mlx.core as mx
        raw = mx.load(str(path))
        return {k: np.asarray(v, dtype=np.float32) for k, v in raw.items()}


def quantize_rows(w):
    w = np.asarray(w, dtype=np.float32)
    scale = np.maximum(np.abs(w).max(1), 1e-8) / 127.0
    q = np.clip(np.round(w / scale[:, None]), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def quantize_int4_g32_neon(w, group_size=32):
    """Symmetric INT4 group-32 quantization with NEON split-half nibble layout:
    per 32-element group, 16 bytes pack low_nibble = q[0..15]+8, high_nibble = q[16..31]+8.
    """
    w = np.asarray(w, dtype=np.float32)
    V, E = w.shape
    assert E % group_size == 0 and group_size == 32
    G = E // group_size
    grp = w.reshape(V, G, group_size)
    scale = (np.maximum(np.abs(grp).max(axis=2), 1e-8) / 7.0).astype(np.float16)
    s_f32 = scale.astype(np.float32)[:, :, None]
    q = np.clip(np.round(grp / s_f32), -7, 7).astype(np.int32)
    u = (q + 8).astype(np.uint8)  # [V, G, 32], values in 1..15
    packed = (u[:, :, :16] | (u[:, :, 16:] << 4)).astype(np.uint8)  # [V, G, 16]
    return packed.reshape(V, E // 2), scale.reshape(V * G)


def fold_morph_embeddings(w, tokens):
    """Algebraically fold training-time char 3/4-gram morph embeddings into static [V, E]."""
    emb = w["emb.weight"].copy()
    if "morph_emb.weight" not in w:
        return emb
    morph_w = w["morph_emb.weight"]
    scale = float(w.get("morph_scale", np.array(0.1)).reshape(-1)[0])
    n_buckets = morph_w.shape[0]
    table = build_morph_table(tokens, n_buckets=n_buckets, max_ngrams=8)
    mask = (table != 0).astype(np.float32)
    denom = np.maximum(mask.sum(axis=1, keepdims=True), 1.0)
    # Chunked lookup to keep peak RAM tiny
    B = 4096
    for i in range(0, len(tokens), B):
        ids = table[i : i + B]
        mk = mask[i : i + B, :, None]
        m = morph_w[ids] * mk
        emb[i : i + B] += scale * (m.sum(axis=1) / denom[i : i + B])
    return emb


def load(ckdir, vocab=0):
    cfg = json.loads((ckdir / "config.json").read_text())
    model_name = cfg["model"]
    if model_name not in GPT_V1 and model_name not in V2_MODELS:
        raise SystemExit(f"unsupported model for export: {model_name}")
    w = load_safetensors_np(ckdir / "best.safetensors")
    tokens = (ckdir / "tokens.txt").read_text(encoding="utf-8").split("\n")[:-1]
    assert len(tokens) == w["emb.weight"].shape[0]
    if model_name in V2_MODELS:
        w["emb.weight"] = fold_morph_embeddings(w, tokens)
    if vocab and vocab < len(tokens):
        tokens = tokens[:vocab]
        w["emb.weight"] = w["emb.weight"][:vocab]
        if "out_bias" in w:
            w["out_bias"] = w["out_bias"][:vocab]
    return w, tokens, cfg


def write_lmv_v1(path, tokens, emb, ctx):
    q, scale = quantize_rows(emb)
    words = b"".join(t.encode("utf-8") + b"\0" for t in tokens)
    pad = (-len(words)) % 4
    with open(path, "wb") as f:
        f.write(b"DBLM" + struct.pack("<5I", 1, q.shape[0], q.shape[1], ctx, len(words) + pad))
        f.write(words + b"\0" * pad)
        f.write(scale.astype("<f4").tobytes())
        f.write(q.tobytes())


def write_lmb_v1(path, w, cfg):
    E, d, L, H = GPT_V1[cfg["model"]]
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


def write_lmv_v2(path, tokens, emb, out_bias, ctx, int8_rows=10000, self_norm=False, logz_mean=0.0, shortlist_k=0):
    V, E = emb.shape
    n_int8 = min(max(0, int8_rows), V)
    n_int4 = V - n_int8
    group_size = 32
    flags = (1 if out_bias is not None else 0) | (2 if self_norm else 0)
    if shortlist_k == 0 and self_norm:
        shortlist_k = 15000 if V > 40000 else min(10000, V)
    words = b"".join(t.encode("utf-8") + b"\0" for t in tokens)
    pad = (-len(words)) % 4
    with open(path, "wb") as f:
        f.write(b"DBLM" + struct.pack(
            "<8If2I",
            2, V, E, ctx, len(words) + pad,
            n_int8, group_size, flags, float(logz_mean), int(shortlist_k), 0,
        ))
        f.write(words + b"\0" * pad)
        if out_bias is not None:
            f.write(np.ascontiguousarray(out_bias, dtype="<f4").tobytes())
        if n_int8 > 0:
            q8, s8 = quantize_rows(emb[:n_int8])
            f.write(s8.astype("<f4").tobytes())
            f.write(q8.tobytes())
        if n_int4 > 0:
            q4, s4 = quantize_int4_g32_neon(emb[n_int8:], group_size=group_size)
            s4_bytes = s4.astype("<f2").tobytes()
            s4_pad = (-len(s4_bytes)) % 4
            f.write(s4_bytes + b"\0" * s4_pad)
            f.write(q4.tobytes())


def write_lmb_v2(path, w, cfg):
    E, d, block_types, n_heads, n_kv_heads, ff, conv_k = V2_MODELS[cfg["model"]]
    L = len(block_types)
    ctx = cfg["ctx"]
    qk_norm = "blocks.1.attn.q_norm.weight" in w or "blocks.0.attn.q_norm.weight" in w
    flags = (1 if qk_norm else 0) | 2  # bit1 = RMSNorm
    rope_theta = 10000.0
    bt_bytes = bytes(0 if bt == "attn" else 1 for bt in block_types)
    bt_pad = (-len(bt_bytes)) % 4
    with open(path, "wb") as f:
        f.write(b"DBLB" + struct.pack(
            "<10If", 2, E, d, L, n_heads, n_kv_heads, ff, ctx, conv_k, flags, rope_theta
        ))
        f.write(bt_bytes + b"\0" * bt_pad)

        def lin_arr(arr):
            q, s = quantize_rows(arr)
            f.write(s.astype("<f4").tobytes())
            f.write(q.tobytes())

        def vec_arr(arr):
            f.write(np.ascontiguousarray(arr, dtype="<f4").tobytes())

        lin_arr(w["up.weight"])
        for i, bt in enumerate(block_types):
            b = f"blocks.{i}."
            vec_arr(w[b + "n1.weight"])
            if bt == "attn":
                qkv = np.concatenate([
                    w[b + "attn.wq.weight"],
                    w[b + "attn.wk.weight"],
                    w[b + "attn.wv.weight"],
                ], axis=0)
                lin_arr(qkv)
                if qk_norm:
                    vec_arr(w[b + "attn.q_norm.weight"])
                    vec_arr(w[b + "attn.k_norm.weight"])
                lin_arr(w[b + "attn.wo.weight"])
            else:
                lin_arr(w[b + "mixer.in_proj.weight"])
                # Transpose [d, k] -> [k, d] so each tap is a contiguous d-float vector in NEON
                vec_arr(w[b + "mixer.dw_weight"].T)
                vec_arr(w[b + "mixer.dw_bias"])
                lin_arr(w[b + "mixer.out_proj.weight"])
            vec_arr(w[b + "n2.weight"])
            ff_gu = np.concatenate([w[b + "ff.w_gate.weight"], w[b + "ff.w_up.weight"]], axis=0)
            lin_arr(ff_gu)
            lin_arr(w[b + "ff.w_down.weight"])
        vec_arr(w["ln.weight"])
        lin_arr(w["down.weight"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("out")
    ap.add_argument("lang")
    ap.add_argument("--vocab", type=int, default=0)
    ap.add_argument("--int8_rows", type=int, default=10000, help="v2: leading vocab rows kept INT8; rest INT4-g32")
    ap.add_argument("--logz_mean", type=float, default=None, help="override calibrated logZ mean for self-norm v2")
    ap.add_argument("--shortlist", type=int, default=0, help="override next-word shortlist size for self-norm v2")
    a = ap.parse_args()
    w, tokens, cfg = load(Path(a.ckpt), a.vocab)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if cfg["model"] in GPT_V1:
        write_lmv_v1(out / f"{a.lang}.lmv", tokens, w["emb.weight"], cfg["ctx"])
        write_lmb_v1(out / f"{a.lang}.lmb", w, cfg)
    else:
        self_norm = float(cfg.get("self_norm", 0.0)) > 0.0
        logz_mean = a.logz_mean if a.logz_mean is not None else float(cfg.get("logz_mean", 0.0))
        write_lmv_v2(
            out / f"{a.lang}.lmv", tokens, w["emb.weight"], w.get("out_bias"), cfg["ctx"],
            int8_rows=a.int8_rows, self_norm=self_norm, logz_mean=logz_mean, shortlist_k=a.shortlist,
        )
        write_lmb_v2(out / f"{a.lang}.lmb", w, cfg)
    print(json.dumps({
        "lang": a.lang, "model": cfg["model"], "vocab": len(tokens),
        "version": 1 if cfg["model"] in GPT_V1 else 2,
        "lmv_mb": round((out / f"{a.lang}.lmv").stat().st_size / 1e6, 2),
        "lmb_mb": round((out / f"{a.lang}.lmb").stat().st_size / 1e6, 2),
    }))


if __name__ == "__main__":
    main()
