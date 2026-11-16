// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.LocaleUtils.engineKey
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestionResults
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Gesture (swipe) typing with the FUTO Swipe encoder and Droneboard's key-path dictionaries.
 * Several languages are decoded at once: the keyboard's language, its secondary languages and
 * every other enabled language that has a swipe dictionary, like Gboard does.
 */
object SwipeGestureDecoder {
    private const val TAG = "SwipeGestureDecoder"
    private const val ASSET_DIR = "swipe"
    private const val ENCODER = "honorable_sturgeon/model_int8.pte"
    private const val ENCODER_METADATA = "honorable_sturgeon/metadata.json"

    /** beam width for the live preview while the finger still moves; the final result uses the
     *  "swipe search width" setting (default 200, see [Defaults.PREF_SWIPE_BEAM_WIDTH]) */
    private const val BEAM_PREVIEW = 32
    private const val MAX_RESULTS = 18
    /** No fixed bonus for the keyboard's main language: an EN keyboard with SK as second language
     *  turned Slovak words into English ones. The language comes from the words before the cursor. */
    private const val MAIN_LANGUAGE_BONUS = 0f
    /**
     * Weight and floor of the context language bonus. Off: on synthetic SK/EN swipes with sentence context
     * (tools/swipe-replay) it gained at most 0.5 pp in one-language text, and a strong prior cost 15 pp on
     * English words inside Slovak sentences, which bilingual users type all the time.
     */
    private const val LANGUAGE_KAPPA = 0f
    private const val LANGUAGE_CAP = 0.5f
    /** words before the cursor used for the language guess; the last one also feeds the bigrams */
    private const val CONTEXT_WORDS = 3

    /**
     * Neural language model fusion of the final decode (NeuralFusion in native/droneswipe/neural_lm.hpp): weight
     * 0.2 growing to 0.4 for ambiguous gestures (margin scale 4), 0.1 without context, OOV offset 3, frequency
     * weight -0.015 and context weight -0.05 when the model has context. Tuned with tools/swipe-replay on
     * synthetic swipes with held-out sentence context in sk, en, cs, de and hu, precise and sloppy: top-1 +3 to
     * +5 pp on precise swipes, +8 to +15 pp on sloppy ones; the user's recordings 82.1 -> 84.9.
     */
    private val NEURAL_FUSION = floatArrayOf(0.2f, 0.4f, 4f, 0.1f, 3f, -0.015f, -0.05f)

    /** what a decode fed the native decoder, for the swipe recorder */
    class LastInput(val x: FloatArray, val y: FloatArray, val t: FloatArray, val context: List<String>)

    /** normalized letter-key centers of the current layout (letter -> x, y), for the swipe recorder */
    @Volatile
    var layoutKeys: List<Triple<String, Float, Float>> = emptyList()
        private set

    private fun contextWords(ngramContext: NgramContext): List<String> {
        val words = ArrayList<String>()
        for (n in 1..CONTEXT_WORDS) {
            if (ngramContext.isNthPrevWordBeginningOfSentence(n)) break
            val w = ngramContext.getNthPrevWord(n)?.toString() ?: break
            if (w.isBlank()) break
            words.add(0, w)
        }
        return words
    }

    /** gamma, beta, lambda, capital-letter penalty, bigram weight (tuned in bench-swipe; de/es/nl/hu on
     *  synthetic swipes with held-out sentence context in tools/swipe-replay). German has no capital-letter
     *  penalty: its nouns are capitalized (+2.1 pp top-1 against -2). */
    private val LANGUAGE_PARAMS = mapOf(
        "sk" to floatArrayOf(0.5f, 0.9866f, 0.025f, -2f, 0.1f),
        "cs" to floatArrayOf(0.6f, 0.9866f, 0.025f, 0f, 0.2f),
        "en" to floatArrayOf(0.5f, 0.9866f, 0.025f, -2f, 0.1f),
        "de" to floatArrayOf(0.5f, 0.9866f, 0.025f, 0f, 0.1f),
        "es" to floatArrayOf(0.5f, 0.9866f, 0.025f, -2f, 0.1f),
        "nl" to floatArrayOf(0.6f, 0.9866f, 0.025f, -2f, 0.2f),
        // Hungarian: gamma 0.4 / 0.6 and bigram weight 0.05 / 0.15 / 0.2 were 0.4-1.9 pp top-1 worse or equal
        "hu" to floatArrayOf(0.5f, 0.9866f, 0.025f, -2f, 0.1f),
    )

