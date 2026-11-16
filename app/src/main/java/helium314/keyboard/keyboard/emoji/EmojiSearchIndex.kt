// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.dictionary.DictionaryInfoUtils
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Gboard-like emoji search: matches a query as a substring of emoji keywords and names
 * ("ocket" or "rock" finds the rocket), ignoring case and diacritics ("raketa", "srdce").
 *
 * The emoji dictionaries map keywords (non-words) to emoji shortcuts, and each emoji to its name.
 * The native dictionary only does prefix lookups, so we keep a small in-memory list per language.
 */
object EmojiSearchIndex {
    private const val TAG = "EmojiSearchIndex"

    /** normalized keyword -> emojis with their weight */
    private class Entry(val key: String, val emojis: List<Pair<String, Int>>)

    private val indexes = HashMap<String, List<Entry>>()

    @Synchronized
    private fun indexFor(context: Context, locale: Locale): List<Entry> {
        val language = locale.language
        indexes[language]?.let { return it }
        // direct boot: the dictionaries are in credential-encrypted storage; don't cache the empty result
        if (!DirectBoot.isUnlocked(context)) return emptyList()
        val file = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context)
            ?: DictionaryFactory.extractBuiltInEmojiDict(context, locale)
        val entries = file?.let { load(it, locale) } ?: emptyList()
        indexes[language] = entries
        return entries
    }

    private fun load(file: File, locale: Locale): List<Entry> {
        val byKey = HashMap<String, MutableMap<String, Int>>()
        fun add(key: String, emoji: String, weight: Int) {
            val map = byKey.getOrPut(normalize(key)) { HashMap() }
            if ((map[emoji] ?: -1) < weight) map[emoji] = weight
        }
        val dict = BinaryDictionary(file.absolutePath, 0, file.length(), false, locale, Dictionary.TYPE_EMOJI, false)
        try {
            if (!dict.isValidDictionary) return emptyList()
            var token = 0
            do {
                val result = dict.getNextWordProperty(token)
                val property = result.mWordProperty
                val word = property.mWord
                val targets = property.mShortcutTargets.orEmpty()
                if (word.isNotEmpty() && targets.isNotEmpty()) {
                    if (isEmoji(word)) {
                        // emoji -> its name, e.g. 🚀 -> "rocket"
                        targets.forEach { add(it.mWord, word, it.mProbabilityInfo?.mProbability ?: 0) }
                    } else {
                        // keyword -> emojis, e.g. "launch" -> 🚀
                        targets.forEach { if (isEmoji(it.mWord)) add(word, it.mWord, it.mProbabilityInfo?.mProbability ?: 0) }
                    }
                }
                token = result.mNextToken
            } while (token != 0)
        } catch (e: Exception) {
            Log.w(TAG, "could not read emoji dictionary $file", e)
        } finally {
            dict.close()
        }
        Log.i(TAG, "emoji search index for ${locale.language}: ${byKey.size} keys")
        return byKey.map { (key, emojis) -> Entry(key, emojis.toList().sortedByDescending { it.second }) }
    }

    /** Emojis matching [query] in any of [locales], best matches first. */
    fun search(context: Context, query: String, locales: Collection<Locale>, limit: Int = 120): List<String> {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val scores = LinkedHashMap<String, Double>()
        for (locale in locales.distinctBy { it.language }) {
            for (entry in indexFor(context, locale)) {
                // every term has to occur in the keyword; multi-word keywords like "space rocket" stay searchable
                var rank = 0.0
                var matches = true
                for (term in terms) {
                    val pos = entry.key.indexOf(term)
                    if (pos < 0) { matches = false; break }
                    rank += when {
                        entry.key == term -> 3.0
                        pos == 0 -> 2.0
                        entry.key[pos - 1] == ' ' -> 1.5 // start of a later word in the keyword
                        else -> 1.0
                    }
                }
                if (!matches) continue
                // shorter keywords are more specific matches
                rank -= entry.key.length / 100.0
                for ((emoji, weight) in entry.emojis) {
                    val score = rank * 100 + weight
                    if ((scores[emoji] ?: Double.NEGATIVE_INFINITY) < score) scores[emoji] = score
                }
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    fun clear() = synchronized(this) { indexes.clear() }

    private val combiningMarks = Regex("\\p{Mn}+")

    private fun normalize(text: String): String =
        combiningMarks.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            .replace(Regex("[_\\-:]+"), " ").trim()

    private fun isEmoji(text: String): Boolean {
        val cp = text.codePointAt(0)
        // emoji, pictographs, dingbats, symbols with emoji presentation, keycaps and flags
        return cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp in 0x2600..0x27BF || cp in 0x3030..0x3299
            || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
            || (text.length > 1 && text.contains('⃣')) || (cp in 0x23..0x39 && text.contains('️'))
    }
}
