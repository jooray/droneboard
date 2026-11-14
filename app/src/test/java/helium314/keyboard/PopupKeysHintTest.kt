// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.Key.KeyParams
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.POPUP_KEYS_ORDER_OLD_DEFAULT
import helium314.keyboard.latin.utils.getEnabledPopupKeys
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Long press preselects the first popup key: with the default orders it must be the hint drawn on the key. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class PopupKeysHintTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    private fun params(locale: String, popupOrder: String = Defaults.PREF_POPUP_KEYS_ORDER) = KeyboardParams().apply {
        mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        mPopupKeyOrder.addAll(getEnabledPopupKeys(popupOrder))
        mPopupKeyHintOrder.addAll(getEnabledPopupKeys(Defaults.PREF_POPUP_KEYS_HINT_ORDER))
        // English has no locale file (no accents by default)
        mLocaleKeyboardInfos = LocaleKeyboardInfos(runCatching { latinIME.assets.open("locale_key_texts/$locale.txt") }.getOrNull(), Locale.forLanguageTag(locale))
    }

    private fun read(path: String) = latinIME.assets.open(path).reader().readText()

    /** letter keys as KeyboardParser builds them: number hints on the top row (unless the number row is shown), symbols from the symbols layout */
    private fun letterKeys(params: KeyboardParams, layout: String, numberRow: Boolean): List<KeyParams> {
        val base = LayoutParser.parseSimpleString(read("layouts/main/$layout.txt"))
        val symbols = LayoutParser.parseSimpleString(read("layouts/symbols/symbols.txt"))
        base.forEachIndexed { i, row ->
            row.forEachIndexed { j, key ->
                key.popup.symbol = symbols.getOrNull(i)?.getOrNull(j)?.label
                if (i == 0 && !numberRow) key.popup.numberLabel = "1234567890".getOrNull(j)?.toString()
            }
        }
        return base.flatten().map { it.toKeyParams(params) }
    }

    private fun KeyParams.firstPopup() = mPopupKeys?.firstOrNull()?.mLabel

    @Test fun preselectedPopupIsTheHint() {
        for ((locale, layout) in listOf("sk" to "qwertz", "sk" to "qwerty", "cs" to "qwertz", "cs" to "qwerty", "en" to "qwerty")) {
            for (numberRow in listOf(false, true)) {
                val keys = letterKeys(params(locale), layout, numberRow)
                keys.forEach { key ->
                    val hint = key.mHintLabel
                    if (hint != null)
                        assertEquals(hint, key.firstPopup(), "$locale $layout numberRow=$numberRow key ${key.mLabel}")
                }
                // every letter of these layouts has a hint from the symbols layout or the number
                assertTrue(keys.all { it.mHintLabel != null }, "$locale $layout")
            }
        }
    }

    @Test fun examples() {
        val sk = letterKeys(params("sk"), "qwertz", false).associateBy { it.mLabel }
        assertEquals("!", sk["n"]!!.mHintLabel)
        assertEquals("!", sk["n"]!!.firstPopup())
        assertTrue(sk["n"]!!.mPopupKeys!!.any { it.mLabel == "ň" }) // accents stay in the popup
        assertEquals("3", sk["e"]!!.firstPopup()) // top row: the number
        assertTrue(sk["e"]!!.mPopupKeys!!.any { it.mLabel == "é" })
        // the old order preselected the accent instead of the hint
        val old = letterKeys(params("sk", POPUP_KEYS_ORDER_OLD_DEFAULT), "qwertz", false).associateBy { it.mLabel }
        assertEquals("ň", old["n"]!!.firstPopup())
        assertEquals("!", old["n"]!!.mHintLabel)
    }

    @Test fun keyWithoutHintKeepsFirstAccent() {
        val params = params("sk")
        val key = LayoutParser.parseSimpleString("n").single().single() // no number, no symbol
        val keyParams = key.toKeyParams(params)
        assertNull(keyParams.mHintLabel)
        assertEquals("ň", keyParams.firstPopup())
    }
}
