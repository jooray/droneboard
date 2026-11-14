"""Build Droneboard word lists, bigrams and HeliBoard .combined dictionaries from open data.

Sources (all open):
  - text corpus: FineWeb-2 (per language) or FineWeb sample-10BT (English), ODC-By,
    fetched with fetch_corpus.py
  - spelling filter: Hunspell dictionaries from LibreOffice (only used to *filter*, not shipped)

Never feed SNK / CNK (Slovak / Czech national corpus) data into this script: their licenses
do not allow redistribution.

usage: build_dicts.py <lang> <out_dir> <hunspell_dict_prefix> <corpus.txt>...
  lang: sk | en | cs
writes:
  <out_dir>/<lang>.words.tsv    surface \t keypath \t freq(0-255) \t count (swipe engine)
  <out_dir>/<lang>.bigrams.tsv  prev_lower \t word_lower \t count         (swipe rescoring)
  <out_dir>/<lang>.combined     HeliBoard combined format with bigrams    (-> main_<lang>.dict)
"""
import bisect
import collections
import datetime
import math
import os
import re
import subprocess
import sys
import unicodedata

LANG = {
    # letters a word may contain (lowercase); anything else rejects the token
    "sk": dict(alphabet="aáäbcčdďeéfghiíjklĺľmnňoóôpqrŕsštťuúvwxyýzž", top=320_000,
               one_letter="a v s z o k u i", min_count=4, description="Slovenčina"),
    "cs": dict(alphabet="aábcčdďeéěfghiíjklmnňoópqrřsštťuúůvwxyýzž", top=500_000,
               one_letter="a v s z o k u i", min_count=4, description="Čeština"),
    "en": dict(alphabet="abcdefghijklmnopqrstuvwxyz'", top=160_000,
               one_letter="a I", min_count=4, description="English"),
}

# rank -> frequency curve of the AOSP Slovak list (HeliBoard 0-255 log scale). The swipe
# scoring weights were tuned on this scale, and every language uses the same curve so that
# frequencies stay comparable when several languages are active at once.
RANK_CURVE = [(1, 251), (10, 233), (100, 209), (1000, 187), (3000, 176), (10000, 162),
              (30000, 148), (100000, 127), (200000, 113), (300000, 103), (500000, 88), (1000000, 62)]

TOKEN = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")
SENTENCE = re.compile(r"[.!?\n]+")
MAX_BIGRAMS = 600_000
MIN_BIGRAM_COUNT = 3


def rank_to_freq(rank):
    logs = [math.log10(r) for r, _ in RANK_CURVE]
    x = math.log10(rank)
    i = bisect.bisect_left(logs, x)
    if i == 0:
        return RANK_CURVE[0][1]
    if i >= len(logs):
        return max(1, RANK_CURVE[-1][1] - 1)
    (x0, (_, f0)), (x1, (_, f1)) = (logs[i - 1], RANK_CURVE[i - 1]), (logs[i], RANK_CURVE[i])
    return round(f0 + (f1 - f0) * (x - x0) / (x1 - x0))


def keypath(word):
    """Base letters a-z of a word: diacritics, case and apostrophes removed ("ľahko" -> "lahko")."""
    out = []
    for ch in word.lower():
        if not ch.isalpha():
            continue
        b = unicodedata.normalize("NFD", ch)[0]
        if not ("a" <= b <= "z"):
            return None
        out.append(b)
    return "".join(out) or None


def count_unigrams(paths):
    uni = collections.Counter()
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f):
                uni.update(TOKEN.findall(line.replace("\u2019", "'")))
                if n % 2_000_000 == 0:
                    print(f"  unigrams {p.split('/')[-2]} line {n:,} types {len(uni):,}", file=sys.stderr, flush=True)
    return uni


def hunspell_accepts(words, prefix):
    """Return the subset of words Hunspell accepts (hunspell -G prints correct words)."""
    out = subprocess.run(["hunspell", "-i", "utf-8", "-d", prefix, "-G"], input="\n".join(words), capture_output=True,
                         text=True, check=True).stdout
    return set(out.split("\n")) - {""}


