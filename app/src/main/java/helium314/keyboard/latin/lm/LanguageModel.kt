// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import helium314.keyboard.latin.common.LocaleUtils.engineKey
import helium314.keyboard.latin.swipe.FutoSwipe
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.latin.utils.Log
import java.util.Locale

/** A predicted word with its log score (comparable within one call) and the language it came from. */
data class WordPrediction(val word: String, val score: Float, val locale: Locale)

/**
 * Predicts the next word, or completes the word being typed, from the words before the cursor.
 * Predictions stay within [locales] (the enabled languages). Implementations: [NgramLanguageModel]
 * (trigrams of the .kpd dictionaries) and [NeuralLanguageModel] (a small neural model per language, falling
 * back to the n-grams).
 * Must be fast (it runs for every keystroke) and safe to call from the suggestion thread.
 */
interface LanguageModel {
    /**
     * @param context words before the cursor, oldest first (at most a few)
     * @param prefix the letters typed of the current word; empty for next-word prediction
     */
    fun predict(context: List<String>, prefix: String, locales: List<Locale>, maxResults: Int): List<WordPrediction>
}

/** The language model the keyboard uses for tap-typing suggestions (null = none). */
object LanguageModels {
    @JvmStatic
    @Volatile
    var current: LanguageModel? = NeuralLanguageModel
}

/** Unigram/bigram/trigram model of Droneboard's .kpd swipe dictionaries (native/droneswipe predict_words). */
object NgramLanguageModel : LanguageModel {
    private const val TAG = "NgramLanguageModel"

    override fun predict(context: List<String>, prefix: String, locales: List<Locale>, maxResults: Int): List<WordPrediction> {
        if (maxResults <= 0 || !FutoSwipe.isAvailable) return emptyList()
        val byLanguage = locales.associateBy { it.engineKey }
        val dicts = SwipeGestureDecoder.loadedDictionaries(locales.map { it.engineKey })
        if (dicts.isEmpty()) return emptyList()
        return try {
            val scores = FloatArray(maxResults)
            val index = IntArray(maxResults)
            val words = FutoSwipe.nativePredict(
                dicts.map { it.second }.toLongArray(), context.toTypedArray(), prefix, maxResults, scores, index
            )
            words.mapIndexed { i, w -> WordPrediction(w, scores[i], byLanguage.getValue(dicts[index[i]].first)) }
        } catch (e: Throwable) {
            Log.w(TAG, "prediction failed", e)
            emptyList()
        }
    }
}