    /** parameters of a dictionary; sk_nd (Slovak without diacritics) uses those of sk */
    private fun params(language: String) =
        LANGUAGE_PARAMS[language] ?: LANGUAGE_PARAMS[language.substringBefore('_')] ?: LANGUAGE_PARAMS.getValue("en")

    /** how long the final decode of a swipe waits for dictionaries that are still loading */
    private const val FINAL_WAIT_MS = 6000L

    /** The user's words: lambda as for languages, bonus 1.0 x weight/255 for every candidate the user has
     *  written (replay on the user's recordings: +3.7 pp top-1 with a helpful history, -1 pp with a history
     *  full of the wrong winners) */
    private val USER_PARAMS = floatArrayOf(0.5f, 0.9866f, 0.025f, 0f, 0f, 0f, 1.0f)
    private const val USER_WORDS_MAX = 20000
    private const val USER_WORDS_REFRESH_MS = 5 * 60 * 1000L

    /** Supplies the user's words with weights 0-255 (typing history, personal dictionary); set by the IME. */
    @JvmStatic
    @Volatile
    var userWordsProvider: ((Int) -> Map<String, Int>)? = null
    @Volatile
    private var userWordsLoadedAt = 0L

    private var session = 0L
    /** loaded dictionaries; filled by the loader thread, read by the decoder */
    private val dictionaries = ConcurrentHashMap<String, Long>()
    /** one latch per language that has been requested; counted down when loading ends (also on failure) */
    private val loading = ConcurrentHashMap<String, CountDownLatch>()
    /** when loading a language last failed; it is tried again after [RETRY_FAILED_MS] instead of never */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_FAILED_MS = 15_000L
    /** loads dictionaries one after another, never on the input thread, never under the decoder lock */
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-load") }
    private val extractLock = Any()
    private var assetDir: File? = null
    private var availableLanguages: Set<String>? = null

    private var layoutSignature: String? = null
    private var activeLanguages: List<String> = emptyList()
    private var activeLocales: List<Locale> = emptyList()
    private var keyboardWidth = 1f
    private var rowsTop = 0f
    private var rowsHeight = 1f

    private val sourceDictionaries = HashMap<Locale, Dictionary>()

    var lastMillis = 0f
        private set

    internal var appContext: Context? = null
        private set

    /** Called when the keyboard service starts: remembers the context and starts loading the
     *  dictionaries of every language a swipe can use, so that the first swipe does not wait. */
    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        // direct boot: the models and dictionaries are extracted into credential-encrypted storage, so nothing
        // loads before the first unlock (the keyboard process restarts on unlock and calls this again)
        if (!DirectBoot.isUnlocked(app)) return
        if (!FutoSwipe.isAvailable) return
        // secondary (multilingual) locales live in the subtype's extra value, e.g. EN keyboard + SK
        val locales = runCatching {
            SubtypeSettings.getEnabledSubtypes(true).flatMap { listOf(it.locale()) + getSecondaryLocales(it.extraValue) }
        }.getOrDefault(emptyList())
        preload(app, locales)
        runCatching { NeuralLanguageModel.preload(app, locales.map { it.engineKey }) }
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        DirectBoot.isUnlocked(context) && FutoSwipe.isAvailable && languagesWithDictionary(context).isNotEmpty()

    /** Languages with a .kpd swipe dictionary in the APK. */
    fun languagesWithDictionary(context: Context): Set<String> = availableLanguages ?: run {
        val files = runCatching { context.assets.list(ASSET_DIR)?.toSet() }.getOrNull() ?: emptySet()
        files.filter { it.endsWith(".kpd") }.map { it.substringBefore('.') }.toSet()
            .also { availableLanguages = it }
    }

