// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.NgramContext
import java.util.Locale

/**
 * Droneboard: capitalizes a standalone English "i" (and i'm, i'll, i'd, i've) when the word is committed, independently
 * of autocorrect. "i" is also a word in Slovak and Czech ("a i", "i keď"), so plain "i" is only capitalized when the
 * context is English: the nearest previous word of the sentence that is in exactly one of English / the other enabled
 * languages decides, and when no previous word decides (none, or all in both or in no dictionary), the main locale does.
 * The contractions exist only in English, they are capitalized whenever English is one of the enabled languages.
 */
object EnglishICapitalizer {
    enum class WordLanguage { ENGLISH, OTHER, UNKNOWN }

    private val forms: Map<String, String> = listOf("", "'m", "'ll", "'d", "'ve").flatMap { suffix ->
        listOf("i$suffix" to "I$suffix", "i${suffix.replace('\'', '’')}" to "I${suffix.replace('\'', '’')}")
    }.toMap()

    /** the capitalized form if [word] is exactly a lowercase "i" or one of its contractions, otherwise null */
    @JvmStatic
    fun capitalizedForm(word: String): String? = forms[word]

    @JvmStatic
    fun isEnglish(locale: Locale) = locale.language == "en"

    /**
     * Language of a single word from main dictionary membership. The word as written counts first, the lowercase form
     * only when the word as written is in no dictionary (capitalized at the start of a sentence, for example).
     */
    fun languageOf(word: String, locales: Collection<Locale>, isInDictionary: (String, Locale) -> Boolean): WordLanguage {
        val english = locales.filter { isEnglish(it) }
        val others = locales.filterNot { isEnglish(it) }
        for (form in listOf(word, word.lowercase(Locale.ROOT)).distinct()) {
            val inEnglish = english.any { isInDictionary(form, it) }
            val inOther = others.any { isInDictionary(form, it) }
            if (!inEnglish && !inOther) continue
            return when {
                inEnglish && !inOther -> WordLanguage.ENGLISH
                inOther && !inEnglish -> WordLanguage.OTHER
                else -> WordLanguage.UNKNOWN
            }
        }
        return WordLanguage.UNKNOWN
    }

    /**
     * The decision itself. [previousWords] are the languages of the previous words of the sentence, nearest first.
     * Returns the word to commit, or null to leave [word] as typed.
     */
    fun decide(word: String, mainLocaleIsEnglish: Boolean, englishEnabled: Boolean, previousWords: List<WordLanguage>): String? {
        val capitalized = capitalizedForm(word) ?: return null
        if (!englishEnabled) return null
        if (word.length > 1) return capitalized // contractions are English only
        for (language in previousWords) {
            when (language) {
                WordLanguage.ENGLISH -> return capitalized
                WordLanguage.OTHER -> return null
                WordLanguage.UNKNOWN -> continue
            }
        }
        return if (mainLocaleIsEnglish) capitalized else null
    }

    /** previous words of [ngramContext] (nearest first), up to the beginning of the sentence */
    fun previousWords(ngramContext: NgramContext): List<String> {
        val words = mutableListOf<String>()
        for (n in 1..ngramContext.prevWordCount) {
            if (ngramContext.isNthPrevWordBeginningOfSentence(n)) break
            val word = ngramContext.getNthPrevWord(n)?.toString() ?: break
            if (word.isEmpty()) break
            words.add(word)
        }
        return words
    }

    /** the word to commit instead of the typed [word], or null; [ngramContext] holds the words before it */
    @JvmStatic
    fun capitalize(word: String, ngramContext: NgramContext, facilitator: DictionaryFacilitator): String? {
        if (capitalizedForm(word) == null) return null // cheap exit for every other word
        val mainLocale = facilitator.mainLocale
        val locales = (listOf(mainLocale) + facilitator.locales).distinct()
        val englishEnabled = locales.any { isEnglish(it) }
        if (!englishEnabled) return null
        val previous = previousWords(ngramContext).map { prev ->
            languageOf(prev, locales) { w, locale -> facilitator.isInMainDictionary(w, locale) }
        }
        return decide(word, isEnglish(mainLocale), englishEnabled, previous)
    }
}
