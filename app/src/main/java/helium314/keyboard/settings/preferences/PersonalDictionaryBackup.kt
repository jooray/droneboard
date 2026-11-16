// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.ContentValues
import android.content.Context
import android.provider.UserDictionary
import org.json.JSONArray
import org.json.JSONObject

/**
 * Droneboard: the personal dictionary lives in Android's own UserDictionary (separate in every profile),
 * so the file backup did not contain it. It is written to the backup as JSON and merged on restore:
 * missing words are added, existing ones are left as they are, nothing is deleted.
 */
internal object PersonalDictionaryBackup {
    const val FILE_NAME = "personal_dictionary.json"

    private val PROJECTION = arrayOf(
        UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT,
        UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE
    )

    /** all entries as a JSON array, or null when there are none (or the provider is unavailable) */
    fun export(context: Context): String? {
        val entries = JSONArray()
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0) ?: continue
                entries.put(JSONObject().apply {
                    put("word", word)
                    c.getString(1)?.let { put("shortcut", it) }
                    put("frequency", c.getInt(2))
                    c.getString(3)?.let { put("locale", it) }
                })
            }
        } ?: return null
        return if (entries.length() == 0) null else entries.toString(1)
    }

    /** adds the entries that are missing; returns how many were added */
    fun restore(context: Context, json: String): Int {
        val entries = JSONArray(json)
        var added = 0
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val word = e.optString("word").takeIf { it.isNotBlank() } ?: continue
            val locale = if (e.has("locale")) e.optString("locale") else null
            if (exists(context, word, locale)) continue
            val values = ContentValues().apply {
                put(UserDictionary.Words.WORD, word)
                put(UserDictionary.Words.FREQUENCY, e.optInt("frequency", 250).coerceIn(1, 255))
                put(UserDictionary.Words.LOCALE, locale)
                put(UserDictionary.Words.APP_ID, 0)
                if (e.has("shortcut")) put(UserDictionary.Words.SHORTCUT, e.optString("shortcut"))
            }
            if (context.contentResolver.insert(UserDictionary.Words.CONTENT_URI, values) != null) added++
        }
        return added
    }

    private fun exists(context: Context, word: String, locale: String?): Boolean {
        val (select, args) = if (locale == null)
            "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE} is null" to arrayOf(word)
        else "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE}=?" to arrayOf(word, locale)
        return context.contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD),
            select, args, null)?.use { it.count > 0 } ?: false
    }
}
