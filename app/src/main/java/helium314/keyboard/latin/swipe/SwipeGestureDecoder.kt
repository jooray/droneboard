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
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipFile
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
    private const val USER_WORDS_CHANGED_MS = 2 * 60 * 1000L
    private const val USER_WORDS_CHANGED_REFRESH_MS = 10 * 1000L

    /** Told on the decoder thread when a final decode starts and stops waiting for a dictionary being prepared. */
    fun interface PreparingListener {
        fun onPreparing(preparing: Boolean)
    }

    /** Set by the IME, which shows a message in the suggestion strip while a swipe waits for its dictionary. */
    @JvmStatic
    @Volatile
    var preparingListener: PreparingListener? = null

    /** Supplies the user's words with weights 0-255 (typing history, personal dictionary); set by the IME. */
    @JvmStatic
    @Volatile
    var userWordsProvider: ((Int) -> Map<String, Int>)? = null
    @Volatile
    private var userWordsLoadedAt = 0L
    /** until this time the user's words are reloaded often, see [invalidateUserWords] */
    @Volatile
    private var userWordsChangedUntil = 0L

    /**
     * The personal dictionary changed (a word was added, a list was imported): reload the user's words at the
     * next swipe instead of after [USER_WORDS_REFRESH_MS]. The keyboard's copy of the personal dictionary is
     * rebuilt in the background, so for a while the words are reloaded every few seconds to catch the new copy.
     */
    @JvmStatic
    fun invalidateUserWords() {
        userWordsChangedUntil = System.currentTimeMillis() + USER_WORDS_CHANGED_MS
        userWordsLoadedAt = 0L
    }

    @Volatile
    private var session = 0L
    private val sessionLock = Any()
    private val sessionPreloadRequested = AtomicBoolean(false)
    /** loaded dictionaries; filled by the loader pool, read by the decoder */
    private val dictionaries = ConcurrentHashMap<String, Long>()
    /** one latch per language that has been requested; counted down when loading ends (also on failure) */
    private val loading = ConcurrentHashMap<String, CountDownLatch>()
    /** when loading a language last failed; it is tried again after [RETRY_FAILED_MS] instead of never */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_FAILED_MS = 15_000L
    /** 2-thread loader pool so bilingual .kpd2 caches and the ExecuTorch encoder warm in parallel */
    private val loaderSeq = AtomicInteger(0)
    private val loader = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "swipe-load-${loaderSeq.incrementAndGet()}").apply { isDaemon = true }
    }
    /**
     * Compiling a v1 .kpd into the .kpd2 cache needs up to ~200 MB of native memory for a big dictionary, so
     * only one is compiled at a time (after an install or an update); loading compiled caches stays parallel.
     */
    private val compileLock = Any()
    private val extractLock = Any()
    private val extractLocks = ConcurrentHashMap<String, Any>()
    private var assetDir: File? = null

    /**
     * The compiled .kpd2 caches live outside the per-installation [swipeAssetDir] and are named after the content
     * of their .kpd (size and CRC-32), so an update that does not change a dictionary reuses its cache. Bump
     * [DICT_CACHE_VERSION] when the native compile writes different data without changing the .kpd2 format
     * version (the native loader rejects other format versions and corrupt files by itself).
     */
    private const val DICT_CACHE_DIR = "kpd2-cache" // not "swipe-...": those are removed at every update
    private const val DICT_CACHE_VERSION = 1
    private val dictCacheNames = ConcurrentHashMap<String, String>()
    private val dictCacheCleaned = AtomicBoolean(false)
    /** size and CRC-32 of every swipe/<lang>.kpd from the APK's zip directory; null if it cannot be read */
    @Volatile
    private var apkDictEntries: Map<String, Pair<Long, Long>>? = null

    /** Soft tie-breaker for the first word in an app (zero context only; never applied mid-sentence). */
    private const val APP_FIRST_WORD_PRIOR = 0.3f
    /** Minimum margin over the best competing other-language candidate to remember an app's language. */
    private const val APP_LANGUAGE_RECORD_MARGIN = 0.5f
    private const val MAX_APP_LANGUAGES = 64
    private val appLanguages = ConcurrentHashMap<String, String>()

    private var layoutSignature: String? = null
    @Volatile
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
        val locales = enabledLocales()
        if (locales.isEmpty()) Log.w(TAG, "no enabled languages at start, nothing preloaded")
        preload(app, locales)
        runCatching { NeuralLanguageModel.preload(app, locales.map { it.engineKey }) }
            .onFailure { Log.w(TAG, "could not preload the neural models", it) }
    }

    /** Every language the user can type in: the enabled keyboards and their secondary (multilingual) languages. */
    fun enabledLocales(): List<Locale> = runCatching {
        SubtypeSettings.getEnabledSubtypes(true).flatMap { listOf(it.locale()) + getSecondaryLocales(it.extraValue) }
            .filter { it.language.isNotEmpty() && it.language != "zz" }.distinct()
    }.onFailure { Log.w(TAG, "could not read the enabled languages", it) }.getOrDefault(emptyList())

    /**
     * Self-healing: starts loading every dictionary of [locales] that ships in the APK and is neither loaded nor
     * loading (called whenever the keyboard is shown). Returns the languages a load was started for.
     */
    fun ensureLoaded(context: Context, locales: List<Locale>): List<String> {
        if (appContext == null) appContext = context.applicationContext ?: context
        if (!DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable) return emptyList()
        val available = languagesWithDictionary(context)
        preloadSessionIfUseful(context, locales, available)
        return locales.map { it.engineKey }.distinct().filter { it in available }.filter { lang ->
            if (dictionaries[lang] != null || loading[lang]?.let { it.count > 0 } == true) return@filter false
            val wasFailed = failedAt.containsKey(lang)
            requestLoad(context, lang)
            // a recent failure is retried only after RETRY_FAILED_MS; then nothing was started now
            !wasFailed || loading[lang]?.let { it.count > 0 } == true
        }
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        DirectBoot.isUnlocked(context) && FutoSwipe.isAvailable && EngineAssets.exists(context, ENCODER)

    /** Languages with a .kpd swipe dictionary in the APK (see [EngineAssets] for why this is not a cached listing). */
    fun languagesWithDictionary(context: Context): Set<String> = EngineAssets.languages(context, ".kpd")

    /**
     * Copies the model and dictionaries out of the APK once per installation (native code needs files).
     * Different files are copied in parallel: after an update the swipe dictionaries and the neural models are
     * requested at the same time by two loader threads, and one lock for all files made each wait for the other.
     */
    private fun swipeAssetDir(context: Context): File {
        if (!DirectBoot.isUnlocked(context)) throw IllegalStateException("user is locked")
        return synchronized(extractLock) {
            assetDir ?: run {
                // keyed by install time, so that every update (also with the same version code) re-extracts
                val installed = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
                File(context.noBackupFilesDir, "swipe-$installed").also {
                    context.noBackupFilesDir.listFiles()?.forEach { old ->
                        if (old.name.startsWith("swipe-") && old != it) old.deleteRecursively()
                    }
                    assetDir = it
                }
            }
        }
    }

    /**
     * The .kpd2 cache of [language], named after the content of the .kpd in the APK. Caches of other contents of
     * the same language and of languages that are no longer in the APK are deleted. Runs on a loader thread.
     */
    private fun dictCacheFile(context: Context, language: String): File {
        if (!DirectBoot.isUnlocked(context)) throw IllegalStateException("user is locked")
        val dir = File(context.noBackupFilesDir, DICT_CACHE_DIR).apply { mkdirs() }
        val entries = apkDictEntries ?: readApkDictEntries(context).also { apkDictEntries = it }
        val name = dictCacheNames.getOrPut(language) {
            val (size, crc) = entries[language] ?: assetSizeAndCrc(context, "$ASSET_DIR/$language.kpd")
            "$language-$size-${crc.toString(16)}-v$DICT_CACHE_VERSION.kpd2"
        }
        // files of this language with another key (also their .tmp); "sk-" does not match "sk_nd-..."
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("$language-") && !f.name.startsWith(name)) f.delete()
        }
        if (entries.isNotEmpty() && dictCacheCleaned.compareAndSet(false, true)) {
            dir.listFiles()?.forEach { f -> if (f.name.substringBefore('-') !in entries) f.delete() }
        }
        return File(dir, name)
    }

    /** Size and CRC-32 of each swipe/<lang>.kpd from the zip directory of the APK (nothing is decompressed). */
    private fun readApkDictEntries(context: Context): Map<String, Pair<Long, Long>> = runCatching {
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("assets/$ASSET_DIR/") && it.name.endsWith(".kpd") && it.crc >= 0 && it.size >= 0 }
                .associate { it.name.substringAfterLast('/').removeSuffix(".kpd") to (it.size to it.crc) }
        }
    }.onFailure { Log.w(TAG, "could not read the swipe dictionaries from the APK directory", it) }
        .getOrDefault(emptyMap())

    /** Fallback when the APK directory cannot be read: reads the asset once. */
    private fun assetSizeAndCrc(context: Context, path: String): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        context.assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return size to crc.value
    }

    internal fun extract(context: Context, name: String): File {
        // fail closed before touching storage: no_backup is credential-encrypted
        if (!DirectBoot.isUnlocked(context)) throw IllegalStateException("user is locked, not extracting $name")
        val dir = swipeAssetDir(context)
        val file = File(dir, name)
        if (file.isFile) return file
        synchronized(extractLocks.computeIfAbsent(name) { Any() }) {
            if (file.isFile) return file
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            context.assets.open("$ASSET_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) throw java.io.IOException("could not move $name into place")
            return file
        }
    }

    private fun ensureSession(context: Context): Boolean {
        if (session != 0L) return true
        extract(context, ENCODER_METADATA)
        val encoder = extract(context, ENCODER)
        synchronized(sessionLock) {
            if (session != 0L) return true
            session = FutoSwipe.nativeCreate(encoder.absolutePath)
            return session != 0L
        }
    }

    /** Warms the encoder session only when gesture typing is on and one of [locales] has a swipe dictionary. */
    private fun preloadSessionIfUseful(context: Context, locales: List<Locale>, available: Set<String>) {
        if (session != 0L || locales.none { it.engineKey in available }) return
        val gestureInput = runCatching { context.prefs().getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT) }
            .getOrDefault(Defaults.PREF_GESTURE_INPUT)
        if (gestureInput) preloadSession(context)
    }

    /** Warms the ExecuTorch encoder session in the background so the first swipe does not stall. */
    private fun preloadSession(context: Context) {
        if (session != 0L || !sessionPreloadRequested.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        loader.execute {
            try {
                if (!ensureSession(app)) sessionPreloadRequested.set(false)
            } catch (e: Throwable) {
                Log.w(TAG, "could not preload swipe encoder session", e)
                sessionPreloadRequested.set(false)
            }
        }
    }

    /** Reloads the user's words on the loader thread every few minutes; the next layout update picks them up. */
    private fun maybeRefreshUserWords() {
        val provider = userWordsProvider ?: return
        if (!DirectBoot.isUnlocked()) return // the user's words are personal data, not read before unlock
        val now = System.currentTimeMillis()
        val interval = if (now < userWordsChangedUntil) USER_WORDS_CHANGED_REFRESH_MS else USER_WORDS_REFRESH_MS
        if (session == 0L || now - userWordsLoadedAt < interval) return
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
        // a load that finished without a dictionary and without a recorded failure would otherwise never be retried
        loading[language]?.let { if (it.count == 0L && dictionaries[language] == null && !failedAt.containsKey(language)) loading.remove(language) }
        return loading.computeIfAbsent(language) {
            val latch = CountDownLatch(1)
            loader.execute {
                try {
                    val v2Cache = dictCacheFile(context, language)
                    val params = params(language) + 0f
                    var handle = if (v2Cache.isFile) {
                        FutoSwipe.nativeLoadDictionary(v2Cache.absolutePath, params).also { h ->
                            if (h == 0L) {
                                Log.w(TAG, "corrupt or rejected swipe dictionary cache $language.kpd2, rebuilding")
                                v2Cache.delete()
                            }
                        }
                    } else 0L
                    if (handle == 0L) {
                        val v1File = extract(context, "$language.kpd")
                        handle = synchronized(compileLock) {
                            FutoSwipe.nativeLoadDictionaryCached(v1File.absolutePath, v2Cache.absolutePath, params)
                        }
                        // Native removes v2Cache if reloading the compiled cache fails, so v2Cache.isFile
                        // is only true when the cache has been verified and mmapped.
                        if (handle != 0L && v2Cache.isFile) v1File.delete()
                    }
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

    /**
     * False while a swipe dictionary of [languages] that ships in the APK is not loaded yet (right after an
     * install or update the files are still being copied out of the APK). Predictions made then come from
     * fewer languages or without n-grams: they are stand-ins and must not be cached.
     */
    @JvmStatic
    fun dictionariesReady(languages: List<String>): Boolean {
        val context = appContext ?: return false
        val available = languagesWithDictionary(context)
        return languages.all { it !in available || dictionaries[it] != null }
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
            preloadSessionIfUseful(context, locales, available)
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
        // the final decode waits for a dictionary that is still being prepared (after an install or update this
        // can take seconds): the IME says so in the suggestion strip meanwhile
        val waiting = waitMs > 0 && wanted.any { dictionaries[it.engineKey] == null && (loading[it.engineKey]?.count ?: 0L) > 0 }
        if (waiting) runCatching { preparingListener?.onPreparing(true) }
        val locales = try {
            wanted.filter { dictionary(context, it.engineKey, (deadline - System.currentTimeMillis()).coerceAtLeast(0)) != 0L }
        } finally {
            if (waiting) runCatching { preparingListener?.onPreparing(false) }
        }
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
        val packageName = runCatching { keyboard.mId.editorInfo.packageName }.getOrNull()?.takeIf { it.isNotEmpty() }
        var finalWords = words
        var finalScores = scores
        var finalDicts = dicts
        if (isFinal && words.isNotEmpty()) {
            // Apply soft +0.3f tie-breaker ONLY on zero-context first words using the app's
            // previously remembered language, so in-sentence bilingual code-switching is unaffected.
            if (activeLanguages.size > 1 && contextWords.isEmpty() && tokens.isEmpty() && packageName != null) {
                val preferredLang = appLanguages[packageName]
                val preferredDictIdx = if (preferredLang != null) activeLanguages.indexOf(preferredLang) else -1
                if (preferredDictIdx >= 0) {
                    val boostedScores = scores.copyOf(words.size)
                    var changed = false
                    for (i in words.indices) {
                        if (dicts[i] == preferredDictIdx) {
                            boostedScores[i] += APP_FIRST_WORD_PRIOR
                            changed = true
                        }
                    }
                    if (changed) {
                        val order = words.indices.sortedByDescending { boostedScores[it] }
                        finalWords = Array(words.size) { words[order[it]] }
                        finalScores = FloatArray(words.size) { boostedScores[order[it]] }
                        finalDicts = IntArray(words.size) { dicts[order[it]] }
                    }
                }
            }
            // Record the app's language from raw (pre-prior) scores when top candidate clearly
            // belongs to a single language with margin > APP_LANGUAGE_RECORD_MARGIN.
            recordAppLanguage(packageName, words, scores, dicts)
        }
        val results = SuggestionResults(MAX_RESULTS, ngramContext, false)
        finalWords.forEachIndexed { i, word ->
            val locale = activeLocales.getOrNull(finalDicts[i]) ?: activeLocales.first()
            // scores are roughly -30..+30; keep their order in the integer score space
            val score = (finalScores[i] * 10_000f).roundToInt() + 1_000_000
            results.add(
                SuggestedWordInfo(
                    word, "", score, SuggestedWordInfo.KIND_CORRECTION, sourceDictionary(locale),
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                )
            )
        }
        if (isFinal && SwipeRecorder.isRecording(runCatching { keyboard.mId.editorInfo }.getOrNull())) {
            SwipeRecorder.onFinalDecode(
                keyboard, pointers, LastInput(x, y, t, contextWords), layoutKeys, finalWords, activeLanguages,
                finalWords.indices.map { activeLanguages.getOrElse(finalDicts[it]) { "" } }, lastMillis
            )
        } else if (isFinal && TypingSwipeRecorder.armed) {
            TypingSwipeRecorder.onFinalDecode(
                keyboard, pointers, LastInput(x, y, t, contextWords), layoutKeys, finalWords, activeLanguages,
                finalWords.indices.map { activeLanguages.getOrElse(finalDicts[it]) { "" } }, lastMillis
            )
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "swipe ${if (isFinal) "final" else "preview"} ${lastMillis}ms (neural ${FutoSwipe.nativeLastNeuralMillis(session)}ms," +
                " ${tokens.size} tokens${if (bos) " +bos" else ""}) prev=$previous -> ${finalWords.take(4)}")
            if (isFinal) Log.d(TAG, "points n=$n first=(${x[0]},${y[0]}) last=(${x[n - 1]},${y[n - 1]}) dur=${t[n - 1]}ms")
        }
        return results
    }

    private fun recordAppLanguage(packageName: String?, words: Array<String>, scores: FloatArray, dicts: IntArray) {
        if (packageName == null || words.isEmpty() || activeLanguages.size < 2) return
        if (Settings.getValues()?.mIncognitoModeEnabled == true) return
        if (words[0].length < 3) return
        val topDict = dicts[0]
        val topLang = activeLanguages.getOrNull(topDict) ?: return
        var bestOtherScore = Float.NEGATIVE_INFINITY
        for (i in 1 until words.size) {
            val d = dicts[i]
            if (d in activeLanguages.indices && d != topDict && scores[i] > bestOtherScore) {
                bestOtherScore = scores[i]
            }
        }
        if (scores[0] - bestOtherScore <= APP_LANGUAGE_RECORD_MARGIN) return
        if (appLanguages.size >= MAX_APP_LANGUAGES && !appLanguages.containsKey(packageName)) {
            appLanguages.keys.firstOrNull()?.let { appLanguages.remove(it) }
        }
        appLanguages[packageName] = topLang
    }

    /**
     * Triggered on swipe finger-down (InputLogicHandler.onStartBatchInput) to warm the neural LMs'
     * KV cache while the user glides across the keyboard. Non-blocking.
     */
    @JvmOverloads
    @JvmStatic
    fun prefetchNeuralForCurrentContext(ngramContext: NgramContext, textBeforeCursor: CharSequence? = null) {
        val context = appContext ?: return
        if (!DirectBoot.isUnlocked(context) || !FutoSwipe.isAvailable) return
        val langs = activeLanguages.ifEmpty {
            val available = languagesWithDictionary(context)
            enabledLocales().map { it.engineKey }.distinct().filter { it in available }.take(3)
        }
        if (langs.isEmpty()) return
        NeuralLanguageModel.prefetchForSwipe(context, langs, contextWords(ngramContext), textBeforeCursor)
    }

    /** Whether loading the swipe dictionary of [language] failed recently (it is tried again later), for the settings. */
    fun loadFailed(language: String): Boolean = failedAt.containsKey(language) && dictionaries[language] == null

    fun filesMissingFromListing(context: Context): List<String> = EngineAssets.missingFromListing(context)

    /** State of the swipe decoder and its dictionaries, for the saved log. No typed text. */
    fun debugState(): String = runCatching {
        val now = System.currentTimeMillis()
        "swipe: context=${appContext != null} native=${FutoSwipe.isAvailable} unlocked=${DirectBoot.isUnlocked()}" +
            " session=${session != 0L} inApk=${appContext?.let { languagesWithDictionary(it).sorted() }}" +
            " loaded=${dictionaries.keys.sorted()} loading=${loading.filter { it.value.count > 0 }.keys.sorted()}" +
            " failed=${failedAt.mapValues { (now - it.value) / 1000 }} active=$activeLanguages dir=${assetDir?.name}" +
            " missingFromListing=${appContext?.let { EngineAssets.missingFromListing(it) }}"
    }.getOrElse { "swipe: $it" }
}
