"""Fetch the text column of the first N parquet row groups of one shard of a
FineWeb dataset (FineWeb-2 per language, or the FineWeb English sample). ODC-By.

usage: fetch_corpus.py <lang> <n_rowgroups> <out_dir>
  lang: a FineWeb-2 config such as slk_Latn / ces_Latn, or "eng" for FineWeb sample-10BT
writes out_dir/corpus.txt (documents separated by blank lines)"""
import os
import sys

import fsspec
import pyarrow.parquet as pq

lang, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
if lang == "eng":
    url = "https://huggingface.co/datasets/HuggingFaceFW/fineweb/resolve/main/sample/10BT/000_00000.parquet"
else:
    url = f"https://huggingface.co/datasets/HuggingFaceFW/fineweb-2/resolve/main/data/{lang}/train/000_00000.parquet"
os.makedirs(out, exist_ok=True)
fs = fsspec.filesystem("https")
f = fs.open(url, block_size=16 << 20)
pf = pq.ParquetFile(f)
n = min(n, pf.num_row_groups)
with open(os.path.join(out, "corpus.txt"), "w") as o:
    for rg in range(n):
        for doc in pf.read_row_group(rg, columns=["text"]).column("text").to_pylist():
            o.write(doc.replace("\r", "") + "\n\n")
        if rg % 20 == 0:
            print(lang, rg, "/", n, file=sys.stderr, flush=True)
