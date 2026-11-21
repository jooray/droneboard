// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants

/**
 * Droneboard: the Enter key (new line, send, search, go, next) can be made to need a short hold, so that a
 * finger that lands on it by accident does not send a message. While the hold is on, the key's popup opens
 * later, so that there is room between "held long enough" and "long press" (found on the phone: with 250 ms
 * and the usual 300 ms long press, holding a moment too long opened the clipboard instead of confirming).
 */
object EnterKeyGuard {
    /** offered in the settings, in milliseconds; 0 is off */
    @JvmField val CHOICES = intArrayOf(0, 100, 150, 200, 250)

    /** true when a key with [code] released after [heldMs] must not act, with the setting at [delayMs] */
    @JvmStatic
    fun blocks(code: Int, heldMs: Long, delayMs: Int): Boolean =
        delayMs > 0 && guards(code) && heldMs < delayMs

    /** the time after which the popup of the key with [code] opens: [normalMs], or later while the hold is on */
    @JvmStatic
    fun longPressTimeout(code: Int, normalMs: Int, delayMs: Int): Int =
        if (delayMs > 0 && guards(code)) maxOf(normalMs, delayMs + ROOM_MS) else normalMs

    /** how long after the hold time the key still confirms on release, before its popup opens */
    const val ROOM_MS = 400

    private fun guards(code: Int) = code == Constants.CODE_ENTER || code == KeyCode.SHIFT_ENTER
        || code == KeyCode.ACTION_NEXT || code == KeyCode.ACTION_PREVIOUS
}
