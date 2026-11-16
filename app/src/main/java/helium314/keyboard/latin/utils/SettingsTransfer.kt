// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutType.Companion.folder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Droneboard: export and import of settings as a human-readable JSON file, to configure another work/personal
 * profile or another device the same way. Unlike Backup and restore (a ZIP with dictionaries, typing history and
 * clipboard), the file holds settings only:
 *
 * - the known preferences of both SharedPreferences files (device-protected and credential-encrypted), with
 *   their types; this covers languages and subtypes, layouts, theme and colors, size and position, toolbar,
 *   swipe and neural settings;
 * - custom layouts (small text files under layouts/), because layout prefs refer to them by name.
 *
 * Never included: typing history, learned and blocked words, the personal dictionary, contacts, clipboard,
 * swipe recordings and models (all of these are files or databases, never read here), recent emojis, per-app
 * language choices (they list the apps) and swipe data gathering state. Bookkeeping keys such as
 * [Settings.PREF_VERSION_CODE] and one-time flags are excluded too, so [checkVersionUpgrade] still works
 * on the target. Background images and fonts are binary files and stay with Backup and restore.
 *
 * Import replaces the transferable settings: a known setting that is missing in the file goes back to its
 * default, so both installs end up configured the same way. Everything here runs from the settings screen
 * only, which needs an unlocked device; [requireUnlocked] makes sure of that.
 */
object SettingsTransfer {
    const val APP_ID = "droneboard"
    const val FORMAT = 1
    const val MIME_TYPE = "application/json"
    private const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_LAYOUT_BYTES = 256 * 1024
    private const val LAYOUT_DIR = "layouts"

    /** Keys that are bookkeeping, one-time flags, UI state or personal data. */
    private val excludedKeys = setOf(
        Settings.PREF_VERSION_CODE, // the importing install keeps its own (AppUpgrade)
        Settings.PREF_LIBRARY_CHECKSUM, // belongs to a library file that is not transferred
        Settings.PREF_RECENT_EMOJIS, // typing history
        Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID,
        Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID,
        Settings.PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG, // one-time flag, dictionaries differ per install
        DebugSettings.PREF_EXPORT_DICTIONARY, // an action, not a setting
        "pinned_clips", // old clipboard storage
        "emoji_recent_keys", // old recent emojis
        "droneboard_multilingual_default_done", // one-time flag of SubtypeSettings
    )
    private val excludedPrefixes = listOf(
        Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, // per-app language, lists the apps the user types in
        "gesture_data_", // swipe data gathering: word and app exclusions, counters, reminders
    )

    /** Settings.PREF_* / DebugSettings.PREF_* constants: exact keys and key prefixes (field name ends in _PREFIX). */
    private val keyFields: List<Pair<String, String>> by lazy {
        listOf(Settings::class.java, DebugSettings::class.java).flatMap { c ->
            c.fields.filter {
                it.name.startsWith("PREF") && it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }.mapNotNull { f -> (f.get(null) as? String)?.takeIf { it.isNotEmpty() }?.let { f.name to it } }
        }
    }
    private val exactKeys by lazy { keyFields.filterNot { it.first.endsWith("_PREFIX") }.associate { it.second to it.first } }
    private val prefixKeys by lazy { keyFields.filter { it.first.endsWith("_PREFIX") }.map { it.second to it.first.removeSuffix("_PREFIX") } }

    fun isExcluded(key: String) = key in excludedKeys || excludedPrefixes.any { key.startsWith(it) }

    /** Name of the Settings constant for [key], or null if this version of the app does not know the key. */
    private fun fieldNameFor(key: String): String? =
        exactKeys[key] ?: prefixKeys.filter { key.startsWith(it.first) }.maxByOrNull { it.first.length }?.second

    fun isTransferable(key: String) = !isExcluded(key) && fieldNameFor(key) != null

    // ---------- types ----------

