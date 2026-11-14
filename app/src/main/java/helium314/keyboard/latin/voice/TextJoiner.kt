// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Fits a dictated phrase to the text before the cursor, the way Gboard does: a space when the
 * phrase would otherwise glue to a word, a capital at the start of a sentence, and a lowercase
 * first word when the phrase continues a sentence (the recognizer capitalizes every utterance).
 * Free of Android, for the tests.
 */
object TextJoiner {
    private const val NO_SPACE_BEFORE = ".,!?;:)]}…%"
    private const val OPENERS = "([{\"'„“‚‘¿¡/"
    private const val SENTENCE_ENDS = ".!?…"

    fun join(before: CharSequence, phrase: String): String {
        var text = phrase.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""
        val trimmedBefore = before.trimEnd()
        val sentenceStart = trimmedBefore.isEmpty() || trimmedBefore.last() in SENTENCE_ENDS || before.endsWith("\n")
        text = if (sentenceStart) capitalize(text) else decapitalize(text)
        val last = before.lastOrNull()
        val needsSpace = last != null && !last.isWhitespace() && last !in OPENERS && text.first() !in NO_SPACE_BEFORE
        return if (needsSpace) " $text" else text
    }

    private fun capitalize(s: String): String {
        val first = s.first()
        return if (first.isLowerCase()) first.uppercase() + s.substring(1) else s
    }

    /** "Hello" → "hello", but "NASA", "I" and "iPhone" stay. */
    private fun decapitalize(s: String): String {
        val word = s.takeWhile { it.isLetter() }
        if (word.length < 2) return s
        if (!word[0].isUpperCase() || word.drop(1).any { it.isUpperCase() }) return s
        return s[0].lowercase() + s.substring(1)
    }
}
