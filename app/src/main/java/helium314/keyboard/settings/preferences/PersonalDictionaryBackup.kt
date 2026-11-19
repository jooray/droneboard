// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.ContentValues
import android.content.Context
import android.provider.UserDictionary
import helium314.keyboard.latin.dictionary.ExpandableDictionaryUtils
import helium314.keyboard.latin.dictionary.UserBinaryDictionary
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.settings.preferences.PersonalDictionaryImport.Entry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Droneboard: the personal dictionary lives in Android's own UserDictionary (separate in every profile),
 * so the file backup did not contain it. It is written to the backup as JSON and merged on restore:
 * missing words are added, existing ones are left as they are, nothing is deleted.
 * Importing a word list ([PersonalDictionaryImport]) merges the same way, through [addMissing].
 *
 * Android lets only an enabled keyboard (or spell checker) use the provider: for anybody else queries are
 * empty and inserts do nothing, without an error.
 */
internal object PersonalDictionaryBackup {
    const val FILE_NAME = "personal_dictionary.json"
    private const val BATCH = 500

    private val PROJECTION = arrayOf(
        UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT,
        UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE
    )

    /** all entries, or null when the provider is unavailable */
    fun readAll(context: Context): List<Entry>? {
        val entries = ArrayList<Entry>()
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0) ?: continue
                entries.add(Entry(word, c.getString(3), c.getString(1), c.getInt(2)))
            }
        } ?: return null
        return entries
    }

    /** all entries as a JSON array, or null when there are none (or the provider is unavailable) */
    fun export(context: Context): String? {
        val all = readAll(context) ?: return null
        if (all.isEmpty()) return null
        val entries = JSONArray()
        for (e in all) {
            entries.put(JSONObject().apply {
                put("word", e.word)
                e.shortcut?.let { put("shortcut", it) }
                put("frequency", e.frequency)
                e.locale?.let { put("locale", it) }
            })
        }
        return entries.toString(1)
    }

    /** adds the entries that are missing; returns how many were added */
    fun restore(context: Context, json: String): Int =
        addMissing(context, PersonalDictionaryImport.parseJson(json).entries) ?: 0

    /** the keys ([PersonalDictionaryImport.key]) of all entries, read with one query; null = provider unavailable */
    fun existingKeys(context: Context): Set<String>? {
        val keys = HashSet<String>()
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI,
            arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE), null, null, null)?.use { c ->
            while (c.moveToNext()) keys.add(PersonalDictionaryImport.key(c.getString(0) ?: continue, c.getString(1)))
        } ?: return null
        return keys
    }

    /**
     * Adds the [entries] that are not in the dictionary yet (same word and same locale), leaves the others
     * as they are and deletes nothing. Returns how many were added, null when the provider is unavailable.
     * Does one query and inserts in batches, so call it off the main thread.
     */
    fun addMissing(context: Context, entries: List<Entry>): Int? {
        val existing = existingKeys(context) ?: return null
        val toAdd = PersonalDictionaryImport.plan(entries, existing).toAdd
        if (toAdd.isEmpty()) return 0
        val resolver = context.contentResolver
        val uri = UserDictionary.Words.CONTENT_URI
        for (batch in toAdd.chunked(BATCH)) {
            val values = batch.map { e ->
                ContentValues().apply {
                    put(UserDictionary.Words.WORD, e.word)
                    put(UserDictionary.Words.FREQUENCY,
                        (e.frequency ?: PersonalDictionaryImport.DEFAULT_FREQUENCY).coerceIn(1, 255))
                    put(UserDictionary.Words.LOCALE, e.locale)
                    put(UserDictionary.Words.APP_ID, 0)
                    if (e.shortcut != null) put(UserDictionary.Words.SHORTCUT, e.shortcut)
                }
            }.toTypedArray()
            // one call into the provider per batch instead of one per word
            val ok = runCatching { resolver.bulkInsert(uri, values) }.isSuccess
            if (!ok) values.forEach { runCatching { resolver.insert(uri, it) } }
        }
        // bulkInsert does not say how many rows were really written (a keyboard that is not enabled writes none)
        val after = existingKeys(context) ?: return null
        val added = toAdd.count { it.key in after }
        if (added > 0) notifyChanged()
        return added
    }

    /** the keyboard's copy of the personal dictionary is rebuilt at the next use, for tapping and for swiping */
    fun notifyChanged() {
        ExpandableDictionaryUtils.setNeedsUpdate(UserBinaryDictionary.NAME)
        SwipeGestureDecoder.invalidateUserWords()
    }
}
