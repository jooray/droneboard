// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import java.io.File
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // plain Application: App.onCreate (keyboard startup) is not needed here
class SettingsTransferTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val heightKey = createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, 0, 2)

    /** What a configured install could look like: settings plus personal data and bookkeeping. */
    private val settings = mapOf<String, Any>(
        Settings.PREF_THEME_COLORS to "ocean",
        Settings.PREF_THEME_KEY_BORDERS to false,
        Settings.PREF_SWIPE_BEAM_WIDTH to 64,
        Settings.PREF_NEURAL_PREDICTIONS to false,
        Settings.PREF_KEYPRESS_SOUND_VOLUME to 0.3f,
        heightKey to 0.92f,
        Settings.PREF_ENABLED_SUBTYPES to "sk:MAIN=qwertz§SecondaryLocales=en·cs",
        Settings.PREF_SELECTED_SUBTYPE to "sk:MAIN=qwertz",
        Settings.PREF_TOOLBAR_KEYS to "VOICE:false|CLIPBOARD:true",
        Settings.PREF_USER_COLORS_PREFIX + "user" to "[1,2,3]",
        Settings.PREF_LAYOUT_PREFIX + "main" to "custom.Zm9v.",
    )
    private val personal = mapOf<String, Any>(
        Settings.PREF_RECENT_EMOJIS to "😀;🙂",
        Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "com.example.bank" to "sk",
        "gesture_data_word_exclusions" to "[\"secret\"]",
        "pinned_clips" to "[{\"content\":\"password\"}]",
        Settings.PREF_VERSION_CODE to BuildConfig.VERSION_CODE,
        Settings.PREF_LIBRARY_CHECKSUM to "abc",
        Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID to 3,
        Settings.PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG to true,
        "droneboard_multilingual_default_done" to true,
        "some_unknown_key" to "x",
    )
    private val layoutFile get() = File(DeviceProtectedUtils.getFilesDir(app), "layouts/main/custom.Zm9v.")

    @Before fun setUp() {
        DirectBoot.lockedOverride = false
        app.prefs().edit { clear() }
        write(settings + personal)
        layoutFile.parentFile!!.mkdirs()
        layoutFile.writeText("[[{\"label\": \"a\"}]]")
    }

    @After fun tearDown() {
        DirectBoot.resetForTest()
        layoutFile.delete()
    }

    private fun write(values: Map<String, Any>) = app.prefs().edit {
        values.forEach { (k, v) ->
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Float -> putFloat(k, v)
                is String -> putString(k, v)
            }
        }
    }

    @Test fun exportClearImportRestoresSettings() {
        val export = SettingsTransfer.export(app)
        app.prefs().edit { clear() }
        layoutFile.delete()
        app.prefs().edit { putInt(Settings.PREF_VERSION_CODE, BuildConfig.VERSION_CODE) } // a fresh install after AppUpgrade

        val parsed = SettingsTransfer.parse(export.json)
        assertEquals(settings.size + 1, SettingsTransfer.countChanges(app, parsed)) // the settings and the layout file
        SettingsTransfer.apply(app, parsed)

        val all = app.prefs().all
        settings.forEach { (k, v) -> assertEquals(v, all[k], k) }
        assertEquals("[[{\"label\": \"a\"}]]", layoutFile.readText())
        assertEquals(BuildConfig.VERSION_CODE, all[Settings.PREF_VERSION_CODE])
        assertEquals(0, SettingsTransfer.countChanges(app, parsed))
    }

    @Test fun importResetsSettingsMissingInFileAndKeepsPersonalData() {
        val parsed = SettingsTransfer.parse(SettingsTransfer.export(app).json)
        app.prefs().edit { putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true) } // set after the export
        SettingsTransfer.apply(app, parsed)
        val all = app.prefs().all
        assertFalse(all.containsKey(Settings.PREF_SHOW_NUMBER_ROW))
        personal.forEach { (k, v) -> assertEquals(v, all[k], k) }
    }

    @Test fun personalDataAndBookkeepingAreNeverExported() {
        val json = SettingsTransfer.export(app).json
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("droneboard", root["app"]!!.jsonPrimitive.content)
        assertEquals(1, root["format"]!!.jsonPrimitive.int)
        assertEquals(BuildConfig.VERSION_CODE, root["versionCode"]!!.jsonPrimitive.int)
        val prefs = root["prefs"]!!.jsonObject
        assertEquals(settings.keys.sorted(), prefs.keys.toList()) // sorted, nothing else
        personal.keys.forEach { assertFalse(json.contains("\"$it\""), it) }
        listOf("secret", "password", "com.example.bank", "😀").forEach { assertFalse(json.contains(it), it) }
        assertEquals("int", prefs[Settings.PREF_SWIPE_BEAM_WIDTH]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("float", prefs[heightKey]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue((root["customLayouts"] as JsonObject).containsKey("main/custom.Zm9v."))
    }

    @Test fun pureExportFiltersAndSorts() {
        val e = SettingsTransfer.export(mapOf("zz" to 1, Settings.PREF_SWIPE_BEAM_WIDTH to 10, Settings.PREF_AUTO_CAP to true,
            Settings.PREF_VERSION_CODE to 5), emptyMap<String, Any>(), emptyMap(), 5016, Date(0))
        assertEquals(2, e.settingCount)
        assertTrue(e.json.indexOf(Settings.PREF_AUTO_CAP) < e.json.indexOf(Settings.PREF_SWIPE_BEAM_WIDTH))
        assertTrue(e.json.contains("\"exported\": \"1970-01-01T00:00:00Z\""))
        assertEquals("droneboard-settings-2026-11-16.json",
            SettingsTransfer.defaultFileName(java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-11-16")!!))
    }

    @Test fun foreignAndGarbageFilesAreRejected() {
        fun reason(text: String) = assertFailsWith<SettingsTransfer.InvalidFileException> { SettingsTransfer.parse(text) }.reason
        val valid = """"prefs":{"auto_cap":{"type":"boolean","value":false}}"""
        assertEquals(SettingsTransfer.Reason.NOT_JSON, reason(""))
        assertEquals(SettingsTransfer.Reason.NOT_JSON, reason("garbage {"))
        assertEquals(SettingsTransfer.Reason.NOT_JSON, reason("[1,2,3]"))
        assertEquals(SettingsTransfer.Reason.NOT_JSON, reason("\u0000\u0001PK"))
        assertEquals(SettingsTransfer.Reason.FOREIGN, reason("{}"))
        assertEquals(SettingsTransfer.Reason.FOREIGN, reason("""{"app":"heliboard","format":1,"versionCode":5016,$valid}"""))
        assertEquals(SettingsTransfer.Reason.FOREIGN, reason("""{"app":"droneboard","format":"1","versionCode":5016,$valid}"""))
        assertEquals(SettingsTransfer.Reason.FOREIGN, reason("""{"app":"droneboard","format":1,$valid}"""))
        assertEquals(SettingsTransfer.Reason.NEWER_FORMAT, reason("""{"app":"droneboard","format":2,"versionCode":5016,$valid}"""))
        assertEquals(SettingsTransfer.Reason.NOTHING_TO_IMPORT, reason("""{"app":"droneboard","format":1,"versionCode":5016}"""))
        assertEquals(SettingsTransfer.Reason.NOTHING_TO_IMPORT,
            reason("""{"app":"droneboard","format":1,"versionCode":5016,"prefs":{"recent_emojis":{"type":"string","value":"x"}}}"""))
        // nothing was written
        assertEquals("ocean", app.prefs().getString(Settings.PREF_THEME_COLORS, null))
    }

    @Test fun unknownKeysWrongTypesAndBadLayoutNamesAreSkipped() {
        val parsed = SettingsTransfer.parse("""
            {"app":"droneboard","format":1,"versionCode":5016,"prefs":{
              "auto_cap":{"type":"boolean","value":false},
              "swipe_beam_width":{"type":"string","value":"64"},
              "theme_colors":{"type":"int","value":3},
              "key_longpress_timeout":{"type":"int","value":1.5},
              "neural_predictions":{"type":"boolean","value":"true"},
              "version_code":{"type":"int","value":1},
              "saved_app_subtype_com.bank":{"type":"string","value":"sk"},
              "not_a_setting":{"type":"string","value":"x"},
              "font_scale":{"type":"weird","value":1},
              "toolbar_keys":"VOICE:true"
            },
            "customLayouts":{"main/custom.ok.":"[]","../evil":"x","main/../../x":"x","bogus/custom.x.":"x","main/qwerty":"x"}}
        """.trimIndent())
        assertEquals(mapOf<String, Any>(Settings.PREF_AUTO_CAP to false), parsed.prefs)
        assertEquals(setOf("main/custom.ok."), parsed.layouts.keys)
        assertEquals(13, parsed.skipped.size)
        SettingsTransfer.apply(app, parsed)
        val all = app.prefs().all
        assertEquals(false, all[Settings.PREF_AUTO_CAP])
        assertNull(all["not_a_setting"])
        assertEquals("sk", all[Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "com.example.bank"]) // personal, untouched
        assertEquals(BuildConfig.VERSION_CODE, all[Settings.PREF_VERSION_CODE])
        File(DeviceProtectedUtils.getFilesDir(app), "layouts/main/custom.ok.").delete()
    }

    @Test fun importFromOlderVersionRunsMigrations() {
        // 0.1.8 replaced split, one-handed and floating toolbar keys by one "size & position" key
        val parsed = SettingsTransfer.parse("""
            {"app":"droneboard","format":1,"versionCode":5007,"prefs":{
              "toolbar_keys":{"type":"string","value":"SPLIT:true|CLIPBOARD:true"}
            }}
        """.trimIndent())
        SettingsTransfer.apply(app, parsed)
        val toolbar = app.prefs().getString(Settings.PREF_TOOLBAR_KEYS, "")!!
        assertTrue(toolbar.contains("SIZE_POSITION:true"), toolbar)
        assertTrue(toolbar.contains("SPLIT:false"), toolbar)
        assertEquals(BuildConfig.VERSION_CODE, app.prefs().getInt(Settings.PREF_VERSION_CODE, 0))
    }

    @Test fun lockedDeviceIsRefused() {
        DirectBoot.lockedOverride = true
        assertFailsWith<IllegalStateException> { SettingsTransfer.export(app) }
    }
}
