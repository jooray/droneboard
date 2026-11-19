// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Droneboard: reads word lists for the personal dictionary and writes them. Pure Kotlin (no Android classes),
 * so that it can be tested on the JVM; [PersonalDictionaryBackup] talks to Android's UserDictionary.
 *
 * Formats, detected by content and not by the file name:
 * - [Format.TEXT]: one entry per line, columns separated by a tab: word, locale, shortcut, frequency (1-255).
 *   Only the word is required; an empty or missing locale means all languages.
 * - [Format.GBOARD]: Gboard's exported dictionary.txt (first line "# Gboard Dictionary version:1"), columns
 *   shortcut, word, locale; also inside the .zip Gboard produces.
 * - [Format.DRONEBOARD]: the JSON array [PersonalDictionaryBackup] writes into backups.
 */
internal object PersonalDictionaryImport {
    /** frequency null = the default of the add-word dialog, [DEFAULT_FREQUENCY] */
    data class Entry(val word: String, val locale: String?, val shortcut: String? = null, val frequency: Int? = null) {
        /** the same word in the same language is the same entry; an empty locale counts as "all languages" */
        val key: String get() = key(word, locale)
    }

    enum class Format { TEXT, GBOARD, DRONEBOARD }

    enum class Error { TOO_LARGE, NOT_A_WORD_LIST }

    /**
     * [entries] has every valid entry once (the first of repeated ones), in the order of the file.
     * [invalidLines] were skipped, [duplicates] repeated an earlier entry. With [error] nothing is usable.
     */
    data class Parsed(
        val format: Format?,
        val entries: List<Entry>,
        val invalidLines: Int,
        val duplicates: Int,
        val error: Error? = null,
    )

    /** what importing [Parsed.entries] into a dictionary would do */
    data class Plan(val toAdd: List<Entry>, val existing: Int)

    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    /** longer words are ignored by the dictionary (DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH) */
    const val MAX_WORD_LENGTH = 48
    /** what the add-word dialog and "add to dictionary" use */
    const val DEFAULT_FREQUENCY = 250
    const val GBOARD_HEADER = "# Gboard Dictionary version:"
    private const val GBOARD_FILE = "dictionary.txt"
    const val EXPORT_HEADER = "# Droneboard personal dictionary\n" +
        "# One entry per line, columns separated by a tab:\n" +
        "# word, language (empty = all languages), shortcut, frequency (1-255)\n"

    private val SIMPLE_LOCALE = Regex("([A-Za-z]{2,3})(?:[_-]([A-Za-z]{2}|[0-9]{3}))?")
    private val LOCALE_CHARS = Regex("[A-Za-z0-9_#-]{2,40}")

    fun key(word: String, locale: String?) = word + "\u0000" + locale.orEmpty()

    /** reads at most [MAX_BYTES] + 1 bytes, so that a larger file is recognised without loading it */
    fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() <= limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Never throws: a file that is not a word list comes back with [Parsed.error]. */
    fun parse(bytes: ByteArray): Parsed {
        return try {
            if (bytes.size > MAX_BYTES) return failed(Error.TOO_LARGE)
            if (isZip(bytes)) {
                val inner = unzip(bytes) ?: return failed(Error.NOT_A_WORD_LIST)
                if (inner.size > MAX_BYTES) return failed(Error.TOO_LARGE)
                parseText(inner)
            } else parseText(bytes)
        } catch (e: Exception) {
            failed(Error.NOT_A_WORD_LIST)
        }
    }

