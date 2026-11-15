// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.engineKey
import helium314.keyboard.latin.common.LocaleUtils.privateUse
import helium314.keyboard.latin.common.LocaleUtils.withoutPrivateUse
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocaleUtilsTest {
    @Test fun createLocales() {
        assertEquals("en_US".constructLocale(), "en-US".constructLocale())
        assertEquals("en_us".constructLocale(), "en-US".constructLocale())
        assertEquals("hi__#Latn".constructLocale(), "hi-Latn".constructLocale())
        assertEquals("hi_zz".constructLocale(), "hi-Latn".constructLocale())
        assertEquals("hi_ZZ".constructLocale(), "hi-Latn".constructLocale())
        assertEquals("zz".constructLocale().toLanguageTag(), "zz")
        assertEquals("zz".constructLocale().toString(), "zz")
    }

    @Test fun localesWithoutDiacritics() {
        val sk = Locale.forLanguageTag("sk")
        val nd = "sk-x-nd".constructLocale()
        assertEquals("sk", nd.language)
        assertEquals("nd", nd.privateUse)
        assertEquals("sk_nd", nd.engineKey)
        assertEquals("sk", sk.engineKey)
        assertEquals(sk, nd.withoutPrivateUse())
        // the Locale.toString() form, as in the subtype locale string, parses back
        assertEquals(nd, nd.toString().constructLocale())
        assertEquals(nd, "sk__#x-nd".constructLocale())
        assertEquals("cs-x-nd".constructLocale(), "cs__#x-nd".constructLocale())
        // own dictionaries: main_sk.dict is no match for sk-x-nd, and the other way round
        val dicts = listOf("sk", "sk-x-nd", "cs").map { it.constructLocale() }
        assertEquals(nd, LocaleUtils.getBestMatch(nd, dicts) { it })
        assertEquals(sk, LocaleUtils.getBestMatch(sk, dicts) { it })
        assertNull(LocaleUtils.getBestMatch(nd, listOf(sk)) { it })
        assertNull(LocaleUtils.getBestMatch(sk, listOf(nd)) { it })
    }
}
