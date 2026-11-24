// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SystemBroadcastReceiver
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.swipe.FutoSwipe
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.checkTimestampFormat
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.InfoButton
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.BackupRestorePreference
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.LoadGestureLibPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun AdvancedSettingsScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val showDebug = BuildConfig.DEBUG || prefs.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, Defaults.PREF_SHOW_DEBUG_SETTINGS)
    val showDeveloperTuning = FutoSwipe.isAvailable || showDebug
    val items = listOf(
        SettingsWithoutKey.BACKUP_RESTORE,
        Settings.PREF_ALWAYS_INCOGNITO_MODE,
        Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD,
        Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.PREF_SHOW_SETUP_WIZARD_ICON else null,
        Settings.PREF_ABC_AFTER_SYMBOL_SPACE,
        Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        Settings.PREF_ABC_AFTER_EMOJI,
        Settings.PREF_CUSTOM_CURRENCY_KEY,
        Settings.PREF_MORE_POPUP_KEYS,
        Settings.PREF_TIMESTAMP_FORMAT,
        R.string.settings_category_experimental,
        Settings.PREF_EMOJI_MAX_SDK,
        Settings.PREF_URL_DETECTION,
        Settings.PREF_ENABLE_HARDWARE_KEYBOARD,
        // Droneboard: swipe is FUTO Swipe; loading the proprietary Google library is hidden (code kept for a possible opt-in engine)
        if (DRONEBOARD_SHOW_GESTURE_LIB_LOADER && BuildConfig.BUILD_TYPE != "nouserlib") SettingsWithoutKey.LOAD_GESTURE_LIB else null,
        if (showDeveloperTuning) R.string.settings_category_developer_tuning else null,
        if (FutoSwipe.isAvailable) Settings.PREF_SWIPE_BEAM_WIDTH else null,
        if (FutoSwipe.isAvailable) SettingsWithoutKey.SWIPE_RECORDER else null,
        if (showDebug) SettingsWithoutKey.DEBUG_SETTINGS else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_backup_and_advanced),
        settings = items
    )
}

