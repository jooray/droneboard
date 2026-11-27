"""Small word-level language models for keyboard next-word prediction (MLX).

All models share a tied, factorized embedding: tokens -> E (emb_dim) -> model -> E -> logits.
"""
import hashlib
import math

import mlx.core as mx
import mlx.nn as nn
import numpy as np


class CIFGLSTM(nn.Module):
    """Gboard-style (Hard et al. 2018): one layer, coupled input-forget gate, projection to emb_dim."""

    def __init__(self, vocab, emb_dim=96, hidden=670, layers=1):
        super().__init__()
        self.emb = nn.Embedding(vocab, emb_dim)
        self.hidden = hidden
        self.layers = layers
        dims = [emb_dim] + [hidden] * (layers - 1)
        # gates: input (i), output (o), candidate (g); forget = 1 - i
        self.wx = [nn.Linear(d, 3 * hidden) for d in dims]
        self.wh = [nn.Linear(hidden, 3 * hidden, bias=False) for _ in dims]
        self.proj = nn.Linear(hidden, emb_dim, bias=False)

    def __call__(self, x):
        h_in = self.emb(x)  # B, T, E
        B, T, _ = h_in.shape
        for wx, wh in zip(self.wx, self.wh):
            xs = wx(h_in)
            h = mx.zeros((B, self.hidden))
            c = mx.zeros((B, self.hidden))
            outs = []
            for t in range(T):
                z = xs[:, t] + wh(h)
                i, o, g = mx.split(z, 3, axis=-1)
                i = mx.sigmoid(i)
                c = (1 - i) * c + i * mx.tanh(g)
                h = mx.sigmoid(o) * mx.tanh(c)
                outs.append(h)
            h_in = mx.stack(outs, axis=1)
        return self.emb.as_linear(self.proj(h_in))


class GRULM(nn.Module):
    def __init__(self, vocab, emb_dim=96, hidden=670):
        super().__init__()
        self.emb = nn.Embedding(vocab, emb_dim)
        self.rnn = nn.GRU(emb_dim, hidden)
        self.proj = nn.Linear(hidden, emb_dim, bias=False)

    def __call__(self, x):
        return self.emb.as_linear(self.proj(self.rnn(self.emb(x))))


class Block(nn.Module):
    def __init__(self, d, heads, ff):
        super().__init__()
        self.ln1 = nn.LayerNorm(d)
        self.attn = nn.MultiHeadAttention(d, heads)
        self.ln2 = nn.LayerNorm(d)
        self.ff1 = nn.Linear(d, ff)
        self.ff2 = nn.Linear(ff, d)

    def __call__(self, x, mask):
        h = self.ln1(x)
        x = x + self.attn(h, h, h, mask)
        return x + self.ff2(nn.gelu(self.ff1(self.ln2(x))))


class TinyGPT(nn.Module):
    """SwiftKey-style (2025): small GPT-2 decoder, factorized embeddings."""

    def __init__(self, vocab, emb_dim=128, d=512, layers=4, heads=4, ff_mult=4, ctx=32):
        super().__init__()
        self.ctx = ctx
        self.emb = nn.Embedding(vocab, emb_dim)
        self.pos = nn.Embedding(ctx, d)
        self.up = nn.Linear(emb_dim, d, bias=False)
        self.blocks = [Block(d, heads, ff_mult * d) for _ in range(layers)]
        self.ln = nn.LayerNorm(d)
        self.down = nn.Linear(d, emb_dim, bias=False)

    def hidden(self, x):
        T = x.shape[1]
        h = self.up(self.emb(x)) + self.pos(mx.arange(T))
        mask = nn.MultiHeadAttention.create_additive_causal_mask(T).astype(h.dtype)
        for b in self.blocks:
            h = b(h, mask)
        return self.down(self.ln(h))

    def __call__(self, x):
        return self.emb.as_linear(self.hidden(x))


# ---------- v2 Architecture: Striped Causal Conv1D + RoPE-GQA + SwiGLU + Folded Morph Embeddings ----------

