// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/**
 * Merges [LanguageModel] predictions into the dictionaries' suggestions for tap typing. The model ranks; the
 * dictionaries add what the model cannot know: the user's own history and typo corrections.
 *
 * - Next word (nothing typed yet): the model's order, with words of the user's history moved up by
 *   [HISTORY_BOOST]; the dictionaries' other predictions follow. There is no typed word to correct, so the
 *   new order cannot cause an autocorrection.
 * - Completion (letters typed): the union of the dictionaries' suggestions and the model's completions, ranked by
 *   the model's log probability minus [AOSP_WEIGHT] × ln(1 + the dictionaries' rank), which carries their spatial
 *   and edit-distance evidence. Model completions match the typed letters without accents ("dak" → "ďakujem").
 *   With autocorrection on, the dictionaries' first suggestion stays first, because autocorrection looks at it.
 * Emoji, shortcuts and whitelisted words keep their places in both cases.
 *
 * Measured end to end (what the strip shows) with TapPredictionEval; see tools/swipe-replay/README.md.
 */
object LanguageModelSuggestions {
    /** model words scoring below this (log probability plus language log posterior) are not shown */
    const val MIN_SCORE = -16f
    /** words before the cursor given to the model */
    const val CONTEXT_WORDS = 3
    /** weight of the dictionaries' rank, -ln(1 + rank), against the model's log probability (completions) */
    @JvmField var AOSP_WEIGHT = 0.35
    /** a dictionary word the model did not return counts as this much less likely than the model's last word */
    @JvmField var FLOOR_GAP = 1.0
    /** log-probability bonus of next-word predictions from the user's history */
    @JvmField var HISTORY_BOOST = 2.0
    /** false: the 0.1.14 merges (for the evaluation's before/after) */
    @JvmField var rerankCompletions = true
    @JvmField var modelFirstNextWord = true

    fun contextWords(ngramContext: NgramContext): List<String> {
        val words = ArrayList<String>()
        for (n in 1..CONTEXT_WORDS) {
            if (ngramContext.isNthPrevWordBeginningOfSentence(n)) break
            val w = ngramContext.getNthPrevWord(n)?.toString() ?: break
            if (w.isBlank()) break
            words.add(0, w)
        }
        return words
    }

    /** lowercase without accents: the letters as typed without the accent keys (ß as ss, like the key paths) */
    fun fold(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        if (lower.all { it < '\u0080' }) return lower
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace("ß", "ss")
    }

    /** [mergeNextWord] when nothing is typed, else [mergeCompletions] */
    fun merge(
        native: List<SuggestedWordInfo>, predictions: List<WordPrediction>, typedWord: String,
        makeInfo: (WordPrediction, Int) -> SuggestedWordInfo, keepNativeFirst: Boolean = false
    ): List<SuggestedWordInfo> =
        if (typedWord.isEmpty()) mergeNextWord(native, predictions, makeInfo)
        else mergeCompletions(native, predictions, typedWord, makeInfo, keepNativeFirst)

    private fun isPinned(info: SuggestedWordInfo) = info.isEmoji
        || info.isKindOf(SuggestedWordInfo.KIND_SHORTCUT) || info.isKindOf(SuggestedWordInfo.KIND_WHITELIST)

    private fun isHistory(info: SuggestedWordInfo) = info.sourceDict.mDictType.let {
        it == Dictionary.TYPE_USER_HISTORY || it == Dictionary.TYPE_USER || it == Dictionary.TYPE_CONTACTS
    }

    /** [ranked] in its order, the pinned entries of [native] back at their places, scores counting down from [top] */
    private fun assemble(native: List<SuggestedWordInfo>, ranked: List<SuggestedWordInfo>, top: Int): List<SuggestedWordInfo> {
        val result = ArrayList<SuggestedWordInfo>(native.size + ranked.size)
        val words = ranked.iterator()
        for (info in native) {
            if (isPinned(info)) result.add(info)
            else if (words.hasNext()) result.add(words.next())
        }
        words.forEachRemaining { result.add(it) }
        return result.mapIndexed { i, info -> if (info.score == top - i) info else info.copy(score = top - i) }
    }

