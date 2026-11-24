// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import helium314.keyboard.latin.utils.Log

/**
 * JNI bindings of libdroneswipe.so: the FUTO Swipe encoder (swipe-library, ExecuTorch) plus
 * Droneboard's key-path dictionaries with diacritic expansion and bigram rescoring.
 * Swipe typing powered by FUTO Swipe.
 */
object FutoSwipe {
    private const val TAG = "FutoSwipe"

    /** true if the native library could be loaded */
    @JvmStatic
    val isAvailable: Boolean = try {
        System.loadLibrary("droneswipe")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "libdroneswipe not available: $e")
        false
    }

    @JvmStatic external fun nativeCreate(encoderPath: String): Long
    @JvmStatic external fun nativeDestroy(handle: Long)

    /** A .kpd dictionary (tools/dicts/pack_swipe_dict.py).
     *  params: gamma, beta, lambda, capsPenalty, bigramWeight, languageBonus[, historyBonus] */
    @JvmStatic external fun nativeLoadDictionary(path: String, params: FloatArray): Long
    /** Loads [cachePath] via zero-copy mmap when present; otherwise parses [v1Path], compiles a flat
     *  .kpd v2 cache at [cachePath], and switches immediately to mmap. */
    @JvmStatic external fun nativeLoadDictionaryCached(v1Path: String, cachePath: String, params: FloatArray): Long
    /** Zero-copy mmap loader for an uncompressed .kpd dictionary inside an APK AssetFileDescriptor. */
    @JvmStatic external fun nativeLoadDictionaryFd(fd: Int, off: Long, len: Long): Long
    @JvmStatic external fun nativeFreeDictionary(dict: Long)
    @JvmStatic external fun nativeSetDictionaryParams(dict: Long, params: FloatArray)

    /** The user's own words (typing history, personal dictionary) with weights 0-255; they are searched as one
     *  more dictionary and every candidate the user has written gets params[6] x weight/255. Takes effect with
     *  the next [nativeSetLayout]. */
    @JvmStatic external fun nativeSetUserWords(handle: Long, words: Array<String>?, weights: IntArray?, params: FloatArray)

    @JvmStatic external fun nativeSetLayout(handle: Long, letters: IntArray, xs: FloatArray, ys: FloatArray, dicts: LongArray): Boolean

    /**
     * context: words before the cursor, oldest first (the last one is the previous word);
     * prior: language prior per dictionary of the layout (null = equal); kappa, cap: weight and
     * floor of the context language bonus.
     * models: the neural models of the layout's dictionaries in their order (null = no neural fusion; a
     * 0 handle switches it off too), reranking with [tokens] before the cursor ([bos]: they start the
     * text) and [fusion] = NeuralFusion's fields (native/droneswipe/neural_lm.hpp; null = defaults).
     */
    @JvmStatic external fun nativeRecognize(
        handle: Long, x: FloatArray, y: FloatArray, t: FloatArray, context: Array<String>,
        prior: FloatArray?, kappa: Float, cap: Float,
        beam: Int, maxResults: Int, outScores: FloatArray, outDict: IntArray,
        models: LongArray?, tokens: Array<String>?, bos: Boolean, fusion: FloatArray?
    ): Array<String>

    @JvmStatic external fun nativeLastMillis(handle: Long): Float

    /** Of [nativeLastMillis]: the time the neural fusion took. */
    @JvmStatic external fun nativeLastNeuralMillis(handle: Long): Float

    /** Language continuity tie-breaker margin of the swipe ranking (0 = off). */
    @JvmStatic external fun nativeSetTieMargin(handle: Long, margin: Float)

    /**
     * Next-word prediction / completion of [prefix] (lowercase matching; may be empty) from the n-gram data of
     * the given dictionaries (the enabled languages). context: words before the cursor, oldest first.
     * Writes scores (log probability plus language log posterior) and the index into [dicts].
     */
    @JvmStatic external fun nativePredict(
        dicts: LongArray, context: Array<String>, prefix: String, maxResults: Int,
        outScores: FloatArray, outDict: IntArray
    ): Array<String>

    /** Neural next-word model: <lang>.lmb + <lang>.lmv (tools/lm/export_keyboard_lm.py); 0 on failure. */
    @JvmStatic external fun nativeLoadNeural(lmbPath: String, lmvPath: String): Long
    /** Zero-copy mmap loader for uncompressed <lang>.lmb + <lang>.lmv APK AssetFileDescriptors; 0 on failure. */
    @JvmStatic external fun nativeLoadNeuralFd(
        lmbFd: Int, lmbOff: Long, lmbLen: Long,
        lmvFd: Int, lmvOff: Long, lmvLen: Long
    ): Long
    @JvmStatic external fun nativeFreeNeural(model: Long)

    /** Warms the neural models' KV cache / logit distribution for [context] (called on swipe finger-down). */
    @JvmStatic external fun nativePrefetchNeural(models: LongArray, context: Array<String>, bos: Boolean)

    /**
     * Next-word prediction / completion of [prefix] with the neural models of the enabled languages, each with
     * the .kpd dictionary of its language in [dicts] (0 = none; with a dictionary, only words it knows are
     * suggested, in its capitalization). context: normalized tokens before the cursor, oldest first; bos: they
     * start at the beginning of the text. Writes scores (log probability plus language log posterior) and the
     * index into [models].
     */
    @JvmStatic external fun nativePredictNeural(
        models: LongArray, dicts: LongArray, context: Array<String>, bos: Boolean, prefix: String, maxResults: Int,
        outScores: FloatArray, outLang: IntArray
    ): Array<String>
}
