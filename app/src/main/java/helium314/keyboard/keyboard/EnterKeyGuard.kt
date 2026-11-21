// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants

/**
 * Droneboard: the Enter key (new line, send, search, go, next) can be made to need a short hold, so that a
 * finger that lands on it by accident does not send a message. The choices stay below the long-press time,
 * after which the key's popup opens instead.
 */
object EnterKeyGuard {
    /** offered in the settings, in milliseconds; 0 is off */
    @JvmField val CHOICES = intArrayOf(0, 100, 150, 200, 250)

    /** true when a key with [code] released after [heldMs] must not act, with the setting at [delayMs] */
    @JvmStatic
    fun blocks(code: Int, heldMs: Long, delayMs: Int): Boolean {
        if (delayMs <= 0) return false
        if (code != Constants.CODE_ENTER && code != KeyCode.SHIFT_ENTER
            && code != KeyCode.ACTION_NEXT && code != KeyCode.ACTION_PREVIOUS) return false
        return heldMs < delayMs
    }
}
