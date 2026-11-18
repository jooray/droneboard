// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.swipe.TypingSwipePolicy.Event
import helium314.keyboard.settings.preferences.backupFilePatterns
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What is written, when, and with which label; the decode itself (step 1) needs a keyboard and is not covered. */
@RunWith(RobolectricTestRunner::class)
class TypingSwipeRecorderTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = TypingSwipeRecorder.file(context)!!
        TypingSwipeRecorder.onSettingsLoaded(context, true, true)
        TypingSwipeRecorder.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
        TypingSwipeRecorder.deleteAll(context)
        flush()
    }

    @After fun tearDown() {
        TypingSwipeRecorder.deleteAll(context)
        flush()
        TypingSwipeRecorder.onSettingsLoaded(context, false, false)
    }

    private fun flush() {
        val latch = CountDownLatch(1)
        SwipeRecorder.onWriterThread { latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
    }

    private fun decode(vararg pred: String, time: Long = System.currentTimeMillis()) = TypingSwipeRecorder.offerDecoded(
        JSONObject().put("pred", JSONArray(pred.toList())).put("prev_words", JSONArray(listOf("dobrý", "deň"))).put("time", time)
    )

    private fun swipe(committed: String, vararg pred: String, cursor: Int = 10) {
        decode(*pred)
        TypingSwipeRecorder.onSwipeCommitted(committed, cursor)
    }

    private fun records(): List<JSONObject> {
        flush()
        return if (file.exists()) file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) } else emptyList()
    }

    @Test fun armedOnlyWhenSwitchedOnInAnAllowedField() {
        assertTrue(TypingSwipeRecorder.armed)
        TypingSwipeRecorder.onSettingsLoaded(context, true, false)
        assertFalse(TypingSwipeRecorder.armed)
        TypingSwipeRecorder.onSettingsLoaded(context, false, true)
        assertFalse(TypingSwipeRecorder.armed)
        TypingSwipeRecorder.onSettingsLoaded(context, true, true)
        assertTrue(TypingSwipeRecorder.armed)
    }

    @Test fun theFieldOnScreenHasTheLastWord() {
        // the same field restarts with the incognito flag: the settings are not reloaded for that
        swipe("ahoj", "ahoj")
        TypingSwipeRecorder.onStartInputView(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        })
        assertFalse(TypingSwipeRecorder.armed)
        assertFalse(TypingSwipeRecorder.hasPending)
        // a changed setting reloads the settings with the old field's attributes: still not armed
        TypingSwipeRecorder.onSettingsLoaded(context, true, true)
        assertFalse(TypingSwipeRecorder.armed)
        TypingSwipeRecorder.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        assertFalse(TypingSwipeRecorder.armed)
        TypingSwipeRecorder.onStartInputView(null)
        assertFalse(TypingSwipeRecorder.armed)
        TypingSwipeRecorder.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
        assertTrue(TypingSwipeRecorder.armed)
        assertEquals(0, records().size)
    }

    @Test fun nothingIsWrittenBeforeTheFateIsKnown() {
        swipe("ahoj", "ahoj", "ahojte")
        assertTrue(TypingSwipeRecorder.hasPending)
        assertEquals(0, records().size)
    }

    @Test fun acceptedByTypingOn() {
        swipe("ahoj", "ahoj", "ahojte")
        TypingSwipeRecorder.onKey(' '.code)
        assertFalse(TypingSwipeRecorder.hasPending)
        val r = records().single()
        assertEquals("typing", r.getString("mode"))
        assertEquals(SwipeRecorder.RECORD_VERSION, r.getInt("record_version"))
        assertEquals("ahoj", r.getString("committed"))
        assertEquals("accepted", r.getString("outcome"))
        assertEquals("key", r.getString("outcome_event"))
        assertEquals("ahoj", r.getString("target"))
        assertTrue(r.getBoolean("top1_hit"))
        assertEquals("deň", r.getJSONArray("prev_words").getString(1))
        assertFalse(r.has("deleted"))
        assertFalse(r.has("chosen"))
        assertEquals(1, TypingSwipeRecorder.stats.value.count)
        assertEquals(file.length(), TypingSwipeRecorder.stats.value.bytes)
    }

    @Test fun acceptedByTheNextSwipe() {
        swipe("dobrý", "dobrý")
        TypingSwipeRecorder.settle(Event.SWIPE, null)
        swipe("deň", "deň")
        TypingSwipeRecorder.settle(Event.FINISH, null)
        assertEquals(listOf("swipe", "finish"), records().map { it.getString("outcome_event") })
        assertEquals(listOf("dobrý", "deň"), records().map { it.getString("target") })
    }

    @Test fun deletedByBackspace() {
        swipe("ahoj", "ahoj")
        TypingSwipeRecorder.onKey(KeyCode.DELETE)
        val r = records().single()
        assertEquals("deleted", r.getString("outcome"))
        assertTrue(r.getBoolean("deleted"))
        assertFalse(r.has("target"))
        assertEquals("ahoj", r.getString("committed"))
    }

    @Test fun replacedByAnotherSuggestion() {
        swipe("ahoj", "ahoj", "ahojte")
        TypingSwipeRecorder.settle(Event.PICK, "ahojte")
        val r = records().single()
        assertEquals("replaced", r.getString("outcome"))
        assertEquals("ahojte", r.getString("target"))
        assertEquals("ahojte", r.getString("chosen"))
        assertFalse(r.getBoolean("top1_hit"))
        assertFalse(r.getBoolean("committed_hit"))
    }

    @Test fun shiftAndTheEditorsEchoDecideNothing() {
        swipe("ahoj", "ahoj", cursor = 10)
        TypingSwipeRecorder.onKey(KeyCode.SHIFT)
        TypingSwipeRecorder.onKey(KeyCode.SYMBOL_ALPHA)
        TypingSwipeRecorder.onSelection(10, 10)
        assertTrue(TypingSwipeRecorder.hasPending)
        // the cursor moved elsewhere: the word stays, and a later backspace is not about it
        TypingSwipeRecorder.onSelection(3, 3)
        assertFalse(TypingSwipeRecorder.hasPending)
        TypingSwipeRecorder.onKey(KeyCode.DELETE)
        val r = records().single()
        assertEquals("accepted", r.getString("outcome"))
        assertEquals("cursor", r.getString("outcome_event"))
    }

    @Test fun switchingOffStopsAtOnceAndKeepsTheData() {
        swipe("prvé", "prvé")
        TypingSwipeRecorder.onKey(' '.code)
        swipe("druhé", "druhé")
        TypingSwipeRecorder.onSettingsLoaded(context, false, false)
        assertFalse(TypingSwipeRecorder.armed)
        assertFalse(TypingSwipeRecorder.hasPending)
        TypingSwipeRecorder.settle(Event.KEY, null)
        // a decode that was on its way is not picked up either
        decode("tretie")
        TypingSwipeRecorder.onSwipeCommitted("tretie", 20)
        assertFalse(TypingSwipeRecorder.hasPending)
        assertEquals(listOf("prvé"), records().map { it.getString("committed") })
    }

    @Test fun aFieldThatMustNotBeRecordedDropsWhatWaits() {
        swipe("ahoj", "ahoj")
        TypingSwipeRecorder.onSettingsLoaded(context, true, false) // e.g. a password field, incognito
        assertFalse(TypingSwipeRecorder.hasPending)
        decode("heslo")
        TypingSwipeRecorder.onSwipeCommitted("heslo", 5)
        assertFalse(TypingSwipeRecorder.hasPending)
        TypingSwipeRecorder.settle(Event.KEY, null)
        assertEquals(0, records().size)
    }

    @Test fun staleOrOddCommitsAreIgnored() {
        decode("ahoj", time = System.currentTimeMillis() - 60_000)
        TypingSwipeRecorder.onSwipeCommitted("ahoj", 4)
        assertFalse(TypingSwipeRecorder.hasPending)
        swipe("dve slová", "dve")
        assertFalse(TypingSwipeRecorder.hasPending)
        // without a decode there is nothing to record
        TypingSwipeRecorder.onSwipeCommitted("ahoj", 4)
        assertFalse(TypingSwipeRecorder.hasPending)
        assertEquals(0, records().size)
    }

    @Test fun viewIsNewestFirstAndDeleteAllRemovesTheFile() {
        swipe("prvé", "prvé")
        TypingSwipeRecorder.onKey(' '.code)
        swipe("druhé", "druha")
        TypingSwipeRecorder.settle(Event.PICK, "druhá")
        flush()
        val entries = TypingSwipeRecorder.entries(context)
        assertEquals(listOf("druhé", "prvé"), entries.map { it.committed })
        assertEquals("druhá", entries[0].chosen)
        assertEquals(listOf("dobrý", "deň"), entries[1].prevWords)
        TypingSwipeRecorder.deleteAll(context)
        flush()
        assertFalse(file.exists())
        assertEquals(0, TypingSwipeRecorder.stats.value.count)
        assertEquals(emptyList(), TypingSwipeRecorder.entries(context))
    }

    @Test fun fileIsInPrivateStorageOutsideEveryBackup() {
        // internal files directory (credential-encrypted), not the external one of the prompted recorder
        assertEquals(File(context.filesDir, "typing-swipes/typing-swipes.jsonl"), file)
        val relative = file.relativeTo(context.filesDir).path
        // the app's own backup file takes only what matches these patterns
        assertFalse(backupFilePatterns.any { relative.matches(it) }, "in the app's backup")
        // Android's backup: both rule files are whitelists (they have includes) and no include covers the file
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val xml = sequenceOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map { File(it) }.firstOrNull { it.exists() }
            assertNotNull(xml, name)
            val includes = Regex("<include\\s+domain=\"([^\"]+)\"\\s+path=\"([^\"]+)\"").findAll(xml.readText()).toList()
            assertTrue(includes.isNotEmpty(), "$name must stay a whitelist")
            assertFalse(xml.readText().contains("<exclude"), name)
            for (m in includes) {
                val (domain, path) = m.destructured
                if (domain != "file") continue // "device_file" is the device-protected directory, a different one
                assertTrue(path != "." && !relative.startsWith(path.trimEnd('/')), "$name includes $path")
            }
        }
    }
}
