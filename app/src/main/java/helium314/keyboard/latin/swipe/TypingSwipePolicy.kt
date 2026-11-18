// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * The decisions of [TypingSwipeRecorder], free of Android state so that they can be tested on the JVM:
 * whether a swipe in the current text field may be recorded at all, which words before the cursor are
 * kept, and how what the user did next labels the record.
 */
object TypingSwipePolicy {
    /** [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] (API 26), the "incognito" request of the app */
    private const val NO_PERSONALIZED_LEARNING = 0x1000000

    /**
     * True only for a plain text field of another app that did not ask for privacy. Never for passwords
     * of any kind (hidden, visible, web, numeric), for e-mail address and URL fields, for fields that are
     * not text at all (numbers, phone, terminal-like fields without an input type), for fields with
     * IME_FLAG_NO_PERSONALIZED_LEARNING, nor for Droneboard's own prompted recorder, which records itself.
     */
    @JvmStatic
    fun fieldAllowed(inputType: Int, imeOptions: Int, privateImeOptions: String?): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI -> return false
        }
        if (imeOptions and NO_PERSONALIZED_LEARNING != 0) return false
        if (privateImeOptions?.contains(SwipeRecorder.IME_OPTION) == true) return false
        return true
    }

    /**
     * The single place that decides whether swipes are recorded while typing.
     * @param enabled the user's switch (off by default)
     * @param incognito the keyboard's own incognito mode (the setting, or forced by the field)
     * @param userUnlocked false before the first unlock after a reboot (direct boot)
     * @param deviceLocked true while the lock screen is up
     */
    @JvmStatic
    fun mayRecord(
        enabled: Boolean, inputType: Int, imeOptions: Int, privateImeOptions: String?,
        incognito: Boolean, userUnlocked: Boolean, deviceLocked: Boolean
    ): Boolean = enabled && !incognito && userUnlocked && !deviceLocked
            && fieldAllowed(inputType, imeOptions, privateImeOptions)

    /** The last [max] words of [textBeforeCursor], without the punctuation around them. */
    @JvmStatic
    fun previousWords(textBeforeCursor: CharSequence?, max: Int): List<String> {
        if (textBeforeCursor.isNullOrEmpty() || max <= 0) return emptyList()
        // a few words never need more than this; the text can be a thousand characters
        val start = (textBeforeCursor.length - 40 * max).coerceAtLeast(0)
        val words = textBeforeCursor.subSequence(start, textBeforeCursor.length).split(WHITESPACE).toMutableList()
        // the window may start in the middle of a word: that piece is not a word
        if (start > 0 && !textBeforeCursor[start - 1].isWhitespace() && words.isNotEmpty()) words.removeAt(0)
        return words.map { w -> w.trim { !it.isLetterOrDigit() } }.filter { it.isNotEmpty() }.takeLast(max)
    }

    /** what the user did right after a swipe */
    enum class Event(val id: String) {
        /** went on swiping the next word */
        SWIPE("swipe"),
        /** typed a letter, a space, punctuation, Enter, ... */
        KEY("key"),
        /** inserted text (a key with several characters, a paste) */
        TEXT("text"),
        /** picked a word from the suggestion strip */
        PICK("pick"),
        /** backspace */
        BACKSPACE("backspace"),
        /** moved the cursor elsewhere */
        CURSOR("cursor"),
        /** left the text field or hid the keyboard */
        FINISH("finish"),
    }

    const val ACCEPTED = "accepted"
    const val REPLACED = "replaced"
    const val DELETED = "deleted"

    /** [target] is the word the user wanted as far as we can tell, null when the swipe was taken back */
    data class Fate(val outcome: String, val target: String?, val chosen: String?)

    /**
     * @param committed the word the swipe put into the text
     * @param picked for [Event.PICK]: the suggestion picked while the swiped word could still be replaced
     *  by it; null when the pick was something after the word (a next-word prediction)
     */
    @JvmStatic
    fun fate(committed: String, event: Event, picked: String?): Fate = when {
        event == Event.BACKSPACE -> Fate(DELETED, null, null)
        event == Event.PICK && picked != null && picked != committed ->
            // an emoji or punctuation picked instead of the word says nothing about the word that was meant
            Fate(REPLACED, picked.takeIf { p -> p.any { it.isLetter() } }, picked)
        else -> Fate(ACCEPTED, committed, null)
    }

    private val WHITESPACE = Regex("\\s+")
}