@SuppressLint("ApplySharedPref")
fun createAdvancedSettings(context: Context) = listOfNotNull(
    Setting(context, SettingsWithoutKey.BACKUP_RESTORE, R.string.backup_restore_title) {
        BackupRestorePreference(it)
    },
    Setting(context, Settings.PREF_ALWAYS_INCOGNITO_MODE,
        R.string.incognito, R.string.prefs_force_incognito_mode_summary)
    {
        SwitchPreference(it, Defaults.PREF_ALWAYS_INCOGNITO_MODE) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, R.string.prefs_long_press_symbol_for_numpad) {
        SwitchPreference(it, Defaults.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD)
    },
    Setting(context, Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, R.string.prefs_enable_emoji_alt_physical_key,
        R.string.prefs_enable_emoji_alt_physical_key_summary)
    {
        SwitchPreference(it, Defaults.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
    },
    Setting(context, Settings.PREF_SHOW_SETUP_WIZARD_ICON, R.string.show_setup_wizard_icon, R.string.show_setup_wizard_icon_summary) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_SHOW_SETUP_WIZARD_ICON) { SystemBroadcastReceiver.toggleAppIcon(ctx) }
    },
    Setting(context, Settings.PREF_ABC_AFTER_SYMBOL_SPACE,
        R.string.switch_keyboard_after, R.string.after_symbol_and_space)
    {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_SYMBOL_SPACE)
    },
    Setting(context, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        R.string.switch_keyboard_after, R.string.after_numpad_and_space)
    {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_NUMPAD_SPACE)
    },
    Setting(context, Settings.PREF_ABC_AFTER_EMOJI, R.string.switch_keyboard_after, R.string.after_emoji) {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_EMOJI)
    },
    Setting(context, Settings.PREF_CUSTOM_CURRENCY_KEY, R.string.customize_currencies) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            val prefs = LocalContext.current.prefs()
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.customize_currencies_detail)) },
                initialText = prefs.getString(setting.key, Defaults.PREF_CUSTOM_CURRENCY_KEY)!!,
                onConfirmed = { prefs.edit { putString(setting.key, it) }; KeyboardLayoutSet.onSystemLocaleChanged() },
                title = { Text(stringResource(R.string.customize_currencies)) },
                neutralButtonText = if (prefs.contains(setting.key)) stringResource(R.string.button_default) else null,
                onNeutral = { prefs.edit { remove(setting.key)}; KeyboardLayoutSet.onSystemLocaleChanged() },
                checkTextValid = { text -> text.splitOnWhitespace().none { it.length > 8 } }
            )
        }
    },
    Setting(context, Settings.PREF_MORE_POPUP_KEYS, R.string.show_popup_keys_title) {
        val items = listOf(
            LocaleKeyboardInfos.POPUP_KEYS_NORMAL,
            LocaleKeyboardInfos.POPUP_KEYS_MAIN,
            LocaleKeyboardInfos.POPUP_KEYS_MORE,
            LocaleKeyboardInfos.POPUP_KEYS_ALL
        ).map { setting ->
            stringResource(LocaleKeyboardInfos.morePopupKeysResId(setting)) to setting
        }
        ListPreference(it, items, Defaults.PREF_MORE_POPUP_KEYS) { KeyboardLayoutSet.onSystemLocaleChanged() }
    },
    Setting(context, Settings.PREF_TIMESTAMP_FORMAT, R.string.timestamp_format_title) { setting ->
        TextInputPreference(setting, Defaults.PREF_TIMESTAMP_FORMAT, stringResource(R.string.timestamp_description)) { checkTimestampFormat(it) }
    },
    Setting(context, Settings.PREF_EMOJI_MAX_SDK, R.string.prefs_key_emoji_max_sdk) { setting ->
        val ctx = LocalContext.current
        val stored = ctx.prefs().getInt(setting.key, Build.VERSION.SDK_INT)
        val upperSdk = maxOf(Build.VERSION.SDK_INT, stored, 36)
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Build.VERSION.SDK_INT.coerceIn(21, upperSdk),
            range = 21f..upperSdk.toFloat(),
            description = { sdk ->
                val effective = if (sdk in 21..upperSdk) sdk else Build.VERSION.SDK_INT
                androidVersionLabel(effective)
            },
            onConfirmed = {
                SupportedEmojis.load(ctx)
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        )
    },
    Setting(context, Settings.PREF_URL_DETECTION, R.string.url_detection_title, R.string.url_detection_summary) {
        SwitchPreference(it, Defaults.PREF_URL_DETECTION)
    },
    Setting(context, Settings.PREF_ENABLE_HARDWARE_KEYBOARD, R.string.prefs_enable_hardware_keyboard) {
        SwitchPreference(it, Defaults.PREF_ENABLE_HARDWARE_KEYBOARD)
    },
    Setting(context, Settings.PREF_SWIPE_BEAM_WIDTH, R.string.swipe_beam_width_title) { def ->
        val items = SWIPE_BEAM_WIDTHS.map { width ->
            when (width) {
                Defaults.PREF_SWIPE_BEAM_WIDTH -> stringResource(R.string.swipe_beam_width_recommended, width)
                100 -> stringResource(R.string.swipe_beam_width_previous_default, width)
                else -> width.toString()
            } to width
        }
        ListPreference(
            def, items, Defaults.PREF_SWIPE_BEAM_WIDTH,
            value = {
                InfoButton(
                    stringResource(R.string.swipe_beam_width_help),
                    stringResource(R.string.swipe_beam_width_help_button)
                )
            }
        )
    },
    Setting(context, SettingsWithoutKey.SWIPE_RECORDER, R.string.swipe_recorder_title, R.string.swipe_recorder_summary) {
        Preference(
            name = it.title,
            description = it.description,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.SwipeRecorder) }
        ) { NextScreenIcon() }
    },
    Setting(context, SettingsWithoutKey.DEBUG_SETTINGS, R.string.debug_settings_title) {
        Preference(
            name = it.title,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.Debug) }
        ) { NextScreenIcon() }
    },
    if (DRONEBOARD_SHOW_GESTURE_LIB_LOADER && BuildConfig.BUILD_TYPE != "nouserlib") {
        Setting(context, SettingsWithoutKey.LOAD_GESTURE_LIB, R.string.load_gesture_library, R.string.load_gesture_library_summary) {
            LoadGestureLibPreference(it)
        }
    } else null,
)

private fun androidVersionLabel(sdk: Int): String = "Android " + when {
    sdk == 21 -> "5.0"
    sdk == 22 -> "5.1"
    sdk == 23 -> "6"
    sdk == 24 -> "7.0"
    sdk == 25 -> "7.1"
    sdk == 26 -> "8.0"
    sdk == 27 -> "8.1"
    sdk == 28 -> "9"
    sdk == 29 -> "10"
    sdk == 30 -> "11"
    sdk == 31 -> "12"
    sdk == 32 -> "12L"
    sdk >= 33 -> (sdk - 20).toString()
    else -> "API $sdk"
}

/** choices of the final swipe decode's beam width (the native engine accepts up to 400) */
private val SWIPE_BEAM_WIDTHS = listOf(50, 100, 200, 300, 400)

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AdvancedSettingsScreen { }
        }
    }
}

/** Droneboard: the proprietary gesture library loader is hidden from the settings */
const val DRONEBOARD_SHOW_GESTURE_LIB_LOADER = false
