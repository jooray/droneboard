// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.makedict.DictIO
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * Not a real test: converts a .combined word list made by tools/dicts/build_dicts.py into a
 * binary main dictionary. Runs only when DRONEBOARD_COMBINED and DRONEBOARD_DICT_OUT are set:
 *
 *   DRONEBOARD_COMBINED=out/sk.combined DRONEBOARD_DICT_OUT=$PWD/app/src/main/assets/dicts/main_sk.dict \
 *     ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.BuildDictionaryTool
 */
@RunWith(RobolectricTestRunner::class) // writeBinaryDictionary logs through android.util.Log
class BuildDictionaryTool {
    @Test fun buildFromEnvironment() {
        val input = System.getenv("DRONEBOARD_COMBINED")
        val output = System.getenv("DRONEBOARD_DICT_OUT")
        Assume.assumeTrue(input != null && output != null)
        val dict = DictIO.readCombinedDictionary(input!!)
        DictIO.writeBinaryDictionary(output!!, dict)
    }
}
