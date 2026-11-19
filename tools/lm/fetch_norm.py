"""Stream open text corpora (FineWeb-2, FineWeb, Wikipedia) and write normalized,
lowercased, space-tokenized paragraphs, one per line. No parquet is stored.

usage: fetch_norm.py <source> <lang> <max_words> <out.txt> [--skip N] [--corpus raw.txt.gz]
  source: fw2 (FineWeb-2, ODC-By) | fw (FineWeb sample-10BT, ODC-By) | wiki (wikimedia/wikipedia, CC BY-SA)
  lang:   sk | cs | en | de | es | nl | hu
  --skip N        row groups of the first file to skip (continue an earlier fetch)
  --corpus PATH   also write the documents in original case and punctuation, gzip, blank line between
                  documents (the tools/dicts/fetch_corpus.py format, for build_dicts.py / count_ngrams.py)
Paragraph = one line. Tokens: words (letters + inner apostrophes), punctuation . , ! ? : ;
and <num> for numbers. Other symbols are dropped. A row group that times out is retried 5 times.
Exits with code 3 when free disk space drops below 10 GB.
"""
import argparse
import gzip
import re
import shutil
import sys
import time

import fsspec
import pyarrow.parquet as pq

ap = argparse.ArgumentParser()
ap.add_argument("source")
ap.add_argument("lang")
ap.add_argument("max_words", type=int)
ap.add_argument("out")
ap.add_argument("skip_pos", nargs="?", type=int, default=0)  # old positional form
ap.add_argument("--skip", type=int, default=0)
ap.add_argument("--corpus")
a = ap.parse_args()
source, lang, max_words, out_path = a.source, a.lang, a.max_words, a.out
skip = a.skip or a.skip_pos

HF = "https://huggingface.co/datasets"
FW2 = {"sk": "slk_Latn", "cs": "ces_Latn", "de": "deu_Latn", "es": "spa_Latn", "nl": "nld_Latn", "hu": "hun_Latn"}
URLS = {
    "fw2": [f"{HF}/HuggingFaceFW/fineweb-2/resolve/main/data/{FW2.get(lang, '')}/train/000_{i:05d}.parquet" for i in range(8)],
    "fw": [f"{HF}/HuggingFaceFW/fineweb/resolve/main/sample/10BT/00{i}_00000.parquet" for i in range(1, 6)],
    "wiki": [f"{HF}/wikimedia/wikipedia/resolve/main/20231101.{lang}/train-{i:05d}-of-{{n}}.parquet" for i in range(41)],
}
WIKI_FILES = {"sk": 1, "cs": 4, "en": 41, "de": 20, "es": 13, "nl": 6, "hu": 4}

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


def read_group(pf, rg):
    for attempt in range(5):
        try:
            return pf.read_row_group(rg, columns=["text"]).column("text").to_pylist()
        except Exception as e:  # fsspec timeouts on long streams
            print("retry", rg, attempt, type(e).__name__, file=sys.stderr, flush=True)
            time.sleep(10 * (attempt + 1))
    raise SystemExit(f"row group {rg} failed 5 times")


urls = URLS[source]
if source == "wiki":
    n = WIKI_FILES[lang]
    urls = [u.replace("{n}", f"{n:05d}") for u in urls[:n]]

fs = fsspec.filesystem("https")
written = 0
raw = gzip.open(a.corpus, "wt", encoding="utf-8", compresslevel=6) if a.corpus else None
with open(out_path, "w", encoding="utf-8") as out:
    for url in urls:
        try:
            f = fs.open(url, block_size=16 << 20)
            pf = pq.ParquetFile(f)
        except Exception as e:  # missing shard
            print("skip", url, e, file=sys.stderr)
            continue
        first = skip if url == urls[0] else 0
        for rg in range(first, pf.num_row_groups):
            if shutil.disk_usage(out_path).free < 10e9:
                print("STOP: less than 10 GB free", file=sys.stderr)
                sys.exit(3)
            for doc in read_group(pf, rg):
                if raw:
                    raw.write(doc.replace("\r", "") + "\n\n")
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
if raw:
    raw.close()
print("done", source, lang, written, file=sys.stderr)
