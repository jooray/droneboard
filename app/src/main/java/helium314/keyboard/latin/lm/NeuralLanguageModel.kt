// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import android.content.Context
import android.os.SystemClock
import helium314.keyboard.latin.common.LocaleUtils.engineKey
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.swipe.FutoSwipe
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.Log
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Small neural next-word model per language (native/droneswipe/neural_lm.cpp, assets swipe/<lang>.lmb and
 * swipe/<lang>.lmv, trained and exported by tools/lm). Only the enabled languages' models run, so predictions
 * stay in those languages; the words before the cursor decide how much each language counts.
 *
 * It sees the text before the cursor (up to [MAX_TOKENS] tokens, punctuation included) when the input logic
 * provides it ([textBeforeCursor]), otherwise the n-gram context words. When the setting is off, a language
 * has no neural model, a model is still loading, or the model does not answer within [TIMEOUT_MS], the
 * prediction comes from [NgramLanguageModel]; a late answer is cached for the next keystroke.
 */
object NeuralLanguageModel : LanguageModel {
    private const val TAG = "NeuralLanguageModel"
    private const val ASSET_DIR = "swipe"
    private const val MAX_TOKENS = 31
    private const val TEXT_CHARS = 300
    const val TIMEOUT_MS = 60L
    private const val CACHE_SIZE = 64
    private const val CANDIDATES = 20
    private const val LAMBDA = 0.7

    /** Text before the cursor for the suggestion request running on this thread (set by the input logic). */
    @JvmField
    val textBeforeCursor = ThreadLocal<CharSequence?>()

    /** characters of text the input logic should hand over in [textBeforeCursor] */
    const val TEXT_BEFORE_CURSOR_CHARS = TEXT_CHARS

    private val lock = ReentrantReadWriteLock()
    private val models = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private var available: Set<String>? = null

    // one worker; a newer request replaces one still waiting in the queue
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { r -> Thread(r, "neural-lm").apply { priority = Thread.NORM_PRIORITY } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val loader = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
        { r -> Thread(r, "neural-lm-load") })

