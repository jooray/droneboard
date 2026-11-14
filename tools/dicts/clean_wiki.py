#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Clean a normalized Wikipedia dump (wikimedia/wikipedia, CC BY-SA; lowercased, space-tokenized,
numbers written as <num>) for word counting: <num> becomes a sentence break, and lines with table/markup leftovers ("align right",
"colspan") are dropped.

usage: clean_wiki.py <wiki.txt> <out.txt>
"""
import re
import sys

MARKUP = re.compile(r"\b(align|colspan|rowspan|bgcolor|style|width|valign|cellpadding|cellspacing|px|nowrap|class)\b")
src, dst = sys.argv[1], sys.argv[2]
kept = dropped = 0
with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as o:
    for line in f:
        if MARKUP.search(line):
            dropped += 1
            continue
        o.write(line.replace("<num>", " . "))
        kept += 1
print("kept", kept, "dropped", dropped)
