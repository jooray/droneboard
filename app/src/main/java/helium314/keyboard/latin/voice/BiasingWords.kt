// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** One entry of the personal dictionary, as far as dictation cares. */
data class UserWord(val word: String, val locale: String?, val shortcut: String?, val frequency: Int)

/**
 * Chooses the words of the personal dictionary that go to Offline Speech to Text (OSTT) with a dictation, so that
 * names and terms come back spelled the way the user spells them. Free of Android, for the tests.
 */
object BiasingWords {
    /** OSTT reads at most this many words of a request. */
    const val MAX_COUNT = 1000
    /** OSTT drops a longer word or phrase. */
    const val MAX_WORD_CHARS = 80
    /** OSTT stops reading after this much UTF-8 in all. */
    const val MAX_BYTES = 16 * 1024

    /**
     * The words to send, most useful first. OSTT repairs close spellings only for the first 128 and gives the first
     * few to a Whisper model as a hint, so the order is what counts: first the words whose spelling a recognizer
     * can't guess (anything with a capital letter or a digit: names, brands, acronyms), by how much the user uses
     * them, then the ordinary words the same way.
     *
     * Left out: words of languages that are not in [languages] (lowercase language codes of the enabled keyboard
     * languages; an entry without a language is for all of them), entries with a shortcut (those are text
     * replacements such as an address or a signature, not vocabulary, and nothing a speech app should be handed),
     * words with fewer than two letters or digits, and everything OSTT would drop anyway (longer than
     * [MAX_WORD_CHARS], control characters, the marks of a model's control tokens). A word that differs from an
     * earlier one only by case is sent once.
     */
    fun select(
        entries: List<UserWord>,
        languages: Set<String>,
        maxCount: Int = MAX_COUNT,
        maxBytes: Int = MAX_BYTES,
    ): List<String> {
        if (maxCount <= 0) return emptyList()
        // whatever an OSTT declares, the contract's own limit holds
        val count = minOf(maxCount, MAX_COUNT)
        val usable = entries.mapNotNull { e ->
            if (!e.shortcut.isNullOrEmpty() || !inLanguages(e.locale, languages)) return@mapNotNull null
            val word = e.word.trim().replace(WHITESPACE, " ")
            if (usable(word)) word to e.frequency else null
        }
        // a stable sort: equally frequent words keep the order of the dictionary
        val ordered = usable.sortedWith(compareBy<Pair<String, Int>> { if (needsSpelling(it.first)) 0 else 1 }
            .thenByDescending { it.second })
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        var bytes = 0
        for ((word, _) in ordered) {
            if (result.size >= count) break
            if (!seen.add(word.lowercase())) continue
            val size = word.toByteArray(Charsets.UTF_8).size
            // the order is the priority, so the list ends at the first word that does not fit
            if (bytes + size > maxBytes) break
            bytes += size
            result.add(word)
        }
        return result
    }

    private val WHITESPACE = Regex("\\s+")

    private fun inLanguages(locale: String?, languages: Set<String>): Boolean {
        if (locale.isNullOrEmpty()) return true
        // "en", "en_US" and "en-GB" are all English: a name is spelled the same in every variant
        return locale.substringBefore('_').substringBefore('-').lowercase() in languages
    }

    private fun usable(word: String): Boolean =
        word.length <= MAX_WORD_CHARS && word.count { it.isLetterOrDigit() } >= 2 &&
            word.none { it.isISOControl() } && "<|" !in word && "|>" !in word

    private fun needsSpelling(word: String) = word.any { it.isUpperCase() || it.isDigit() }
}
