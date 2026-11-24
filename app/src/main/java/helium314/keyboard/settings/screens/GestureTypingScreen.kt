// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.text.format.Formatter
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.swipe.FutoSwipe
import helium314.keyboard.latin.swipe.TypingSwipeRecorder
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun GestureTypingScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val hasGestureTyping = JniUtils.hasGestureTyping()
    val gestureFloatingPreviewEnabled = prefs.getBoolean(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
    val gestureEnabled = hasGestureTyping && prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
    val recordTypingSwipes = prefs.getBoolean(Settings.PREF_RECORD_TYPING_SWIPES, Defaults.PREF_RECORD_TYPING_SWIPES)
    val swipeStats by TypingSwipeRecorder.stats.collectAsState()
    LaunchedEffect(Unit) {
        if (FutoSwipe.isAvailable) TypingSwipeRecorder.refresh(ctx)
    }
    val usesTouchpad = Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.TOUCHPAD_MODE
        || prefs.getString(Settings.PREF_SPACE_LONG_PRESS, Defaults.PREF_SPACE_LONG_PRESS) == Defaults.SPACE_LONG_PRESS_TRACKPAD
    val showRecordedSwipes = FutoSwipe.isAvailable && (swipeStats.count > 0 || recordTypingSwipes)
    val showSwipeImprovement = FutoSwipe.isAvailable && (gestureEnabled || swipeStats.count > 0)
    val items = listOfNotNull(
        if (hasGestureTyping) Settings.PREF_GESTURE_INPUT else null,
        if (gestureEnabled) Settings.PREF_GESTURE_PREVIEW_TRAIL else null,
        if (gestureEnabled) Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT else null,
        if (gestureEnabled && gestureFloatingPreviewEnabled) Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC else null,
        if (gestureEnabled) Settings.PREF_GESTURE_SPACE_AWARE else null,
        if (gestureEnabled) Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN else null,
        if (gestureEnabled &&
            (prefs.getBoolean(Settings.PREF_GESTURE_PREVIEW_TRAIL, Defaults.PREF_GESTURE_PREVIEW_TRAIL) || gestureFloatingPreviewEnabled))
            Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION else null,
        R.string.settings_category_space_and_delete_gestures,
        Settings.PREF_DELETE_SWIPE,
        Settings.PREF_SPACE_LONG_PRESS,
        Settings.PREF_SPACE_HORIZONTAL_SWIPE,
        Settings.PREF_SPACE_VERTICAL_SWIPE,
        if (Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE
            || Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE)
            Settings.PREF_LANGUAGE_SWIPE_DISTANCE else null,
        if (usesTouchpad) Settings.PREF_TOUCHPAD_SENSITIVITY else null,
        if (usesTouchpad) Settings.PREF_TOUCHPAD_EDGE_SCROLL else null,
        if (usesTouchpad) Settings.PREF_TOUCHPAD_DIM else null,
        if (showSwipeImprovement) R.string.settings_category_swipe_improvement else null,
        if (gestureEnabled && FutoSwipe.isAvailable) Settings.PREF_RECORD_TYPING_SWIPES else null,
        if (showRecordedSwipes) SettingsWithoutKey.TYPING_SWIPES else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_glide_typing),
        settings = items
    )
}

