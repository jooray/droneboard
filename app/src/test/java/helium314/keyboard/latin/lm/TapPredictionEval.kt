// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.suggestions.Suggest
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import helium314.keyboard.latin.suggestions.SuggestionResults
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.test.Test

/**
 * End-to-end tap-typing evaluation: what the suggestion strip shows, not what the model ranks.
 *
 * Replays held-out text (tools/lm/make_lm_eval_set.py items) through the AOSP dictionaries (the real native
 * library, built for the host), the app's merge with the language model ([LanguageModelSuggestions], model
 * candidates dumped by tools/swipe-replay lm-eval --dump), the facilitator's emoji and word rules, and the strip
 * arrangement of [Suggest]. Then it counts a hit when the target word is in a visible slot: three slots for the
 * next word, while typing the two side slots (the centre echoes the typed word), one when an emoji takes the
 * right slot.
 *
 * Skipped unless TAP_EVAL_DIR is set. The directory holds libjni_latinime.dylib (the app's jni sources built for
 * the host), items-<lang>.jsonl, dump-<lang>.jsonl and optionally dump-<lang>-fold.jsonl (letters typed without
 * accents). TAP_EVAL_DICTS is the assets/dicts directory (default: the app's).
 *   TAP_EVAL_DIR=... ./gradlew :app:testDebugUnitTest --tests '*TapPredictionEval*' -i
 */
@RunWith(RobolectricTestRunner::class)
class TapPredictionEval {
    private val dir = System.getenv("TAP_EVAL_DIR")?.let { File(it) }
    private val dictDir = File(System.getenv("TAP_EVAL_DICTS") ?: "src/main/assets/dicts")

    private class Group(val locale: Locale, val dicts: List<BinaryDictionary>, var weight: Float)


    @Test fun eval() {
        Assume.assumeTrue("TAP_EVAL_DIR not set", dir != null)
        System.load(File(dir, "libjni_latinime.dylib").absolutePath)
        val pi = makeProximityInfo()
        val out = StringBuilder()
        val only = System.getenv("TAP_EVAL_LANGS")?.split(",")
        for ((lang, other) in listOf("sk" to "en", "en" to "sk", "cs" to "en")) {
            if (only != null && lang !in only) continue
            val main = loadGroup(lang, 1f)
            val second = loadGroup(other, 0.7f) // the less confident language (DictionaryGroup.getWeightForLocale)
            for (variant in listOf("", "-fold")) {
                val dump = File(dir, "dump-$lang$variant.jsonl")
                if (!dump.isFile) continue
                val r = run(File(dir, "items-$lang.jsonl"), dump, listOf(main, second), pi)
                r.forEachIndexed { ci, s -> out.append("%-8s %-8s ".format(Locale.ROOT, configs[ci].name, "$lang$variant")).append(s).append('\n') }
            }
        }
        println("TAP-EVAL\n$out")
        File(dir, "result.txt").writeText(out.toString())
    }

