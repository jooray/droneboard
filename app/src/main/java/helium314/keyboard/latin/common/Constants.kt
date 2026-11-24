// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    const val GITHUB = "https://github.com/jooray/droneboard"
    // upstream HeliBoard credit (retained for open-source attribution)
    const val HELIBOARD = "https://github.com/HeliBorg/HeliBoard"
    const val LICENSE = "$GITHUB/blob/main/LICENSE"
    // Droneboard is offline: instead of direct download links, point to a page explaining where dictionaries come from
    const val DICTIONARY_HELP = "$GITHUB/blob/main/docs/dictionaries.md"
    const val WEBSITE = "https://cypherpunk.today/droneboard/"
    const val WIKI_URL = WEBSITE
    const val LAYOUT_WIKI_URL = WEBSITE
    const val CUSTOM_LAYOUTS = WEBSITE
    const val CUSTOM_COLORS = WEBSITE
    const val COMMUNITY_LINKS = GITHUB
}

val combiningRange = 0x300..0x35b