fun createGestureTypingSettings(context: Context): List<Setting> {
    val gestureSpecific = if (JniUtils.hasGestureTyping()) listOf(
        Setting(context, Settings.PREF_GESTURE_INPUT, R.string.gesture_input, R.string.gesture_input_summary) {
            SwitchPreference(it, Defaults.PREF_GESTURE_INPUT)
        },
        Setting(context, Settings.PREF_GESTURE_PREVIEW_TRAIL, R.string.gesture_preview_trail) {
            SwitchPreference(it, Defaults.PREF_GESTURE_PREVIEW_TRAIL)
        },
        Setting(context, Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
            R.string.gesture_floating_preview_static, R.string.gesture_floating_preview_static_summary)
        {
            SwitchPreference(it, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
        },
        Setting(context, Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC,
            R.string.gesture_floating_preview_text, R.string.gesture_floating_preview_dynamic_summary)
        { def ->
            val ctx = LocalContext.current
            SwitchPreference(def, Defaults.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC) {
                // default value is based on system reduced motion
                val default = Settings.readGestureDynamicPreviewDefault(ctx)
                val followingSystem = it == default
                // allow the default to be overridden
                ctx.prefs().edit { putBoolean(Settings.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM, followingSystem) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        },
        Setting(context, Settings.PREF_GESTURE_SPACE_AWARE, R.string.gesture_space_aware, R.string.gesture_space_aware_summary) {
            SwitchPreference(it, Defaults.PREF_GESTURE_SPACE_AWARE)
        },
        Setting(context, Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, R.string.gesture_fast_typing_cooldown) { def ->
            SliderPreference(
                name = def.title,
                key = def.key,
                default = Defaults.PREF_GESTURE_FAST_TYPING_COOLDOWN,
                range = 0f..500f,
                description = {
                    if (it <= 0) stringResource(R.string.gesture_fast_typing_cooldown_instant)
                    else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
                }
            )
        },
        Setting(context, Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, R.string.gesture_trail_fadeout_duration) { def ->
            SliderPreference(
                name = def.title,
                key = def.key,
                default = Defaults.PREF_GESTURE_TRAIL_FADEOUT_DURATION,
                range = 100f..1900f,
                description = { stringResource(R.string.abbreviation_unit_milliseconds, (it + 100).toString()) },
                stepSize = 10,
            ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        },
        Setting(context, Settings.PREF_RECORD_TYPING_SWIPES, R.string.typing_swipes_switch, R.string.typing_swipes_switch_summary) { setting ->
            var showPrivacyConfirm by rememberSaveable { mutableStateOf(false) }
            val ctx = LocalContext.current
            SwitchPreference(
                setting = setting,
                default = Defaults.PREF_RECORD_TYPING_SWIPES,
                allowCheckedChange = { enabling ->
                    if (enabling) {
                        showPrivacyConfirm = true
                        false
                    } else true
                }
            )
            if (showPrivacyConfirm) {
                ConfirmationDialog(
                    onDismissRequest = { showPrivacyConfirm = false },
                    title = { Text(stringResource(R.string.typing_swipes_privacy_title)) },
                    content = { Text(stringResource(R.string.typing_swipes_privacy_message)) },
                    confirmButtonText = stringResource(R.string.typing_swipes_privacy_confirm),
                    onConfirmed = {
                        ctx.prefs().edit { putBoolean(setting.key, true) }
                    }
                )
            }
        },
        Setting(context, SettingsWithoutKey.TYPING_SWIPES, R.string.typing_swipes_data_title, R.string.typing_swipes_data_summary) {
            val ctx = LocalContext.current
            val stats by TypingSwipeRecorder.stats.collectAsState()
            LaunchedEffect(Unit) { TypingSwipeRecorder.refresh(ctx) }
            Preference(
                name = it.title,
                description = stringResource(R.string.typing_swipes_count, stats.count, Formatter.formatShortFileSize(ctx, stats.bytes))
                    + ". " + it.description,
                onClick = { SettingsDestination.navigateTo(SettingsDestination.TypingSwipes) }
            ) { NextScreenIcon() }
        },
    ) else emptyList()

    val spaceAndDeleteGestures = listOf(
        Setting(context, Settings.PREF_DELETE_SWIPE, R.string.delete_swipe) {
            val items = listOf(
                stringResource(R.string.auto_correction_threshold_mode_off) to 0,
                stringResource(R.string.delete_swipe_letters) to 1,
                stringResource(R.string.delete_swipe_words) to 2
            )
            ListPreference(it, items, Defaults.PREF_DELETE_SWIPE, description = stringResource(R.string.delete_swipe_summary))
        },
        Setting(context, Settings.PREF_SPACE_LONG_PRESS, R.string.space_long_press) {
            val items = listOf(
                stringResource(R.string.space_long_press_trackpad) to Defaults.SPACE_LONG_PRESS_TRACKPAD,
                stringResource(R.string.space_long_press_language) to Defaults.SPACE_LONG_PRESS_LANGUAGE,
                stringResource(R.string.action_none) to Defaults.SPACE_LONG_PRESS_NONE,
            )
            ListPreference(it, items, Defaults.PREF_SPACE_LONG_PRESS)
        },
        Setting(context, Settings.PREF_SPACE_HORIZONTAL_SWIPE, R.string.show_horizontal_space_swipe) {
            val items = listOf(
                stringResource(R.string.space_swipe_move_cursor_entry) to KeyboardActionListener.SwipeAction.MOVE_CURSOR.name,
                stringResource(R.string.switch_language) to KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE.name,
                stringResource(R.string.space_swipe_toggle_numpad_entry) to KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD.name,
                stringResource(R.string.space_swipe_toggle_dpad_entry) to KeyboardActionListener.SwipeAction.TOGGLE_DPAD.name,
                stringResource(R.string.action_none) to KeyboardActionListener.SwipeAction.NONE.name,
            )
            ListPreference(it, items, Defaults.PREF_SPACE_HORIZONTAL_SWIPE)
        },
        Setting(context, Settings.PREF_SPACE_VERTICAL_SWIPE, R.string.show_vertical_space_swipe) {
            val items = listOf(
                stringResource(R.string.space_swipe_move_cursor_entry) to KeyboardActionListener.SwipeAction.MOVE_CURSOR.name,
                stringResource(R.string.switch_language) to KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE.name,
                stringResource(R.string.space_swipe_toggle_numpad_entry) to KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD.name,
                stringResource(R.string.space_swipe_toggle_dpad_entry) to KeyboardActionListener.SwipeAction.TOGGLE_DPAD.name,
                stringResource(R.string.space_swipe_hide_keyboard_entry) to KeyboardActionListener.SwipeAction.HIDE_KEYBOARD.name,
                stringResource(R.string.space_swipe_touchpad_mode_entry) to KeyboardActionListener.SwipeAction.TOUCHPAD_MODE.name,
                stringResource(R.string.action_none) to KeyboardActionListener.SwipeAction.NONE.name,
            )
            ListPreference(it, items, Defaults.PREF_SPACE_VERTICAL_SWIPE)
        },
        Setting(context, Settings.PREF_LANGUAGE_SWIPE_DISTANCE, R.string.prefs_language_swipe_distance) { setting ->
            SliderPreference(
                name = setting.title,
                key = setting.key,
                default = Defaults.PREF_LANGUAGE_SWIPE_DISTANCE,
                range = 2f..18f,
                description = { it.toString() }
            )
        },
        Setting(context, Settings.PREF_TOUCHPAD_SENSITIVITY, R.string.touchpad_sensitivity) {
            SliderPreference(
                name = it.title,
                key = it.key,
                default = Defaults.PREF_TOUCHPAD_SENSITIVITY,
                range = 0f..100f,
                description = { value -> value.toString() }
            )
        },
        Setting(context, Settings.PREF_TOUCHPAD_EDGE_SCROLL, R.string.touchpad_edge_scroll, R.string.touchpad_edge_scroll_description) {
            SwitchPreference(it, Defaults.PREF_TOUCHPAD_EDGE_SCROLL)
        },
        Setting(context, Settings.PREF_TOUCHPAD_DIM, R.string.touchpad_dim, R.string.touchpad_dim_description) {
            SwitchPreference(it, Defaults.PREF_TOUCHPAD_DIM)
        },
    )

    return gestureSpecific + spaceAndDeleteGestures
}

@Preview
@Composable
private fun Preview() {
    JniUtils.sHaveGestureLib = true
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            GestureTypingScreen { }
        }
    }
}
