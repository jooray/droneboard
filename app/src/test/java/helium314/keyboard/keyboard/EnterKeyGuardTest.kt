// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterKeyGuardTest {
    @Test fun offByDefaultNothingIsBlocked() {
        assertFalse(EnterKeyGuard.blocks(Constants.CODE_ENTER, 5, 0))
    }

    @Test fun aQuickTapOnEnterIsBlockedAndAHoldIsNot() {
        assertTrue(EnterKeyGuard.blocks(Constants.CODE_ENTER, 80, 150))
        assertFalse(EnterKeyGuard.blocks(Constants.CODE_ENTER, 150, 150))
        assertFalse(EnterKeyGuard.blocks(Constants.CODE_ENTER, 400, 150))
    }

    @Test fun shiftEnterAndTheActionKeysAreGuardedToo() {
        assertTrue(EnterKeyGuard.blocks(KeyCode.SHIFT_ENTER, 10, 100))
        assertTrue(EnterKeyGuard.blocks(KeyCode.ACTION_NEXT, 10, 100))
    }

    @Test fun otherKeysAreNeverBlocked() {
        assertFalse(EnterKeyGuard.blocks('a'.code, 1, 250))
        assertFalse(EnterKeyGuard.blocks(KeyCode.DELETE, 1, 250))
        assertFalse(EnterKeyGuard.blocks(Constants.CODE_SPACE, 1, 250))
    }

    @Test fun thePopupOpensLaterWhileTheHoldIsOnSoThereIsRoomToRelease() {
        assertEquals(300, EnterKeyGuard.longPressTimeout(Constants.CODE_ENTER, 300, 0))
        assertEquals(650, EnterKeyGuard.longPressTimeout(Constants.CODE_ENTER, 300, 250))
        assertEquals(900, EnterKeyGuard.longPressTimeout(Constants.CODE_ENTER, 900, 100))
        assertEquals(300, EnterKeyGuard.longPressTimeout('a'.code, 300, 250))
    }
}
