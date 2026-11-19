// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.settings.screens.LetterIndex.Section
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** pure JVM: no Android classes, no Robolectric */
class LetterIndexTest {
    private val collator: Collator = Collator.getInstance(Locale.ROOT)
    private fun build(vararg words: String) = LetterIndex.build(words.toList(), collator) { it }

    @Test fun emptyList() {
        val r = build()
        assertTrue(r.items.isEmpty())
        assertTrue(r.sections.isEmpty())
    }

    @Test fun singleLetter() {
        val r = build("bob", "Bea", "bar")
        assertEquals(listOf(Section("B", 0)), r.sections)
        assertEquals(listOf("bar", "Bea", "bob"), r.items)
    }

    @Test fun mixedCaseIsOneBucket() {
        val r = build("zebra", "Anna", "adam", "Zoe", "milk")
        assertEquals(listOf("adam", "Anna", "milk", "zebra", "Zoe"), r.items)
        assertEquals(listOf(Section("A", 0), Section("M", 2), Section("Z", 3)), r.sections)
    }

    @Test fun accentsBelongToTheBaseLetter() {
        assertEquals("A", LetterIndex.bucket("áno"))
        assertEquals("C", LetterIndex.bucket("čaj"))
        assertEquals("Z", LetterIndex.bucket("Žilina"))
        assertEquals("O", LetterIndex.bucket("öl"))
        val r = build("čaj", "dom", "cesta", "áno", "auto", "Žilina", "zima", "öl", "okno", "Čadca")
        assertEquals(listOf("A", "C", "D", "O", "Z"), r.sections.map { it.label })
        // every letter is one run of rows, accented words among the others of the same letter
        assertEquals(listOf("áno", "auto"), r.items.subList(0, 2))
        assertEquals(setOf("čaj", "cesta", "Čadca"), r.items.subList(2, 5).toSet())
        assertEquals("dom", r.items[5])
        assertEquals(listOf("okno", "öl"), r.items.subList(6, 8))
        assertEquals(setOf("Žilina", "zima"), r.items.subList(8, 10).toSet())
    }

    @Test fun nonLettersComeFirstInOneBucket() {
        val r = build("#nostr", "2fa", "alfa", "_x", "😀", " ", "")
        assertEquals(Section(LetterIndex.OTHER, 0), r.sections[0])
        assertEquals(Section("A", 6), r.sections[1])
        assertEquals(2, r.sections.size)
        assertEquals("alfa", r.items.last())
    }

    @Test fun lettersThatDoNotDecomposeAndOtherScripts() {
        assertEquals("Ł", LetterIndex.bucket("łódź"))
        assertEquals("Ж", LetterIndex.bucket("жена"))
        assertEquals("B", LetterIndex.bucket("  bob"))
        val r = build("жена", "alfa", "łódź", "lampa")
        assertEquals(listOf("A", "L", "Ł", "Ж"), r.sections.map { it.label })
    }

    @Test fun sectionsPointAtTheFirstEntryOfTheirLetter() {
        val words = listOf("Ábel", "abeceda", "Zuzana", "9mm", "čučoriedka", "Cyril", "ch", "hora", "Ďurko", "delta", "ôsmy")
        for (c in listOf(collator, Collator.getInstance(Locale.forLanguageTag("sk")))) {
            val r = LetterIndex.build(words, c) { it }
            assertEquals(words.toSet(), r.items.toSet())
            assertEquals(words.size, r.items.size)
            // the order of the list and the buckets agree: the bucket changes exactly at the section starts
            val starts = r.items.indices.filter { it == 0 || LetterIndex.bucket(r.items[it]) != LetterIndex.bucket(r.items[it - 1]) }
            assertEquals(starts, r.sections.map { it.firstIndex })
            assertEquals(starts.map { LetterIndex.bucket(r.items[it]) }, r.sections.map { it.label })
            assertEquals(listOf("#", "A", "C", "D", "H", "O", "Z"), r.sections.map { it.label })
        }
    }

    @Test fun orderDoesNotDependOnTheInputOrder() {
        val a = build("Auto", "auto", "AUTO", "áuto").items
        val b = build("áuto", "AUTO", "Auto", "auto").items
        assertEquals(a, b)
    }

    @Test fun largeList() {
        val words = List(5000) { i -> "${'a' + (i * 7 % 26)}word$i" }
        val r = LetterIndex.build(words, collator) { it }
        assertEquals(5000, r.items.size)
        assertEquals(('A'..'Z').map { it.toString() }, r.sections.map { it.label })
    }

    @Test fun thinOut() {
        assertEquals(emptyList<Int>(), LetterIndex.thinOut(0, 10))
        assertEquals(emptyList<Int>(), LetterIndex.thinOut(5, 0))
        assertEquals(listOf(0, 1, 2), LetterIndex.thinOut(3, 10))
        assertEquals(listOf(0), LetterIndex.thinOut(5, 1))
        assertEquals(listOf(0, 25), LetterIndex.thinOut(26, 2))
        val t = LetterIndex.thinOut(27, 10)
        assertEquals(10, t.size)
        assertEquals(0, t.first())
        assertEquals(26, t.last())
        assertEquals(t.sorted().distinct(), t)
        val gaps = t.zipWithNext { x, y -> y - x }
        assertTrue(gaps.max() - gaps.min() <= 1, "evenly spread: $t")
    }

    @Test fun sectionAt() {
        assertEquals(-1, LetterIndex.sectionAt(10f, 100f, 0))
        assertEquals(-1, LetterIndex.sectionAt(10f, 0f, 5))
        assertEquals(0, LetterIndex.sectionAt(-5f, 100f, 4))
        assertEquals(0, LetterIndex.sectionAt(24f, 100f, 4))
        assertEquals(1, LetterIndex.sectionAt(25f, 100f, 4))
        assertEquals(3, LetterIndex.sectionAt(99f, 100f, 4))
        assertEquals(3, LetterIndex.sectionAt(500f, 100f, 4))
    }
}
