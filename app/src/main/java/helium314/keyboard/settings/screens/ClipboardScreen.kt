// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun ClipboardScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val items = listOf(
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        Settings.PREF_SUGGEST_CLIPBOARD_CONTENT,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_USE_FILES else null,
        if (clipboardHistoryEnabled && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES))
            Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT else null,
        if (clipboardHistoryEnabled) Settings.PREF_ABC_AFTER_CLIP else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_clipboard),
        settings = items
    )
}

fun createClipboardSettings(context: Context) = listOf(
    Setting(
        context, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        R.string.enable_clipboard_history, R.string.enable_clipboard_history_summary
    ) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) { ClipboardDao.getInstance(ctx)?.clearNonPinned() }
    },
    Setting(
        context, Settings.PREF_SUGGEST_CLIPBOARD_CONTENT,
        R.string.suggest_clipboard_content, R.string.suggest_clipboard_content_summary
    ) {
        SwitchPreference(it, Defaults.PREF_SUGGEST_CLIPBOARD_CONTENT)
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, R.string.clipboard_history_retention_time) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME,
            description = {
                if (it > 120) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_minutes, it.toString())
            },
            range = 1f..121f,
        ) { ClipboardDao.getInstance(ctx)?.clearOldClips(true) }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, R.string.clipboard_history_pinned_first) {
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)
    },
    Setting(context, Settings.PREF_CLIPBOARD_USE_FILES, R.string.clipboard_history_files) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_USE_FILES) {
            ClipboardDao.getInstance(ctx)?.cleanupFiles(ctx.prefs())
        }
    },
    Setting(context, Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, R.string.clipboard_history_max_file_size) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT,
            description = {
                if (it > 1000) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_mb, it.toString())
            },
            range = 1f..1001f,
        ) { ClipboardDao.getInstance(ctx)?.cleanupFiles(ctx.prefs()) }
    },
    Setting(context, Settings.PREF_ABC_AFTER_CLIP, R.string.switch_keyboard_after, R.string.after_clip) {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_CLIP)
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ClipboardScreen { }
        }
    }
}
