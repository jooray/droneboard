# Dictionaries in Droneboard

Droneboard has no internet access, so it never downloads anything itself. The dictionaries it needs come in the app or are imported by you from a file.

## Bundled

- **Main dictionaries:** Slovak, built from open data (FineWeb-2 word frequencies filtered through Hunspell), plus the AOSP/HeliBoard main dictionaries for English and several other languages.
- **Swipe word lists:** Slovak and English, also with word-pair frequencies, used by the FUTO Swipe decoder.
- **Emoji dictionaries:** English, Slovak and Czech, from Unicode CLDR annotations and Signal's emoji search data. They power emoji search and emoji suggestions.

## Adding a dictionary for another language

1. Download a `.dict` file in the AOSP format with your browser. The largest collection is [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) (`dictionaries/` for words, `emoji_cldr_signal_dictionaries/` for emoji). Check each file's license there.
2. In Droneboard: Settings → Dictionaries → Add dictionary, and pick the file.

Swipe typing currently uses only the bundled Slovak and English word lists. Other languages can be typed by tapping, with suggestions from the imported dictionary.
