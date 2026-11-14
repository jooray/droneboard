"""Build per-language word vocabularies and uint16 token-id streams.

usage: prepare.py <workdir> <lang> <vocab_size> <personal_clean_txt>
reads   <workdir>/norm/<lang>.*.txt (normalized paragraphs, one per line)
writes  <workdir>/vocab/<lang>.txt           one token per line, id = line number
        <workdir>/ids/<lang>.{train,test}.u16  open data (1% of paragraphs held out)
        <workdir>/ids/<lang>.{ptrain,ptest}.u16 personal writing (10% held out)
Ids: 0 <pad>, 1 <unk>, 2 <bos>; every paragraph starts with <bos>.
"""
import glob
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

import numpy as np

work, lang, vsize, personal = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4]
(work / "vocab").mkdir(exist_ok=True)
(work / "ids").mkdir(exist_ok=True)

TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|\d+(?:[.,]\d+)*|[.,!?:;]")


def norm(par):
    return ["<num>" if t[0].isdigit() else t.lower().replace("’", "'") for t in TOKEN.findall(par)]


def is_test(line, pct):
    return int(hashlib.md5(line.encode()).hexdigest()[:8], 16) % 100 < pct


files = sorted(glob.glob(str(work / "norm" / f"{lang}.*.txt")))
counts = Counter()
for fn in files:
    with open(fn, encoding="utf-8") as f:
        for line in f:
            if not is_test(line, 1):
                counts.update(line.split())
specials = ["<pad>", "<unk>", "<bos>"]
vocab = specials + [w for w, _ in counts.most_common(vsize - len(specials))]
index = {w: i for i, w in enumerate(vocab)}
total = sum(counts.values())
covered = sum(counts[w] for w in vocab[3:])
(work / "vocab" / f"{lang}.txt").write_text("\n".join(vocab) + "\n", encoding="utf-8")
counts_arr = np.array([0, 0, 0] + [counts[w] for w in vocab[3:]], dtype=np.int64)
np.save(work / "vocab" / f"{lang}.counts.npy", counts_arr)


def encode(lines, out_path):
    buf = []
    n = 0
    with open(out_path, "wb") as out:
        for line in lines:
            buf.append(2)
            buf.extend(index.get(t, 1) for t in line.split())
            if len(buf) > 1 << 20:
                np.asarray(buf, dtype=np.uint16).tofile(out)
                n += len(buf)
                buf = []
        np.asarray(buf, dtype=np.uint16).tofile(out)
        n += len(buf)
    return n


def open_lines(test):
    for fn in files:
        with open(fn, encoding="utf-8") as f:
            for line in f:
                if is_test(line, 1) == test:
                    yield line


stats = {"lang": lang, "vocab": len(vocab), "train_tokens_counted": total, "coverage": covered / total}
stats["train"] = encode(open_lines(False), work / "ids" / f"{lang}.train.u16")
stats["test"] = encode(open_lines(True), work / "ids" / f"{lang}.test.u16")

plines = [" ".join(norm(l)) for l in open(personal, encoding="utf-8")]
plines = [l for l in plines if len(l.split()) >= 3]
stats["ptrain"] = encode((l for l in plines if not is_test(l, 10)), work / "ids" / f"{lang}.ptrain.u16")
stats["ptest"] = encode((l for l in plines if is_test(l, 10)), work / "ids" / f"{lang}.ptest.u16")
json.dump(stats, open(work / "vocab" / f"{lang}.stats.json", "w"), indent=1)
print(json.dumps(stats))
