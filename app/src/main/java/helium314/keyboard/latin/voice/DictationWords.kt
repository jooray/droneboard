// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.PersonalDictionaryBackup
import helium314.keyboard.settings.preferences.PersonalDictionaryImport
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DroneboardOstt"

/**
 * The words of the personal dictionary that go to Offline Speech to Text with every dictation request, so names
 * come back spelled right ([BiasingWords] chooses them). The dictionary is read on a thread of its own and the
 * list is kept; it is read again when the dictionary changed, or after [REFRESH_MS] (the keyboard is not always
 * told about a change, the same reason swipe typing reads its copy again). The words are never logged, only counted.
 */
object DictationWords {
    private const val REFRESH_MS = 5 * 60 * 1000L

    @Volatile private var words: List<String> = emptyList()
    @Volatile private var loadedAt = 0L
    /** counts changes of the dictionary, so a list read while it changed is read again */
    @Volatile private var changes = 0
    private val loading = AtomicBoolean(false)

    private fun enabled(context: Context) = context.prefs()
        .getBoolean(Settings.PREF_DICTATION_SEND_PERSONAL_WORDS, Defaults.PREF_DICTATION_SEND_PERSONAL_WORDS)

    /** The personal dictionary changed (a word was added, removed or imported). */
    @JvmStatic
    fun invalidate() {
        changes++
        loadedAt = 0L
    }

    /**
     * Main thread: reads the list again when it is old or the dictionary changed. Does nothing when the user turned
     * the setting off, and nothing before the first unlock (the dictionary can't be read then anyway).
     */
    fun refresh(context: Context) {
        if (!enabled(context)) {
            // switched off: the words are not kept around either
            words = emptyList()
            loadedAt = 0L
            return
        }
        if (!DirectBoot.isUnlocked(context)) return
        val now = SystemClock.elapsedRealtime()
        if (loadedAt != 0L && now - loadedAt < REFRESH_MS) return
        if (!loading.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        val languages = SwipeGestureDecoder.enabledLocales().map { it.language.lowercase() }.toSet()
        val changesBefore = changes
        Thread({
            try {
                val all = PersonalDictionaryBackup.readAll(app).orEmpty()
                words = BiasingWords.select(all.map {
                    UserWord(it.word, it.locale, it.shortcut, it.frequency ?: PersonalDictionaryImport.DEFAULT_FREQUENCY)
                }, languages)
                loadedAt = if (changes == changesBefore) SystemClock.elapsedRealtime() else 0L
                Log.i(TAG, "preferred words for dictation: ${words.size} of ${all.size} entries of the personal dictionary")
            } catch (t: Throwable) {
                Log.w(TAG, "could not read the personal dictionary: ${t.javaClass.simpleName}")
            } finally {
                loading.set(false)
            }
        }, "droneboard-dictation-words").start()
    }

    /**
     * The words for one request, at most [maxCount] (what the installed OSTT says it reads; 0 when it reads none).
     * Empty when the setting is off, before the first unlock and while the phone is locked: a request made then
     * (a dictation that ended because the screen went off) is sent without them.
     */
    fun forRequest(context: Context, maxCount: Int): List<String> {
        if (maxCount <= 0 || !enabled(context)) return emptyList()
        val locked = runCatching { !DirectBoot.isUnlocked(context) || isDeviceLocked(context) }.getOrDefault(true)
        return if (locked) emptyList() else words.take(maxCount)
    }
}