    private val cache = object : LinkedHashMap<String, List<WordPrediction>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<WordPrediction>>?) = size > CACHE_SIZE
    }

    // latency statistics for the log
    private val latencies = FloatArray(200)
    private val nextWordLatencies = FloatArray(200)
    private var nextWordCount = 0
    @Volatile private var lastSplit = floatArrayOf(0f, 0f)
    private var latencyCount = 0

    /** true when the last [predict] on this thread answered with a stand-in that a later call will improve
     *  (model still loading, timeout, error): such results must not be cached */
    @JvmField
    val lastAnswerTemporary = ThreadLocal<Boolean>()

    /** The context the model sees for next-word prediction. The next-word cache is keyed on it as well as on
     *  the n-gram context: punctuation and earlier words change the model's answer. */
    fun contextKey(context: List<String>): String {
        val (tokens, bos) = tokensBeforeCursor(textBeforeCursor.get(), context, "")
        return bos.toString() + "|" + tokens.joinToString(" ")
    }

    override fun predict(context: List<String>, prefix: String, locales: List<Locale>, maxResults: Int): List<WordPrediction> {
        lastAnswerTemporary.set(false)
        val languages = locales.map { it.engineKey }.distinct()
        // While a dictionary is still loading (right after an update the files are being copied out of the APK),
        // an answer comes from fewer languages or without n-grams. It is a stand-in like the ones below: caching
        // it would keep the worse answer for that context until the keyboard restarts.
        val dictionariesReady = SwipeGestureDecoder.dictionariesReady(languages)
        val fallback = {
            if (!dictionariesReady) lastAnswerTemporary.set(true)
            NgramLanguageModel.predict(context, prefix, locales, maxResults)
        }
        val temporaryFallback = { lastAnswerTemporary.set(true); fallback() }
        // direct boot: no model files before the first unlock, and no predictions from personal data
        if (maxResults <= 0 || !DirectBoot.isUnlocked() || !FutoSwipe.isAvailable) return emptyList()
        if (!Settings.getValues().mNeuralPredictions) return fallback()
        val app = SwipeGestureDecoder.appContext ?: return fallback()
        if (languages.isEmpty() || !languages.all { it in neuralLanguages(app) }) return fallback()
        releaseOthers(languages)
        val handles = languages.map { models[it] }
        if (handles.any { it == null }) {
            languages.forEach { requestLoad(app, it) }
            return temporaryFallback()
        }
        val (tokens, bos) = tokensBeforeCursor(textBeforeCursor.get(), context, prefix)
        val key = languages.joinToString(",") + "|" + bos + "|" + tokens.joinToString(" ") + "|" + prefix + "|" + maxResults
        synchronized(cache) { cache[key] }?.let { return it }
        if (!dictionariesReady) lastAnswerTemporary.set(true)
        val future: Future<List<WordPrediction>> = executor.submit<List<WordPrediction>> {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = run(languages, tokens, bos, prefix, maxResults, locales, context)
            record((SystemClock.elapsedRealtimeNanos() - start) / 1e6f, prefix.isEmpty())
            if (result != null && dictionariesReady) synchronized(cache) { cache[key] = result }
            result ?: emptyList()
        }
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.i(TAG, "no answer within $TIMEOUT_MS ms, using n-grams")
            temporaryFallback()
        } catch (e: Exception) {
            Log.w(TAG, "prediction failed", e)
            temporaryFallback()
        }
    }

    private fun run(
        languages: List<String>, tokens: List<String>, bos: Boolean, prefix: String, maxResults: Int,
        locales: List<Locale>, ngramContext: List<String>
    ): List<WordPrediction>? = lock.read {
        val byLanguage = locales.associateBy { it.engineKey }
        val handles = languages.map { models[it] ?: return@read null }
        val dicts = SwipeGestureDecoder.loadedDictionaries(languages).toMap()
        val scores = FloatArray(CANDIDATES)
        val index = IntArray(CANDIDATES)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val words = FutoSwipe.nativePredictNeural(
            handles.toLongArray(), languages.map { dicts[it] ?: 0L }.toLongArray(), tokens.toTypedArray(), bos,
            prefix, CANDIDATES, scores, index
        )
        val neural = words.mapIndexed { i, w -> WordPrediction(w, scores[i], byLanguage.getValue(languages[index[i]])) }
        val t1 = SystemClock.elapsedRealtimeNanos()
        val ngram = NgramLanguageModel.predict(ngramContext, prefix, locales, CANDIDATES)
        lastSplit = floatArrayOf((t1 - t0) / 1e6f, (SystemClock.elapsedRealtimeNanos() - t1) / 1e6f)
        interpolate(neural, ngram, maxResults)
    }

    /**
     * P = [LAMBDA] P_neural + (1 - [LAMBDA]) P_ngram over both candidate lists. Measured with tools/swipe-replay
     * lm-eval on held-out SK+EN text (keystroke savings, neural alone -> interpolated): open data 51.0 -> 50.7,
     * the author's own writing 48.0 -> 50.1 (the n-gram dictionaries carry personal and cypherpunk vocabulary).
     */
    internal fun interpolate(neural: List<WordPrediction>, ngram: List<WordPrediction>, maxResults: Int): List<WordPrediction> {
        class Mix(var p: Double, var best: WordPrediction, var bestP: Double)
        val mix = LinkedHashMap<String, Mix>()
        fun add(list: List<WordPrediction>, weight: Double) = list.forEach { w ->
            val p = weight * Math.exp(w.score.toDouble())
            val m = mix.getOrPut(w.word.lowercase(Locale.ROOT)) { Mix(0.0, w, -1.0) }
            m.p += p
            if (p > m.bestP) { m.best = w; m.bestP = p }
        }
        add(neural, LAMBDA)
        add(ngram, 1 - LAMBDA)
        return mix.values.sortedByDescending { it.p }.take(maxResults)
            .map { WordPrediction(it.best.word, Math.log(it.p + 1e-30).toFloat(), it.best.locale) }
    }

    /** Languages with a neural model in the APK. */
    fun neuralLanguages(context: Context): Set<String> = available ?: run {
        val files = runCatching { context.assets.list(ASSET_DIR)?.toSet() }.getOrNull() ?: emptySet()
        files.filter { it.endsWith(".lmb") }.map { it.substringBefore('.') }
            .filter { "$it.lmv" in files }.toSet().also { available = it }
    }

    /** Loads the models of [languages] in the background (called when the keyboard starts). */
    fun preload(context: Context, languages: Collection<String>) {
        if (!DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable || !Settings.getValues().mNeuralPredictions) return
        languages.filter { it in neuralLanguages(context) }.forEach { requestLoad(context, it) }
    }

    private fun requestLoad(context: Context, language: String) {
        if (!DirectBoot.isUnlocked(context) || models.containsKey(language) || !loading.add(language)) return
        loader.execute {
            try {
                val start = SystemClock.elapsedRealtime()
                val lmb = SwipeGestureDecoder.extract(context, "$language.lmb")
                val lmv = SwipeGestureDecoder.extract(context, "$language.lmv")
                val handle = FutoSwipe.nativeLoadNeural(lmb.absolutePath, lmv.absolutePath)
                if (handle != 0L) {
                    models[language] = handle
                    Log.i(TAG, "loaded $language in ${SystemClock.elapsedRealtime() - start} ms")
                } else {
                    Log.w(TAG, "could not load the neural model for $language")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "could not load the neural model for $language", e)
            } finally {
                loading.remove(language)
            }
        }
    }

    /** Languages of the swipe decoder's dictionaries; their models stay loaded for [withSwipeModels]. */
    @Volatile private var swipeLanguages: List<String> = emptyList()

    /**
     * Runs [block] with the handles of the loaded models of [languages] (the swipe decoder's dictionaries, in
     * that order), or with null when neural predictions are off, the user is locked (direct boot: no model
     * files before the first unlock), a language has no neural model, or a model is still loading (it starts
     * loading then). The models cannot be freed while [block] runs.
     */
    fun <T> withSwipeModels(context: Context, languages: List<String>, block: (LongArray?) -> T): T {
        if (languages.isEmpty() || !DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable
            || Settings.getValues()?.mNeuralPredictions != true) return block(null)
        swipeLanguages = languages
        if (!languages.all { it in neuralLanguages(context) }) return block(null)
        return lock.read {
            val handles = languages.map { models[it] }
            if (handles.any { it == null }) {
                languages.forEach { requestLoad(context, it) }
                block(null)
            } else {
                block(LongArray(handles.size) { handles[it]!! })
            }
        }
    }

    /** Frees the models of languages that are no longer enabled (for typing or swiping). */
    private fun releaseOthers(typing: List<String>) {
        val languages = typing + swipeLanguages
        if (models.keys.all { it in languages }) return
        loader.execute {
            lock.write {
                models.keys.filter { it !in languages }.forEach { lang ->
                    models.remove(lang)?.let { FutoSwipe.nativeFreeNeural(it) }
                    Log.i(TAG, "released $lang")
                }
                synchronized(cache) { cache.clear() }
            }
        }
    }

    private fun record(ms: Float, nextWord: Boolean) {
        synchronized(latencies) {
            latencies[latencyCount % latencies.size] = ms
            latencyCount++
            if (nextWord) nextWordLatencies[nextWordCount++ % nextWordLatencies.size] = ms
            if (latencyCount % 50 == 0) {
                val n = minOf(latencyCount, latencies.size)
                val sorted = latencies.copyOf(n).sorted()
                val m = minOf(nextWordCount, nextWordLatencies.size)
                val next = nextWordLatencies.copyOf(m).sorted()
                val split = lastSplit
                Log.i(TAG, ("latency over the last $n predictions: median %.1f ms, p95 %.1f ms; next word only: median %.1f ms, " +
                    "p95 %.1f ms (n=$m); last call: neural %.1f ms + n-gram %.1f ms")
                    .format(Locale.ROOT, sorted[n / 2], sorted[(n * 95) / 100], next.getOrElse(m / 2) { 0f },
                        next.getOrElse((m * 95) / 100) { 0f }, split[0], split[1]))
            }
        }
    }

    private val TOKEN = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*|\\p{Nd}+(?:[.,]\\p{Nd}+)*|[.,!?:;]")

    /**
     * The tokens the model was trained on (tools/lm/prepare.py): lowercase words, numbers as <num>, the
     * punctuation .,!?:; — of the text before the cursor without the word being typed ([prefix]).
     * Falls back to [contextWords] (the n-gram context) when there is no text.
     */
    internal fun tokensBeforeCursor(text: CharSequence?, contextWords: List<String>, prefix: String): Pair<List<String>, Boolean> {
        if (text == null) {
            val words = contextWords.map { it.lowercase(Locale.ROOT).replace('’', '\'') }
            return words to words.isEmpty()
        }
        var s = text.toString()
        // the word being typed is the prefix, not context (also when the editor's text is a few letters ahead)
        if (prefix.isNotEmpty()) {
            s = if (s.endsWith(prefix)) s.substring(0, s.length - prefix.length)
                else s.trimEnd { it.isLetter() || it == '\'' || it == '’' }
        }
        val tokens = TOKEN.findAll(s).map { m ->
            val t = m.value
            if (t[0].isDigit()) "<num>" else t.lowercase(Locale.ROOT).replace('’', '\'')
        }.toList()
        // the text is the whole field when fewer characters came back than were asked for
        val wholeField = text.length < TEXT_CHARS
        val bos = wholeField && tokens.size <= MAX_TOKENS
        return tokens.takeLast(MAX_TOKENS) to bos
    }
}
