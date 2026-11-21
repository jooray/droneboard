// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Which words of the personal dictionary go to the speech app with a dictation, and in which order. */
class BiasingWordsTest {

    private fun w(word: String, frequency: Int = 250, locale: String? = null, shortcut: String? = null) =
        UserWord(word, locale, shortcut, frequency)

    private val languages = setOf("sk", "en")

    @Test
    fun `names and acronyms come first by frequency then ordinary words by frequency`() {
        val words = BiasingWords.select(listOf(
            w("hodnotenie", 255),
            w("Nostr", 100),
            w("pivo", 10),
            w("iPhone", 200),
            w("k8s", 50),
            w("OSTT", 250),
            w("zajtra", 200),
        ), languages)
        assertEquals(listOf("OSTT", "iPhone", "Nostr", "k8s", "hodnotenie", "zajtra", "pivo"), words)
    }

    @Test
    fun `equally frequent words keep the order of the dictionary`() {
        val words = BiasingWords.select(listOf(w("Zuzana"), w("Adam"), w("mama"), w("auto")), languages)
        assertEquals(listOf("Zuzana", "Adam", "mama", "auto"), words)
    }

    @Test
    fun `words of enabled languages and words for all languages are sent and others are not`() {
        val words = BiasingWords.select(listOf(
            w("Bratislava", locale = "sk"),
            w("Trnava", locale = "sk_SK"),
            w("London", locale = "en_GB"),
            w("Leeds", locale = "en-US"),
            w("Berlin", locale = "de"),
            w("Wien", locale = "de_AT"),
            w("Juraj", locale = null),
            w("Lisa", locale = ""),
        ), languages)
        assertEquals(listOf("Bratislava", "Trnava", "London", "Leeds", "Juraj", "Lisa"), words)
    }

    @Test
    fun `text replacements are not vocabulary`() {
        val words = BiasingWords.select(listOf(
            w("juraj@example.com", shortcut = "@@"),
            w("S pozdravom Juraj", shortcut = "spj"),
            w("Droneboard", shortcut = ""),
            w("Zapstore"),
        ), languages)
        assertEquals(listOf("Droneboard", "Zapstore"), words)
    }

    @Test
    fun `too short and unusable entries are left out one by one`() {
        val long = "A" + "b".repeat(80)
        val words = BiasingWords.select(listOf(
            w("a"),
            w("X"),
            w("-"),
            w("5"),
            w(long),
            w("A" + "b".repeat(79)),
            w("bad\u0007word"),
            w("<|endoftext|>"),
            w("to|>ken"),
            w("   "),
            w("Go"),
            w("C3"),
        ), languages)
        assertEquals(listOf("A" + "b".repeat(79), "Go", "C3"), words)
    }

    @Test
    fun `spaces are tidied and a word is sent once whatever its case`() {
        val words = BiasingWords.select(listOf(
            w("nostr", 255),
            w("  New   York\t", 100),
            w("Nostr", 10),
            w("NOSTR", 5),
            w("new york", 255),
        ), languages)
        // the spelling with capitals wins, because it is the one worth restoring
        assertEquals(listOf("New York", "Nostr"), words)
    }

    @Test
    fun `the list stops at the number of words the speech app reads`() {
        val entries = (1..50).map { w("slovo" + "x".repeat(it), 255) } + (1..50).map { w("Name$it", 200 - it) }
        val words = BiasingWords.select(entries, languages, maxCount = 60)
        assertEquals(60, words.size)
        assertEquals("Name1", words.first())
        assertEquals("Name50", words[49])
        assertEquals("slovox", words[50])
        assertTrue(BiasingWords.select(entries, languages, maxCount = 0).isEmpty())
        // never more than the app reads at all, whatever it declares
        val many = (1..3000).map { w("Slovo$it") }
        assertEquals(BiasingWords.MAX_COUNT, BiasingWords.select(many, languages).size)
    }

    @Test
    fun `the list stops at the byte budget counted in UTF-8`() {
        // "Žľab1": 7 bytes in UTF-8, 5 characters
        val entries = (1..9).map { w("Žľab$it", 255 - it) }
        val words = BiasingWords.select(entries, languages, maxBytes = 30)
        assertEquals(listOf("Žľab1", "Žľab2", "Žľab3", "Žľab4"), words)
        assertTrue(words.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 30)
        // the real budget holds a full list of long words
        val full = BiasingWords.select((1..2000).map { w("Pomenovanie" + "x".repeat(30) + it) }, languages)
        assertTrue(full.sumOf { it.toByteArray(Charsets.UTF_8).size } <= BiasingWords.MAX_BYTES)
        assertFalse(full.isEmpty())
    }

    @Test
    fun `an empty dictionary gives an empty list`() {
        assertTrue(BiasingWords.select(emptyList(), languages).isEmpty())
    }
}