def build_morph_table(tokens, n_buckets=16384, max_ngrams=8):
    """Hash character 3-5-grams (prioritizing prefix/suffix boundaries) into [V, max_ngrams]."""
    table = np.zeros((len(tokens), max_ngrams), dtype=np.int32)
    for idx, tok in enumerate(tokens):
        if idx < 3 or tok.startswith("<") or not any(ch.isalpha() for ch in tok):
            continue
        s = f"<{tok}>"
        grams = []
        if len(s) >= 4:
            grams.append("P4:" + s[:4])
            grams.append("S4:" + s[-4:])
        if len(s) >= 3:
            grams.append("P3:" + s[:3])
            grams.append("S3:" + s[-3:])
        if len(s) >= 5:
            grams.append("S5:" + s[-5:])
            grams.append("P5:" + s[:5])
        for i in range(1, max(1, len(s) - 3)):
            grams.append("I3:" + s[i : i + 3])
        seen = []
        for g in grams:
            h = int.from_bytes(hashlib.blake2b(g.encode("utf-8"), digest_size=4).digest(), "little")
            b = 1 + (h % (n_buckets - 1))
            if b not in seen:
                seen.append(b)
            if len(seen) >= max_ngrams:
                break
        for j, b in enumerate(seen):
            table[idx, j] = b
    return table


def ste_int8_rows(w):
    """Straight-through symmetric per-row INT8 fake quantization matching neural_lm.cpp."""
    s = mx.maximum(mx.abs(w).max(axis=-1, keepdims=True), 1e-8) / 127.0
    q = mx.clip(mx.round(w / s), -127.0, 127.0) * s
    return w + mx.stop_gradient(q - w)