    enum class Type(val json: String) { BOOLEAN("boolean"), INT("int"), FLOAT("float"), LONG("long"), STRING("string"), STRING_SET("stringSet") }

    private fun typeOf(value: Any?): Type? = when (value) {
        is Boolean -> Type.BOOLEAN
        is Int -> Type.INT
        is Long -> Type.LONG
        is Float -> Type.FLOAT
        is String -> Type.STRING
        is Set<*> -> if (value.all { it is String }) Type.STRING_SET else null
        else -> null
    }

    /** Type the app reads [key] with, from the default in [Defaults]; null if there is no typed default. */
    private fun defaultType(key: String): Type? {
        val name = fieldNameFor(key) ?: return null
        val value = try {
            Defaults::class.java.getDeclaredField(name).apply { isAccessible = true }.get(Defaults)
        } catch (e: Throwable) {
            return null
        }
        return typeOf(if (value is Array<*>) value.firstOrNull() else value)
    }

    // ---------- export ----------

    data class Export(val json: String, val settingCount: Int, val layoutCount: Int)

    fun export(context: Context): Export {
        requireUnlocked(context)
        val prefs = context.prefs()
        val credential = context.protectedPrefs().takeIf { it !== prefs }
        val main = prefs.all.toMutableMap()
        // Android 13+ keeps the app language in the system, the pref may be stale
        val appLanguage = AppLanguage.get(context)
        if (appLanguage.isEmpty()) main.remove(Settings.PREF_APP_LANGUAGE) else main[Settings.PREF_APP_LANGUAGE] = appLanguage
        return export(main, credential?.all ?: emptyMap<String, Any?>(), readCustomLayouts(context), BuildConfig.VERSION_CODE, Date())
    }

    /** Pure part of [export]: builds the JSON from preference maps and custom layout files. */
    fun export(prefs: Map<String, *>, credentialPrefs: Map<String, *>, layouts: Map<String, String>, versionCode: Int, date: Date): Export {
        val main = toJsonPrefs(prefs)
        val credential = toJsonPrefs(credentialPrefs)
        val root = linkedMapOf<String, JsonElement>(
            "app" to JsonPrimitive(APP_ID),
            "format" to JsonPrimitive(FORMAT),
            "versionCode" to JsonPrimitive(versionCode),
            "exported" to JsonPrimitive(isoDate(date)),
            "prefs" to JsonObject(main),
        )
        if (credential.isNotEmpty()) root["credentialPrefs"] = JsonObject(credential)
        if (layouts.isNotEmpty()) root["customLayouts"] = JsonObject(layouts.toSortedMap().mapValues { JsonPrimitive(it.value) })
        return Export(prettyJson.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n", main.size + credential.size, layouts.size)
    }

    private fun toJsonPrefs(prefs: Map<String, *>): Map<String, JsonElement> = prefs.entries
        .filter { isTransferable(it.key) }
        .mapNotNull { (key, value) ->
            val type = typeOf(value) ?: return@mapNotNull null
            val json = when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                is Set<*> -> JsonArray(value.map { JsonPrimitive(it as String) }.sortedBy { it.content })
                else -> return@mapNotNull null
            }
            key to JsonObject(linkedMapOf("type" to JsonPrimitive(type.json), "value" to json))
        }.sortedBy { it.first }.toMap(LinkedHashMap<String, JsonElement>())

