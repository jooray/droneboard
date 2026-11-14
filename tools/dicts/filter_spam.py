#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Drop spam documents (porn, dating, casino, pharmacy) from a FineWeb-style corpus.

usage: filter_spam.py <lang> <out.txt> <corpus.txt>...

Documents are separated by blank lines (fetch_corpus.py output). A document is dropped when at
least 3 of its tokens, and at least 0.4 % of them, are on the spam list below. Without this the
Czech FineWeb-2 shard ranks "sex" as the 27th most frequent word.
"""
import re
import sys

SPAM = {
    "cs": """sex sexu sexem sexy sexuální porno porna pornem pornu erotika erotiky erotické erotický eroticky
        erotická seznamka seznamky seznamce escort escortní holky holek holkami nahé nahá nahý nahou kundy kunda
        kundičky píchání šukání šukat šuká mrdání mrdat milf xxx lesbičky lesbické gay gayové prsatá prsaté
        kozy kozatá masturbace orgasmus casino kasino kasina kasinu výherní automaty viagra cialis""",
    "sk": """sex sexu sexom sexy sexuálne porno porna pornom erotika erotické erotický zoznamka zoznamky escort
        baby nahé nahá nahý píchanie jebanie milf xxx lesby gay casino kasíno kasína viagra cialis""",
    "en": """sex sexy porn porno xxx milf escort escorts nude naked pussy cock fuck fucking hardcore camgirl
        casino casinos viagra cialis""",
}
TOKEN = re.compile(r"[^\W\d_]+")


def main():
    lang, out_path, *paths = sys.argv[1:]
    spam = set(SPAM[lang].split())
    kept = dropped = 0
    with open(out_path, "w", encoding="utf-8") as out:
        for path in paths:
            doc = []
            with open(path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    if line.strip():
                        doc.append(line)
                        continue
                    if doc:
                        text = "".join(doc)
                        toks = TOKEN.findall(text.lower())
                        hits = sum(t in spam for t in toks)
                        if hits >= 3 and hits >= 0.004 * len(toks):
                            dropped += 1
                        else:
                            out.write(text + "\n")
                            kept += 1
                        doc = []
                if doc:
                    out.write("".join(doc) + "\n")
                    kept += 1
    print(f"{lang}: kept {kept:,} documents, dropped {dropped:,} as spam", file=sys.stderr)


if __name__ == "__main__":
    main()
