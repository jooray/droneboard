// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.util.Locale

/**
 * Merges [LanguageModel] predictions into the dictionaries' suggestions for tap typing.
 *
 * - Next word (nothing typed yet): the two rankings are fused by reciprocal rank, so a word both
 *   rank high comes first, and the user's own history (part of the dictionary suggestions) keeps
 *   its weight. There is no typed word to correct, so the new order cannot cause an autocorrection.
 * - Completion (letters typed): the dictionaries' first suggestion stays first, because
 *   autocorrection and the typed-word logic look at it. Up to [MAX_COMPLETIONS] model completions
 *   the dictionaries lack follow right behind it.
 */
object LanguageModelSuggestions {
    /** reciprocal rank fusion: score = 1 / (RRF_K + rank) per ranking */
    const val RRF_K = 2.0
    const val LM_WEIGHT = 1.0
    /** model words scoring below this (log probability plus language log posterior) are not shown */
    const val MIN_SCORE = -16f
    const val MAX_COMPLETIONS = 2
    /** words before the cursor given to the model */
    const val CONTEXT_WORDS = 3

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

    /** Next-word prediction: [native] best first; returns the fused list with scores that keep its order. */
    fun mergeNextWord(
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
            e.fused += LM_WEIGHT / (RRF_K + i)
        }
        val ordered = entries.values.sortedByDescending { it.fused } // stable: ties keep the dictionaries' order
        val top = native.firstOrNull()?.score ?: BASE_SCORE
        return ordered.mapIndexed { i, e ->
            val score = top - i
            e.info?.copy(score = score) ?: makeInfo(e.prediction!!, score)
        }
    }

    /** Completion of [typedWord]: [native] best first; returns it plus the model's completions behind its first entry. */
    fun mergeCompletions(
        native: List<SuggestedWordInfo>, predictions: List<WordPrediction>, typedWord: String,
        makeInfo: (WordPrediction, Int) -> SuggestedWordInfo
    ): List<SuggestedWordInfo> {
        if (typedWord.isEmpty()) return native
        val typedLower = typedWord.lowercase(Locale.ROOT)
        val present = native.mapTo(HashSet()) { it.word.lowercase(Locale.ROOT) }
        val adds = predictions.asSequence()
            .filter { it.score >= MIN_SCORE }
            .filter { it.word.length > typedWord.length && it.word.lowercase(Locale.ROOT).startsWith(typedLower) }
            .filter { it.word.lowercase(Locale.ROOT) !in present }
            .take(MAX_COMPLETIONS).toList()
        if (adds.isEmpty()) return native
        val top = native.firstOrNull()?.score ?: BASE_SCORE
        val result = ArrayList<SuggestedWordInfo>(native.size + adds.size)
        if (native.isNotEmpty()) result.add(native.first())
        adds.forEachIndexed { i, p -> result.add(makeInfo(p, if (native.isEmpty()) top - i else top - 1 - i)) }
        // the rest of the dictionaries' suggestions stay behind the completions
        val floor = top - 1 - adds.size
        native.drop(1).forEach { result.add(if (it.score > floor) it.copy(score = floor) else it) }
        return result
    }

    private const val BASE_SCORE = 1_000_000
}
