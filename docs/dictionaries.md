# Dictionaries in Droneboard

Droneboard has no internet access, so it never downloads anything itself. The dictionaries it needs come in the app or are imported by you from a file.

## Bundled

- **Main dictionaries:** Slovak, Czech (both also without diacritics) and Hungarian, built from open data (FineWeb-2 word frequencies filtered through Hunspell), plus the AOSP/HeliBoard main dictionaries for English, German, Spanish, Dutch and several other languages.
- **Swipe word lists:** Slovak, Czech, English, German, Spanish, Dutch and Hungarian, and Slovak and Czech without diacritics, with word-pair and word-triple frequencies, used by the FUTO Swipe decoder and by next-word prediction.
- **Neural next-word models:** the same nine languages.
- **Emoji dictionaries:** English, Slovak, Czech, German, Spanish, Dutch and Hungarian, from Unicode CLDR annotations and Signal's emoji search data. They power emoji search and emoji suggestions.

## Adding a dictionary for another language

1. Download a `.dict` file in the AOSP format with your browser. The largest collection is [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) (`dictionaries/` for words, `emoji_cldr_signal_dictionaries/` for emoji). Check each file's license there.
2. In Droneboard: Settings → Dictionaries → Add dictionary, and pick the file.

Swipe typing uses only the bundled swipe word lists. Other languages can be typed by tapping, with suggestions from the imported dictionary.

## Importing your own words into the personal dictionary

If you have a list of words you often write (names, places, terms from your work), you do not have to add them one by one. Settings → Text correction → Personal dictionary → **Import from file** reads the list, shows how many words are new and adds them after you confirm. Words that are already in the dictionary stay as they are and nothing is deleted, so the same file can be imported again. **Export to file** on the same screen writes the whole personal dictionary in the text format below.

Droneboard has to be turned on as a keyboard, because Android lets only a keyboard change the personal dictionary. A file can have up to 5 MB and 50,000 entries. The format is recognised from the content of the file, not from its name:

- **Text file** in UTF-8, one entry per line. A line is a word, or columns separated by a tab: word, language, shortcut, frequency. Only the word is required. The language is written as `sk`, `en_US` or `en-US`; when it is empty or missing, the word is used in all languages. The frequency is a number from 1 to 255 (250 when missing). An entry can have more words (`Paralelná Polis`), up to 48 characters. Blank lines and lines starting with `#` are skipped; a line that starts with `#` and has a tab is an entry, so hashtags survive an export and import.
- **Gboard dictionary**: the `dictionary.txt` that Gboard exports (in Gboard: Settings → Dictionary → Personal dictionary, then Export in the menu), or the `.zip` it is packed in.
- **Droneboard's `personal_dictionary.json`** from a backup, or the backup `.zip` itself.

Example of a text file (the gaps are tabs):

```
# word	language	shortcut	frequency
Bratislava
Paralelná Polis	sk	pp
lightning	en
On my way!	en_US	omw	200
```

You do not have to write the list by hand. `tools/personal-dict/personal_dict.py` finds the words in text you have already written (notes, sent mail, your chat messages) that Droneboard does not know yet, and writes this file; it runs on your computer and needs only Python. If you work with an AI assistant, give it `skills/droneboard-personal-dictionary/SKILL.md`: it describes the whole procedure, including the cleaning of the word list.

Lines that cannot be read (no word, a word longer than 48 characters, an unknown language, a frequency outside 1 to 255) are skipped and counted in the summary before the import.
