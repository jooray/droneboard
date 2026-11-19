"""Run tools/dicts/build_dicts.py for languages the repo copy does not list yet (de, es, nl),
with a key path that also maps ß to "ss" (NFD has no decomposition for ß, so German words
like "straße" were dropped from words.tsv). Spanish ñ already maps to n through NFD.

usage: swipe_inputs.py <tools/dicts dir> <lang> <out_dir> <hunspell prefix> <corpus.txt>...
Outputs as build_dicts.py: <lang>.words.tsv, <lang>.bigrams.tsv, <lang>.combined.
"""
import sys
import unicodedata

dicts_dir, lang, *rest = sys.argv[1:]
sys.path.insert(0, dicts_dir)
import build_dicts as bd  # noqa: E402

bd.LANG.update({
    "de": dict(alphabet="abcdefghijklmnopqrstuvwxyzäöüß", top=320_000, one_letter="", min_count=4,
               description="Deutsch"),
    "es": dict(alphabet="abcdefghijklmnopqrstuvwxyzáéíóúüñ", top=240_000, one_letter="a e o u y", min_count=4,
               description="Español"),
    "nl": dict(alphabet="abcdefghijklmnopqrstuvwxyzáéíóúàèëïöüâêîôû'", top=300_000, one_letter="u", min_count=4,
               description="Nederlands"),
    # Hungarian is agglutinative: keep many forms (like Czech). Key path through NFD:
    # á->a é->e í->i ó/ö/ő->o ú/ü/ű->u
    "hu": dict(alphabet="aábcdeéfghiíjklmnoóöőpqrstuúüűvwxyz", top=500_000, one_letter="a s e", min_count=4,
               description="Magyar"),
})
SPECIAL = {"ß": "ss", "æ": "ae", "œ": "oe"}


def keypath(word):
    """Base letters a-z: diacritics, case and apostrophes removed; ß -> ss ("straße" -> "strasse")."""
    out = []
    for ch in word.lower():
        if not ch.isalpha():
            continue
        if ch in SPECIAL:
            out.append(SPECIAL[ch])
            continue
        b = unicodedata.normalize("NFD", ch)[0]
        if not ("a" <= b <= "z"):
            return None
        out.append(b)
    return "".join(out) or None


bd.keypath = keypath
sys.argv = ["build_dicts.py", lang, *rest]
bd.main()
