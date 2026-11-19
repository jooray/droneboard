"""A language without diacritics ("sk_nd", "cs_nd"): LM data and a warm-start checkpoint.

usage: make_nd.py <workdir> <lang> <ckpt_dir> [train_stream]
  train_stream: the open training ids to convert (default <lang>.train; sk uses sk.big.train)
Every Slovak word is reduced to base letters (NFD, combining marks dropped: ä->a, ô->o, ľ->l,
ĺ->l, ŕ->r, ...). Words that become equal are merged ("súd"/"sud" -> "sud"), with their counts
summed. Because the stripped forms come from the 50k Slovak vocabulary, the stripped vocabulary
has fewer than 50k words; <unk> stays <unk> (the raw text of out-of-vocabulary words is not kept).
writes  vocab/sk_nd.txt, vocab/sk_nd.counts.npy, vocab/sk_nd.stats.json
        ids/sk_nd.{train,test,ptrain,ptest}.u16   (train = the deduplicated big Slovak stream)
        ckpt/<sk ckpt>-nd-init/best.safetensors   embedding rows = count-weighted mean of the merged
                                                  Slovak rows, everything else copied (warm start)
        ids/sk_nd.ready
"""
import json
import shutil
import sys
import unicodedata
from pathlib import Path

import numpy as np
from safetensors.numpy import load_file, save_file

work, lang, ck = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
train_src = sys.argv[4] if len(sys.argv) > 4 else f"{lang}.train"
nd_name = f"{lang}_nd"


def strip(w):
    return "".join(c for c in unicodedata.normalize("NFD", w) if unicodedata.category(c) != "Mn")


sk = (work / "vocab" / f"{lang}.txt").read_text(encoding="utf-8").split("\n")[:-1]
counts = np.load(work / "vocab" / f"{lang}.counts.npy").astype(np.float64)
specials = sk[:3]
merged = {}
for i, w in enumerate(sk[3:], 3):
    s = strip(w)
    merged[s] = merged.get(s, 0.0) + counts[i]
nd = specials + [w for w, _ in sorted(merged.items(), key=lambda kv: -kv[1])]
index = {w: i for i, w in enumerate(nd)}
lmap = np.array([0, 1, 2] + [index[strip(w)] for w in sk[3:]], dtype=np.uint16)
nd_counts = np.array([0, 0, 0] + [merged[w] for w in nd[3:]], dtype=np.int64)
(work / "vocab" / f"{nd_name}.txt").write_text("\n".join(nd) + "\n", encoding="utf-8")
np.save(work / "vocab" / f"{nd_name}.counts.npy", nd_counts)

stats = {"lang": nd_name, "vocab": len(nd), "from_vocab": len(sk), "merged_words": len(sk) - len(nd)}
for src, dst in [(train_src, f"{nd_name}.train"), (f"{lang}.test", f"{nd_name}.test"),
                 (f"{lang}.ptrain", f"{nd_name}.ptrain"), (f"{lang}.ptest", f"{nd_name}.ptest")]:
    a = np.memmap(work / "ids" / f"{src}.u16", dtype=np.uint16, mode="r")
    with open(work / "ids" / f"{dst}.u16", "wb") as f:
        for s in range(0, len(a), 1 << 26):
            lmap[a[s:s + (1 << 26)]].tofile(f)
    stats[dst.split(".")[1]] = int(len(a))

# warm start: merge embedding rows (count-weighted), keep the rest of the model
w = load_file(str(ck / "best.safetensors"))
E = w["emb.weight"].astype(np.float32)
new = np.zeros((len(nd), E.shape[1]), dtype=np.float64)
wsum = np.zeros(len(nd))
cw = np.maximum(counts, 1.0)
np.add.at(new, lmap.astype(np.int64), E.astype(np.float64) * cw[:, None])
np.add.at(wsum, lmap.astype(np.int64), cw)
new /= wsum[:, None]
w["emb.weight"] = new.astype(w["emb.weight"].dtype)
init = work / "ckpt" / f"{ck.name}-nd-init"
init.mkdir(parents=True, exist_ok=True)
save_file(w, str(init / "best.safetensors"))
cfg = json.loads((ck / "config.json").read_text())
cfg.update({"vocab": len(nd), "langs": nd_name, "out": init.name})
(init / "config.json").write_text(json.dumps(cfg))
(init / "tokens.txt").write_text("\n".join(nd) + "\n", encoding="utf-8")
json.dump(stats, open(work / "vocab" / f"{nd_name}.stats.json", "w"), indent=1)
(work / "ids" / f"{nd_name}.ready").touch()
print(json.dumps(stats))
