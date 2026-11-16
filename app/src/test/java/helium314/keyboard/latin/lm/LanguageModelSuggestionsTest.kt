// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LanguageModelSuggestionsTest {
    private val sk = Locale.forLanguageTag("sk")

    private fun dict(type: String) = object : Dictionary(type, sk) {
        override fun getSuggestions(
            composedData: ComposedData?, ngramContext: NgramContext?, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion?, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?
        ): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String?) = false
    }
    private val main = dict(Dictionary.TYPE_MAIN)
    private val history = dict(Dictionary.TYPE_USER_HISTORY)

    private fun info(word: String, score: Int, source: Dictionary = main, kind: Int = SuggestedWordInfo.KIND_CORRECTION) =
        SuggestedWordInfo(word, "", score, kind, source, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private val makeInfo = { p: WordPrediction, score: Int -> info(p.word, score) }

    private fun pred(word: String, score: Float = -5f) = WordPrediction(word, score, sk)

    private fun ordered(list: List<SuggestedWordInfo>) = list.zipWithNext().all { (a, b) -> a.score > b.score }

    @Test fun `next word follows the model, the dictionaries' other predictions follow`() {
        val native = listOf(info("je", 900), info("sa", 800), info("to", 700))
        val merged = LanguageModelSuggestions.mergeNextWord(native, listOf(pred("to", -2f), pred("aj", -3f)), makeInfo)
        assertEquals(listOf("to", "aj", "je", "sa"), merged.map { it.word })
        assertEquals(900, merged.first().score)
        assertTrue(ordered(merged))
    }

    @Test fun `next word moves the user's history up`() {
        val native = listOf(info("je", 900), info("nostr", 850, history), info("bitcoin", 800, history))
        val merged = LanguageModelSuggestions.mergeNextWord(
            native, listOf(pred("to", -2f), pred("aj", -3f), pred("bitcoin", -3.5f)), makeInfo
        )
        // bitcoin: model -3.5 + history 2 = -1.5 beats "to"; nostr (history only): floor -4.5 + 2 = -2.5 beats "aj"
        assertEquals(listOf("bitcoin", "to", "nostr", "aj", "je"), merged.map { it.word })
        assertSame(history, merged.first().sourceDict)
    }

    @Test fun `next word without usable predictions returns the dictionaries' list`() {
        val native = listOf(info("je", 900))
        assertSame(native, LanguageModelSuggestions.mergeNextWord(native, emptyList(), makeInfo))
        assertSame(native, LanguageModelSuggestions.mergeNextWord(native, listOf(pred("aj", -30f)), makeInfo))
    }

    @Test fun `completions are ranked by the model with the dictionaries' rank`() {
        val native = listOf(info("raketa", 1000), info("raketám", 990), info("raketami", 980))
        val merged = LanguageModelSuggestions.mergeCompletions(
            native, listOf(pred("raketou", -2f), pred("raketám", -4f), pred("raketový", -6f)), "rak", makeInfo
        )
        // with AOSP_WEIGHT w: raketou -2 - w ln 4; raketám -4 - w ln 2; raketový -6 - w ln 4; raketa (floor -7) - w ln 1; raketami -7 - w ln 3
        assertEquals(listOf("raketou", "raketám", "raketový", "raketa", "raketami"), merged.map { it.word })
        assertEquals(1000, merged.first().score)
        assertTrue(ordered(merged))
    }

    @Test fun `with autocorrection the dictionaries' first suggestion stays first`() {
        val native = listOf(info("raketa", 1000), info("raketám", 990))
        val merged = LanguageModelSuggestions.mergeCompletions(
            native, listOf(pred("raketou", -2f)), "rak", makeInfo, keepNativeFirst = true
        )
        assertEquals(listOf("raketa", "raketou", "raketám"), merged.map { it.word })
    }

    @Test fun `completions match the typed letters without accents`() {
        val native = listOf(info("dakovat", 1000))
        val merged = LanguageModelSuggestions.mergeCompletions(
            native, listOf(pred("ďakujem", -1f), pred("dom", -1f), pred("dakotský", -9f)), "dak", makeInfo
        )
        assertEquals(listOf("ďakujem", "dakotský", "dakovat"), merged.map { it.word })
    }

    @Test fun `emoji keep their places`() {
        val native = listOf(info("raketa", 1000), info("🚀", 999), info("raketám", 990))
        val merged = LanguageModelSuggestions.mergeCompletions(native, listOf(pred("raketou", -1f)), "rak", makeInfo)
        assertEquals("🚀", merged[1].word)
        assertTrue(ordered(merged))
    }

    @Test fun `completions must extend the typed letters`() {
        val native = listOf(info("ahoj", 500))
        val merged = LanguageModelSuggestions.mergeCompletions(
            native, listOf(pred("ah"), pred("bratislava"), pred("ahojte", -40f)), "ah", makeInfo
        )
        assertSame(native, merged)
    }

    @Test fun `completions without dictionary suggestions`() {
        val merged = LanguageModelSuggestions.mergeCompletions(emptyList(), listOf(pred("nostr"), pred("nostra")), "nos", makeInfo)
        assertEquals(listOf("nostr", "nostra"), merged.map { it.word })
    }

    @Test fun `fold drops accents and case`() {
        assertEquals("dakujem", LanguageModelSuggestions.fold("Ďakujem"))
        assertEquals("strasse", LanguageModelSuggestions.fold("Straße"))
        assertEquals("nino", LanguageModelSuggestions.fold("niño"))
    }
}
