"""Stream open text corpora (FineWeb-2, FineWeb, Wikipedia) and write normalized,
lowercased, space-tokenized paragraphs, one per line.

usage: fetch_norm.py <source> <lang> <max_words> <out.txt>
  source: fw2 (FineWeb-2, ODC-By) | fw (FineWeb sample-10BT, ODC-By) | wiki (wikimedia/wikipedia, CC BY-SA)
  lang:   sk | cs | en
Paragraph = one line. Tokens: words (letters + inner apostrophes), punctuation . , ! ? : ;
and <num> for numbers. Other symbols are dropped.
"""
import re
import sys

import fsspec
import pyarrow.parquet as pq

source, lang, max_words, out_path = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]

HF = "https://huggingface.co/datasets"
FW2 = {"sk": "slk_Latn", "cs": "ces_Latn"}
URLS = {
    "fw2": [f"{HF}/HuggingFaceFW/fineweb-2/resolve/main/data/{FW2.get(lang, '')}/train/000_0000{i}.parquet" for i in range(4)],
    "fw": [f"{HF}/HuggingFaceFW/fineweb/resolve/main/sample/10BT/00{i}_00000.parquet" for i in range(1, 6)],
    "wiki": [f"{HF}/wikimedia/wikipedia/resolve/main/20231101.{lang}/train-{i:05d}-of-{{n}}.parquet" for i in range(41)],
}
WIKI_FILES = {"sk": 1, "cs": 4, "en": 41}

TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|\d+(?:[.,]\d+)*|[.,!?:;]")


def norm(par: str):
    toks = []
    for m in TOKEN.finditer(par):
        t = m.group(0)
        if t[0].isdigit():
            toks.append("<num>")
        else:
            toks.append(t.lower().replace("’", "'"))
    return toks


urls = URLS[source]
if source == "wiki":
    n = WIKI_FILES[lang]
    urls = [u.replace("{n}", f"{n:05d}") for u in urls[:n]]

fs = fsspec.filesystem("https")
written = 0
with open(out_path, "w", encoding="utf-8") as out:
    for url in urls:
        try:
            f = fs.open(url, block_size=16 << 20)
            pf = pq.ParquetFile(f)
        except Exception as e:  # missing shard
            print("skip", url, e, file=sys.stderr)
            continue
        for rg in range(pf.num_row_groups):
            for doc in pf.read_row_group(rg, columns=["text"]).column("text").to_pylist():
                for par in doc.split("\n"):
                    toks = norm(par)
                    if len(toks) < 4:
                        continue
                    out.write(" ".join(toks) + "\n")
                    written += len(toks)
            print(source, lang, url.rsplit("/", 1)[-1], rg, written, file=sys.stderr, flush=True)
            if written >= max_words:
                break
        if written >= max_words:
            break
print("done", source, lang, written, file=sys.stderr)
