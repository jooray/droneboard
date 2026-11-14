"""Code-switching evaluation for the enabled languages.

Compares
  (a) per-language models combined at inference: P(w|h) = sum_l pi_l(h) P_l(w|h), where the
      language posterior pi follows a sticky HMM over the words typed so far in the paragraph
      (after an English word the next word is probably English, a word valid in both languages
      gets probability from both);
  (b) one multilingual model with output masked to the enabled languages' vocabulary.
Test data: paragraphs of each enabled language interleaved in random order (personal held-out
text by default, which also contains natural in-sentence switches), up to --n target words.

usage: mix_eval.py --work DIR --langs sk,en --split ptest
         --a sk=ckpt_dir|kn3,en=ckpt_dir|kn3 [--b multi_ckpt_dir] [--switch 0.05]
"""
import argparse
import json
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from evaluate import Metrics  # noqa: E402


def read_paragraphs(work, lang, split, vocab):
    ids = np.memmap(work / "ids" / f"{lang}.{split}.u16", dtype=np.uint16, mode="r")
    starts = np.flatnonzero(ids == 2)
    paras = []
    for s, e in zip(starts, list(starts[1:]) + [len(ids)]):
        toks = [vocab[i] for i in ids[s + 1:e][:31]]
        if len(toks) >= 4:
            paras.append((lang, toks))
    return paras


class NeuralLM:
    def __init__(self, ckdir):
        import mlx.core as mx
        from models import build
        self.mx = mx
        cfg = json.loads((ckdir / "config.json").read_text())
        self.tokens = (ckdir / "tokens.txt").read_text(encoding="utf-8").split("\n")[:-1]
        self.index = {t: i for i, t in enumerate(self.tokens)}
        self.model = build(cfg["model"], len(self.tokens))
        self.model.load_weights(str(ckdir / "best.safetensors"))

    def seq_logprobs(self, toks, start_token):
        """log P(next | prefix) for every prefix of the paragraph: [len(toks), V]."""
        mx = self.mx
        ids = [self.index.get(start_token, 2)] + [self.index.get(t, 1) for t in toks]
        lg = self.model(mx.array([ids[:-1]], dtype=mx.int32))[0]
        return np.array(lg - mx.logsumexp(lg, axis=-1, keepdims=True))


class KNLM:
    def __init__(self, work, lang):
        from kn import KN
        self.tokens = (work / "vocab" / f"{lang}.txt").read_text(encoding="utf-8").split("\n")[:-1]
        self.index = {t: i for i, t in enumerate(self.tokens)}
        train = np.memmap(work / "ids" / f"{lang}.train.u16", dtype=np.uint16, mode="r")
        self.kn = KN(train, len(self.tokens), order=3)

    def seq_logprobs(self, toks, start_token):
        ids = [2, 2] + [self.index.get(t, 1) for t in toks]
        hist = np.array([ids[i:i + 2] for i in range(len(toks))])
        return self.kn.logprobs(hist)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--langs", required=True)
    ap.add_argument("--split", default="ptest")
    ap.add_argument("--a", required=True)
    ap.add_argument("--b")
    ap.add_argument("--switch", type=float, default=0.05)
    ap.add_argument("--n", type=int, default=6000)
    a = ap.parse_args()
    work = Path(a.work)
    langs = a.langs.split(",")
    vocabs = {l: (work / "vocab" / f"{l}.txt").read_text(encoding="utf-8").split("\n")[:-1] for l in langs}
    paras = []
    for l in langs:
        paras += read_paragraphs(work, l, a.split, vocabs[l])
    random.Random(0).shuffle(paras)

    # union vocabulary of the enabled languages (strings)
    union = ["<pad>", "<unk>", "<bos>"]
    uidx = {t: i for i, t in enumerate(union)}
    for l in langs:
        for t in vocabs[l][3:]:
            if t not in uidx:
                uidx[t] = len(union)
                union.append(t)

    # (a) per-language models
    spec = dict(kv.split("=") for kv in a.a.split(","))
    models = {l: (KNLM(work, l) if spec[l].startswith("kn") else NeuralLM(Path(spec[l]))) for l in langs}
    maps = {l: np.array([uidx.get(t, 1) for t in models[l].tokens]) for l in langs}
    res = {}
    ma = Metrics(union)
    mb = Metrics(union) if a.b else None
    leak = [0, 0]
    if a.b:
        mbm = NeuralLM(Path(a.b))
        bmap = np.array([mbm.index.get(t, 1) for t in union])  # union -> multilingual model ids
        allowed = np.zeros(len(mbm.tokens), dtype=bool)
        allowed[bmap[3:]] = True
        word_b = Metrics(mbm.tokens).is_word
    count = 0
    for lang, toks in paras:
        if count >= a.n:
            break
        lps = {l: models[l].seq_logprobs(toks, f"<lang:{l}>") for l in langs}
        post = np.full(len(langs), 1.0 / len(langs))
        if a.b:
            lpb = mbm.seq_logprobs(toks, f"<lang:{langs[0]}>")
        for t, tok in enumerate(toks):
            target = uidx.get(tok, 1)
            prior = (1 - a.switch) * post + a.switch * (1 - post) / max(len(langs) - 1, 1)
            mix = np.zeros(len(union))
            for k, l in enumerate(langs):
                p = np.exp(lps[l][t])
                np.add.at(mix, maps[l], prior[k] * p)
            if ma.is_word[target] or target == 1:
                ma.add(np.log(mix + 1e-12), target)
                if a.b:
                    row = lpb[t]
                    s = np.where(word_b, row, -np.inf)
                    top = np.argsort(-s)[:3]
                    leak[0] += int((~allowed[top]).sum())
                    leak[1] += 3
                    masked = np.where(allowed, row, -np.inf)
                    mb.add(masked[bmap], target)
                count += 1
            # posterior update with the observed token
            like = np.array([np.exp(lps[l][t][models[l].index.get(tok, 1)]) for l in langs])
            post = prior * like
            post /= post.sum()
    res["a_mixture"] = ma.result()
    if a.b:
        res["b_multilingual_masked"] = mb.result()
        res["b_unmasked_top3_outside_enabled_langs"] = round(leak[0] / max(leak[1], 1), 4)
    print(json.dumps({"langs": langs, "split": a.split, "a": a.a, "b": a.b, "switch": a.switch, **res}))


if __name__ == "__main__":
    main()
