// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.NgramContext.WordInfo
import helium314.keyboard.latin.inputlogic.EnglishICapitalizer
import helium314.keyboard.latin.inputlogic.EnglishICapitalizer.WordLanguage.ENGLISH
import helium314.keyboard.latin.inputlogic.EnglishICapitalizer.WordLanguage.OTHER
import helium314.keyboard.latin.inputlogic.EnglishICapitalizer.WordLanguage.UNKNOWN
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnglishICapitalizerTest {
    private val en = Locale.forLanguageTag("en-US")
    private val sk = Locale.forLanguageTag("sk")
    private val cs = Locale.forLanguageTag("cs")
    private val dictionaries = mapOf(
        en to setOf("I", "think", "and", "a", "so", "do", "you", "the"),
        sk to setOf("i", "a", "keď", "prišiel", "on", "do", "som", "to"),
        cs to setOf("i", "a", "když", "přišel", "on", "do", "jsem", "to"),
    )
    private val isIn = { word: String, locale: Locale -> dictionaries[locale]?.contains(word) == true }
    private fun lang(word: String, locales: List<Locale> = listOf(en, sk, cs)) = EnglishICapitalizer.languageOf(word, locales, isIn)

    @Test fun forms() {
        assertEquals("I", EnglishICapitalizer.capitalizedForm("i"))
        assertEquals("I'm", EnglishICapitalizer.capitalizedForm("i'm"))
        assertEquals("I'll", EnglishICapitalizer.capitalizedForm("i'll"))
        assertEquals("I'd", EnglishICapitalizer.capitalizedForm("i'd"))
        assertEquals("I've", EnglishICapitalizer.capitalizedForm("i've"))
        assertEquals("I’m", EnglishICapitalizer.capitalizedForm("i’m"))
        assertNull(EnglishICapitalizer.capitalizedForm("I"))
        assertNull(EnglishICapitalizer.capitalizedForm("in"))
        assertNull(EnglishICapitalizer.capitalizedForm("is"))
        assertNull(EnglishICapitalizer.capitalizedForm("im"))
        assertNull(EnglishICapitalizer.capitalizedForm("í"))
    }

    @Test fun wordLanguage() {
        assertEquals(ENGLISH, lang("think"))
        assertEquals(ENGLISH, lang("I")) // exact case first: "I" is English, even though "i" is Slovak
        assertEquals(OTHER, lang("keď"))
        assertEquals(OTHER, lang("Prišiel")) // capitalized at sentence start, lowercase form counts
        assertEquals(UNKNOWN, lang("a")) // in both
        assertEquals(UNKNOWN, lang("do"))
        assertEquals(UNKNOWN, lang("Xyzzy")) // in no dictionary
        assertEquals(UNKNOWN, lang("think", listOf(sk))) // English not enabled: never English
    }

    @Test fun mainLocaleEnglish() {
        // no previous word, or only ambiguous ones: the main locale decides
        assertEquals("I", EnglishICapitalizer.decide("i", true, true, emptyList()))
        assertEquals("I", EnglishICapitalizer.decide("i", true, true, listOf(UNKNOWN, UNKNOWN)))
        assertEquals("I", EnglishICapitalizer.decide("i", true, true, listOf(ENGLISH)))
        // a clearly Slovak / Czech previous word keeps "i" ("prišiel i on")
        assertNull(EnglishICapitalizer.decide("i", true, true, listOf(OTHER)))
        assertNull(EnglishICapitalizer.decide("i", true, true, listOf(UNKNOWN, OTHER)))
    }

    @Test fun mainLocaleNotEnglish() {
        assertNull(EnglishICapitalizer.decide("i", false, true, emptyList()))
        assertNull(EnglishICapitalizer.decide("i", false, true, listOf(UNKNOWN))) // "a i"
        assertNull(EnglishICapitalizer.decide("i", false, true, listOf(OTHER)))
        assertEquals("I", EnglishICapitalizer.decide("i", false, true, listOf(ENGLISH))) // "and i"
        assertEquals("I", EnglishICapitalizer.decide("i", false, true, listOf(UNKNOWN, ENGLISH))) // "so do i"
        // nearest decisive word wins
        assertNull(EnglishICapitalizer.decide("i", false, true, listOf(OTHER, ENGLISH)))
    }

    @Test fun noEnglish() {
        assertNull(EnglishICapitalizer.decide("i", false, false, listOf(ENGLISH)))
        assertNull(EnglishICapitalizer.decide("i'm", false, false, emptyList()))
    }

    @Test fun contractionsAreAlwaysEnglish() {
        assertEquals("I'm", EnglishICapitalizer.decide("i'm", false, true, listOf(OTHER)))
        assertEquals("I've", EnglishICapitalizer.decide("i've", true, true, emptyList()))
    }

    @Test fun previousWordsStopAtSentenceStart() {
        val context = NgramContext(3, WordInfo("think"), WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO, WordInfo("ok"))
        assertEquals(listOf("think"), EnglishICapitalizer.previousWords(context))
        assertEquals(emptyList(), EnglishICapitalizer.previousWords(NgramContext.BEGINNING_OF_SENTENCE))
        assertEquals(listOf("a", "prišiel"), EnglishICapitalizer.previousWords(NgramContext(3, WordInfo("a"), WordInfo("prišiel"))))
    }

    @Test fun sentences() {
        fun run(main: Locale, vararg previous: String): String? {
            val locales = listOf(main) + listOf(en, sk, cs).filter { it != main }
            return EnglishICapitalizer.decide("i", main == en, true, previous.map { lang(it, locales) })
        }
        assertEquals("I", run(en)) // "i think"
        assertEquals("I", run(en, "and")) // "you and i"
        assertEquals("I", run(sk, "and"))
        assertNull(run(sk)) // "i keď ..."
        assertNull(run(sk, "a")) // "a i"
        assertNull(run(cs, "a")) // "a i"
        assertNull(run(en, "prišiel")) // "prišiel i on"
        assertEquals("I", run(sk, "do", "so")) // "so do i"
    }
}
