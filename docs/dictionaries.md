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
