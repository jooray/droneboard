// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.support.SupportCheckout
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SupportAskStrip
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PersonalDictionaryBackup
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MainSettingsScreen(
    onClickLanguage: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickVoiceDictation: () -> Unit,
    onClickClipboard: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAbout: () -> Unit,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var osttStatus by remember { mutableStateOf(checkOsttStatus(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        osttStatus = checkOsttStatus(ctx)
    }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                BrandHeader()
                SupportAskStrip(SupportCheckout.REF_SETTINGS_HOME)
                Preference(
                    name = stringResource(R.string.language_and_layouts_title),
                    description = enabledSubtypes.joinToString(", ") { it.displayName() },
                    onClick = onClickLanguage,
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_preferences),
                    onClick = onClickPreferences,
                    icon = R.drawable.ic_settings_preferences
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_theme_and_layout),
                    onClick = onClickAppearance,
                    icon = R.drawable.ic_settings_appearance
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_correction_and_dictionaries),
                    onClick = onClickTextCorrection,
                    icon = R.drawable.ic_settings_correction
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_glide_typing),
                    onClick = onClickGestureTyping,
                    icon = R.drawable.ic_settings_gesture
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_voice_dictation),
                    description = osttShortSummary(osttStatus),
                    onClick = onClickVoiceDictation,
                    icon = R.drawable.sym_keyboard_voice_rounded
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_clipboard),
                    description = stringResource(R.string.clipboard_screen_summary),
                    onClick = onClickClipboard,
                    icon = R.drawable.sym_keyboard_clipboard_rounded
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_backup_and_advanced),
                    onClick = onClickAdvanced,
                    icon = R.drawable.ic_settings_advanced
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_about_and_support),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
            }
        }
    }
}

/** the number of words, read off the main thread; the plain description until it is known or when there are none */
@Composable
fun personalDictionarySummary(): String {
    val ctx = LocalContext.current
    val count by produceState<Int?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { PersonalDictionaryBackup.count(ctx, everything = true, locale = null) }.getOrNull()
        }
    }
    val words = count
    return if (words != null && words > 0) stringResource(R.string.personal_dictionary_word_count, words)
        else stringResource(R.string.personal_dictionary_main_summary)
}

/** Droneboard brand: the icon on its slate tile, with a red swipe line under the name */
@Composable
private fun BrandHeader() {
    val slate = Color(0xFF2E3A44)
    val red = Color(0xFFB23A2E)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(slate)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                stringResource(R.string.english_ime_name),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(red)
            )
            Text(
                stringResource(R.string.brand_version_offline, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
