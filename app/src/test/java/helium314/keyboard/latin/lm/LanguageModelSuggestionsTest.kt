// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.lm

import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LanguageModelSuggestionsTest {
    private val sk = Locale.forLanguageTag("sk")

    private fun info(word: String, score: Int) = SuggestedWordInfo(
        word, "", score, SuggestedWordInfo.KIND_CORRECTION, Dictionary.DICTIONARY_USER_TYPED,
        SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
    )

    private val makeInfo = { p: WordPrediction, score: Int -> info(p.word, score) }

    private fun pred(word: String, score: Float = -5f) = WordPrediction(word, score, sk)

    @Test fun `next word fuses both rankings and keeps the order in the scores`() {
        val native = listOf(info("je", 900), info("sa", 800), info("to", 700))
        val merged = LanguageModelSuggestions.mergeNextWord(native, listOf(pred("to"), pred("aj")), makeInfo)
        assertEquals(listOf("to", "je", "sa", "aj"), merged.map { it.word })
        assertEquals(900, merged.first().score)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.score > b.score })
    }

    @Test fun `next word without usable predictions returns the dictionaries' list`() {
        val native = listOf(info("je", 900))
        assertSame(native, LanguageModelSuggestions.mergeNextWord(native, emptyList(), makeInfo))
        assertSame(native, LanguageModelSuggestions.mergeNextWord(native, listOf(pred("aj", -30f)), makeInfo))
    }

    @Test fun `completions follow the first suggestion and are not duplicated`() {
        val native = listOf(info("raketa", 1000), info("raketám", 990), info("raketami", 980))
        val merged = LanguageModelSuggestions.mergeCompletions(
            native, listOf(pred("raketám"), pred("raketou"), pred("raketový"), pred("raketoplán")), "rak", makeInfo
        )
        assertEquals(listOf("raketa", "raketou", "raketový", "raketám", "raketami"), merged.map { it.word })
        assertEquals(1000, merged.first().score)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.score >= b.score })
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
}