def ste_int4_g32(w):
    """Straight-through symmetric group-32 INT4 [-7,+7] fake quantization matching neural_lm.cpp."""
    V, E = w.shape
    g = w.reshape(V, E // 32, 32)
    s = mx.maximum(mx.abs(g).max(axis=-1, keepdims=True), 1e-8) / 7.0
    q = mx.clip(mx.round(g / s), -7.0, 7.0) * s
    return w + mx.stop_gradient(q.reshape(V, E) - w)


def lin_apply(layer, x, qat=False):
    if not qat:
        return layer(x)
    xq = ste_int8_rows(x)
    wq = ste_int8_rows(layer.weight)
    out = xq @ wq.T
    if "bias" in layer:
        out = out + layer.bias
    return out


class SwiGLU(nn.Module):
    def __init__(self, d, ff):
        super().__init__()
        self.w_gate = nn.Linear(d, ff, bias=False)
        self.w_up = nn.Linear(d, ff, bias=False)
        self.w_down = nn.Linear(ff, d, bias=False)

    def __call__(self, x, qat=False):
        return lin_apply(self.w_down, nn.silu(lin_apply(self.w_gate, x, qat)) * lin_apply(self.w_up, x, qat), qat)


class RoPEGQA(nn.Module):
    def __init__(self, d, n_heads=4, n_kv_heads=2, qk_norm=True, rope_theta=10000.0):
        super().__init__()
        self.n_heads = n_heads
        self.n_kv_heads = n_kv_heads
        self.head_dim = d // n_heads
        self.scale = self.head_dim ** -0.5
        self.wq = nn.Linear(d, n_heads * self.head_dim, bias=False)
        self.wk = nn.Linear(d, n_kv_heads * self.head_dim, bias=False)
        self.wv = nn.Linear(d, n_kv_heads * self.head_dim, bias=False)
        self.wo = nn.Linear(n_heads * self.head_dim, d, bias=False)
        self.rope = nn.RoPE(self.head_dim, traditional=False, base=rope_theta)
        self.qk_norm = qk_norm
        if qk_norm:
            self.q_norm = nn.RMSNorm(self.head_dim)
            self.k_norm = nn.RMSNorm(self.head_dim)

    def __call__(self, x, mask, qat=False):
        B, T, _ = x.shape
        q = lin_apply(self.wq, x, qat).reshape(B, T, self.n_heads, self.head_dim).transpose(0, 2, 1, 3)
        k = lin_apply(self.wk, x, qat).reshape(B, T, self.n_kv_heads, self.head_dim).transpose(0, 2, 1, 3)
        v = lin_apply(self.wv, x, qat).reshape(B, T, self.n_kv_heads, self.head_dim).transpose(0, 2, 1, 3)
        if self.qk_norm:
            q = self.q_norm(q)
            k = self.k_norm(k)
        q = self.rope(q)
        k = self.rope(k)
        out = mx.fast.scaled_dot_product_attention(q, k, v, scale=self.scale, mask=mask)
        out = out.transpose(0, 2, 1, 3).reshape(B, T, -1)
        return lin_apply(self.wo, out, qat)


class ModernAttnBlock(nn.Module):
    def __init__(self, d, n_heads=4, n_kv_heads=2, ff=688, qk_norm=True, rope_theta=10000.0):
        super().__init__()
        self.n1 = nn.RMSNorm(d)
        self.attn = RoPEGQA(d, n_heads, n_kv_heads, qk_norm=qk_norm, rope_theta=rope_theta)
        self.n2 = nn.RMSNorm(d)
        self.ff = SwiGLU(d, ff)

    def __call__(self, x, mask, qat=False):
        x = x + self.attn(self.n1(x), mask, qat=qat)
        return x + self.ff(self.n2(x), qat=qat)


class CausalGatedConvMixer(nn.Module):
    """Causal gated depthwise Conv1D(k=4): O(1) 4-slot ring state, local n-gram specialist."""

    def __init__(self, d, k=4):
        super().__init__()
        self.k = k
        self.in_proj = nn.Linear(d, 2 * d, bias=False)
        self.dw_weight = mx.random.normal((d, k)) * (1.0 / math.sqrt(k))
        self.dw_bias = mx.zeros((d,))
        self.out_proj = nn.Linear(d, d, bias=False)

    def __call__(self, x, mask=None, qat=False):
        _, T, _ = x.shape
        uv = lin_apply(self.in_proj, x, qat)
        u, v = mx.split(uv, 2, axis=-1)
        padded = mx.pad(u, [(0, 0), (self.k - 1, 0), (0, 0)])
        acc = padded[:, self.k - 1 : self.k - 1 + T, :] * self.dw_weight[:, 0]
        for j in range(1, self.k):
            acc = acc + padded[:, self.k - 1 - j : self.k - 1 - j + T, :] * self.dw_weight[:, j]
        acc = acc + self.dw_bias
        return lin_apply(self.out_proj, acc * nn.silu(v), qat)


class ConvMixerBlock(nn.Module):
    def __init__(self, d, ff=688, k=4):
        super().__init__()
        self.n1 = nn.RMSNorm(d)
        self.mixer = CausalGatedConvMixer(d, k=k)
        self.n2 = nn.RMSNorm(d)
        self.ff = SwiGLU(d, ff)

    def __call__(self, x, mask=None, qat=False):
        x = x + self.mixer(self.n1(x), qat=qat)
        return x + self.ff(self.n2(x), qat=qat)


class StripedLM(nn.Module):
    """v2 keyboard LM: Gated Causal Conv1D + RoPE-GQA + SwiGLU + RMSNorm + VocabBias + foldable morph emb."""

    def __init__(
        self,
        vocab,
        emb_dim=128,
        d=256,
        block_types=("conv", "attn", "conv", "attn"),
        n_heads=4,
        n_kv_heads=2,
        ff=688,
        ctx=64,
        conv_k=4,
        qk_norm=True,
        rope_theta=10000.0,
        tokens=None,
        morph_buckets=16384,
    ):
        super().__init__()
        self.ctx = ctx
        self.vocab = vocab
        self.emb_dim = emb_dim
        self.d = d
        self.block_types = tuple(block_types)
        self.n_heads = n_heads
        self.n_kv_heads = n_kv_heads
        self.ff = ff
        self.conv_k = conv_k
        self.qk_norm = qk_norm
        self.rope_theta = rope_theta
        self.qat = False
        self.qat_int8_rows = vocab

        self.emb = nn.Embedding(vocab, emb_dim)
        self.use_morph = tokens is not None
        if self.use_morph:
            morph_table = build_morph_table(tokens, n_buckets=morph_buckets, max_ngrams=8)
            self.morph_emb = nn.Embedding(morph_buckets, emb_dim)
            self.morph_emb.weight = mx.zeros((morph_buckets, emb_dim))
            self.morph_scale = mx.array(0.1)
            self._morph_ids = mx.array(morph_table)
            self._morph_mask = (self._morph_ids != 0).astype(mx.float32)
            self._morph_denom = mx.maximum(self._morph_mask.sum(axis=-1, keepdims=True), 1.0)

        self.up = nn.Linear(emb_dim, d, bias=False)
        blocks = []
        for bt in self.block_types:
            if bt == "attn":
                blocks.append(ModernAttnBlock(d, n_heads, n_kv_heads, ff, qk_norm=qk_norm, rope_theta=rope_theta))
            elif bt == "conv":
                blocks.append(ConvMixerBlock(d, ff, k=conv_k))
            else:
                raise ValueError(bt)
        self.blocks = blocks
        self.ln = nn.RMSNorm(d)
        self.down = nn.Linear(d, emb_dim, bias=False)
        self.out_bias = mx.zeros((vocab,))

    def effective_emb_weight(self):
        if not self.use_morph:
            W = self.emb.weight
        else:
            m = self.morph_emb(self._morph_ids) * self._morph_mask[:, :, None]
            m_mean = m.sum(axis=1) / self._morph_denom
            W = self.emb.weight + self.morph_scale * m_mean
        if self.qat:
            n8 = min(max(0, self.qat_int8_rows), self.vocab)
            if n8 >= self.vocab:
                W = ste_int8_rows(W)
            elif n8 == 0:
                W = ste_int4_g32(W)
            else:
                W = mx.concatenate([ste_int8_rows(W[:n8]), ste_int4_g32(W[n8:])], axis=0)
        return W

    def hidden(self, x, W=None):
        T = x.shape[1]
        if W is None:
            W = self.effective_emb_weight()
        qat = self.qat
        h = lin_apply(self.up, W[x], qat)
        mask = nn.MultiHeadAttention.create_additive_causal_mask(T).astype(h.dtype)
        for b in self.blocks:
            h = b(h, mask, qat=qat)
        return lin_apply(self.down, self.ln(h), qat)

    def __call__(self, x):
        W = self.effective_emb_weight()
        z = self.hidden(x, W)
        if self.qat:
            z = ste_int8_rows(z)
        return (z @ W.T) + self.out_bias


def build(name, vocab, tokens=None, ctx=None):
    if name == "cifg":  # Gboard 2018 size class
        return CIFGLSTM(vocab, 96, 670)
    if name == "cifg2":
        return CIFGLSTM(vocab, 128, 512, layers=2)
    if name == "gru":
        return GRULM(vocab, 96, 670)
    if name == "gpt256":
        return TinyGPT(vocab, 128, 256, 4, 4, ctx=ctx or 32)
    if name == "gpt384":
        return TinyGPT(vocab, 128, 384, 4, 6, ctx=ctx or 32)
    if name == "gpt512":  # SwiftKey 2025 configuration
        return TinyGPT(vocab, 128, 512, 4, 4, ctx=ctx or 32)
    if name == "striped256":
        return StripedLM(
            vocab, emb_dim=128, d=256, block_types=("conv", "attn", "conv", "attn"),
            n_heads=4, n_kv_heads=2, ff=688, ctx=ctx or 64, conv_k=4, qk_norm=True, tokens=tokens,
        )
    if name == "striped256x6":
        return StripedLM(
            vocab, emb_dim=128, d=256, block_types=("conv", "attn", "conv", "attn", "conv", "attn"),
            n_heads=4, n_kv_heads=2, ff=688, ctx=ctx or 64, conv_k=4, qk_norm=True, tokens=tokens,
        )
    if name == "striped384":
        return StripedLM(
            vocab, emb_dim=128, d=384, block_types=("conv", "attn", "conv", "attn"),
            n_heads=6, n_kv_heads=2, ff=1024, ctx=ctx or 64, conv_k=4, qk_norm=True, tokens=tokens,
        )
    if name == "striped384x6":
        return StripedLM(
            vocab, emb_dim=128, d=384, block_types=("conv", "attn", "conv", "attn", "conv", "attn"),
            n_heads=6, n_kv_heads=2, ff=1024, ctx=ctx or 64, conv_k=4, qk_norm=True, tokens=tokens,
        )
    if name == "modern256":
        return StripedLM(
            vocab, emb_dim=128, d=256, block_types=("attn", "attn", "attn", "attn"),
            n_heads=4, n_kv_heads=2, ff=688, ctx=ctx or 64, conv_k=4, qk_norm=True, tokens=tokens,
        )
    if name == "modern384":
        return StripedLM(
            vocab, emb_dim=128, d=384, block_types=("attn", "attn", "attn", "attn"),
            n_heads=6, n_kv_heads=2, ff=1024, ctx=ctx or 64, conv_k=4, qk_norm=True, tokens=tokens,
        )
    raise ValueError(name)


def n_params(model):
    from mlx.utils import tree_flatten
    return sum(v.size for _, v in tree_flatten(model.parameters()))
