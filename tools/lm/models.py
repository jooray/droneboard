"""Small word-level language models for keyboard next-word prediction (MLX).

All models share a tied, factorized embedding: tokens -> E (emb_dim) -> model -> E -> logits.
"""
import math

import mlx.core as mx
import mlx.nn as nn


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
        self.emb = nn.Embedding(vocab, emb_dim)
        self.pos = nn.Embedding(ctx, d)
        self.up = nn.Linear(emb_dim, d, bias=False)
        self.blocks = [Block(d, heads, ff_mult * d) for _ in range(layers)]
        self.ln = nn.LayerNorm(d)
        self.down = nn.Linear(d, emb_dim, bias=False)

    def __call__(self, x):
        T = x.shape[1]
        h = self.up(self.emb(x)) + self.pos(mx.arange(T))
        mask = nn.MultiHeadAttention.create_additive_causal_mask(T).astype(h.dtype)
        for b in self.blocks:
            h = b(h, mask)
        return self.emb.as_linear(self.down(self.ln(h)))


def build(name, vocab):
    if name == "cifg":  # Gboard 2018 size class
        return CIFGLSTM(vocab, 96, 670)
    if name == "cifg2":
        return CIFGLSTM(vocab, 128, 512, layers=2)
    if name == "gru":
        return GRULM(vocab, 96, 670)
    if name == "gpt256":
        return TinyGPT(vocab, 128, 256, 4, 4)
    if name == "gpt384":
        return TinyGPT(vocab, 128, 384, 4, 6)
    if name == "gpt512":  # SwiftKey 2025 configuration
        return TinyGPT(vocab, 128, 512, 4, 4)
    raise ValueError(name)


def n_params(model):
    from mlx.utils import tree_flatten
    return sum(v.size for _, v in tree_flatten(model.parameters()))
