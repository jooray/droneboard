// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer

/**
 * Droneboard: the letter index of a long word list (the personal dictionary). Pure Kotlin (no Android classes),
 * so that it can be tested on the JVM; [LetterIndexBar] draws it.
 *
 * The list is sorted the way the index is grouped, so that every letter is one run of rows: first the entries
 * that do not start with a letter ([OTHER]), then letter by letter, where an accented letter belongs to its
 * base letter (á to A, č to C, ö to O). Inside a letter the order is the collator's.
 */
object LetterIndex {
    /** the bucket of entries that do not start with a letter (digits, #hashtags, emoji) */
    const val OTHER = "#"

    /** [label] is shown in the index, [firstIndex] is the position of its first entry in [Result.items] */
    data class Section(val label: String, val firstIndex: Int)

    class Result<T>(val items: List<T>, val sections: List<Section>)

    private class Keyed<T>(val item: T, val word: String, val bucket: String, val key: CollationKey)

    /** the base letter [word] starts with, upper case, or [OTHER] */
    fun bucket(word: String): String {
        val trimmed = word.trimStart()
        if (trimmed.isEmpty()) return OTHER
        val first = trimmed.substring(0, Character.charCount(trimmed.codePointAt(0)))
        // NFD splits á into a and the accent; letters that do not decompose (ł, ß, đ) stay as they are
        val base = Normalizer.normalize(first, Normalizer.Form.NFD).codePointAt(0)
        if (!Character.isLetter(base)) return OTHER
        return String(Character.toChars(Character.toUpperCase(base)))
    }

    /**
     * Sorts [items] and finds where every letter starts. One pass to compute the keys and one sort,
     * so it is O(n log n); call it off the main thread for long lists.
     */
    fun <T> build(items: List<T>, collator: Collator, word: (T) -> String): Result<T> {
        if (items.isEmpty()) return Result(emptyList(), emptyList())
        val keyed = items.map { val w = word(it); Keyed(it, w, bucket(w), collator.getCollationKey(w)) }
        val bucketKeys = HashMap<String, CollationKey>()
        for (k in keyed) if (k.bucket != OTHER) bucketKeys.getOrPut(k.bucket) { collator.getCollationKey(k.bucket) }
        val sorted = keyed.sortedWith(Comparator { a, b ->
            if (a.bucket != b.bucket) {
                if (a.bucket == OTHER) return@Comparator -1
                if (b.bucket == OTHER) return@Comparator 1
                val c = bucketKeys.getValue(a.bucket).compareTo(bucketKeys.getValue(b.bucket))
                return@Comparator if (c != 0) c else a.bucket.compareTo(b.bucket)
            }
            val c = a.key.compareTo(b.key)
            // the collator ignores some differences; the plain order keeps the result the same on every run
            if (c != 0) c else a.word.compareTo(b.word)
        })
        val sections = ArrayList<Section>()
        sorted.forEachIndexed { i, k -> if (i == 0 || sorted[i - 1].bucket != k.bucket) sections.add(Section(k.bucket, i)) }
        return Result(sorted.map { it.item }, sections)
    }

    /**
     * Which of [count] labels to draw when only [max] fit: evenly spread, always with the first and the last.
     * Returns their positions in ascending order.
     */
    fun thinOut(count: Int, max: Int): List<Int> {
        if (count <= 0 || max <= 0) return emptyList()
        if (count <= max) return List(count) { it }
        if (max == 1) return listOf(0)
        return List(max) { i -> (i.toLong() * (count - 1) / (max - 1)).toInt() }
    }

    /** the section under the finger: the index is [height] tall and every one of [count] sections has the same share */
    fun sectionAt(y: Float, height: Float, count: Int): Int {
        if (count <= 0 || height <= 0f) return -1
        return (y / height * count).toInt().coerceIn(0, count - 1)
    }
}