    private fun failed(error: Error) = Parsed(null, emptyList(), 0, 0, error)

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** Gboard's dictionary.txt, or the personal dictionary of a Droneboard backup */
    private fun unzip(bytes: ByteArray): ByteArray? {
        var fallback: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory) continue
                if (name == GBOARD_FILE) return readLimited(zip)
                if (name == PersonalDictionaryBackup.FILE_NAME) fallback = readLimited(zip)
            }
        }
        return fallback
    }

    private fun parseText(bytes: ByteArray): Parsed {
        // malformed UTF-8 becomes U+FFFD, and such lines are skipped as invalid
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        if (text.indexOf('\u0000') >= 0) return failed(Error.NOT_A_WORD_LIST) // binary, or UTF-16
        val start = text.trimStart()
        return when {
            start.startsWith("[") -> parseJson(text)
            start.startsWith(GBOARD_HEADER) -> parseLines(text, Format.GBOARD)
            else -> parseLines(text, Format.TEXT)
        }
    }

    /** the format of [PersonalDictionaryBackup]: a JSON array of objects with word, locale, shortcut, frequency */
    fun parseJson(json: String): Parsed {
        val array = try {
            Json.parseToJsonElement(json.removePrefix("﻿")) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return failed(Error.NOT_A_WORD_LIST)
        val collector = Collector(Format.DRONEBOARD)
        for (element in array) {
            val o = element as? JsonObject
            val word = o?.string("word")
            if (o == null || word == null) { collector.invalid++; continue }
            val frequency = (o["frequency"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 255)
            if (!collector.add(word, o.string("locale"), o.string("shortcut"), frequency)) break
        }
        return collector.result()
    }

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun parseLines(text: String, format: Format): Parsed {
        val collector = Collector(format)
        for (rawLine in text.lineSequence()) {
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) continue
            // a comment; with a tab it is an entry whose first column starts with # (a hashtag, a shortcut)
            if (line.trimStart().startsWith("#") && '\t' !in line) continue
            if (line.startsWith(GBOARD_HEADER)) continue
            val columns = line.split('\t').map { it.trim() }
            val ok = if (format == Format.GBOARD) {
                // shortcut, word, locale; later versions may append columns, which are ignored
                if (columns.size < 2) { collector.invalid++; continue }
                collector.add(columns[1], columns.getOrNull(2), columns[0], null)
            } else {
                if (columns.size > 4) { collector.invalid++; continue }
                val frequencyText = columns.getOrNull(3).orEmpty()
                val frequency = if (frequencyText.isEmpty()) null else frequencyText.toIntOrNull()
                if (frequencyText.isNotEmpty() && (frequency == null || frequency !in 1..255)) { collector.invalid++; continue }
                collector.add(columns[0], columns.getOrNull(1), columns.getOrNull(2), frequency)
            }
            if (!ok) break
        }
        return collector.result()
    }

    private class Collector(val format: Format) {
        val entries = ArrayList<Entry>()
        val keys = HashSet<String>()
        var invalid = 0
        var duplicates = 0
        var tooMany = false

        /** false when the limit of entries was exceeded and reading should stop */
        fun add(rawWord: String, rawLocale: String?, rawShortcut: String?, frequency: Int?): Boolean {
            val word = rawWord.trim()
            if (!isValidText(word)) { invalid++; return true }
            val locale = when (val l = normalizeLocale(rawLocale)) {
                // a backup holds what the provider had, so an unusual locale is kept as it is
                INVALID_LOCALE -> if (format == Format.DRONEBOARD) rawLocale else { invalid++; return true }
                else -> l
            }
            var shortcut = rawShortcut?.trim().takeUnless { it.isNullOrEmpty() }
            if (shortcut != null && !isValidText(shortcut)) { invalid++; return true }
            if (shortcut == word) shortcut = null
            val entry = Entry(word, locale, shortcut, frequency)
            if (!keys.add(entry.key)) { duplicates++; return true }
            if (entries.size >= MAX_ENTRIES) { tooMany = true; return false }
            entries.add(entry)
            return true
        }

        fun result() = if (tooMany) failed(Error.TOO_LARGE) else Parsed(format, entries, invalid, duplicates)
    }

    private fun isValidText(s: String) = s.isNotEmpty() && s.length <= MAX_WORD_LENGTH
        && s.none { it == '�' || Character.isISOControl(it) }

    private const val INVALID_LOCALE = "\u0000"

    /**
     * The form the UserDictionary provider stores, which is Locale.toString(): "sk", "en_US", "sr__#Latn".
     * Accepts that form and language tags ("en-US", "sr-Latn"). Empty = all languages (null);
     * [INVALID_LOCALE] when it is not a locale.
     */
    private fun normalizeLocale(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        SIMPLE_LOCALE.matchEntire(s)?.let { m ->
            val language = m.groupValues[1].lowercase(Locale.ROOT)
            val region = m.groupValues[2].uppercase(Locale.ROOT)
            return if (region.isEmpty()) language else "${language}_$region"
        }
        if (!LOCALE_CHARS.matches(s)) return INVALID_LOCALE
        if ('_' in s) return s // already the provider's form, e.g. sr__#Latn or en_US_POSIX
        val locale = Locale.forLanguageTag(s)
        return if (locale.language.isEmpty()) INVALID_LOCALE else locale.toString()
    }

    /** null when [raw] is not a locale; for tests and callers that need the normalised form */
    fun localeOrNull(raw: String?): String? = normalizeLocale(raw).takeUnless { it == INVALID_LOCALE }

    /** merge: entries whose word + locale are not in [existingKeys] (see [key]) are added, the rest stay as they are */
    fun plan(entries: List<Entry>, existingKeys: Set<String>): Plan {
        val seen = HashSet<String>()
        val toAdd = entries.filter { it.key !in existingKeys && seen.add(it.key) }
        return Plan(toAdd, entries.count { it.key in existingKeys })
    }

    /** the text format, with all four columns, so that [parse] reads it back unchanged */
    fun toText(entries: List<Entry>): String {
        val sb = StringBuilder(EXPORT_HEADER)
        for (e in entries) {
            val word = clean(e.word)
            if (word.isEmpty()) continue
            sb.append(word).append('\t').append(e.locale.orEmpty()).append('\t')
                .append(clean(e.shortcut.orEmpty())).append('\t')
                .append((e.frequency ?: DEFAULT_FREQUENCY).coerceIn(1, 255)).append('\n')
        }
        return sb.toString()
    }

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()
}