    /** Next-word prediction: [native] best first; returns the merged list with scores that keep its order. */
    fun mergeNextWord(
        native: List<SuggestedWordInfo>, predictions: List<WordPrediction>,
        makeInfo: (WordPrediction, Int) -> SuggestedWordInfo
    ): List<SuggestedWordInfo> {
        if (!modelFirstNextWord) return mergeNextWordRrf(native, predictions, makeInfo)
        val lm = predictions.filter { it.score >= MIN_SCORE }
        if (lm.isEmpty()) return native
        class Entry(var info: SuggestedWordInfo?, val prediction: WordPrediction?, var score: Double, val order: Int)
        val entries = LinkedHashMap<String, Entry>()
        lm.forEachIndexed { i, p -> entries.putIfAbsent(p.word.lowercase(Locale.ROOT), Entry(null, p, p.score.toDouble(), i)) }
        val floor = lm.last().score - FLOOR_GAP
        native.forEachIndexed { i, info ->
            if (isPinned(info)) return@forEachIndexed
            val boost = if (isHistory(info)) HISTORY_BOOST else 0.0
            val key = info.word.lowercase(Locale.ROOT)
            val e = entries[key]
            if (e == null) {
                // the dictionaries' other predictions follow the model's, in their order
                entries[key] = Entry(info, null, floor - 0.01 * i + boost, lm.size + i)
            } else {
                if (e.info == null || isHistory(info)) e.info = info
                if (e.prediction != null) e.score = maxOf(e.score, e.prediction.score + boost)
            }
        }
        val top = native.firstOrNull()?.score ?: BASE_SCORE
        val ranked = entries.values.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.order })
            .map { it.info ?: makeInfo(it.prediction!!, 0) }
        return assemble(native, ranked, top)
    }

    /** Completion of [typedWord]: the dictionaries' suggestions and the model's completions, ranked together. */
    fun mergeCompletions(
        native: List<SuggestedWordInfo>, predictions: List<WordPrediction>, typedWord: String,
        makeInfo: (WordPrediction, Int) -> SuggestedWordInfo, keepNativeFirst: Boolean = false
    ): List<SuggestedWordInfo> {
        if (typedWord.isEmpty()) return native
        if (!rerankCompletions) return mergeCompletionsAospFirst(native, predictions, typedWord, makeInfo)
        val typedLower = typedWord.lowercase(Locale.ROOT)
        val typedFold = fold(typedWord)
        val lm = predictions.filter {
            it.score >= MIN_SCORE && it.word.lowercase(Locale.ROOT) != typedLower && fold(it.word).startsWith(typedFold)
        }
        if (lm.isEmpty()) return native
        class Entry(val info: SuggestedWordInfo?, var prediction: WordPrediction?, val nativeRank: Int)
        val entries = LinkedHashMap<String, Entry>()
        var rank = 0
        for (info in native) {
            if (isPinned(info)) continue
            val key = info.word.lowercase(Locale.ROOT)
            if (key !in entries) entries[key] = Entry(info, null, rank++)
        }
        val nativeCount = rank
        for (p in lm) {
            val e = entries.getOrPut(p.word.lowercase(Locale.ROOT)) { Entry(null, p, nativeCount) }
            if (e.prediction == null) e.prediction = p
        }
        val floor = lm.last().score - FLOOR_GAP
        fun score(e: Entry) = (e.prediction?.score?.toDouble() ?: floor) - AOSP_WEIGHT * ln(1.0 + e.nativeRank)
        val first = if (keepNativeFirst) native.firstOrNull { !isPinned(it) } else null
        val ranked = entries.values.asSequence().filter { first == null || it.info !== first }
            .sortedByDescending { score(it) }.map { it.info ?: makeInfo(it.prediction!!, 0) }.toList()
        val top = native.firstOrNull()?.score ?: BASE_SCORE
        return assemble(native, if (first != null) listOf(first) + ranked else ranked, top)
    }

    // ---- 0.1.14, kept for the evaluation's comparison

    private const val RRF_K = 2.0

    private fun mergeNextWordRrf(
        native: List<SuggestedWordInfo>, predictions: List<WordPrediction>,
        makeInfo: (WordPrediction, Int) -> SuggestedWordInfo
    ): List<SuggestedWordInfo> {
        val lm = predictions.filter { it.score >= MIN_SCORE }
        if (lm.isEmpty()) return native
        class Entry(var info: SuggestedWordInfo?, var prediction: WordPrediction?, var fused: Double)
        val entries = LinkedHashMap<String, Entry>()
        native.forEachIndexed { i, info ->
            entries.getOrPut(info.word.lowercase(Locale.ROOT)) { Entry(info, null, 0.0) }.fused += 1.0 / (RRF_K + i)
        }
        lm.forEachIndexed { i, p ->
            val e = entries.getOrPut(p.word.lowercase(Locale.ROOT)) { Entry(null, p, 0.0) }
            if (e.prediction == null) e.prediction = p
            e.fused += 1.0 / (RRF_K + i)
        }
        val ordered = entries.values.sortedByDescending { it.fused }
        val top = native.firstOrNull()?.score ?: BASE_SCORE
        return ordered.mapIndexed { i, e ->
            val score = top - i
            e.info?.copy(score = score) ?: makeInfo(e.prediction!!, score)
        }
    }

    private fun mergeCompletionsAospFirst(
        native: List<SuggestedWordInfo>, predictions: List<WordPrediction>, typedWord: String,
        makeInfo: (WordPrediction, Int) -> SuggestedWordInfo
    ): List<SuggestedWordInfo> {
        val typedLower = typedWord.lowercase(Locale.ROOT)
        val present = native.mapTo(HashSet()) { it.word.lowercase(Locale.ROOT) }
        val adds = predictions.asSequence()
            .filter { it.score >= MIN_SCORE }
            .filter { it.word.length > typedWord.length && it.word.lowercase(Locale.ROOT).startsWith(typedLower) }
            .filter { it.word.lowercase(Locale.ROOT) !in present }
            .take(2).toList()
        if (adds.isEmpty()) return native
        val top = native.firstOrNull()?.score ?: BASE_SCORE
        val result = ArrayList<SuggestedWordInfo>(native.size + adds.size)
        if (native.isNotEmpty()) result.add(native.first())
        adds.forEachIndexed { i, p -> result.add(makeInfo(p, if (native.isEmpty()) top - i else top - 1 - i)) }
        val floor = top - 1 - adds.size
        native.drop(1).forEach { result.add(if (it.score > floor) it.copy(score = floor) else it) }
        return result
    }

    private const val BASE_SCORE = 1_000_000
}
