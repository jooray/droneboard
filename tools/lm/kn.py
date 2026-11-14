"""Interpolated Kneser-Ney bigram/trigram baselines on the same uint16 id streams."""
import numpy as np


class KN:
    def __init__(self, ids, V, order=3, D=0.75, min_count=1):
        ids = np.asarray(ids, dtype=np.int64)
        self.V, self.order, self.D = V, order, D
        V2 = V * V
        # unigram continuation P1(w) = N1+(. w) / N1+(. .)
        big = ids[:-1] * V + ids[1:]
        bk, bc = np.unique(big, return_counts=True)
        if min_count > 1:
            keep = bc >= min_count
            bk, bc = bk[keep], bc[keep]
        cont1 = np.bincount(bk % V, minlength=V).astype(np.float64)
        self.p1 = (cont1 + 1e-3) / (cont1.sum() + 1e-3 * V)
        if order == 2:
            # highest order uses raw bigram counts
            self.big_keys, self.big_vals = bk, bc.astype(np.float64)
            ctx = bk // V
            self.ctx_tot = np.bincount(ctx, weights=self.big_vals, minlength=V)
            self.ctx_types = np.bincount(ctx, minlength=V).astype(np.float64)
            self.nbytes = bk.size * 6
            return
        # middle order uses continuation counts N1+(. v w)
        tri = (ids[:-2] * V + ids[1:-1]) * V + ids[2:]
        tk, tc = np.unique(tri, return_counts=True)
        if min_count > 1:
            keep = tc >= min_count
            tk, tc = tk[keep], tc[keep]
        vw, ncont = np.unique(tk % V2, return_counts=True)
        self.big_keys, self.big_vals = vw, ncont.astype(np.float64)
        ctx = vw // V
        self.ctx_tot = np.bincount(ctx, weights=self.big_vals, minlength=V)
        self.ctx_types = np.bincount(ctx, minlength=V).astype(np.float64)
        self.tri_keys, self.tri_vals = tk, tc.astype(np.float64)
        self.nbytes = tk.size * 8 + vw.size * 6

    def _bigram(self, v):
        p = self.p1.copy()
        tot = self.ctx_tot[v]
        if tot <= 0:
            return p
        lo, hi = np.searchsorted(self.big_keys, [v * self.V, (v + 1) * self.V])
        ws = self.big_keys[lo:hi] % self.V
        c = self.big_vals[lo:hi]
        p *= self.D * self.ctx_types[v] / tot
        p[ws] += np.maximum(c - self.D, 0) / tot
        return p

    def probs(self, u, v):
        p2 = self._bigram(v)
        if self.order == 2:
            return p2
        base = (u * self.V + v) * self.V
        lo, hi = np.searchsorted(self.tri_keys, [base, base + self.V])
        if hi == lo:
            return p2
        ws = self.tri_keys[lo:hi] % self.V
        c = self.tri_vals[lo:hi]
        tot = c.sum()
        p = p2 * (self.D * (hi - lo) / tot)
        p[ws] += np.maximum(c - self.D, 0) / tot
        return p

    def logprobs(self, hist):
        """hist: [B, >=2] ids; returns [B, V] log-probabilities."""
        return np.log(np.stack([self.probs(int(h[-2]), int(h[-1])) for h in hist]) + 1e-12)
