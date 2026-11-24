// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Links
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.IntentUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.SupportMenuPreference
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val FUTO_SWIPE_LICENSE_ASSET = "swipe/honorable_sturgeon/LICENSE.md"

@Composable
fun AboutScreen(
    onClickBack: () -> Unit,
) {
    val items = listOf(
        SettingsWithoutKey.APP,
        SettingsWithoutKey.VERSION,
        SettingsWithoutKey.LICENSE,
        SettingsWithoutKey.FUTO_SWIPE,
        SettingsWithoutKey.HIDDEN_FEATURES,
        SettingsWithoutKey.GITHUB_WIKI,
        SettingsWithoutKey.COMMUNITY_LINKS,
        SettingsWithoutKey.GITHUB,
        SettingsWithoutKey.SUPPORT,
        SettingsWithoutKey.SAVE_LOG,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_about_and_support),
        settings = items
    )
}

fun createAboutSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.APP, R.string.english_ime_name, R.string.app_slogan) {
        Preference(
            name = it.title,
            description = it.description,
            onClick = null,
            icon = R.mipmap.ic_launcher_round
        )
    },
    Setting(context, SettingsWithoutKey.VERSION, R.string.version) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        Preference(
            name = it.title,
            description = stringResource(R.string.version_text, BuildConfig.VERSION_NAME),
            onClick = {
                if (prefs.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, Defaults.PREF_SHOW_DEBUG_SETTINGS) || BuildConfig.DEBUG)
                    return@Preference
                count++
                if (count < 5) return@Preference
                prefs.edit { putBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, true) }
                Toast.makeText(ctx, R.string.prefs_debug_settings_enabled, Toast.LENGTH_LONG).show()
            },
            icon = R.drawable.ic_settings_about_version
        )
    },
    Setting(context, SettingsWithoutKey.LICENSE, R.string.license, R.string.gnu_gpl) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.LICENSE.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_license
        )
    },
    // required by the FUTO Model Weights License: visible "powered by FUTO Swipe" notice
    Setting(context, SettingsWithoutKey.FUTO_SWIPE, R.string.about_futo_swipe_title, R.string.about_futo_swipe_summary) {
        var showLicense by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = it.title,
            description = it.description,
            onClick = { showLicense = true },
            icon = R.drawable.ic_settings_gesture
        ) { NextScreenIcon() }
        if (showLicense) {
            FutoSwipeLicenseDialog { showLicense = false }
        }
    },
    Setting(context, SettingsWithoutKey.HIDDEN_FEATURES, R.string.keyboard_tips_title, R.string.keyboard_tips_summary) {
        var showTips by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = it.title,
            description = it.description,
            onClick = { showTips = true },
            icon = R.drawable.ic_settings_about_hidden_features
        ) { NextScreenIcon() }
        if (showTips) {
            KeyboardTipsDialog { showTips = false }
        }
    },
    Setting(context, SettingsWithoutKey.GITHUB_WIKI, R.string.about_wiki_link, R.string.about_wiki_link_description) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.WIKI_URL.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_wiki
        )
    },
    Setting(context, SettingsWithoutKey.COMMUNITY_LINKS, R.string.about_community_links, R.string.about_community_links_description) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.COMMUNITY_LINKS.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_community
        )
    },
    Setting(context, SettingsWithoutKey.GITHUB, R.string.about_github_link) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.GITHUB.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_github
        )
    },
    Setting(context, SettingsWithoutKey.SUPPORT, R.string.support_menu) {
        SupportMenuPreference()
    },
    Setting(context, SettingsWithoutKey.SAVE_LOG, R.string.save_log) { setting ->
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val os = ctx.getActivity()?.contentResolver?.openOutputStream(uri) ?: return@runCatching false
                        os.use { stream ->
                            stream.writer().use { writer ->
                                val logcat = Runtime.getRuntime().exec("logcat -d -b all *:W").inputStream.use { it.reader().readText() }
                                val internal = Log.getLog().joinToString("\n")
                                val state = runCatching { helium314.keyboard.latin.utils.PredictionDiagnostics.report(ctx) }.getOrElse { "state: $it" }
                                writer.write(logcat + "\n\n" + internal + "\n\n" + state + "\n")
                            }
                        }
                        true
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    ctx,
                    if (saved) R.string.save_log_success else R.string.save_log_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = {
                val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Calendar.getInstance().time)
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "text/plain")
                    .setAction(Intent.ACTION_CREATE_DOCUMENT)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        ctx.getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_log_$date.txt"
                    )
                launcher.launch(intent)
            },
            icon = R.drawable.ic_settings_about_log
        )
    },
)

@Composable
private fun FutoSwipeLicenseDialog(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var licenseText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        licenseText = withContext(Dispatchers.IO) {
            runCatching {
                ctx.assets.open(FUTO_SWIPE_LICENSE_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: ctx.getString(R.string.about_futo_swipe_license_unavailable)
    }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { },
        confirmButtonText = null,
        cancelButtonText = stringResource(R.string.dialog_close),
        scrollContent = true,
        title = { Text(stringResource(R.string.about_futo_swipe_license_title)) },
        content = {
            Text(
                text = licenseText.orEmpty(),
                style = MaterialTheme.typography.bodySmall
            )
        }
    )
}

private data class KeyboardTip(@StringRes val titleRes: Int, @StringRes val bodyRes: Int)

private val KEYBOARD_TIPS = listOf(
    KeyboardTip(R.string.tip_trackpad_title, R.string.tip_trackpad_body),
    KeyboardTip(R.string.tip_edit_shortcuts_title, R.string.tip_edit_shortcuts_body),
    KeyboardTip(R.string.tip_delete_swipe_title, R.string.tip_delete_swipe_body),
    KeyboardTip(R.string.tip_dictation_undo_title, R.string.tip_dictation_undo_body),
    KeyboardTip(R.string.tip_quick_clips_title, R.string.tip_quick_clips_body),
    KeyboardTip(R.string.tip_resize_title, R.string.tip_resize_body),
    KeyboardTip(R.string.tip_shift_symbols_title, R.string.tip_shift_symbols_body),
    KeyboardTip(R.string.tip_toolbar_incognito_title, R.string.tip_toolbar_incognito_body),
)

@Composable
private fun KeyboardTipsDialog(onDismissRequest: () -> Unit) {
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { },
        confirmButtonText = null,
        cancelButtonText = stringResource(R.string.dialog_close),
        scrollContent = true,
        title = { Text(stringResource(R.string.keyboard_tips_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KEYBOARD_TIPS.forEachIndexed { index, tip ->
                    if (index > 0) HorizontalDivider()
                    Column(
                        modifier = Modifier.padding(top = if (index > 0) 4.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(tip.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(tip.bodyRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AboutScreen { }
        }
    }
}
