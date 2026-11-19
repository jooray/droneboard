#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build a personal dictionary for Droneboard from text you have already written.

Three steps, each a subcommand. Only the Python standard library is needed.

    personal_dict.py known   Droneboard.apk --lang sk en -o known.txt
    personal_dict.py collect notes/ mail.txt --known known.txt -o candidates.tsv
    personal_dict.py export  cleaned.txt -o droneboard-words.txt

known     lists every word the keyboard already knows in the given languages, read from the
          dictionaries inside the APK (or from app/src/main/assets of a checkout).
collect   reads your text (files, folders, an SQLite query, standard input), counts the words and
          keeps those the keyboard does not know. The result is a table to review, not a dictionary:
          it still contains typos and junk. Clean it by hand or let an assistant do it.
export    turns the cleaned list (one word per line) into the file that Droneboard imports in
          Settings > Dictionaries > Personal dictionary > Import from file.

Nothing leaves the computer: the script reads local files and writes local files.
"""
import argparse
import collections
import os
import re
import sqlite3
import struct
import sys
import unicodedata
import zipfile

# ---------------------------------------------------------------------------------------------
# known: the words inside Droneboard's dictionaries
# ---------------------------------------------------------------------------------------------


def _varint(b, i):
    n = s = 0
    while True:
        x = b[i]
        i += 1
        n |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return n, i


def kpd_words(b):
    """Words of a swipe dictionary (.kpd, version 2); the format is in native/droneswipe/FORMAT.md."""
    if b[:4] != b"KPD1":
        raise ValueError("not a .kpd file")
    head = "<IIIIIdI"
    version, n_lower, _, _, _, _, n_sec = struct.unpack_from(head, b, 4)
    if version != 2:
        raise ValueError(f"unknown .kpd version {version}")
    off = 4 + struct.calcsize(head)
    sec = {}
    for _ in range(n_sec):
        sec[b[off:off + 4]] = struct.unpack_from("<II", b, off + 4)
        off += 12
    o, ln = sec[b"LOWR"]
    i, prev, lowers = o, b"", []
    while i < o + ln:
        shared, i = _varint(b, i)
        n, i = _varint(b, i)
        prev = prev[:shared] + b[i:i + n]
        i += n
        lowers.append(prev.decode())
    assert len(lowers) == n_lower
    i, _ = sec[b"WREC"]
    for low in lowers:
        spec, i = _varint(b, i)
        if spec & 4:  # explicit key path, not needed here
            n, i = _varint(b, i)
            i += n
        if spec & 1:
            yield low
        if spec & 2:
            yield low[:1].upper() + low[1:]
        for _ in range(spec >> 3):
            n, i = _varint(b, i)
            yield b[i:i + n].decode()
            i += n


def aosp_dict_words(d):
    """Words of an AOSP binary dictionary (.dict, format version 2, as HeliBoard and Droneboard ship)."""
    if d[:4] != b"\x9b\xc1\x3a\xfe":
        raise ValueError("not an AOSP .dict file")
    start = struct.unpack(">I", d[8:12])[0]

    def char(p):
        if d[p] >= 0x20 or d[p] == 0x1F:
            return d[p], p + 1
        return (d[p] << 16) | (d[p + 1] << 8) | d[p + 2], p + 3

    stack = [(start, "")]
    while stack:
        p, prefix = stack.pop()
        n = d[p]
        p += 1
        if n & 0x80:
            n = ((n & 0x7F) << 8) | d[p]
            p += 1
        for _ in range(n):
            flags = d[p]
            p += 1
            s = ""
            if flags & 0x20:  # several characters
                while True:
                    c, p = char(p)
                    if c == 0x1F:
                        break
                    s += chr(c)
            else:
                c, p = char(p)
                s = chr(c)
            is_word = bool(flags & 0x10)
            if is_word:
                p += 1  # frequency
            kind, child = flags & 0xC0, 0
            if kind == 0x40:
                child = p + d[p] if d[p] else 0
                p += 1
            elif kind == 0x80:
                v = (d[p] << 8) | d[p + 1]
                child = p + v if v else 0
                p += 2
            elif kind == 0xC0:
                v = (d[p] << 16) | (d[p + 1] << 8) | d[p + 2]
                child = p + v if v else 0
                p += 3
            if flags & 0x08:  # shortcuts
                p += (d[p] << 8) | d[p + 1]
            if flags & 0x04:  # bigrams
                while True:
                    bf = d[p]
                    p += 1 + {0x10: 1, 0x20: 2, 0x30: 3}[bf & 0x30]
                    if not bf & 0x80:
                        break
            if is_word:
                yield prefix + s
            if child:
                stack.append((child, prefix + s))


def _asset_files(source):
    """(name, bytes) of every file under assets/dicts and assets/swipe of an APK or a checkout."""
    if os.path.isdir(source):
        root = source
        for sub in ("app/src/main/assets", "assets"):
            if os.path.isdir(os.path.join(source, sub)):
                root = os.path.join(source, sub)
        for sub in ("dicts", "swipe"):
            folder = os.path.join(root, sub)
            if not os.path.isdir(folder):
                continue
            for name in sorted(os.listdir(folder)):
                path = os.path.join(folder, name)
                if os.path.isfile(path):
                    yield name, (lambda p=path: open(p, "rb").read())
    else:
        z = zipfile.ZipFile(source)
        for info in z.infolist():
            parts = info.filename.split("/")
            if len(parts) == 3 and parts[0] == "assets" and parts[1] in ("dicts", "swipe"):
                yield parts[2], (lambda i=info: z.read(i))


def _dictionary_language(name):
    """sk.kpd -> sk, main_en-US.dict -> en, vocab_sk.dict -> sk; None for emoji and model files."""
    base, _, ext = name.rpartition(".")
    if ext == "kpd":
        return base.split("_")[0]
    if ext == "dict" and base.split("_")[0] in ("main", "vocab"):
        return re.split(r"[-_]", base.split("_", 1)[1])[0]
    return None


def cmd_known(args):
    wanted = {lang.lower() for lang in args.lang}
    words, used = set(), []
    for name, read in _asset_files(args.source):
        if _dictionary_language(name) not in wanted:
            continue
        data = read()
        found = list(kpd_words(data) if name.endswith(".kpd") else aosp_dict_words(data))
        words.update(found)
        used.append(f"{name} ({len(found)})")
    if not words:
        sys.exit(f"no dictionaries for {sorted(wanted)} in {args.source}")
    with open(args.output, "w", encoding="utf-8") as f:
        for w in sorted(words):
            f.write(w + "\n")
    print(f"{len(words)} words from {', '.join(used)} -> {args.output}", file=sys.stderr)


# ---------------------------------------------------------------------------------------------
# collect: words in your text that the keyboard does not know
# ---------------------------------------------------------------------------------------------

# prose only: data files (json, csv, html) are full of field names that look like unknown words;
# add them with --ext when your text really lives there
TEXT_EXTENSIONS = {".txt", ".md", ".markdown", ".org", ".rst", ".tex", ".eml", ".mbox", ".vtt", ".srt"}
# long runs without a space (base64, hashes, minified data) go first, so the patterns below stay fast
LONG_RUN = re.compile(r"\S{60,}")
URL = re.compile(r"""(?:https?://|www\.)\S+|[\w.+-]{1,64}@[\w.-]{1,64}|\b[\w-]{1,40}(?:\.[\w-]{1,40}){0,3}\.(?:com|org|net|io|sk|cz|eu|dev|app)\b\S*""", re.I)
CODE = re.compile(r"```.{0,20000}?```|`[^`\n]{0,200}`|<[^>\n]{1,200}>", re.S)
# a word: letters, with ' ’ or - inside (don't, e-mail). No digits, no underscores.
WORD = re.compile(r"[^\W\d_]+(?:['’\-][^\W\d_]+)*")
SENTENCE_END = set(".!?:\n\"“„(")


def strip_diacritics(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def read_documents(args):
    """Yields (label, text). A document is one file, one row of the query, or standard input."""
    extensions = TEXT_EXTENSIONS | {"." + e.lstrip(".").lower() for e in args.ext or []}
    for path in args.paths:
        if path == "-":
            yield "stdin", sys.stdin.read()
        elif os.path.isdir(path):
            for folder, dirs, files in os.walk(path):
                dirs[:] = [d for d in dirs if not d.startswith(".") and d not in ("node_modules", "__pycache__")]
                for name in files:
                    if os.path.splitext(name)[1].lower() in extensions and not name.startswith("."):
                        full = os.path.join(folder, name)
                        if os.path.getsize(full) <= args.max_file_mb * 1024 * 1024:
                            yield full, _read_text(full)
        else:
            yield path, _read_text(path)
    if args.sqlite:
        if not args.query:
            sys.exit("--sqlite needs --query, e.g. --query \"select body from messages where mine = 1\"")
        db = sqlite3.connect(f"file:{args.sqlite}?mode=ro", uri=True)
        for n, row in enumerate(db.execute(args.query)):
            if row and isinstance(row[0], str):
                yield f"{args.sqlite}#{n}", row[0]


def _read_text(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8", errors="ignore")


def cmd_collect(args):
    known = set()
    for path in args.known:
        with open(path, encoding="utf-8") as f:
            known.update(line.strip() for line in f if line.strip())
    known_lower = {w.lower() for w in known}
    known_plain = {strip_diacritics(w) for w in known_lower}

    count = collections.Counter()          # lowercase -> occurrences
    surfaces = collections.defaultdict(collections.Counter)  # lowercase -> spelling -> occurrences away from a sentence start
    documents = collections.defaultdict(set)
    example = {}
    n_docs = 0
    for label, text in read_documents(args):
        n_docs += 1
        text = URL.sub(" ", CODE.sub(" ", LONG_RUN.sub(" ", text)))
        for m in WORD.finditer(text):
            word = m.group().strip("'’-")
            if not args.min_length <= len(word) <= 30:
                continue
            low = word.lower()
            count[low] += 1
            documents[low].add(n_docs)
            before = text[max(0, m.start() - 2):m.start()].strip()
            if before and before[-1] not in SENTENCE_END:
                surfaces[low][word] += 1  # the capital of a sentence start says nothing about the word
            if low not in example:
                snippet = text[max(0, m.start() - 30):m.end() + 30]
                example[low] = " ".join(snippet.split())

    rows, dropped_plain = [], 0
    for low, n in count.items():
        if n < args.min_count or len(documents[low]) < args.min_documents:
            continue
        if low in known_lower:
            continue
        # "dakujem" for "ďakujem": the keyboard already types that from the word with accents
        if not args.keep_unaccented and strip_diacritics(low) in known_plain:
            dropped_plain += 1
            continue
        if low.endswith("'s") or low.endswith("’s"):
            if low[:-2] in known_lower:
                continue
        spelling = surfaces[low].most_common(1)[0][0] if surfaces[low] else low
        if spelling.isupper() and len(spelling) > 5 and surfaces[low][spelling] < 0.8 * sum(surfaces[low].values()):
            spelling = low  # shouting, not an abbreviation
        rows.append((n, len(documents[low]), spelling, example[low]))
    rows.sort(key=lambda r: (-r[0], r[2]))
    with open(args.output, "w", encoding="utf-8") as f:
        f.write("# word\tcount\tdocuments\texample\n")
        for n, docs, spelling, ex in rows:
            f.write(f"{spelling}\t{n}\t{docs}\t{ex}\n")
    print(f"{n_docs} documents, {len(count)} different words, {len(rows)} candidates -> {args.output}"
          f" ({dropped_plain} left out as known words typed without accents)", file=sys.stderr)


# ---------------------------------------------------------------------------------------------
# export: the file Droneboard imports
# ---------------------------------------------------------------------------------------------


def cmd_export(args):
    known_lower = set()
    for path in args.known or []:
        with open(path, encoding="utf-8") as f:
            known_lower.update(line.strip().lower() for line in f if line.strip())
    words, seen = [], set()

    def add(entry):
        entry = " ".join(entry.split())
        if not entry or entry.lower() in seen:
            return
        if entry.lower() in known_lower:
            print(f"already in the dictionary, left out: {entry}", file=sys.stderr)
            return
        if len(entry) > 48:
            print(f"too long, left out: {entry[:40]}...", file=sys.stderr)
            return
        seen.add(entry.lower())
        words.append(entry)

    for path in args.lists:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if line.strip() and not line.startswith("#"):
                    add(line.split("\t")[0])
    for entry in args.add or []:
        for piece in entry.split(","):
            add(piece)
    with open(args.output, "w", encoding="utf-8") as f:
        f.write("# Droneboard personal dictionary. Columns, separated by a tab: word, locale, shortcut, frequency.\n")
        f.write("# Only the word is required. An empty locale means all languages.\n")
        for w in sorted(words, key=str.lower):
            f.write(f"{w}\t{args.locale}\n" if args.locale else f"{w}\n")
    print(f"{len(words)} words -> {args.output}", file=sys.stderr)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    k = sub.add_parser("known", help="list the words Droneboard already knows")
    k.add_argument("source", help="the Droneboard APK, or a checkout of the repository")
    k.add_argument("--lang", nargs="+", required=True, help="language codes, e.g. sk en")
    k.add_argument("-o", "--output", required=True)
    k.set_defaults(run=cmd_known)

    c = sub.add_parser("collect", help="find words in your text that Droneboard does not know")
    c.add_argument("paths", nargs="*", help="text files and folders; - for standard input")
    c.add_argument("--known", nargs="+", required=True, help="word list(s) from the known step")
    c.add_argument("--sqlite", help="an SQLite database to read text from (opened read-only)")
    c.add_argument("--query", help="SQL that returns the text in its first column")
    c.add_argument("--min-count", type=int, default=3, help="how often a word must occur (default 3)")
    c.add_argument("--min-documents", type=int, default=1, help="in how many files or rows (default 1)")
    c.add_argument("--ext", nargs="+", help="more file extensions to read in folders, e.g. json csv html")
    c.add_argument("--min-length", type=int, default=3)
    c.add_argument("--max-file-mb", type=float, default=20)
    c.add_argument("--keep-unaccented", action="store_true",
                   help="keep words that are a known word without its accents")
    c.add_argument("-o", "--output", required=True)
    c.set_defaults(run=cmd_collect)

    e = sub.add_parser("export", help="write the file that Droneboard imports")
    e.add_argument("lists", nargs="*", help="cleaned word lists, one word per line (extra columns are ignored)")
    e.add_argument("--add", action="append", help="more words or names, separated by commas")
    e.add_argument("--known", nargs="+", help="leave out what is in these word lists")
    e.add_argument("--locale", default="", help="e.g. sk or en_US; empty (default) means all languages")
    e.add_argument("-o", "--output", required=True)
    e.set_defaults(run=cmd_export)

    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
