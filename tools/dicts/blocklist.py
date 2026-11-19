#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Words that must never get into a shipped dictionary, whatever the corpora contain.

The list is a local, untracked text file, so that the words themselves are not published with
the repository. It is looked up in this order:

    1. the path given to load() (the scripts' --blocklist option)
    2. the environment variable DRONEBOARD_BLOCKLIST
    3. tools/dicts/blocklist.local.txt (in .gitignore)

No file means an empty list. One entry per line, `#` starts a comment:

    word      blocks the word in any case, with or without diacritics, also with 's
    stem*     blocks every word that starts with the stem (inflected forms)

A blocked word is left out of the word lists, counts as an out-of-vocabulary token in the n-gram
counts (it breaks the chain, so no bigram or trigram contains it) and is not added from the
personal corpus.

    blocklist.py <words.tsv | list.combined | tokens.txt> ...    prints the blocked entries of the files
"""
import os
import sys
import unicodedata

DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "blocklist.local.txt")


def fold(token):
    """Lowercase, without combining marks and without a possessive 's."""
    s = "".join(c for c in unicodedata.normalize("NFD", token.lower().replace("’", "'"))
                if unicodedata.category(c) != "Mn")
    return s[:-2] if s.endswith("'s") else s


class Blocklist:
    def __init__(self, entries=()):
        self.exact = set()
        self.prefixes = ()
        prefixes = []
        for e in entries:
            e = e.split("#", 1)[0].strip()
            if not e:
                continue
            if e.endswith("*"):
                prefixes.append(fold(e[:-1]))
            else:
                self.exact.add(fold(e))
        self.prefixes = tuple(p for p in prefixes if p)
        self._seen = {}

    def __bool__(self):
        return bool(self.exact or self.prefixes)

    def __contains__(self, token):
        if not self:
            return False
        hit = self._seen.get(token)
        if hit is None:
            f = fold(token)
            hit = self._seen[token] = f in self.exact or (bool(self.prefixes) and f.startswith(self.prefixes))
        return hit


def load(path=None):
    path = path or os.environ.get("DRONEBOARD_BLOCKLIST") or DEFAULT
    if not os.path.exists(path):
        if path != DEFAULT:
            raise SystemExit(f"blocklist {path} not found")
        return Blocklist()
    with open(path, encoding="utf-8") as f:
        bl = Blocklist(f)
    print(f"blocklist: {len(bl.exact)} words, {len(bl.prefixes)} stems from {path}", file=sys.stderr)
    return bl


def main():
    bl = load()
    found = 0
    for path in sys.argv[1:]:
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            body = line.strip()
            if body.startswith(("word=", "bigram=", "shortcut=")):  # .combined
                toks = [body.split(",f=")[0].split("=", 1)[1]]
            else:
                toks = body.split("\t")
            if any(t in bl for t in toks):
                print(f"{path}:{n}: {body}")
                found += 1
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