    /** Copies the model and dictionaries out of the APK once per app version (native code needs files). */
    internal fun extract(context: Context, name: String): File = synchronized(extractLock) {
        // fail closed before touching storage: no_backup is credential-encrypted
        if (!DirectBoot.isUnlocked(context)) throw IllegalStateException("user is locked, not extracting $name")
        // keyed by install time, so that every update (also with the same version code) re-extracts
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
        val dir = assetDir ?: File(context.noBackupFilesDir, "swipe-$installed").also {
            context.noBackupFilesDir.listFiles()?.forEach { old ->
                if (old.name.startsWith("swipe-") && old != it) old.deleteRecursively()
            }
            assetDir = it
        }
        val file = File(dir, name)
        if (file.isFile) return file
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        context.assets.open("$ASSET_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(file)
        file
    }

    private fun ensureSession(context: Context): Boolean {
        if (session != 0L) return true
        extract(context, ENCODER_METADATA)
        val encoder = extract(context, ENCODER)
        session = FutoSwipe.nativeCreate(encoder.absolutePath)
        return session != 0L
    }

    /** Reloads the user's words on the loader thread every few minutes; the next layout update picks them up. */
    private fun maybeRefreshUserWords() {
        val provider = userWordsProvider ?: return
        if (!DirectBoot.isUnlocked()) return // the user's words are personal data, not read before unlock
        val now = System.currentTimeMillis()
        if (session == 0L || now - userWordsLoadedAt < USER_WORDS_REFRESH_MS) return
        userWordsLoadedAt = now
        loader.execute {
            try {
                val words = provider(USER_WORDS_MAX).entries.filter { it.key.length > 1 && it.value > 0 }
                synchronized(this) {
                    if (session == 0L) return@synchronized
                    FutoSwipe.nativeSetUserWords(session, words.map { it.key }.toTypedArray(),
                        words.map { it.value }.toIntArray(), USER_PARAMS)
                    layoutSignature = null
                }
                Log.i(TAG, "swipe user words: ${words.size}")
            } catch (e: Exception) {
                Log.w(TAG, "could not load user words for swipe", e)
            }
        }
    }

    /** Starts loading [language] on the loader thread unless it is loaded or already loading. */
    private fun requestLoad(context: Context, language: String): CountDownLatch {
        // while locked nothing is requested, so a failed attempt is never remembered as "loaded"
        if (!DirectBoot.isUnlocked(context)) return CountDownLatch(0)
        // a failed load (for example during an app update or a low-memory moment) must not disable the
        // language until the process restarts: forget it after a while so that the next swipe retries
        failedAt[language]?.let { t ->
            if (System.currentTimeMillis() - t < RETRY_FAILED_MS) return CountDownLatch(0)
            failedAt.remove(language)
            if (dictionaries[language] == null) loading.remove(language)
        }
        return loading.computeIfAbsent(language) {
            val latch = CountDownLatch(1)
            loader.execute {
                try {
                    val file = extract(context, "$language.kpd")
                    val params = params(language) + 0f
                    val handle = FutoSwipe.nativeLoadDictionary(file.absolutePath, params)
                    if (handle != 0L) dictionaries[language] = handle
                    else Log.w(TAG, "could not load swipe dictionary $language")
                } catch (e: Throwable) {
                    Log.w(TAG, "could not load swipe dictionary $language", e)
                } finally {
                    if (dictionaries[language] == null) failedAt[language] = System.currentTimeMillis()
                    latch.countDown()
                }
            }
            latch
        }
    }

    /**
     * Loaded .kpd dictionaries of [languages] (in that order; languages without a dictionary or still
     * loading are left out and requested in the background), for the n-gram language model.
     */
    @JvmStatic
    fun loadedDictionaries(languages: List<String>): List<Pair<String, Long>> {
        val context = appContext ?: return emptyList()
        if (!DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable) return emptyList()
        val available = languagesWithDictionary(context)
        return languages.distinct().filter { it in available }.mapNotNull { lang ->
            val handle = dictionaries[lang] ?: run { requestLoad(context, lang); null }
            handle?.let { lang to it }
        }
    }

    /** Handle of the loaded dictionary, 0 if it is not loaded (yet). With [waitMs] > 0, waits for loading. */
    private fun dictionary(context: Context, language: String, waitMs: Long): Long {
        dictionaries[language]?.let { return it }
        val latch = requestLoad(context, language)
        if (waitMs > 0 && latch.count > 0) {
            val start = System.currentTimeMillis()
            latch.await(waitMs, TimeUnit.MILLISECONDS)
            Log.i(TAG, "waited ${System.currentTimeMillis() - start} ms for swipe dictionary $language")
        }
        return dictionaries[language] ?: 0L
    }

    /** Locales to decode: main first, then secondary locales, then other enabled languages. */
    private fun swipeLocales(context: Context, keyboard: Keyboard, secondary: List<Locale>): List<Locale> {
        val available = languagesWithDictionary(context)
        val result = LinkedHashMap<String, Locale>()
        // only languages the user enabled (a turned-off English must not show up in swipe results)
        val candidates = listOf(keyboard.mId.locale) + secondary +
            runCatching { SubtypeSettings.getEnabledSubtypes(true).map { it.locale() } }.getOrDefault(emptyList())
        for (l in candidates) {
            if (l.engineKey in available && l.engineKey !in result) result[l.engineKey] = l
            if (result.size >= 3) break
        }
        return result.values.toList()
    }

    /** Starts loading dictionaries in the background (returns at once), so that the first swipe does not wait. */
    @JvmStatic
    fun preload(context: Context, locales: List<Locale>) {
        if (!DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable) return
        try {
            val available = languagesWithDictionary(context)
            locales.map { it.engineKey }.distinct().filter { it in available }.forEach { requestLoad(context, it) }
        } catch (e: Exception) {
            Log.w(TAG, "preload failed", e)
        }
    }

    /**
     * Sets the key layout and the dictionaries of the languages to decode. A language whose dictionary is
     * still loading is left out (preview) or waited for (final decode, [waitMs]); once it has loaded, the
     * next call adds it, because the signature contains the loaded languages.
     */
    private fun updateLayout(context: Context, keyboard: Keyboard, secondary: List<Locale>, waitMs: Long): Boolean {
        val wanted = swipeLocales(context, keyboard, secondary)
        if (wanted.isEmpty()) return false
        wanted.forEach { requestLoad(context, it.engineKey) }
        maybeRefreshUserWords()
        val deadline = System.currentTimeMillis() + waitMs
        val locales = wanted.filter { dictionary(context, it.engineKey, (deadline - System.currentTimeMillis()).coerceAtLeast(0)) != 0L }
        if (locales.isEmpty()) return false
        val languages = locales.map { it.engineKey }
        val letterKeys = keyboard.sortedKeys
            .filter { it.code > 0 && Character.isLetter(it.code) && it.width > 0 }
            .distinctBy { Character.toLowerCase(it.code) }
        if (letterKeys.size < 10) return false
        val signature = "${keyboard.mId.locale}|${keyboard.mOccupiedWidth}x${keyboard.mOccupiedHeight}|" +
            letterKeys.joinToString("") { "${it.code}:${it.x}:${it.y}," } + languages
        if (signature == layoutSignature) return true

        // Normalize like the FUTO model expects: x by keyboard width, y so that the letter rows
        // sit at 1/6, 1/2 and 5/6 (one row = 1/rows of the height).
        val rowCenters = letterKeys.map { it.y + it.height / 2f }.distinct().sorted()
        val rows = rowCenters.size.coerceAtLeast(1)
        val pitch = if (rows > 1) (rowCenters.last() - rowCenters.first()) / (rows - 1) else letterKeys.first().height.toFloat()
        rowsTop = rowCenters.first() - pitch / 2
        rowsHeight = pitch * rows
        keyboardWidth = keyboard.mOccupiedWidth.toFloat().coerceAtLeast(1f)

        val letters = letterKeys.map { Character.toLowerCase(it.code) }.toIntArray()
        val xs = letterKeys.map { (it.x + it.width / 2f) / keyboardWidth }.toFloatArray()
        val ys = letterKeys.map { (it.y + it.height / 2f - rowsTop) / rowsHeight }.toFloatArray()

        val handles = ArrayList<Long>()
        languages.forEachIndexed { i, lang ->
            val d = dictionaries[lang] ?: 0L
            if (d == 0L) return@forEachIndexed
            val params = params(lang) + (if (i == 0) MAIN_LANGUAGE_BONUS else 0f)
            FutoSwipe.nativeSetDictionaryParams(d, params)
            handles.add(d)
        }
        if (handles.isEmpty()) return false
        if (!FutoSwipe.nativeSetLayout(session, letters, xs, ys, handles.toLongArray())) return false
        activeLanguages = languages.filter { dictionaries[it] in handles }
        activeLocales = locales.filter { it.engineKey in activeLanguages }
        layoutKeys = letters.indices.map { Triple(String(Character.toChars(letters[it])), xs[it], ys[it]) }
        layoutSignature = signature
        Log.i(TAG, "swipe layout ${letters.size} keys, languages $activeLanguages")
        return true
    }

    private fun sourceDictionary(locale: Locale) = sourceDictionaries.getOrPut(locale) {
        object : Dictionary(TYPE_MAIN, locale) {
            override fun getSuggestions(
                composedData: helium314.keyboard.latin.common.ComposedData?, ngramContext: NgramContext?,
                proximityInfoHandle: Long, settingsValuesForSuggestion: helium314.keyboard.latin.settings.SettingsValuesForSuggestion?,
                sessionId: Int, weightForLocale: Float, inOutWeightOfLangModelVsSpatialModel: FloatArray?
            ): ArrayList<SuggestedWordInfo>? = null

            override fun isInDictionary(word: String?) = false
        }
    }

    /**
     * Decodes the gesture in [pointers]. Returns null if swipe typing is not possible here (no
     * native library, no dictionary for the language, non-letter keyboard), so that the caller
     * can fall back to the regular dictionaries.
     */
    @JvmStatic
    @Synchronized
    fun getSuggestions(
        pointers: InputPointers, ngramContext: NgramContext, keyboard: Keyboard,
        secondaryLocales: List<Locale>, isFinal: Boolean
    ): SuggestionResults? {
        val context = appContext ?: return null
        if (!DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable || !keyboard.mId.element.isAlphabet) return null
        try {
            if (!ensureSession(context) || !updateLayout(context, keyboard, secondaryLocales, if (isFinal) FINAL_WAIT_MS else 0L))
                return null
        } catch (e: Throwable) {
            Log.w(TAG, "could not set up swipe decoder", e)
            return null
        }
        val size = pointers.pointerSize
        if (size < 2) return null
        val xsIn = pointers.xCoordinates
        val ysIn = pointers.yCoordinates
        val times = pointers.times
        val ids = pointers.pointerIds
        val firstId = ids[0]
        val n = (0 until size).count { ids[it] == firstId }
        val x = FloatArray(n)
        val y = FloatArray(n)
        val t = FloatArray(n)
        var j = 0
        val t0 = times[0]
        // gesture points are in view coordinates, keys in keyboard coordinates (padding, vertical correction)
        val detector = PointerTracker.getMainKeyDetector()?.takeIf { it.keyboard === keyboard }
        for (i in 0 until size) {
            if (ids[i] != firstId) continue
            val kx = detector?.getTouchX(xsIn[i]) ?: xsIn[i]
            val ky = detector?.getTouchY(ysIn[i]) ?: ysIn[i]
            x[j] = kx / keyboardWidth
            y[j] = (ky - rowsTop) / rowsHeight
            t[j] = (times[i] - t0).toFloat()
            j++
        }
        val contextWords = contextWords(ngramContext)
        val previous = contextWords.lastOrNull()
        val scores = FloatArray(MAX_RESULTS)
        val dicts = IntArray(MAX_RESULTS)
        // the final decode reranks with the neural models of the swipe languages (see NEURAL_FUSION), from the
        // text before the cursor when the input logic hands it over, otherwise from the context words
        val (tokens, bos) = NeuralLanguageModel.tokensBeforeCursor(NeuralLanguageModel.textBeforeCursor.get(), contextWords, "")
        val words = NeuralLanguageModel.withSwipeModels(context, if (isFinal) activeLanguages else emptyList()) { models ->
            FutoSwipe.nativeRecognize(
                session, x, y, t, contextWords.toTypedArray(), null, LANGUAGE_KAPPA, LANGUAGE_CAP,
                if (isFinal) (Settings.getValues()?.mSwipeBeamWidth ?: Defaults.PREF_SWIPE_BEAM_WIDTH) else BEAM_PREVIEW,
                MAX_RESULTS, scores, dicts, models, tokens.toTypedArray(), bos, NEURAL_FUSION
            )
        }
        lastMillis = FutoSwipe.nativeLastMillis(session)
        val results = SuggestionResults(MAX_RESULTS, ngramContext, false)
        words.forEachIndexed { i, word ->
            val locale = activeLocales.getOrNull(dicts[i]) ?: activeLocales.first()
            // scores are roughly -30..+30; keep their order in the integer score space
            val score = (scores[i] * 10_000f).roundToInt() + 1_000_000
            results.add(
                SuggestedWordInfo(
                    word, "", score, SuggestedWordInfo.KIND_CORRECTION, sourceDictionary(locale),
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                )
            )
        }
        if (isFinal && SwipeRecorder.isRecording(runCatching { keyboard.mId.editorInfo }.getOrNull())) {
            SwipeRecorder.onFinalDecode(
                keyboard, pointers, LastInput(x, y, t, contextWords), layoutKeys, words, activeLanguages,
                words.indices.map { activeLanguages.getOrElse(dicts[it]) { "" } }, lastMillis
            )
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "swipe ${if (isFinal) "final" else "preview"} ${lastMillis}ms (neural ${FutoSwipe.nativeLastNeuralMillis(session)}ms," +
                " ${tokens.size} tokens${if (bos) " +bos" else ""}) prev=$previous -> ${words.take(4)}")
            if (isFinal) Log.d(TAG, "points n=$n first=(${x[0]},${y[0]}) last=(${x[n - 1]},${y[n - 1]}) dur=${t[n - 1]}ms")
        }
        return results
    }
}
