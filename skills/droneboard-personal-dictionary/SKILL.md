---
name: droneboard-personal-dictionary
description: Build a personal dictionary for the Droneboard Android keyboard from text the user has already written (notes, e-mails, chat exports, documents), so that names, places and terms they type often are suggested and can be swiped without adding them by hand. Use when the user asks for a personal dictionary, custom words or "teach the keyboard my words" for Droneboard, or wants to import words from Gboard or a word list.
license: GPL-3.0-only
compatibility: Needs Python 3.8 or newer (standard library only) and Droneboard 0.2.1 or newer on the phone.
---

# A personal dictionary for Droneboard

Everyone has words they type all the time that no dictionary has: family names, streets, company and
project names, jargon. This skill finds them in the user's own text and produces a file that Droneboard
imports in one step. Words the keyboard already knows are left out.

Everything runs on the user's computer. The text is private: do not upload it, do not paste large
parts of it into a chat, and keep the working files out of any git repository.

## What you need

1. **The tool:** `tools/personal-dict/personal_dict.py` from https://github.com/jooray/droneboard
   (one file, no dependencies). Download just that file if the repository is not checked out.
2. **The Droneboard APK**, the same version as on the phone if possible, from
   https://github.com/jooray/droneboard/releases/latest. The tool reads the built-in dictionaries out of it.
3. **The user's text.** Ask where it is. Good sources, in this order:
   - notes and documents they wrote (Markdown, text, Obsidian or Logseq vaults);
   - sent e-mail (export the Sent folder as mbox or .eml; not the inbox, that is other people's words);
   - chat exports, **only the user's own messages**. In a database or export with several senders,
     find the user's sender id and filter by it. Show the user their last ten messages as you
     identified them and let them confirm before going on;
   - blog posts, books, transcripts of their own talks.
   Text written by other people (newsletters, saved articles, group chats) adds words the user reads
   but does not type. Leave it out, or accept more cleaning.
4. **The languages** the user types in (Droneboard codes: `sk cs en de es nl hu`, more for tap typing).

## Steps

Work in a private folder outside any repository, for example `~/droneboard-personal-dict/` (mode 700).

```sh
T=path/to/personal_dict.py

# 1. what the keyboard already knows (once per app version and language set)
python3 $T known Droneboard_0.2.1-release.apk --lang sk en -o known.txt

# 2. words in the user's text that are not known
python3 $T collect ~/notes ~/Documents/letters --known known.txt -o candidates.tsv
#    chat or mail in SQLite: add  --sqlite messages.db --query "select body from messages where sender = '<their id>'"
#    one export file: pass it as a path; text from a pipe: pass -
#    useful switches: --min-count 3 (default), --min-documents 2 (a word must occur in two files or
#    messages: removes most data-file noise), --ext json csv html (folders are read as prose only by default)

# 3. clean candidates.tsv (see below) into cleaned.txt, one word per line

# 4. the import file
python3 $T export cleaned.txt --known known.txt --add "Names, The User, Asked For" -o droneboard-words.txt
```

`candidates.tsv` has four columns: the word in its most common spelling, how often it occurs, in how
many documents, and one example. Sorted by count. Expect a few thousand lines for a few megabytes of text.

## Cleaning (the part that needs judgement)

The candidates are not a dictionary yet. Roughly half are junk. Go through every line; do not filter by
a pattern. For a long list, split it into chunks of about 900 lines and give each chunk to a small,
cheap model with the rules below, the word, the count and the example. Then check that every returned
word really was in the input (models sometimes add words) and drop the ones that were not.

Keep:
- names of people, places, companies, products, projects, protocols;
- abbreviations and tickers the user really types;
- jargon, slang and technical terms that are real words to the user;
- correctly spelled words and inflected forms that were simply missing;
- accents and capital letters as they should be written.

Drop:
- typos, missing or doubled letters, two words run together;
- words typed without accents when the accented word is an ordinary word (the tool already drops most);
- code and data identifiers (camelCase, field names, CSS classes), file names, slugs, paths;
- hyphenated compounds of ordinary words (`self-hosted`): the keyboard types the parts;
- random strings, hashes, keys, fragments, parts of links, domains, e-mail addresses;
- ordinary words of a language the user did not ask for.

Being rude is not a reason to drop a word. The user's own name and handles are wanted, even though
they look like "handles": add them with `--add` if cleaning removed them. Ask the user for names of
family, friends, streets and towns that may not be in the text, and add those too. Multi-word entries
such as `Paralelná Polis` are allowed (they are suggested when tapping; swipe works on single words).

Show the user the final count and a random sample of about 50 words before importing, and offer the
full list for a look. It is their dictionary.

## The import file

UTF-8 text, one entry per line. Only the word is required; more columns are separated by a tab:

```
# word	language	shortcut	frequency
Bernolákovo
Paralelná Polis
lightning	en
On my way!	en_US	omw	200
```

- language: `sk`, `en_US`, ...; empty means all languages, which is right for people who mix languages;
- shortcut: optional text that expands to the word;
- frequency: 1 to 255, 250 when missing;
- lines starting with `#` and blank lines are ignored; at most 48 characters per entry, 50,000 entries, 5 MB.

Droneboard also imports Gboard's exported `dictionary.txt` (or its zip) and its own
`personal_dictionary.json`, so words added by hand in Gboard can be moved without this tool:
in Gboard, Settings > Dictionary > Personal dictionary > Export.

## Getting it onto the phone

Android allows only the keyboard itself to write the personal dictionary, so it cannot be filled from
a computer over adb. The file has to be imported in the app:

1. Copy `droneboard-words.txt` to the phone (`adb push droneboard-words.txt /sdcard/Download/`, or
   any way the user moves files: USB, their own cloud, a message to themselves).
2. On the phone: Droneboard settings > Text correction > Personal dictionary > **Import from file**,
   pick the file. Droneboard shows how many words are new and adds them after confirmation.
3. Importing the same or a longer file again is safe: existing words stay, nothing is deleted.

The words then appear in suggestions and work with swipe typing. Delete the copy in Download
afterwards if the list is private. The personal dictionary is part of Droneboard's backup file.

## When something is off

- `known` finds no dictionaries: wrong language code, or the file is not a Droneboard APK
  (the dictionaries are in `assets/dicts` and `assets/swipe`).
- Thousands of odd candidates such as field names: data files were read; drop `--ext`, or raise
  `--min-documents` to 2.
- Very few candidates: lower `--min-count` to 2, or add more text.
- "Import from file" is missing: the phone has a Droneboard older than 0.2.1.
- The import says nothing could be written: turn Droneboard on as a keyboard first.
