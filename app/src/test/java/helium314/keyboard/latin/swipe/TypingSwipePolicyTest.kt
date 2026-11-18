// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.swipe.TypingSwipePolicy.Event
import helium314.keyboard.latin.swipe.TypingSwipePolicy.Fate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingSwipePolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val message = text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT

    private fun may(
        enabled: Boolean = true, inputType: Int = message, imeOptions: Int = EditorInfo.IME_ACTION_SEND,
        privateImeOptions: String? = null, incognito: Boolean = false, userUnlocked: Boolean = true,
        deviceLocked: Boolean = false
    ) = TypingSwipePolicy.mayRecord(enabled, inputType, imeOptions, privateImeOptions, incognito, userUnlocked, deviceLocked)

    @Test fun normalTextFields() {
        assertTrue(may())
        assertTrue(may(inputType = text))
        assertTrue(may(inputType = text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
        assertTrue(may(inputType = text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, imeOptions = 0))
        assertTrue(may(inputType = text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
        assertTrue(may(privateImeOptions = "com.example.someOption,other=1"))
    }

    @Test fun switchOff() {
        assertFalse(may(enabled = false))
    }

    @Test fun passwordsOfEveryKind() {
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(may(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        // flags around the variation change nothing
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }

    @Test fun fieldsThatAreNotPlainText() {
        assertFalse(may(inputType = InputType.TYPE_NULL)) // terminals
        assertFalse(may(inputType = InputType.TYPE_CLASS_NUMBER))
        assertFalse(may(inputType = InputType.TYPE_CLASS_PHONE))
        assertFalse(may(inputType = InputType.TYPE_CLASS_DATETIME))
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertFalse(may(inputType = text or InputType.TYPE_TEXT_VARIATION_URI))
    }

    @Test fun incognitoRequestedByTheApp() {
        assertFalse(may(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(may(imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            or EditorInfo.IME_FLAG_NO_EXTRACT_UI))
        // the policy has its own copy of the flag's value
        assertEquals(0x1000000, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(may(imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN))
    }

    @Test fun keyboardIncognitoMode() {
        assertFalse(may(incognito = true))
    }

    @Test fun lockScreenAndDirectBoot() {
        assertFalse(may(userUnlocked = false))
        assertFalse(may(deviceLocked = true))
        assertFalse(may(userUnlocked = false, deviceLocked = true))
    }

    @Test fun ownRecorderScreen() {
        assertFalse(may(privateImeOptions = SwipeRecorder.IME_OPTION))
        assertFalse(may(privateImeOptions = "a=b," + SwipeRecorder.IME_OPTION))
    }

    @Test fun everySingleReasonIsEnough() {
        // all combinations of the reasons: recording only when none applies
        for (bits in 0 until 64) {
            val enabled = bits and 1 != 0
            val incognito = bits and 2 != 0
            val unlocked = bits and 4 != 0
            val deviceLocked = bits and 8 != 0
            val password = bits and 16 != 0
            val noLearning = bits and 32 != 0
            val expected = enabled && !incognito && unlocked && !deviceLocked && !password && !noLearning
            assertEquals(expected, may(enabled = enabled, incognito = incognito, userUnlocked = unlocked, deviceLocked = deviceLocked,
                inputType = if (password) text or InputType.TYPE_TEXT_VARIATION_PASSWORD else message,
                imeOptions = if (noLearning) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0), "bits $bits")
        }
    }

    @Test fun previousWords() {
        assertEquals(listOf("dobrý", "deň", "prajem"), TypingSwipePolicy.previousWords("Ahoj, dobrý deň prajem ", 3))
        assertEquals(listOf("to", "nie", "je"), TypingSwipePolicy.previousWords("Áno. A to nie je", 3))
        assertEquals(listOf("don't", "e-mail"), TypingSwipePolicy.previousWords("\"don't\" (e-mail)", 3))
        assertEquals(listOf("one"), TypingSwipePolicy.previousWords("one", 3))
        assertEquals(listOf("riadok", "druhý"), TypingSwipePolicy.previousWords("riadok\ndruhý  ", 3))
        assertEquals(emptyList(), TypingSwipePolicy.previousWords("", 3))
        assertEquals(emptyList(), TypingSwipePolicy.previousWords(null, 3))
        assertEquals(emptyList(), TypingSwipePolicy.previousWords("  ... ", 3))
        assertEquals(emptyList(), TypingSwipePolicy.previousWords("a b c", 0))
    }

    @Test fun previousWordsOfLongText() {
        assertEquals(listOf("alfa", "beta", "gama"), TypingSwipePolicy.previousWords("x".repeat(500) + " alfa beta gama", 3))
        // a word cut by the window is not passed off as a word
        assertEquals(listOf("koniec"), TypingSwipePolicy.previousWords("y".repeat(300) + " koniec", 3))
        assertEquals(emptyList(), TypingSwipePolicy.previousWords("y".repeat(300), 3))
        // the window starts exactly at a word
        assertEquals(listOf("b".repeat(119)), TypingSwipePolicy.previousWords("aaa " + "b".repeat(119) + " ", 3))
    }

    @Test fun fates() {
        for (e in listOf(Event.SWIPE, Event.KEY, Event.TEXT, Event.CURSOR, Event.FINISH))
            assertEquals(Fate("accepted", "ahoj", null), TypingSwipePolicy.fate("ahoj", e, null), e.id)
        assertEquals(Fate("deleted", null, null), TypingSwipePolicy.fate("ahoj", Event.BACKSPACE, null))
        assertEquals(Fate("replaced", "ahojte", "ahojte"), TypingSwipePolicy.fate("ahoj", Event.PICK, "ahojte"))
        // picking the same word, or a prediction after the word, keeps it
        assertEquals(Fate("accepted", "ahoj", null), TypingSwipePolicy.fate("ahoj", Event.PICK, "ahoj"))
        assertEquals(Fate("accepted", "ahoj", null), TypingSwipePolicy.fate("ahoj", Event.PICK, null))
        // capitalization is a correction too
        assertEquals(Fate("replaced", "Ahoj", "Ahoj"), TypingSwipePolicy.fate("ahoj", Event.PICK, "Ahoj"))
        // an emoji instead of the word: no target
        assertEquals(Fate("replaced", null, "👋"), TypingSwipePolicy.fate("ahoj", Event.PICK, "👋"))
    }
}