    /**
     * Suggest.preferNextWordSuggestion on swipe results: swout-<set>.jsonl (swipe-replay --out, top 10 with
     * scores), swin-<set>.jsonl (the swipes with their context) and swnext-<set>.jsonl (lm-eval --dump
     * --max-typed 0: the model's next-word candidates). Prints top-1 of the decoder and after the promotion.
     */
    @Test fun swipePromotion() {
        Assume.assumeTrue("TAP_EVAL_DIR not set", dir != null)
        System.load(File(dir, "libjni_latinime.dylib").absolutePath)
        val pi = makeProximityInfo()
        val out = StringBuilder()
        for ((set, langs) in listOf("rec" to listOf("sk", "en"), "synsk" to listOf("sk", "en"), "synen" to listOf("en", "sk"),
                "futocs" to listOf("cs"), "syncs" to listOf("cs"))) {
            val outFile = File(dir, "swout-$set.jsonl")
            if (!outFile.isFile) continue
            val groups = langs.mapIndexed { i, l -> loadGroup(l, if (i == 0) 1f else 0.7f) }
            val ins = File(dir, "swin-$set.jsonl").readLines()
            val outs = outFile.readLines()
            val nexts = File(dir, "swnext-$set.jsonl").readLines()
            var n = 0; var top1 = 0; var promoted = 0; var changed = 0; var gained = 0; var lost = 0
            for (i in outs.indices) {
                val o = JSONObject(outs[i])
                val inp = JSONObject(ins[i])
                val target = o.getString("target")
                if (target != inp.getString("target")) error("misaligned $set line $i")
                val ctx = inp.optJSONArray("context")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val ngram = if (ctx.isEmpty()) NgramContext.BEGINNING_OF_SENTENCE
                    else NgramContext(*ctx.takeLast(3).reversed().map { NgramContext.WordInfo(it) }.toTypedArray())
                val pa = o.getJSONArray("pred")
                val container = ArrayList((0 until pa.length()).map { k ->
                    val a = pa.getJSONArray(k)
                    val g = groups.firstOrNull { it.locale.language == a.getString(1) } ?: groups[0]
                    SuggestedWordInfo(a.getString(0), "", (a.getDouble(2) * 10_000).toInt() + 1_000_000,
                        SuggestedWordInfo.KIND_CORRECTION, g.dicts.first { it.mDictType == Dictionary.TYPE_MAIN },
                        SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
                })
                if (container.isEmpty()) { n++; continue }
                val nrow = JSONArray(nexts[i]).getJSONObject(0).getJSONArray("p")
                val preds = (0 until nrow.length()).map { k ->
                    val a = nrow.getJSONArray(k)
                    WordPrediction(a.getString(0), a.getDouble(1).toFloat(), Locale.forLanguageTag(a.getString(2)))
                }
                configs.last().apply()
                val next = facilitator("", ngram, groups, pi, preds)
                val before = container[0].word
                val after = Suggest.preferNextWordSuggestion(container[0], container, next, null, true)?.word
                n++
                if (before == target) top1++
                if (after == target) promoted++
                if (after != before) { changed++; if (after == target) gained++; if (before == target) lost++ }
            }
            out.append("%-7s n=%5d  decoder top-1 %5.1f  with preferNextWordSuggestion %5.1f  (changed %d: +%d -%d)\n".format(
                Locale.ROOT, set, n, 100.0 * top1 / n, 100.0 * promoted / n, changed, gained, lost))
        }
        println("SWIPE-PROMOTION\n$out")
        File(dir, "swipe-result.txt").writeText(out.toString())
    }

    /** prints the emoji dictionary's results with their scores for the prefixes of a few keywords */
    @Test fun emojiScores() {
        Assume.assumeTrue("TAP_EVAL_DIR not set", dir != null && System.getenv("TAP_EVAL_EMOJI") != null)
        System.load(File(dir, "libjni_latinime.dylib").absolutePath)
        val pi = makeProximityInfo()
        val sb = StringBuilder()
        for ((lang, words) in listOf("sk" to listOf("raketa", "srdce", "pivo", "mačka", "láska", "auto", "slnko", "smiech"),
                "en" to listOf("rocket", "heart", "beer", "cat", "love", "car", "sun", "laugh", "the", "this"))) {
            val g = loadGroup(lang, 1f)
            val emoji = g.dicts.first { it.mDictType == Dictionary.TYPE_EMOJI }
            for (w in words) for (k in 2..w.length) {
                val typed = w.take(k)
                val r = emoji.getSuggestions(composed(typed), NgramContext.BEGINNING_OF_SENTENCE, pi, settings, 0, 1f,
                    floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL)).orEmpty()
                sb.append("$lang $typed: ").append(r.take(4).joinToString { "${it.word} ${it.score} k${it.kind}" }).append('\n')
            }
        }
        File(dir, "emoji-scores.txt").writeText(sb.toString())
    }

