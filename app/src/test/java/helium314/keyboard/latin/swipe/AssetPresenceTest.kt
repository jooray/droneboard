// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetPresenceTest {
    private var time = 0L
    private val files = HashSet<String>()
    private var probes = 0
    private val presence = AssetPresence(20_000L, { time }, probe = { probes++; it in files })

    @Test fun aFileThatExistsIsAskedForOnce() {
        files.add("sk.kpd")
        assertTrue(presence.has("sk.kpd"))
        assertTrue(presence.has("sk.kpd"))
        assertEquals(1, probes)
    }

    /** the fault this class exists for: the file could not be seen when the process started */
    @Test fun aFileThatWasNotSeenAtFirstIsFoundLater() {
        assertFalse(presence.has("sk.kpd"))
        files.add("sk.kpd")
        time += 5_000
        assertFalse("not asked again too soon", presence.has("sk.kpd"))
        time += 20_000
        assertTrue(presence.has("sk.kpd"))
        assertEquals(setOf("sk.kpd"), presence.known())
    }

    @Test fun aMissingFileIsNotProbedOnEveryKeystroke() {
        repeat(100) { assertFalse(presence.has("zz.kpd")); time += 10 }
        assertEquals(1, probes)
    }

    @Test fun existenceIsRememberedEvenIfTheFileCannotBeSeenLater() {
        files.add("en.lmb")
        assertTrue(presence.has("en.lmb"))
        files.clear()
        time += 60_000
        assertTrue(presence.has("en.lmb"))
    }
}