def select_words(lang, uni, prefix):
    cfg = LANG[lang]
    alphabet = set(cfg["alphabet"])
    cand = collections.Counter()
    for w, c in uni.items():
        if c < cfg["min_count"]:
            continue
        if len(w) > 1 and w.isupper():           # ALL-CAPS junk ("TRIM", "ZASTUPITEĽSTVO")
            continue
        if any(ch not in alphabet for ch in w.lower()):  # foreign letters, e.g. Czech ě ř ů in Slovak
            continue
        if len(w) > 1 and any(ch.isupper() for ch in w[1:]):  # mIxEd case
            continue
        cand[w] = c
    # Hunspell filter on the most frequent candidates
    top = [w for w, _ in cand.most_common(cfg["top"] * 3)]
    ok = set()
    for i in range(0, len(top), 50_000):
        ok |= hunspell_accepts(top[i:i + 50_000], prefix)
    # frequent all-lowercase words Hunspell does not know yet ("app", "blog", "online"), but not
    # words typed without diacritics ("dakujem") when a correct spelling of the same key path exists
    total = sum(uni.values())
    common = 3 * total / 1_000_000
    spelled = collections.Counter()
    for w in ok:
        kp = keypath(w)
        if kp:
            spelled[kp] = max(spelled[kp], cand[w])
    ok |= {w for w in top if w.islower() and cand[w] >= common and keypath(w) not in spelled}
    print(f"  candidates {len(top):,}, accepted {len(ok):,}", file=sys.stderr)
    lower = collections.Counter()
    for w, c in cand.items():
        lower[w.lower()] += c
    words = {}
    for w in ok:
        c = cand[w]
        if w[0].isupper() and w.lower() in ok:
            # sentence-initial capitalization of a common word: keep only the lowercase form,
            # unless the capitalized form clearly dominates (proper noun such as "Slovensko")
            if c < 2 * cand.get(w.lower(), 0):
                continue
        words[w] = c
    for w in cfg["one_letter"].split():
        words[w] = max(words.get(w, 0), lower.get(w.lower(), 0), 1)
    ranked = sorted(words.items(), key=lambda kv: -kv[1])[: cfg["top"]]
    return ranked, lower


def count_bigrams(paths, vocab_lower):
    big = collections.Counter()
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f):
                for s in SENTENCE.split(line.replace("\u2019", "'")):
                    toks = [t.lower() for t in TOKEN.findall(s)]
                    for a, b in zip(toks, toks[1:]):
                        if a in vocab_lower and b in vocab_lower:
                            big[(a, b)] += 1
                if n % 2_000_000 == 0:
                    print(f"  bigrams line {n:,} pairs {len(big):,}", file=sys.stderr, flush=True)
    return big


def main():
    lang, out, prefix, *corpus = sys.argv[1:]
    os.makedirs(out, exist_ok=True)
    uni = count_unigrams(corpus)
    ranked, lower = select_words(lang, uni, prefix)
    print(f"  {lang}: {len(ranked):,} words", file=sys.stderr)
    freq = {}
    with open(os.path.join(out, f"{lang}.words.tsv"), "w") as o:
        for i, (w, c) in enumerate(ranked):
            freq[w] = rank_to_freq(i + 1)
            kp = keypath(w)
            if kp:
                o.write(f"{w}\t{kp}\t{freq[w]}\t{lower.get(w.lower(), c)}\n")
    vocab_lower = {w.lower() for w, _ in ranked}
    big = count_bigrams(corpus, vocab_lower)
    kept = [(k, c) for k, c in big.most_common(MAX_BIGRAMS) if c >= MIN_BIGRAM_COUNT]
    with open(os.path.join(out, f"{lang}.bigrams.tsv"), "w") as o:
        for (a, b), c in kept:
            o.write(f"{a}\t{b}\t{c}\n")
    print(f"  {lang}: {len(kept):,} bigrams", file=sys.stderr)

    # HeliBoard combined format; bigram probability on the same 0-255 scale, from P(b|a)
    by_first = collections.defaultdict(list)
    surface_of = {}
    for w, _ in ranked:  # most frequent surface per lowercase form
        surface_of.setdefault(w.lower(), w)
    for (a, b), c in kept:
        by_first[a].append((b, c))
    date = int(datetime.datetime(2026, 11, 13).timestamp())
    with open(os.path.join(out, f"{lang}.combined"), "w") as o:
        o.write(f"dictionary=main:{lang},locale={lang},description={LANG[lang]['description']},"
                f"date={date},version=1\n")
        for w, _ in ranked:
            o.write(f" word={w},f={freq[w]}\n")
            if surface_of.get(w.lower()) != w:
                continue
            nexts = sorted(by_first.get(w.lower(), []), key=lambda x: -x[1])[:48]
            prev_count = max(lower.get(w.lower(), 1), 1)
            for b, c in nexts:
                p = c / prev_count
                bf = max(1, min(255, round(255 + 16 * math.log2(max(p, 1e-12)))))
                o.write(f"  bigram={surface_of[b]},f={bf}\n")


if __name__ == "__main__":
    main()