    private fun loadGroup(lang: String, weight: Float): Group {
        val files = dictDir.listFiles()!!.filter {
            val n = it.name.substringBeforeLast(".")
            val tag = n.substringAfter("_")
            tag == lang || (lang == "en" && tag == "en-US")
        }
        val dicts = files.map { f ->
            val type = f.name.substringBefore("_")
            BinaryDictionary(f.absolutePath, 0, f.length(), false, Locale.forLanguageTag(lang), type, false)
        }.filter { it.isValidDictionary }
        return Group(Locale.forLanguageTag(lang), dicts, weight)
    }

    // ---------------------------------------------------------------- keyboard geometry

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val keyW = 100
    private val keyH = 150
    private val keyCenter = HashMap<Char, Pair<Int, Int>>().apply {
        rows.forEachIndexed { r, row ->
            val off = when (r) { 0 -> 0; 1 -> 50; else -> 150 }
            row.forEachIndexed { i, c -> put(c, (off + i * keyW + keyW / 2) to (r * keyH + keyH / 2)) }
        }
    }

    private fun makeProximityInfo(): Long {
        val width = 1000
        val height = 600
        val gw = 32
        val gh = 16
        val cw = (width + gw - 1) / gw
        val ch = (height + gh - 1) / gh
        val keys = keyCenter.entries.toList()
        val prox = IntArray(gw * gh * ProximityInfo.MAX_PROXIMITY_CHARS_SIZE) { Constants.NOT_A_CODE }
        val threshold = (1.2f * keyW) * (1.2f * keyW)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val cx = gx * cw + cw / 2
            val cy = gy * ch + ch / 2
            var k = (gy * gw + gx) * ProximityInfo.MAX_PROXIMITY_CHARS_SIZE
            val near = keys.map { (c, xy) ->
                val dx = maxOf(0, kotlin.math.abs(cx - xy.first) - keyW / 2).toFloat()
                val dy = maxOf(0, kotlin.math.abs(cy - xy.second) - keyH / 2).toFloat()
                c to dx * dx + dy * dy
            }.filter { it.second < threshold }.sortedBy { it.second }.take(ProximityInfo.MAX_PROXIMITY_CHARS_SIZE)
            near.forEach { prox[k++] = it.first.code }
        }
        val m = ProximityInfo::class.java.getDeclaredMethod("setProximityInfoNative",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, IntArray::class.java, IntArray::class.java, IntArray::class.java,
            IntArray::class.java, IntArray::class.java, FloatArray::class.java, FloatArray::class.java, FloatArray::class.java)
        m.isAccessible = true
        return m.invoke(null, width, height, gw, gh, keyW, keyH, prox, keys.size,
            keys.map { it.value.first - keyW / 2 }.toIntArray(), keys.map { it.value.second - keyH / 2 }.toIntArray(),
            IntArray(keys.size) { keyW }, IntArray(keys.size) { keyH }, keys.map { it.key.code }.toIntArray(),
            null, null, null) as Long
    }

    private fun baseLetter(c: Char): Char =
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().lowercaseChar()

    private fun composed(typed: String): ComposedData {
        val p = InputPointers(48)
        typed.forEachIndexed { i, c ->
            val xy = keyCenter[baseLetter(c)] ?: (-1 to -1)
            p.addPointer(xy.first, xy.second, 0, i * 200)
        }
        return ComposedData(p, false, typed)
    }


    // ---------------------------------------------------------------- the pipeline

    private val settings = SettingsValuesForSuggestion(false, false)

    /** a setting of the merge: name and the fields to set ("rerank=0,modelnext=0,emoji=0,aosp=1.0,gap=1.0") */
    private class Config(val name: String, val values: Map<String, String>) {
        fun apply() {
            LanguageModelSuggestions.rerankCompletions = values["rerank"] != "0"
            LanguageModelSuggestions.modelFirstNextWord = values["modelnext"] != "0"
            LanguageModelSuggestions.AOSP_WEIGHT = values["aosp"]?.toDouble() ?: DEFAULT_AOSP
            LanguageModelSuggestions.FLOOR_GAP = values["gap"]?.toDouble() ?: DEFAULT_GAP
            DictionaryFacilitatorImpl.emojiNeedsKeywordMatch = values["emoji"] != "0"
            DictionaryFacilitatorImpl.emojiMinTypedLength = values["emojimin"]?.toInt()
                ?: if (values["emoji"] == "0") 1 else DEFAULT_EMOJI_MIN
        }
        companion object { // the app's settings
            val DEFAULT_AOSP = LanguageModelSuggestions.AOSP_WEIGHT
            val DEFAULT_GAP = LanguageModelSuggestions.FLOOR_GAP
            val DEFAULT_EMOJI_MIN = DictionaryFacilitatorImpl.emojiMinTypedLength
        }
    }

    @org.junit.After fun restoreSettings() = Config("app", emptyMap()).apply()

    private val configs = (System.getenv("TAP_EVAL_CONFIGS") ?: "now:").split(";").filter { it.isNotBlank() }.map { c ->
        val name = c.substringBefore(":")
        Config(name, c.substringAfter(":", "").split(",").filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") })
    }

    /** the dictionaries' part of DictionaryFacilitatorImpl.getSuggestionResults: per group, all their suggestions */
    private fun nativeSuggestions(typed: String, ngram: NgramContext, groups: List<Group>, pi: Long): Array<List<SuggestedWordInfo>?> {
        val cd = if (typed.isEmpty()) ComposedData(InputPointers(1), false, "") else composed(typed)
        val array = Array<List<SuggestedWordInfo>?>(groups.size) { null }
        groups.forEachIndexed { gi, g ->
            val list = ArrayList<SuggestedWordInfo>()
            for (d in g.dicts) {
                d.getSuggestions(cd, ngram, pi, settings, 0, g.weight, floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL))
                    ?.let { list.addAll(it) }
            }
            array[gi] = list
        }
        return array
    }

    /** the rest of DictionaryFacilitatorImpl.getSuggestionResults with the model's [predictions] */
    private fun facilitator(typed: String, ngram: NgramContext, groups: List<Group>, array: Array<List<SuggestedWordInfo>?>,
                            predictions: List<WordPrediction>): SuggestionResults {
        val results = SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, ngram, false)
        array.forEach { it?.let { l -> results.addAll(l) } }
        DictionaryFacilitatorImpl.dropNonKeywordEmoji(results, typed)
        if (predictions.isNotEmpty() && typed.none { !Character.isLetter(it) && it != '\'' && it != '-' }) {
            val native = results.toList()
            val makeInfo = { p: WordPrediction, score: Int ->
                SuggestedWordInfo(p.word, "", score,
                    if (typed.isEmpty()) SuggestedWordInfo.KIND_PREDICTION else SuggestedWordInfo.KIND_COMPLETION,
                    (groups.firstOrNull { it.locale.language == p.locale.language } ?: groups[0]).dicts[0],
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            }
            val merged = LanguageModelSuggestions.merge(native, predictions, typed, makeInfo)
            if (merged !== native) { results.clear(); merged.forEach { results.add(it) } }
        }
        DictionaryFacilitatorImpl.includeAtLeastTwoWordSuggestions(results, array, typed)
        DictionaryFacilitatorImpl.keepBestEmoji(results, array, typed)
        return results
    }

    private fun facilitator(typed: String, ngram: NgramContext, groups: List<Group>, pi: Long,
                            predictions: List<WordPrediction>) =
        facilitator(typed, ngram, groups, nativeSuggestions(typed, ngram, groups, pi), predictions)

    /** the visible words of the strip, as Suggest.getSuggestedWordsForNonBatchInput arranges them (no caps,
     *  no autocorrection, typed word in the centre) */
    private fun visible(typed: String, results: SuggestionResults): Pair<List<String>, Boolean> {
        val c = ArrayList(results)
        SuggestedWordInfo.removeDupsAndTypedWord(typed, c)
        Suggest.makeFirstTwoSuggestionsNonEmoji(c)
        if (typed.isEmpty()) return c.take(3).map { it.word } to false
        val typedInfo = SuggestedWordInfo(typed, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        c.add(0, typedInfo)
        c.add(1, typedInfo)
        Suggest.moveEmojiToRightSlot(c)
        val shown = c.drop(2).take(2)
        return shown.map { it.word } to (shown.getOrNull(1)?.isEmoji == true)
    }

    // ---------------------------------------------------------------- scoring

    private class Score {
        var n = 0; var chars = 0L; var saved = 0L; var modelSaved = 0L
        var next = 0; var modelNext = 0; var l1 = 0; var l2 = 0; var m1 = 0; var m2 = 0
        var states = 0; var emojiStates = 0
        override fun toString(): String {
            fun p(k: Int) = "%5.1f".format(Locale.ROOT, 100.0 * k / maxOf(n, 1))
            return "n=$n  visible KSS %5.1f (model top-3 %5.1f)  next word %s (%s)  1 letter %s (%s)  2 letters %s (%s)  emoji in right slot %4.1f%%"
                .format(Locale.ROOT, 100.0 * saved / chars, 100.0 * modelSaved / chars, p(next), p(modelNext), p(l1), p(m1), p(l2), p(m2),
                    100.0 * emojiStates / maxOf(states, 1))
        }
    }

    private fun run(items: File, dump: File, groups: List<Group>, pi: Long): List<Score> {
        val scores = configs.map { Score() }
        val dumps = dump.readLines()
        items.readLines().forEachIndexed { idx, line ->
            if (idx >= dumps.size) return@forEachIndexed
            val item = JSONObject(line)
            val target = item.getString("target").lowercase(Locale.ROOT)
            val ctx = item.getJSONArray("context").let { a -> (0 until a.length()).map { a.getString(it) } }
            val ngram = if (ctx.isEmpty()) NgramContext.BEGINNING_OF_SENTENCE
                else NgramContext(*ctx.reversed().map { NgramContext.WordInfo(it) }.toTypedArray())
            val rows = JSONArray(dumps[idx])
            val saved = IntArray(configs.size) { -1 }
            var modelSaved = -1
            val hits = BooleanArray(configs.size)
            var modelHitAt = BooleanArray(3)
            for (k in 0 until rows.length()) {
                if (k > target.length) break
                val row = rows.getJSONObject(k)
                val typed = row.getString("t")
                val pa = row.getJSONArray("p")
                val preds = (0 until pa.length()).map { i ->
                    val a = pa.getJSONArray(i)
                    WordPrediction(a.getString(0), a.getDouble(1).toFloat(), Locale.forLanguageTag(a.getString(2)))
                }
                val native = nativeSuggestions(typed, ngram, groups, pi)
                val modelHit = preds.filter { it.score >= LanguageModelSuggestions.MIN_SCORE }.take(3)
                    .any { it.word.lowercase(Locale.ROOT) == target }
                if (modelHit && modelSaved < 0) modelSaved = target.length - k
                configs.forEachIndexed { ci, cfg ->
                    cfg.apply()
                    val s = scores[ci]
                    val (shown, emoji) = visible(typed, facilitator(typed, ngram, groups, native, preds))
                    if (typed.isNotEmpty()) { s.states++; if (emoji) s.emojiStates++ }
                    val hit = shown.any { it.lowercase(Locale.ROOT) == target }
                    when (k) {
                        0 -> { if (hit) s.next++; if (modelHit) s.modelNext++ }
                        1 -> { if (hit) s.l1++; if (modelHit) s.m1++ }
                        2 -> { if (hit) s.l2++; if (modelHit) s.m2++ }
                    }
                    if (hit && saved[ci] < 0) saved[ci] = target.length - k
                }
            }
            scores.forEachIndexed { ci, s ->
                s.n++
                s.chars += target.length + 1
                s.saved += maxOf(saved[ci], 0)
                s.modelSaved += maxOf(modelSaved, 0)
            }
        }
        return scores
    }
}
