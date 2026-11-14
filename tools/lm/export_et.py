"""Export an MLX-trained keyboard LM to ExecuTorch (.pte), fp32 or int8 (XNNPACK).

usage: export_et.py <ckpt_dir> <out.pte> [--int8] [--check]
The exported program takes int64 token ids [1, ctx] (left-padded with 0) and returns the
log-probabilities of the next token [1, V] at the last position.
"""
import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from safetensors.numpy import load_file


class Block(nn.Module):
    def __init__(self, d, heads, ff):
        super().__init__()
        self.heads = heads
        self.ln1 = nn.LayerNorm(d)
        self.q = nn.Linear(d, d, bias=False)
        self.k = nn.Linear(d, d, bias=False)
        self.v = nn.Linear(d, d, bias=False)
        self.o = nn.Linear(d, d, bias=False)
        self.ln2 = nn.LayerNorm(d)
        self.ff1 = nn.Linear(d, ff)
        self.ff2 = nn.Linear(ff, d)

    def forward(self, x, mask):
        B, T, D = x.shape
        h = self.ln1(x)
        q = self.q(h).view(B, T, self.heads, -1).transpose(1, 2)
        k = self.k(h).view(B, T, self.heads, -1).transpose(1, 2)
        v = self.v(h).view(B, T, self.heads, -1).transpose(1, 2)
        att = (q @ k.transpose(-1, -2)) / math.sqrt(q.shape[-1]) + mask
        y = (att.softmax(-1) @ v).transpose(1, 2).reshape(B, T, D)
        x = x + self.o(y)
        return x + self.ff2(F.gelu(self.ff1(self.ln2(x))))


class QEmbedding(nn.Module):
    """int8 rows + per-row scale; the tied output layer becomes an nn.Linear that XNNPACK
    quantizes (dynamic int8), so both copies of the table are stored as int8."""

    def __init__(self, weight):
        super().__init__()
        scale = weight.abs().amax(1, keepdim=True).clamp(min=1e-8) / 127
        self.register_buffer("q", torch.round(weight / scale).to(torch.int8))
        self.register_buffer("scale", scale)

    def forward(self, ids):
        return self.q[ids].to(torch.float32) * self.scale[ids]


def quantize_tables(m):
    w = m.emb.weight.detach()
    m.out = nn.Linear(w.shape[1], w.shape[0], bias=False)
    m.out.weight = nn.Parameter(w.clone())
    m.emb = QEmbedding(w)
    return m


class TinyGPT(nn.Module):
    def __init__(self, V, E, d, layers, heads, ctx=32):
        super().__init__()
        self.emb = nn.Embedding(V, E)
        self.pos = nn.Embedding(ctx, d)
        self.up = nn.Linear(E, d, bias=False)
        self.blocks = nn.ModuleList([Block(d, heads, 4 * d) for _ in range(layers)])
        self.ln = nn.LayerNorm(d)
        self.down = nn.Linear(d, E, bias=False)
        self.register_buffer("mask", torch.triu(torch.full((ctx, ctx), -1e9), 1))
        self.register_buffer("positions", torch.arange(ctx))

    def forward(self, ids):
        h = self.up(self.emb(ids)) + self.pos(self.positions)
        for b in self.blocks:
            h = b(h, self.mask)
        z = self.down(self.ln(h[:, -1]))
        scores = self.out(z) if hasattr(self, "out") else z @ self.emb.weight.T
        return torch.log_softmax(scores, -1)


class CIFG(nn.Module):
    def __init__(self, V, E, H, ctx=32):
        super().__init__()
        self.emb = nn.Embedding(V, E)
        self.wx = nn.Linear(E, 3 * H)
        self.wh = nn.Linear(H, 3 * H, bias=False)
        self.proj = nn.Linear(H, E, bias=False)
        self.H, self.ctx = H, ctx

    def forward(self, ids):
        xs = self.wx(self.emb(ids))
        h = torch.zeros(ids.shape[0], self.H)
        c = torch.zeros(ids.shape[0], self.H)
        for t in range(self.ctx):
            i, o, g = (xs[:, t] + self.wh(h)).chunk(3, -1)
            i = torch.sigmoid(i)
            c = (1 - i) * c + i * torch.tanh(g)
            h = torch.sigmoid(o) * torch.tanh(c)
        z = self.proj(h)
        scores = self.out(z) if hasattr(self, "out") else z @ self.emb.weight.T
        return torch.log_softmax(scores, -1)


GPT = {"gpt256": (128, 256, 4, 4), "gpt384": (128, 384, 4, 6), "gpt512": (128, 512, 4, 4)}


def load(ckdir):
    cfg = json.loads((ckdir / "config.json").read_text())
    w = load_file(str(ckdir / "best.safetensors"))
    V = cfg["vocab"]
    name = cfg["model"]
    sd = {}
    if name in GPT:
        E, d, L, H = GPT[name]
        m = TinyGPT(V, E, d, L, H, cfg["ctx"])
        ren = {"attn.query_proj": "q", "attn.key_proj": "k", "attn.value_proj": "v", "attn.out_proj": "o"}
        for k, v in w.items():
            for a, b in ren.items():
                k = k.replace(a, b)
            sd[k] = torch.from_numpy(np.array(v, dtype=np.float32))
    elif name == "cifg":
        m = CIFG(V, 96, 670, cfg["ctx"])
        for k, v in w.items():
            sd[k.replace("wx.0", "wx").replace("wh.0", "wh")] = torch.from_numpy(np.array(v, dtype=np.float32))
    else:
        raise ValueError(name)
    missing = m.load_state_dict(sd, strict=False)
    assert not missing.unexpected_keys, missing
    assert set(missing.missing_keys) <= {"mask", "positions"}, missing
    return m.eval(), cfg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("out")
    ap.add_argument("--int8", action="store_true")
    a = ap.parse_args()
    model, cfg = load(Path(a.ckpt))
    ex = (torch.randint(3, 1000, (1, cfg["ctx"])),)
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower
    ref_fp = model(*ex)
    if a.int8:
        model = quantize_tables(model)
        from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (
            XNNPACKQuantizer, get_symmetric_quantization_config)
        from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
        q = XNNPACKQuantizer().set_global(get_symmetric_quantization_config(is_per_channel=True, is_dynamic=True))
        g = torch.export.export(model, ex).module()
        g = prepare_pt2e(g, q)
        g(*ex)
        model = convert_pt2e(g)
    prog = torch.export.export(model, ex)
    et = to_edge_transform_and_lower(prog, partitioner=[XnnpackPartitioner()]).to_executorch()
    Path(a.out).write_bytes(et.buffer)
    ref = ref_fp
    from executorch.runtime import Runtime
    rt = Runtime.get().load_program(Path(a.out).read_bytes()).load_method("forward")
    t = time.time()
    for _ in range(20):
        out = rt.execute([ex[0]])[0]
    ms = (time.time() - t) / 20 * 1000
    print(json.dumps({"pte": a.out, "mb": round(Path(a.out).stat().st_size / 1e6, 2), "int8": a.int8,
                      "max_abs_diff_vs_fp32": float((out - ref).abs().max().detach()),
                      "top1_same": bool(out.argmax() == ref.argmax()), "host_ms": round(ms, 2)}))


if __name__ == "__main__":
    main()
