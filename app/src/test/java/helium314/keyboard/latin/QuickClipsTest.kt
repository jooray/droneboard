// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.QuickClips.Clip
import helium314.keyboard.latin.QuickClips.Type
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickClipsTest {
    private fun texts(content: String) = QuickClips.extract(content).map { it.text }

    @Test fun codeInSms() {
        assertEquals(listOf(Clip(Type.CODE, "482913")), QuickClips.extract("Váš overovací kód je 482913. Neposielajte ho nikomu."))
        assertEquals(listOf("G7K2QX"), texts("Your code: G7K2QX"))
        assertEquals(listOf("123456"), texts("Use 123 456 to sign in"))
        assertEquals(listOf("123456"), texts("Use 123-456 to sign in"))
    }

    @Test fun emailAndLink() {
        assertEquals(listOf(Clip(Type.EMAIL, "jan.novak+x@example.org")), QuickClips.extract("Napíš na jan.novak+x@example.org."))
        assertEquals(listOf(Clip(Type.LINK, "https://example.org/a?b=1")), QuickClips.extract("Pozri (https://example.org/a?b=1)."))
        assertEquals(listOf("https://example.org/x", "me@example.org"), texts("https://example.org/x alebo me@example.org"))
    }

    @Test fun nothingToOffer() {
        assertEquals(emptyList(), texts("obyčajný text bez ničoho"))
        assertEquals(emptyList(), texts("482913")) // the whole clip is the code, one chip is enough
        assertEquals(emptyList(), texts(" https://example.org/x\n"))
        assertEquals(emptyList(), texts("HELLO WORLD")) // capitals without a digit
        assertEquals(emptyList(), texts("v roku 2024 a 2025 ich bolo 1500")) // several numbers: none is a code
        assertEquals(emptyList(), texts("cena 12.5000 alebo 1,2345"))
        assertEquals(emptyList(), texts("x".repeat(40_000) + " 482913"))
    }

    @Test fun numbersInsideOtherThings() {
        assertEquals(listOf("https://example.org/123456"), texts("link https://example.org/123456 tu"))
        assertEquals(emptyList(), texts("telefón +421 900 123 456"))
        assertEquals(emptyList(), texts("verzia 1.2.3456 je vonku"))
        assertEquals(emptyList(), texts("2026-11-18 21:47"))
    }

    @Test fun labels() {
        assertEquals("example.org/a", QuickClips.label(Clip(Type.LINK, "https://www.example.org/a/")))
        assertEquals("a".repeat(22) + "…", QuickClips.label(Clip(Type.EMAIL, "a".repeat(30))))
    }
}