    private fun isoDate(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    fun defaultFileName(date: Date = Date()) =
        "$APP_ID-settings-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date) + ".json"

    // ---------- custom layouts ----------

    private fun layoutsDir(context: Context) = File(DeviceProtectedUtils.getFilesDir(context), LAYOUT_DIR)

    /** Custom layout files as "main/custom.xyz." to content. */
    fun readCustomLayouts(context: Context): Map<String, String> {
        val result = sortedMapOf<String, String>()
        LayoutType.entries.forEach { type ->
            File(DeviceProtectedUtils.getFilesDir(context), type.folder).listFiles()?.forEach { file ->
                if (file.isFile && LayoutUtilsCustom.isCustomLayout(file.name) && file.length() <= MAX_LAYOUT_BYTES)
                    runCatching { file.readText() }.getOrNull()?.let { result[type.name.lowercase() + "/" + file.name] = it }
            }
        }
        return result
    }

    /** The file for a layout entry name, or null if the name is not a plain custom layout in a known folder. */
    private fun layoutFile(baseDir: File, name: String): File? {
        val type = name.substringBefore("/", "")
        val fileName = name.substringAfter("/", "")
        if (LayoutType.entries.none { it.name.lowercase() == type }) return null
        if (!LayoutUtilsCustom.isCustomLayout(fileName) || fileName.contains('/') || fileName.contains('\\')
            || fileName.contains("..") || fileName.contains('\u0000')) return null
        val dir = File(baseDir, type)
        val file = File(dir, fileName)
        return if (file.canonicalFile.parentFile == dir.canonicalFile) file else null
    }

    // ---------- import ----------

    class InvalidFileException(val reason: Reason, message: String? = null) : Exception(message ?: reason.name)
    enum class Reason { NOT_JSON, FOREIGN, NEWER_FORMAT, NOTHING_TO_IMPORT, TOO_LARGE }

    data class Parsed(
        val versionCode: Int,
        val prefs: Map<String, Any>,
        val credentialPrefs: Map<String, Any>,
        val layouts: Map<String, String>,
        /** entries that were ignored: unknown or excluded keys, wrong or unknown types, bad layout names */
        val skipped: List<String>,
    )

    /** Validates and reads a settings file; throws [InvalidFileException] for anything that is not one. */
    fun parse(text: String): Parsed {
        if (text.length > MAX_FILE_BYTES) throw InvalidFileException(Reason.TOO_LARGE)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw InvalidFileException(Reason.NOT_JSON)
        if ((root["app"] as? JsonPrimitive)?.takeIf { it.isString }?.content != APP_ID)
            throw InvalidFileException(Reason.FOREIGN)
        val format = (root["format"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw InvalidFileException(Reason.FOREIGN)
        if (format > FORMAT) throw InvalidFileException(Reason.NEWER_FORMAT)
        if (format < 1) throw InvalidFileException(Reason.FOREIGN)
        val versionCode = (root["versionCode"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
            ?: throw InvalidFileException(Reason.FOREIGN)
        val skipped = mutableListOf<String>()
        val prefs = parsePrefs(root["prefs"], skipped)
        val credentialPrefs = parsePrefs(root["credentialPrefs"], skipped)
        val layouts = sortedMapOf<String, String>()
        (root["customLayouts"] as? JsonObject)?.forEach { (name, value) ->
            val content = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (content == null || content.length > MAX_LAYOUT_BYTES || layoutFile(File("/"), name) == null) skipped.add(name)
            else layouts[name] = content
        }
        if (prefs.isEmpty() && credentialPrefs.isEmpty() && layouts.isEmpty())
            throw InvalidFileException(Reason.NOTHING_TO_IMPORT)
        return Parsed(versionCode, prefs, credentialPrefs, layouts, skipped)
    }

    private fun parsePrefs(element: JsonElement?, skipped: MutableList<String>): Map<String, Any> {
        val obj = element as? JsonObject ?: return emptyMap()
        val result = sortedMapOf<String, Any>()
        obj.forEach { (key, entry) ->
            val value = if (isTransferable(key)) parseValue(entry as? JsonObject) else null
            val expected = defaultType(key)
            if (value == null || (expected != null && typeOf(value) != expected)) skipped.add(key)
            else result[key] = value
        }
        return result
    }

    private fun parseValue(entry: JsonObject?): Any? {
        entry ?: return null
        val type = (entry["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val raw = entry["value"] ?: return null
        if (type == Type.STRING_SET.json) {
            val array = raw as? JsonArray ?: return null
            val strings = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            return strings.toSet()
        }
        val p = raw as? JsonPrimitive ?: return null
        return when (type) {
            Type.STRING.json -> p.takeIf { it.isString }?.content
            Type.BOOLEAN.json -> p.takeIf { !it.isString }?.booleanOrNull
            Type.INT.json -> p.takeIf { !it.isString }?.intOrNull
            Type.LONG.json -> p.takeIf { !it.isString }?.longOrNull
            Type.FLOAT.json -> p.takeIf { !it.isString }?.floatOrNull?.takeIf { it.isFinite() }
            else -> null
        }
    }

    // ---------- apply ----------

    /** Preferences that differ between [prefs] and what an import of [values] would leave there. */
    fun changedKeys(prefs: Map<String, *>, values: Map<String, Any>): Set<String> {
        val current = prefs.filterKeys { isTransferable(it) }
        // a key with the wrong local type is never overwritten; it is not removed either if the file skipped it
        return (current.keys + values.keys).filterTo(sortedSetOf()) { current[it] != values[it] }
    }

    private fun changedLayouts(baseDir: File, layouts: Map<String, String>) = layouts.count { (name, content) ->
        val file = layoutFile(baseDir, name) ?: return@count false
        !file.isFile || runCatching { file.readText() }.getOrNull() != content
    }

    /** Number of settings and custom layouts an import of [parsed] would change. */
    fun countChanges(context: Context, parsed: Parsed): Int {
        requireUnlocked(context)
        val prefs = context.prefs()
        val credential = context.protectedPrefs().takeIf { it !== prefs }
        val main = prefs.all.toMutableMap()
        val appLanguage = AppLanguage.get(context)
        if (appLanguage.isEmpty()) main.remove(Settings.PREF_APP_LANGUAGE) else main[Settings.PREF_APP_LANGUAGE] = appLanguage
        var count = changedKeys(main, parsed.prefs).size + changedLayouts(layoutsDir(context), parsed.layouts)
        if (credential != null) count += changedKeys(credential.all, parsed.credentialPrefs).size
        return count
    }

    /** Writes [values] into [prefs]: transferable keys not in [values] are removed, everything else stays. */
    fun applyPrefs(prefs: SharedPreferences, values: Map<String, Any>) {
        val e = prefs.edit()
        prefs.all.keys.filter { isTransferable(it) && it !in values }.forEach { e.remove(it) }
        values.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> e.putBoolean(key, value)
                is Int -> e.putInt(key, value)
                is Long -> e.putLong(key, value)
                is Float -> e.putFloat(key, value)
                is String -> e.putString(key, value)
                is Set<*> -> e.putStringSet(key, value as Set<String>)
            }
        }
        e.commit()
    }

    /**
     * Applies [parsed] and runs the app upgrade on it: the file's versionCode is set as the previous version, so
     * migrations for renamed or changed settings run as after an app update. The caller reloads the keyboard.
     */
    fun apply(context: Context, parsed: Parsed) {
        requireUnlocked(context)
        val prefs = context.prefs()
        val credential = context.protectedPrefs().takeIf { it !== prefs }
        val layoutsBase = layoutsDir(context)
        parsed.layouts.forEach { (name, content) ->
            val file = layoutFile(layoutsBase, name) ?: return@forEach
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        applyPrefs(prefs, parsed.prefs)
        if (credential != null) applyPrefs(credential, parsed.credentialPrefs)
        if (parsed.versionCode < BuildConfig.VERSION_CODE) {
            prefs.edit().putInt(Settings.PREF_VERSION_CODE, parsed.versionCode).commit()
            checkVersionUpgrade(context)
        }
    }

    /** Settings import and export need credential-encrypted storage, i.e. an unlocked device. */
    fun requireUnlocked(context: Context) {
        if (!DirectBoot.isUnlocked(context)) throw IllegalStateException("device is locked")
    }

    private val prettyJson = Json { prettyPrint = true }
}
