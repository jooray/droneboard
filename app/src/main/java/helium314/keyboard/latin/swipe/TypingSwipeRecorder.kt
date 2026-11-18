// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.swipe.TypingSwipePolicy.Event
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional (off by default): records the swipes made during normal typing, with the words before them
 * and what the user did next, so that the decoder can be measured and tuned on real use. Everything
 * stays in one file in the app's private, credential-encrypted storage, which neither Android's backup
 * nor the app's own backup file includes; the user can look at it, delete it and export it from the
 * settings. Neither the app that was typed into nor the text field is recorded.
 *
 * Whether a field is recorded at all is decided by [TypingSwipePolicy.mayRecord] when the settings are
 * loaded for it; the result is the single flag [armed] that the touch path and the decoder check.
 *
 * A swipe goes through three steps:
 * 1. the decoder thread builds the record of the final decode ([onFinalDecode], the same record as the
 *    prompted recorder's, see [SwipeRecorder.buildRecord]);
 * 2. the input logic commits the word ([onSwipeCommitted]); the record waits;
 * 3. the next thing the user does decides its label ([settle]): going on makes it "accepted", picking
 *    another suggestion "replaced", backspace "deleted". Only then it is written, on the writer thread.
 */
object TypingSwipeRecorder {
    private const val TAG = "TypingSwipeRecorder"
    const val DIR = "typing-swipes"
    const val FILE_NAME = "typing-swipes.jsonl"
    /** recording stops when the file has this many swipes or this size, whichever comes first */
    const val MAX_RECORDS = 10_000
    const val MAX_BYTES = 20L * 1024 * 1024
    /** words before the swiped word that are kept */
    const val PREV_WORDS = 3
    /** a decode older than this does not belong to the word being committed */
    private const val DECODE_MAX_AGE_MS = 5000L

    /** true only while swipes in the current text field are recorded; the only check on the typing path */
    @JvmField @Volatile
    var armed = false

    /** a committed swipe waits for its label */
    @JvmField @Volatile
    var hasPending = false

    @Volatile private var enabled = false
    @Volatile private var allowed = false
    @Volatile private var fieldVeto = false
    @Volatile private var full = false
    @Volatile private var appContext: Context? = null

    class Stats(val count: Int, val bytes: Long, val full: Boolean)
    /** what is stored, for the settings screen */
    val stats = MutableStateFlow(Stats(0, 0, false))

    // writer thread only
    private var loaded = false
    private var count = 0
    private var bytes = 0L

    private val decoded = AtomicReference<JSONObject?>()
    private class Pending(val json: JSONObject, val committed: String, val cursor: Int)
    private var pending: Pending? = null

    /** The file of typing swipes, in credential-encrypted storage only; null when that is not what [context] has. */
    fun file(context: Context): File? {
        val ctx = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && ctx.isDeviceProtectedStorage) return null
        return File(File(ctx.filesDir ?: return null, DIR), FILE_NAME)
    }

    /**
     * Called whenever the settings are loaded (a new text field, a changed setting).
     * @param switchOn the user's setting
     * @param mayRecord [TypingSwipePolicy.mayRecord] for the current field
     */
    @JvmStatic
    fun onSettingsLoaded(context: Context, switchOn: Boolean, mayRecord: Boolean) {
        if (!switchOn && !enabled) return
        appContext = context.applicationContext ?: context
        enabled = switchOn
        allowed = switchOn && mayRecord
        // switched off, or a field that must not be recorded: nothing waits, nothing is kept in memory
        if (!allowed) discard()
        rearm()
        if (allowed) SwipeRecorder.onWriterThread { load(context) }
    }

    /**
     * The keyboard is shown for a field: checks the field itself once more, because the settings (and
     * with them [onSettingsLoaded]) are only reloaded when the input type changes.
     */
    @JvmStatic
    fun onStartInputView(info: EditorInfo?) {
        // kept up to date also while the switch is off, so that switching it on never starts from a stale field
        fieldVeto = info == null || !TypingSwipePolicy.fieldAllowed(info.inputType, info.imeOptions, info.privateImeOptions)
        if (!enabled) return
        if (fieldVeto) discard()
        rearm()
    }

    private fun rearm() {
        val now = enabled && allowed && !fieldVeto && !full
        val was = armed
        armed = now
        if (was && !now && !SwipeRecorder.isActive()) SwipeRecorder.clearStroke()
    }

    @Synchronized
    private fun discard() {
        pending = null
        hasPending = false
        decoded.set(null)
    }

    // ---- 1. decoder thread ----

    fun onFinalDecode(
        keyboard: Keyboard, pointers: InputPointers, input: SwipeGestureDecoder.LastInput,
        layoutKeys: List<Triple<String, Float, Float>>, words: Array<String>, languages: List<String>,
        wordLanguages: List<String>, millis: Float
    ) {
        if (!armed) return
        // the keyboard that was swiped on knows its field: check it once more
        val info = runCatching { keyboard.mId.editorInfo }.getOrNull() ?: return
        if (!TypingSwipePolicy.fieldAllowed(info.inputType, info.imeOptions, info.privateImeOptions)) return
        val json = SwipeRecorder.buildRecord(keyboard, pointers, input, layoutKeys, words, languages, wordLanguages, millis) ?: return
        try {
            // the input logic has put the text before the cursor here for the decoder (no extra call to the app)
            json.put("prev_words", JSONArray(TypingSwipePolicy.previousWords(NeuralLanguageModel.textBeforeCursor.get(), PREV_WORDS)))
        } catch (e: Exception) {
            return
        }
        offerDecoded(json)
    }

    /** the record of the last final decode, waiting for its word to be committed */
    internal fun offerDecoded(json: JSONObject) = decoded.set(json)

    // ---- 2. and 3. input logic (main thread) ----

    /** The swiped word [committed] is in the text now, the cursor at [cursor]. */
    @JvmStatic
    @Synchronized
    fun onSwipeCommitted(committed: String, cursor: Int) {
        if (pending != null) settle(Event.SWIPE, null)
        val json = decoded.getAndSet(null) ?: return
        if (!armed) return
        if (System.currentTimeMillis() - json.optLong("time") > DECODE_MAX_AGE_MS) return
        if (committed.isEmpty() || committed.any { it.isWhitespace() }) return
        pending = Pending(json, committed, cursor)
        hasPending = true
    }

    /** A key was pressed. Shift and the layout switches say nothing about the word. */
    @JvmStatic
    fun onKey(keyCode: Int) {
        when (keyCode) {
            KeyCode.SHIFT, KeyCode.CAPS_LOCK, KeyCode.SYMBOL_ALPHA, KeyCode.ALPHA, KeyCode.SYMBOL -> return
            KeyCode.DELETE, KeyCode.DELETE_WORD -> settle(Event.BACKSPACE, null)
            else -> settle(Event.KEY, null)
        }
    }

    /** The selection changed. The editor reporting the cursor where the swipe left it is no cursor move. */
    @JvmStatic
    @Synchronized
    fun onSelection(selStart: Int, selEnd: Int) {
        val p = pending ?: return
        if (selStart == selEnd && selStart == p.cursor) return
        settle(Event.CURSOR, null)
    }

    /**
     * What the user did after the swipe is known: label the record and write it.
     * @param picked see [TypingSwipePolicy.fate]
     */
    @JvmStatic
    @Synchronized
    fun settle(event: Event, picked: String?) {
        val p = pending ?: return
        pending = null
        hasPending = false
        if (!enabled) return
        val fate = TypingSwipePolicy.fate(p.committed, event, picked)
        val json = p.json
        try {
            val first = json.optJSONArray("pred")?.optString(0)
            json.put("record_version", SwipeRecorder.RECORD_VERSION)
            json.put("mode", "typing")
            json.put("committed", p.committed)
            json.put("outcome", fate.outcome)
            json.put("outcome_event", event.id)
            json.put("settled_ms", System.currentTimeMillis() - json.optLong("time"))
            if (fate.chosen != null) json.put("chosen", fate.chosen)
            if (fate.target != null) {
                json.put("target", fate.target)
                json.put("top1_hit", first.equals(fate.target, ignoreCase = true))
                json.put("committed_hit", p.committed.equals(fate.target, ignoreCase = true))
            }
            if (fate.outcome == TypingSwipePolicy.DELETED) json.put("deleted", true)
        } catch (e: Exception) {
            Log.w(TAG, "could not label swipe", e)
            return
        }
        val context = appContext ?: return
        SwipeRecorder.onWriterThread { write(context, json) }
    }

    // ---- writer thread ----

    private fun publish() {
        stats.value = Stats(count, bytes, full)
    }

    /** a record is about 10 to 20 kB: full while the next one still fits */
    private fun isFull() = count >= MAX_RECORDS || bytes + 64 * 1024 >= MAX_BYTES

    private fun load(context: Context) {
        if (loaded) return
        val f = file(context) ?: return
        count = if (f.exists()) runCatching { f.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0) else 0
        bytes = if (f.exists()) f.length() else 0
        full = isFull()
        loaded = true
        rearm()
        publish()
    }

    private fun write(context: Context, json: JSONObject) {
        val f = file(context) ?: return
        load(context)
        val line = json.toString()
        if (count < MAX_RECORDS && bytes + line.length < MAX_BYTES) {
            f.parentFile?.mkdirs()
            if (SwipeRecorder.append(f, line)) {
                count++
                bytes = f.length()
            }
        }
        full = isFull()
        rearm()
        publish()
    }

    // ---- the user's view of the data (settings) ----

    /** Counts what is stored again (the screen was opened). */
    fun refresh(context: Context) {
        SwipeRecorder.onWriterThread {
            loaded = false
            load(context)
        }
    }

    /** Deletes all recorded typing swipes; [done] runs on the writer thread. */
    fun deleteAll(context: Context, done: () -> Unit = {}) {
        discard()
        SwipeRecorder.onWriterThread {
            runCatching { file(context)?.delete() }
            loaded = false
            load(context)
            done()
        }
    }

    /**
     * Restoring the app's backup file replaces the files directory: moves the recorded swipes from the
     * directory that was set aside ([oldFilesDir]) into the new one.
     */
    fun keepAcrossRestore(oldFilesDir: File, filesDir: File) {
        val kept = File(oldFilesDir, DIR)
        if (!kept.exists()) return
        val target = File(filesDir, DIR)
        runCatching {
            filesDir.mkdirs()
            target.deleteRecursively()
            if (!kept.renameTo(target)) Log.w(TAG, "could not keep the recorded swipes")
        }
        SwipeRecorder.onWriterThread { loaded = false }
    }

    /** one recorded swipe as the user sees it */
    class Entry(
        val time: Long, val prevWords: List<String>, val committed: String, val outcome: String, val chosen: String?
    )

    /** Everything that is stored, newest first (blocking; call off the main thread). */
    fun entries(context: Context): List<Entry> {
        val f = file(context)?.takeIf { it.exists() } ?: return emptyList()
        val result = ArrayList<Entry>()
        runCatching {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val j = JSONObject(line)
                    val prev = j.optJSONArray("prev_words")
                    result.add(Entry(
                        j.optLong("time"),
                        if (prev == null) emptyList() else List(prev.length()) { prev.optString(it) },
                        j.optString("committed"),
                        j.optString("outcome"),
                        if (j.has("chosen")) j.optString("chosen") else null
                    ))
                }
            }
        }
        result.reverse()
        return result
    }

    /** Copies the file to [uri] (from the system's "save file" picker); blocking. */
    fun export(context: Context, uri: Uri): Boolean = try {
        val f = file(context)
        if (f == null || !f.exists()) false
        else context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        Log.w(TAG, "export failed", e)
        false
    }
}
