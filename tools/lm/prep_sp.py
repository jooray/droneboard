"""Subword (SentencePiece) variant of prepare.py, for the word-vs-subword comparison.

usage: prep_sp.py <workdir> <lang> <pieces> <personal_clean_txt>
writes vocab/<lang>.sp.model, vocab/<lang>.sp.txt (pieces, id = line), ids/<lang>.sp.{train,test,ptrain,ptest}.u16
Same 1%/10% held-out split as prepare.py. Ids: 0 pad, 1 unk, 2 bos (paragraph start).
"""
import glob
import hashlib
import random
import re
import sys
from pathlib import Path

import numpy as np
import sentencepiece as spm

work, lang, pieces, personal = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4]
TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|\d+(?:[.,]\d+)*|[.,!?:;]")


def is_test(line, pct):
    return int(hashlib.md5(line.encode()).hexdigest()[:8], 16) % 100 < pct


files = sorted(glob.glob(str(work / "norm" / f"{lang}.*.txt")))
sample = work / "vocab" / f"{lang}.sp.sample.txt"
rnd = random.Random(0)
with open(sample, "w", encoding="utf-8") as out:
    for fn in files:
        for line in open(fn, encoding="utf-8"):
            if rnd.random() < 0.03 and not is_test(line, 1):
                out.write(line)
prefix = str(work / "vocab" / f"{lang}.sp")
spm.SentencePieceTrainer.train(input=str(sample), model_prefix=prefix, vocab_size=pieces, model_type="unigram",
                               character_coverage=1.0, pad_id=0, unk_id=1, bos_id=2, eos_id=-1,
                               input_sentence_size=3_000_000, shuffle_input_sentence=True,
                               user_defined_symbols=["<num>"], num_threads=8)
sample.unlink()
sp = spm.SentencePieceProcessor(model_file=prefix + ".model")
(work / "vocab" / f"{lang}.sp.txt").write_text("\n".join(sp.id_to_piece(i) for i in range(sp.get_piece_size())) + "\n",
                                                encoding="utf-8")


def encode(lines, out_path):
    with open(out_path, "wb") as out:
        batch = []
        for line in lines:
            batch.append(line.rstrip("\n"))
            if len(batch) == 20000:
                for ids in sp.encode(batch):
                    np.asarray([2] + ids, dtype=np.uint16).tofile(out)
                batch = []
        for ids in sp.encode(batch):
            np.asarray([2] + ids, dtype=np.uint16).tofile(out)


def open_lines(test):
    for fn in files:
        for line in open(fn, encoding="utf-8"):
            if is_test(line, 1) == test:
                yield line


def norm(par):
    return " ".join("<num>" if t[0].isdigit() else t.lower().replace("’", "'") for t in TOKEN.findall(par))


encode(open_lines(False), work / "ids" / f"{lang}.sp.train.u16")
encode(open_lines(True), work / "ids" / f"{lang}.sp.test.u16")
plines = [norm(l) for l in open(personal, encoding="utf-8")]
plines = [l for l in plines if len(l.split()) >= 3]
encode((l for l in plines if not is_test(l, 10)), work / "ids" / f"{lang}.sp.ptrain.u16")
encode((l for l in plines if is_test(l, 10)), work / "ids" / f"{lang}.sp.ptest.u16")
print("done", lang, pieces)
